//! Sealing Prism's history (`rocky_prism::seal`): the worker's link to the
//! coordinator, the admin routes the rollout Job calls, and the Job itself
//! (`prism-worker coordinate`).
//!
//! The root key never stays on a worker. The Job holds it, from a Secret that
//! exists only while it runs, and passes it to each worker for one call:
//!
//! - `POST /v1/rocky/trust` `{root}`: the worker renews its credentials if
//!   its registry doesn't record it yet, then trusts its own identity.
//!   → `{principal, status}`
//! - `POST /v1/rocky/coordinate` `{root, trusted, revision}`: the worker
//!   registers (revision 1) or renews (2, 3, ...) the database's domain at
//!   the coordinator, trusting `trusted`, and names the coordinator in the
//!   database's settings. → `{receipt, status}`
//! - `GET /v1/rocky/seal` → this replica's `SealStatus`.
//!
//! All three need the admin token and are refused when they came through
//! the Ingress (`X-Forwarded-For`): a root key never crosses the internet.

use crate::admin::{bearer, error, unavailable};
use crate::auth::{self, verify_admin_token};
use crate::http::AppState;
use axum::{
    Json,
    body::Bytes,
    extract::State,
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use rocky_prism::coordinator::{
    CoordinatorLink, PUBLIC_COORDINATOR_RELAY, PUBLIC_COORDINATOR_ROOT,
};
use rocky_prism::{PrismError, RootSigner};
use serde::Deserialize;
use serde_json::json;
use std::path::PathBuf;
use std::time::Duration;

type Failure = Box<dyn std::error::Error + Send + Sync>;

const MAX_BODY: usize = 64 << 10;

/// Starts the worker's coordinator link when `PRISM_ROCKY_COORDINATOR_BUNDLE`
/// (or `_FILE`) is set: the trust bundle of the coordinator, pinned to
/// `PRISM_ROCKY_COORDINATOR_ROOT` and reached through
/// `PRISM_ROCKY_COORDINATOR_RELAY` (both default to the public service at
/// rocky.quickme.at). Without a bundle the worker runs uncoordinated, as
/// before, and never seals.
pub async fn link_from_env(
    env: impl Fn(&str) -> Option<String>,
) -> Result<Option<CoordinatorLink>, Failure> {
    let Some(bundle) = auth::secret_from("PRISM_ROCKY_COORDINATOR_BUNDLE", &env) else {
        return Ok(None);
    };
    let root = env("PRISM_ROCKY_COORDINATOR_ROOT")
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| PUBLIC_COORDINATOR_ROOT.into());
    let relay = env("PRISM_ROCKY_COORDINATOR_RELAY")
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| PUBLIC_COORDINATOR_RELAY.into());
    let link = CoordinatorLink::start(&bundle, &root, &relay).await?;
    eprintln!("prism-worker: seals are coordinated through {relay}");
    Ok(Some(link))
}

/// Where the worker keeps the coordinator control's signing times, beside
/// its replica (`/data/prism-coordinator`), so a retried request is the same.
pub fn state_dir(data_dir: &std::path::Path) -> PathBuf {
    PathBuf::from(format!("{}-coordinator", data_dir.display()))
}

#[allow(clippy::result_large_err)]
fn require_in_cluster_admin(state: &AppState, headers: &HeaderMap) -> Result<(), Response> {
    if headers.contains_key("x-forwarded-for") {
        return Err(error(
            StatusCode::FORBIDDEN,
            "Rocky's root key is only taken inside the cluster.",
            "forbidden",
        ));
    }
    let Some(key) = state.event_key.as_deref().filter(|k| !k.is_empty()) else {
        return Err(error(
            StatusCode::SERVICE_UNAVAILABLE,
            "The admin API needs the worker's PRISM_EVENT_KEY.",
            "unavailable",
        ));
    };
    if bearer(headers).is_some_and(|t| verify_admin_token(key, t)) {
        return Ok(());
    }
    Err(error(
        StatusCode::UNAUTHORIZED,
        "This needs Prism's admin token.",
        "unauthorized",
    ))
}

