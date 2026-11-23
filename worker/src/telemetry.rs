//! The phone's metrics (`POST /v1/telemetry/write`): Prometheus remote write
//! that the workers pass on to the cluster's telemetry store.
//!
//! The phone used to push to telemetry.quickme.at, which is reachable only
//! over NetBird. A push every 15 minutes kept its NetBird peer from ever going
//! idle (NetBird idles a peer after 15 minutes without data), so WireGuard's
//! keepalives woke the modem every 25 s. The phone already reaches the workers
//! at prism.quickme.at, so it pushes here and the worker forwards the body
//! unchanged to `telemetry_url` in its config (ClickHouse's remote write
//! handler in the cluster).
//!
//! The phone authenticates with the credential it already holds for
//! telemetry, basic auth `telemetry_user` (default `prism_phone`) and the
//! password in `PRISM_TELEMETRY_PASSWORD(_FILE)`, and the worker sends the
//! same credential upstream. It has no `prism_` device token: its replica's
//! provisioning is its identity (docs/prism-api.md). The check needs no
//! database, so a push never waits on Rocky's storage.

use crate::admin::error;
use crate::http::AppState;
use axum::{
    body::Bytes,
    extract::State,
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
};
use data_encoding::BASE64;
use sha2::{Digest, Sha256};
use std::sync::OnceLock;
use std::time::Duration;

/// A day of the phone's readings is well under this, snappy-compressed.
pub const MAX_BODY: usize = 2 << 20;
pub const PASSWORD_SECRET: &str = "PRISM_TELEMETRY_PASSWORD";
/// The phone retries a failed push with the next job, so a slow upstream
/// costs it a retry rather than a long-held request.
const UPSTREAM_TIMEOUT: Duration = Duration::from_secs(10);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(3);
/// The remote write headers passed on as the phone sent them.
const FORWARDED: [&str; 3] = [
    "content-encoding",
    "content-type",
    "x-prometheus-remote-write-version",
];

fn upstream_client() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .timeout(UPSTREAM_TIMEOUT)
            .connect_timeout(CONNECT_TIMEOUT)
            .build()
            .expect("an HTTP client")
    })
}

/// The basic auth credential in `headers`, if any.
fn basic_auth(headers: &HeaderMap) -> Option<(String, String)> {
    let encoded = headers
        .get(header::AUTHORIZATION)?
        .to_str()
        .ok()?
        .strip_prefix("Basic ")?
        .trim();
    let decoded = String::from_utf8(BASE64.decode(encoded.as_bytes()).ok()?).ok()?;
    let (user, password) = decoded.split_once(':')?;
    Some((user.to_string(), password.to_string()))
}

/// Compares digests, so neither the content nor the length of the password
/// shows in how long a refusal takes.
fn same_secret(given: &str, expected: &str) -> bool {
    let (a, b) = (Sha256::digest(given), Sha256::digest(expected));
    a.iter()
        .zip(b.iter())
        .fold(0u8, |acc, (x, y)| acc | (x ^ y))
        == 0
}