#[allow(clippy::result_large_err)]
fn parse<T: for<'de> Deserialize<'de>>(body: &Bytes) -> Result<T, Response> {
    if body.len() > MAX_BODY {
        return Err(error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "The request body is too large.",
            "invalid",
        ));
    }
    serde_json::from_slice(body).map_err(|_| {
        error(
            StatusCode::BAD_REQUEST,
            "The request body is not valid JSON.",
            "invalid",
        )
    })
}

#[allow(clippy::result_large_err)]
fn signer(root: &str) -> Result<RootSigner, Response> {
    RootSigner::parse(root).map_err(|_| {
        error(
            StatusCode::BAD_REQUEST,
            "root must be the Rocky root phrase or the Prism root.",
            "invalid",
        )
    })
}

fn prism_error(what: &str, err: PrismError) -> Response {
    match err {
        PrismError::InvalidInput(message) => error(StatusCode::BAD_REQUEST, message, "invalid"),
        other => unavailable(what, other),
    }
}

/// `GET /v1/rocky/seal`
pub async fn get_seal(State(state): State<AppState>, headers: HeaderMap) -> Response {
    if let Err(resp) = require_in_cluster_admin(&state, &headers) {
        return resp;
    }
    match state.client.seal_status().await {
        Ok(status) => Json(status).into_response(),
        Err(err) => unavailable("seal status", err),
    }
}

#[derive(Deserialize)]
struct TrustBody {
    root: String,
}

/// `POST /v1/rocky/trust`
pub async fn post_trust(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    if let Err(resp) = require_in_cluster_admin(&state, &headers) {
        return resp;
    }
    let req: TrustBody = match parse(&body) {
        Ok(req) => req,
        Err(resp) => return resp,
    };
    let root = match signer(&req.root) {
        Ok(root) => root,
        Err(resp) => return resp,
    };
    let principal = match state.client.trust_self(&root).await {
        Ok(principal) => principal,
        Err(err) => return prism_error("trusting this worker", err),
    };
    eprintln!("prism-worker: the root trusts this worker's identity {principal}");
    match state.client.seal_status().await {
        Ok(status) => Json(json!({ "principal": principal, "status": status })).into_response(),
        Err(err) => unavailable("seal status", err),
    }
}

#[derive(Deserialize)]
struct CoordinateBody {
    root: String,
    trusted: Vec<String>,
    revision: u64,
}

/// `POST /v1/rocky/coordinate`
pub async fn post_coordinate(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    if let Err(resp) = require_in_cluster_admin(&state, &headers) {
        return resp;
    }
    let req: CoordinateBody = match parse(&body) {
        Ok(req) => req,
        Err(resp) => return resp,
    };
    let root = match signer(&req.root) {
        Ok(root) => root,
        Err(resp) => return resp,
    };
    let Some(link) = state.coordinator.as_deref() else {
        return error(
            StatusCode::CONFLICT,
            "This worker has no coordinator: set PRISM_ROCKY_COORDINATOR_BUNDLE_FILE.",
            "unavailable",
        );
    };
    let receipt = match state
        .client
        .register_coordination(
            link,
            &root,
            &req.trusted,
            req.revision,
            &state_dir(&state.data_dir),
        )
        .await
    {
        Ok(receipt) => receipt,
        Err(err) => return prism_error("registering with the coordinator", err),
    };
    if let Err(err) = state.client.declare_coordinated(&root).await {
        return prism_error("naming the coordinator", err);
    }
    eprintln!(
        "prism-worker: coordinated, control revision {} until {}",
        receipt.revision, receipt.expires
    );
    match state.client.seal_status().await {
        Ok(status) => Json(json!({ "receipt": receipt, "status": status })).into_response(),
        Err(err) => unavailable("seal status", err),
    }
}