/// `POST /v1/telemetry/write` → the upstream's status, with an empty body.
pub async fn post_write(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let url = state.cfg.telemetry_url.trim();
    let user = state.cfg.telemetry_user.as_str();
    let Some(password) = (state.secrets)(PASSWORD_SECRET).filter(|_| !url.is_empty()) else {
        return error(
            StatusCode::SERVICE_UNAVAILABLE,
            "Telemetry is not configured on this worker.",
            "unavailable",
        );
    };
    if !basic_auth(&headers).is_some_and(|(u, p)| u == user && same_secret(&p, &password)) {
        eprintln!("prism-worker: rejected a telemetry write");
        return error(
            StatusCode::UNAUTHORIZED,
            "The telemetry credential is wrong.",
            "unauthorized",
        );
    }
    if body.len() > MAX_BODY {
        return StatusCode::PAYLOAD_TOO_LARGE.into_response();
    }
    let mut req = upstream_client()
        .post(url)
        .basic_auth(user, Some(&password))
        .body(body);
    for name in FORWARDED {
        if let Some(value) = headers.get(name) {
            req = req.header(name, value);
        }
    }
    match req.send().await {
        // The worker checked the phone's credential against its own, so a
        // refusal upstream means the worker's copy is wrong: a gateway
        // failure the phone retries, not a 401 that tells it to give up.
        Ok(resp) if matches!(resp.status().as_u16(), 401 | 403) => {
            eprintln!(
                "prism-worker: telemetry upstream refused {user}: HTTP {}",
                resp.status()
            );
            StatusCode::BAD_GATEWAY.into_response()
        }
        Ok(resp) => {
            let status =
                StatusCode::from_u16(resp.status().as_u16()).unwrap_or(StatusCode::BAD_GATEWAY);
            if !status.is_success() {
                eprintln!("prism-worker: telemetry upstream answered HTTP {status}");
            }
            status.into_response()
        }
        Err(err) if err.is_timeout() => {
            eprintln!("prism-worker: telemetry upstream timed out");
            StatusCode::GATEWAY_TIMEOUT.into_response()
        }
        Err(err) => {
            eprintln!("prism-worker: telemetry upstream failed: {err}");
            StatusCode::BAD_GATEWAY.into_response()
        }
    }
}

#[cfg(test)]
mod tests {
    use crate::config::Config;
    use crate::http::{AppState, router};
    use crate::testutil::serve;
    use axum::{
        Router,
        body::{Body, Bytes},
        http::{HeaderMap, Request, StatusCode},
        routing::post,
    };
    use data_encoding::BASE64;
    use rocky_prism::PrismClient;
    use std::sync::{Arc, Mutex};
    use tower::ServiceExt;

    const PASSWORD: &str = "phone-secret";
    /// A snappy-framed remote write body as far as the worker cares: bytes.
    const BODY: &[u8] = b"\x0a\x05remote-write\xff\x00";

    type Seen = Arc<Mutex<Vec<(HeaderMap, Bytes)>>>;

    /// A remote write endpoint answering `status`, recording what it got.
    async fn upstream(status: StatusCode) -> (String, Seen) {
        let seen: Seen = Arc::default();
        let log = Arc::clone(&seen);
        let app = Router::new().route(
            "/write",
            post(move |headers: HeaderMap, body: Bytes| {
                let log = Arc::clone(&log);
                async move {
                    log.lock().unwrap().push((headers, body));
                    status
                }
            }),
        );
        (format!("{}/write", serve(app).await), seen)
    }

    /// The worker's router; the route never opens the database, but the
    /// state needs one, which lives as long as the returned directory.
    async fn app(url: &str, password: Option<&str>) -> (axum::Router, tempfile::TempDir) {
        let dir = tempfile::tempdir().expect("tempdir");
        let client = Arc::new(
            PrismClient::open_or_create(dir.path().join("db").display().to_string(), None)
                .await
                .expect("client"),
        );
        let cfg = format!(r#"{{"telemetry_url": {}}}"#, serde_json::json!(url));
        let password = password.map(str::to_string);
        let state = AppState::new(client, None)
            .with_config(Arc::new(Config::parse(&cfg).expect("config")))
            .with_secrets(Arc::new(move |name: &str| {
                (name == super::PASSWORD_SECRET)
                    .then(|| password.clone())
                    .flatten()
            }));
        (router(state), dir)
    }

    fn basic(user: &str, password: &str) -> String {
        format!(
            "Basic {}",
            BASE64.encode(format!("{user}:{password}").as_bytes())
        )
    }

    async fn write(app: &axum::Router, auth: Option<String>, body: Vec<u8>) -> StatusCode {
        let mut req = Request::builder()
            .method("POST")
            .uri("/v1/telemetry/write")
            .header("content-type", "application/x-protobuf")
            .header("content-encoding", "snappy")
            .header("x-prometheus-remote-write-version", "0.1.0");
        if let Some(auth) = auth {
            req = req.header("authorization", auth);
        }
        app.clone()
            .oneshot(req.body(Body::from(body)).expect("request"))
            .await
            .expect("response")
            .status()
    }

    #[tokio::test]
    async fn forwards_the_body_and_headers_with_the_workers_credential() {
        let (url, seen) = upstream(StatusCode::NO_CONTENT).await;
        let (app, _dir) = app(&url, Some(PASSWORD)).await;
        let status = write(&app, Some(basic("prism_phone", PASSWORD)), BODY.to_vec()).await;
        assert_eq!(status, StatusCode::NO_CONTENT);
        let seen = seen.lock().unwrap();
        assert_eq!(seen.len(), 1);
        let (headers, body) = &seen[0];
        assert_eq!(body.as_ref(), BODY);
        let header = |name: &str| headers.get(name).and_then(|v| v.to_str().ok());
        assert_eq!(header("content-type"), Some("application/x-protobuf"));
        assert_eq!(header("content-encoding"), Some("snappy"));
        assert_eq!(header("x-prometheus-remote-write-version"), Some("0.1.0"));
        assert_eq!(
            header("authorization"),
            Some(basic("prism_phone", PASSWORD).as_str())
        );
    }

    #[tokio::test]
    async fn refuses_a_wrong_credential_before_the_upstream() {
        let (url, seen) = upstream(StatusCode::NO_CONTENT).await;
        let (app, _dir) = app(&url, Some(PASSWORD)).await;
        for auth in [
            None,
            Some(basic("prism_phone", "guess")),
            Some(basic("fleet_writer", PASSWORD)),
            Some(basic("prism_phone", "")),
            Some("Basic not-base64!".into()),
            Some(format!("Bearer {PASSWORD}")),
        ] {
            assert_eq!(
                write(&app, auth.clone(), BODY.to_vec()).await,
                StatusCode::UNAUTHORIZED,
                "{auth:?}"
            );
        }
        assert!(seen.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn answers_unavailable_until_configured() {
        let (url, _) = upstream(StatusCode::NO_CONTENT).await;
        for (url, password) in [(url.as_str(), None), ("", Some(PASSWORD))] {
            let (app, _dir) = app(url, password).await;
            let status = write(&app, Some(basic("prism_phone", PASSWORD)), BODY.to_vec()).await;
            assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE, "{url:?}");
        }
    }

    #[tokio::test]
    async fn caps_the_body() {
        let (url, seen) = upstream(StatusCode::NO_CONTENT).await;
        let (app, _dir) = app(&url, Some(PASSWORD)).await;
        let big = vec![0u8; super::MAX_BODY + 1];
        let status = write(&app, Some(basic("prism_phone", PASSWORD)), big).await;
        assert_eq!(status, StatusCode::PAYLOAD_TOO_LARGE);
        assert!(seen.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn passes_on_the_upstreams_answer() {
        for (upstream_status, expected) in [
            (StatusCode::BAD_REQUEST, StatusCode::BAD_REQUEST),
            (StatusCode::TOO_MANY_REQUESTS, StatusCode::TOO_MANY_REQUESTS),
            (
                StatusCode::SERVICE_UNAVAILABLE,
                StatusCode::SERVICE_UNAVAILABLE,
            ),
            // The worker's own credential is wrong: the phone should retry.
            (StatusCode::UNAUTHORIZED, StatusCode::BAD_GATEWAY),
        ] {
            let (url, _) = upstream(upstream_status).await;
            let (app, _dir) = app(&url, Some(PASSWORD)).await;
            let status = write(&app, Some(basic("prism_phone", PASSWORD)), BODY.to_vec()).await;
            assert_eq!(status, expected, "{upstream_status}");
        }

        // Nothing listens there any more.
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind");
        let gone = format!("http://{}/write", listener.local_addr().expect("addr"));
        drop(listener);
        let (app, _dir) = app(&gone, Some(PASSWORD)).await;
        let status = write(&app, Some(basic("prism_phone", PASSWORD)), BODY.to_vec()).await;
        assert_eq!(status, StatusCode::BAD_GATEWAY);
    }
}