fn admin_client(
    env: &impl Fn(&str) -> Option<String>,
) -> Result<(reqwest::Client, String), Failure> {
    let key = auth::secret_from("PRISM_EVENT_KEY", env)
        .ok_or("set PRISM_EVENT_KEY_FILE (or PRISM_EVENT_KEY): the admin token derives from it")?;
    let token = auth::admin_token(key.as_bytes()).ok_or("PRISM_EVENT_KEY is too short")?;
    // Renewing credentials and signing settings wait on Rocky's queue, which
    // is slow on a replica with a long unsealed history.
    let http = reqwest::Client::builder()
        .timeout(Duration::from_secs(600))
        .build()?;
    Ok((http, token))
}

/// `prism-worker seal WORKER_URL...`: prints each worker's `SealStatus`, for
/// checking the rollout from a worker pod (`kubectl exec`; it reads the
/// pod's `PRISM_EVENT_KEY_FILE`).
pub async fn seal_command(
    urls: &[&str],
    env: impl Fn(&str) -> Option<String>,
) -> Result<(), Failure> {
    if urls.is_empty() {
        return Err("usage: prism-worker seal WORKER_URL...".into());
    }
    let (http, token) = admin_client(&env)?;
    for url in urls {
        let base = url.trim_end_matches('/');
        let response = http
            .get(format!("{base}/v1/rocky/seal"))
            .bearer_auth(&token)
            .send()
            .await?;
        println!("{base}: {} {}", response.status(), response.text().await?);
    }
    Ok(())
}

/// `prism-worker coordinate [--revision N] WORKER_URL...`: the rollout Job.
/// Reads the root from `PRISM_ROOT_FILE` (or `PRISM_ROOT`) and the event key
/// from `PRISM_EVENT_KEY_FILE` (or `PRISM_EVENT_KEY`), trusts every worker,
/// then registers or renews the domain trusting all of them, through the
/// first. Prints what each worker reports; never the root.
pub async fn command(args: &[&str], env: impl Fn(&str) -> Option<String>) -> Result<(), Failure> {
    let (revision, urls) = match args {
        ["--revision", n, urls @ ..] => (n.parse::<u64>()?, urls),
        urls => (1, urls),
    };
    if urls.is_empty() {
        return Err("usage: prism-worker coordinate [--revision N] WORKER_URL...".into());
    }
    let root = auth::secret_from("PRISM_ROOT", &env)
        .ok_or("set PRISM_ROOT_FILE (or PRISM_ROOT) to the Prism root or its root phrase")?;
    // Only the phrase travels: the whole Prism root is about 320 KB.
    let root = RootSigner::parse(&root)?.phrase();
    let (http, token) = admin_client(&env)?;
    let call = |url: String, body: serde_json::Value| {
        let request = http.post(url).bearer_auth(&token).json(&body);
        async move {
            let response = request.send().await?;
            let status = response.status();
            let text = response.text().await?;
            if !status.is_success() {
                return Err::<serde_json::Value, Failure>(format!("HTTP {status}: {text}").into());
            }
            Ok(serde_json::from_str(&text)?)
        }
    };
    let mut trusted = Vec::new();
    for url in urls {
        let base = url.trim_end_matches('/');
        let answer = call(format!("{base}/v1/rocky/trust"), json!({ "root": root }))
            .await
            .map_err(|err| format!("{base}: trust: {err}"))?;
        let principal = answer["principal"]
            .as_str()
            .ok_or_else(|| format!("{base}: trust: no principal"))?
            .to_owned();
        println!("{base}: trusted {principal}");
        trusted.push(principal);
    }
    let first = urls[0].trim_end_matches('/');
    let answer = call(
        format!("{first}/v1/rocky/coordinate"),
        json!({ "root": root, "trusted": trusted, "revision": revision }),
    )
    .await
    .map_err(|err| format!("{first}: coordinate: {err}"))?;
    println!(
        "{first}: coordinated {}",
        serde_json::to_string_pretty(&answer)?
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::http::router;
    use axum::body::Body;
    use axum::http::Request;
    use std::collections::BTreeMap;
    use std::sync::Arc;
    use tower::ServiceExt;

    const KEY: &[u8] = b"0123456789abcdef0123456789abcdef-prism-event-key";

    async fn call(app: axum::Router, request: Request<Body>) -> (StatusCode, serde_json::Value) {
        let response = app.oneshot(request).await.expect("response");
        let status = response.status();
        let body = http_body_util::BodyExt::collect(response.into_body())
            .await
            .expect("body")
            .to_bytes();
        (status, serde_json::from_slice(&body).unwrap_or_default())
    }

    #[tokio::test]
    async fn the_routes_need_the_admin_token_inside_the_cluster() {
        let dir = tempfile::tempdir().expect("tempdir");
        let client = Arc::new(
            rocky_prism::PrismClient::open_or_create(dir.path().display().to_string(), None)
                .await
                .expect("open"),
        );
        let app = router(AppState::new(Arc::clone(&client), Some(KEY.to_vec())));
        let token = auth::admin_token(KEY).expect("token");
        let get = |token: Option<&str>, forwarded: bool| {
            let mut request = Request::get("/v1/rocky/seal");
            if let Some(token) = token {
                request = request.header("authorization", format!("Bearer {token}"));
            }
            if forwarded {
                request = request.header("x-forwarded-for", "203.0.113.1");
            }
            request.body(Body::empty()).expect("request")
        };
        assert_eq!(
            call(app.clone(), get(None, false)).await.0,
            StatusCode::UNAUTHORIZED
        );
        assert_eq!(
            call(app.clone(), get(Some(&token), true)).await.0,
            StatusCode::FORBIDDEN
        );
        let (status, seal) = call(app.clone(), get(Some(&token), false)).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(seal["trusted"], false);
        assert_eq!(seal["coordination"], "not_configured");

        // A worker without a coordinator refuses to register one.
        let root = rocky_prism::PrismRoot::create()
            .await
            .expect("root")
            .root_key()
            .expect("phrase");
        let coordinate = Request::post("/v1/rocky/coordinate")
            .header("authorization", format!("Bearer {token}"))
            .header("content-type", "application/json")
            .body(Body::from(
                json!({"root": root, "trusted": ["00"], "revision": 1}).to_string(),
            ))
            .expect("request");
        assert_eq!(call(app.clone(), coordinate).await.0, StatusCode::CONFLICT);
        let bad_root = Request::post("/v1/rocky/trust")
            .header("authorization", format!("Bearer {token}"))
            .body(Body::from(json!({"root": "nope"}).to_string()))
            .expect("request");
        assert_eq!(call(app, bad_root).await.0, StatusCode::BAD_REQUEST);
        drop(client);
    }

    #[tokio::test]
    async fn no_bundle_means_no_link_and_a_foreign_bundle_is_refused() {
        let env = |vars: BTreeMap<&'static str, String>| move |key: &str| vars.get(key).cloned();
        assert!(
            link_from_env(env(BTreeMap::new()))
                .await
                .expect("none")
                .is_none()
        );
        let foreign = BTreeMap::from([
            (
                "PRISM_ROCKY_COORDINATOR_BUNDLE",
                include_str!("../../deploy/rocky-coordinator-bundle.json").to_owned(),
            ),
            ("PRISM_ROCKY_COORDINATOR_ROOT", "00".repeat(32)),
        ]);
        assert!(link_from_env(env(foreign)).await.is_err());
    }

    #[tokio::test]
    async fn the_job_needs_workers_a_root_and_the_event_key() {
        let none = |_: &str| None::<String>;
        assert!(command(&[], none).await.is_err());
        assert!(command(&["http://w0:8080"], none).await.is_err());
        let root_only = |key: &str| (key == "PRISM_ROOT").then(|| "not a root".to_owned());
        assert!(command(&["http://w0:8080"], root_only).await.is_err());
        assert!(seal_command(&[], none).await.is_err());
        assert!(seal_command(&["http://w0:8080"], none).await.is_err());
    }
}
