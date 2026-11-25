//! The owner's admin API: what `prism pair`, `prism machines` and `prism
//! device-token|device-revoke` call. The Go server ran these in its pod
//! against PostgreSQL; the worker serves them over HTTP to the admin token,
//! which `prism` derives from the event key (`auth::verify_admin_token`), so
//! only where the key is (the worker pod) can call them.
//!
//! - `POST /v1/t3/pair` `{machine, link, access?}` → `{paired, answer}`
//! - `GET /v1/machines` → the machines view (`machines.rs`)
//! - `POST /v1/devices/{device}/token` → `{device, token}`
//! - `DELETE /v1/devices/{device}/token` → `{device, revoked}`
//! - `PUT /v1/placement/account-limits/{account}` `{sessionPercent?,
//!   weeklyPercent?, until?}` → the account's limits; `DELETE` removes it
//!
//! Device tokens are `prism_` + 32 random bytes (base64url), as Go minted
//! them. Only their SHA-256 is stored, in the replicated `settings` table
//! under `device.token.<device>`; a revoked token's row is emptied.

use crate::auth::{is_valid_machine_name, verify_admin_token};
use crate::http::AppState;
use axum::{
    Json,
    body::Bytes,
    extract::{Path, State},
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
};
use data_encoding::BASE64URL_NOPAD;
use rocky_prism::PrismClient;
use serde::{Deserialize, Serialize};
use serde_json::json;
use sha2::{Digest, Sha256};

pub const DEVICE_TOKEN_PREFIX: &str = "prism_";
const DEVICE_TOKEN_KEY: &str = "device.token.";
const MAX_ADMIN_BODY: usize = 64 << 10;

pub(crate) fn error(status: StatusCode, message: impl Into<String>, code: &str) -> Response {
    (
        status,
        Json(json!({ "error": message.into(), "code": code })),
    )
        .into_response()
}

pub(crate) fn unavailable(what: &str, err: impl std::fmt::Display) -> Response {
    eprintln!("prism-worker: {what} failed: {err}");
    error(
        StatusCode::SERVICE_UNAVAILABLE,
        "Something went wrong. Try again.",
        "unavailable",
    )
}

pub(crate) fn bearer(headers: &HeaderMap) -> Option<&str> {
    headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .map(str::trim)
}

#[allow(clippy::result_large_err)]
fn require_admin(state: &AppState, headers: &HeaderMap) -> Result<(), Response> {
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
        "This needs Prism's admin token; run prism where PRISM_EVENT_KEY is, in a prism-worker pod.",
        "unauthorized",
    ))
}

#[derive(Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct DeviceTokenRecord {
    token_hash: String,
    created_at: i64,
}

fn token_digest(token: &str) -> String {
    hex::encode(Sha256::digest(token.as_bytes()))
}

/// Mints a device's Prism API token, replacing its earlier one.
pub async fn issue_device_token(
    client: &PrismClient,
    device: &str,
    now: i64,
) -> Result<Result<String, String>, String> {
    if !is_valid_machine_name(device) {
        return Ok(Err(format!("invalid device name {device:?}")));
    }
    let mut random = [0u8; 32];
    getrandom::getrandom(&mut random).map_err(|e| e.to_string())?;
    let token = format!("{DEVICE_TOKEN_PREFIX}{}", BASE64URL_NOPAD.encode(&random));
    let record = DeviceTokenRecord {
        token_hash: token_digest(&token),
        created_at: now,
    };
    client
        .set_setting(
            &format!("{DEVICE_TOKEN_KEY}{device}"),
            &serde_json::to_string(&record).map_err(|e| e.to_string())?,
            now,
        )
        .await
        .map_err(|e| e.to_string())?;
    Ok(Ok(token))
}

/// Ends a device's token; whether it had one.
pub async fn revoke_device(client: &PrismClient, device: &str, now: i64) -> Result<bool, String> {
    if !is_valid_machine_name(device) {
        return Ok(false);
    }
    let key = format!("{DEVICE_TOKEN_KEY}{device}");
    let had = client
        .get_setting(&key)
        .await
        .map_err(|e| e.to_string())?
        .is_some_and(|v| !v.is_empty());
    if had {
        client
            .set_setting(&key, "", now)
            .await
            .map_err(|e| e.to_string())?;
    }
    Ok(had)
}

/// The device a `prism_` bearer token belongs to (Go's `authenticate`).
pub async fn authenticate_device(
    client: &PrismClient,
    headers: &HeaderMap,
) -> Result<Option<String>, String> {
    let Some(token) =
        bearer(headers).filter(|t| t.starts_with(DEVICE_TOKEN_PREFIX) && t.len() <= 200)
    else {
        return Ok(None);
    };
    let digest = token_digest(token);
    let settings = client.list_settings().await.map_err(|e| e.to_string())?;
    Ok(settings.into_iter().find_map(|row| {
        let device = row.key.strip_prefix(DEVICE_TOKEN_KEY)?;
        let record: DeviceTokenRecord = serde_json::from_str(&row.value).ok()?;
        (!record.token_hash.is_empty() && record.token_hash == digest).then(|| device.to_string())
    }))
}

#[derive(Deserialize)]
struct PairBody {
    #[serde(default)]
    machine: String,
    #[serde(default)]
    link: String,
    #[serde(default)]
    access: String,
}

/// `POST /v1/t3/pair`: pairs the worker with a machine's T3. Refusals are
/// 200 with `paired: false`, so `prism pair` prints T3's answer as Go did.
pub async fn post_pair(State(state): State<AppState>, headers: HeaderMap, body: Bytes) -> Response {
    if let Err(resp) = require_admin(&state, &headers) {
        return resp;
    }
    if body.len() > MAX_ADMIN_BODY {
        return error(
            StatusCode::PAYLOAD_TOO_LARGE,
            "The request body is too large.",
            "invalid",
        );
    }
    let Ok(req) = serde_json::from_slice::<PairBody>(&body) else {
        return error(
            StatusCode::BAD_REQUEST,
            "The request body is not valid JSON.",
            "invalid",
        );
    };
    // The argument Go's `prism pair` handed `PairArgument`.
    let mut argument = format!("@{} {}", req.machine, req.link);
    if !req.access.is_empty() {
        argument = format!("{argument} {}", req.access);
    }
    match crate::pairing::pair_argument(&state.client, &state.cfg, &argument, (state.now_fn)())
        .await
    {
        Ok(outcome) => (
            StatusCode::OK,
            Json(json!({ "paired": outcome.paired, "answer": outcome.answer })),
        )
            .into_response(),
        Err(err) => unavailable("pairing", err),
    }
}

/// `GET /v1/machines`: how the worker reaches each machine.
pub async fn get_machines(State(state): State<AppState>, headers: HeaderMap) -> Response {
    if let Err(resp) = require_admin(&state, &headers) {
        return resp;
    }
    match crate::machines::list_machines(
        &state.client,
        &state.cfg,
        &state.secrets,
        (state.now_fn)(),
    )
    .await
    {
        Ok(view) => (StatusCode::OK, Json(view)).into_response(),
        Err(err) => unavailable("machine list", err),
    }
}

/// `POST /v1/devices/{device}/token`: mints a device's Prism API token.
pub async fn post_device_token(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(device): Path<String>,
) -> Response {
    if let Err(resp) = require_admin(&state, &headers) {
        return resp;
    }
    match issue_device_token(&state.client, &device, (state.now_fn)()).await {
        Ok(Ok(token)) => {
            println!("prism-worker: minted a Prism API token for {device}");
            (
                StatusCode::OK,
                Json(json!({ "device": device, "token": token })),
            )
                .into_response()
        }
        Ok(Err(message)) => error(StatusCode::BAD_REQUEST, message, "invalid"),
        Err(err) => unavailable("device token", err),
    }
}

/// `PUT` and `DELETE /v1/placement/account-limits/{account}`: sets or
/// removes an account's temporary placement limit (`prism account-limit`).
pub async fn put_account_limit(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(account): Path<String>,
    body: Bytes,
) -> Response {
    if let Err(resp) = require_admin(&state, &headers) {
        return resp;
    }
    if body.len() > MAX_ADMIN_BODY {
        return error(StatusCode::PAYLOAD_TOO_LARGE, "Body too large.", "invalid");
    }
    let Ok(limit) = serde_json::from_slice::<rocky_prism::PlacementAccountLimit>(&body) else {
        return error(StatusCode::BAD_REQUEST, "Invalid JSON.", "invalid");
    };
    account_limit(&state, &account, Some(limit)).await
}

pub async fn delete_account_limit(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(account): Path<String>,
) -> Response {
    if let Err(resp) = require_admin(&state, &headers) {
        return resp;
    }
    account_limit(&state, &account, None).await
}

async fn account_limit(
    state: &AppState,
    account: &str,
    limit: Option<rocky_prism::PlacementAccountLimit>,
) -> Response {
    let now = (state.now_fn)();
    match rocky_prism::retry_unavailable(|| {
        state.client.set_account_limit(account, limit.clone(), now)
    })
    .await
    {
        Ok(()) => {
            println!("prism-worker: account limit for {account}: {limit:?}");
            (
                StatusCode::OK,
                Json(json!({ "account": account, "limit": limit })),
            )
                .into_response()
        }
        Err(rocky_prism::PrismError::InvalidInput(message)) => {
            error(StatusCode::BAD_REQUEST, message, "invalid")
        }
        Err(err) => unavailable("account limit", err),
    }
}

/// `DELETE /v1/devices/{device}/token`: revokes a device's Prism API token.
pub async fn delete_device_token(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(device): Path<String>,
) -> Response {
    if let Err(resp) = require_admin(&state, &headers) {
        return resp;
    }
    match revoke_device(&state.client, &device, (state.now_fn)()).await {
        Ok(revoked) => (
            StatusCode::OK,
            Json(json!({ "device": device, "revoked": revoked })),
        )
            .into_response(),
        Err(err) => unavailable("device revoke", err),
    }
}

#[cfg(test)]
mod tests {
    use crate::auth::{admin_token, machine_token};
    use crate::config::Config;
    use crate::http::{AppState, router};
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use http_body_util::BodyExt;
    use rocky_prism::PrismClient;
    use serde_json::{Value, json};
    use std::sync::Arc;
    use tower::ServiceExt;

    const KEY: &[u8] = b"test-event-key-0123456789abcdef0123456789";
    const NOW: i64 = 1_800_000_000;

    async fn app(dir: &std::path::Path, cfg: &str) -> (axum::Router, Arc<PrismClient>) {
        let client = Arc::new(
            PrismClient::open_or_create(dir.join("db").display().to_string(), None)
                .await
                .expect("client"),
        );
        let state = AppState::new(Arc::clone(&client), Some(KEY.to_vec()))
            .with_config(Arc::new(Config::parse(cfg).expect("config")))
            .with_secrets(Arc::new(|_: &str| None));
        let state = AppState {
            now_fn: Arc::new(|| NOW),
            ..state
        };
        (router(state), client)
    }

    async fn call(
        app: &axum::Router,
        method: &str,
        uri: &str,
        token: &str,
        body: Value,
    ) -> (StatusCode, Value) {
        let mut req = Request::builder().method(method).uri(uri);
        if !token.is_empty() {
            req = req.header("authorization", format!("Bearer {token}"));
        }
        let body = if body.is_null() {
            Body::empty()
        } else {
            Body::from(body.to_string())
        };
        let res = app
            .clone()
            .oneshot(req.body(body).expect("request"))
            .await
            .expect("response");
        let status = res.status();
        let bytes = res.into_body().collect().await.expect("body").to_bytes();
        (status, serde_json::from_slice(&bytes).unwrap_or_default())
    }

    #[tokio::test]
    async fn admin_routes_need_the_admin_token() {
        let dir = tempfile::tempdir().expect("tempdir");
        let (app, _) = app(dir.path(), "{}").await;
        let machine = machine_token(KEY, "david-mb").expect("token");
        let wrong = admin_token(b"another-key-0123456789abcdef0123456789").expect("token");
        for token in ["", machine.as_str(), wrong.as_str()] {
            for (method, uri) in [
                ("GET", "/v1/machines"),
                ("POST", "/v1/t3/pair"),
                ("POST", "/v1/devices/pixel/token"),
                ("DELETE", "/v1/devices/pixel/token"),
            ] {
                let (status, body) = call(&app, method, uri, token, json!({})).await;
                assert_eq!(status, StatusCode::UNAUTHORIZED, "{method} {uri}");
                assert_eq!(body["code"], "unauthorized");
            }
        }
    }

    #[tokio::test]
    async fn pair_answers_refusals_as_unpaired() {
        let dir = tempfile::tempdir().expect("tempdir");
        let (app, client) = app(
            dir.path(),
            r#"{"machines": {"ccs-oracle-1": {"t3": false}}}"#,
        )
        .await;
        let admin = admin_token(KEY).expect("admin");
        let (status, body) = call(
            &app,
            "POST",
            "/v1/t3/pair",
            &admin,
            json!({"machine": "ccs-oracle-1", "link": "one-use-code"}),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(
            body,
            json!({"paired": false, "answer": "ccs-oracle-1 has no T3, so there is nothing to pair: agents for it run on an SSH runner and use SSH."})
        );
        let (_, body) = call(
            &app,
            "POST",
            "/v1/t3/pair",
            &admin,
            json!({"machine": "new", "link": "one-use-code", "access": "admin"}),
        )
        .await;
        assert_eq!(body["paired"], false);
        assert!(
            body["answer"]
                .as_str()
                .unwrap()
                .starts_with("Access must be one of")
        );
        assert!(
            client
                .list_t3_sessions()
                .await
                .expect("sessions")
                .is_empty()
        );
    }

    #[tokio::test]
    async fn machines_lists_the_configured_machines() {
        let dir = tempfile::tempdir().expect("tempdir");
        let (app, _) = app(
            dir.path(),
            r#"{"machines": {"ccs-oracle-1": {"t3": false}, "david-mb": {}}}"#,
        )
        .await;
        let (status, body) = call(
            &app,
            "GET",
            "/v1/machines",
            &admin_token(KEY).unwrap(),
            Value::Null,
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(
            body["cluster"],
            json!({"name": "cluster", "runsOn": "", "workspace": ""})
        );
        let routes: Vec<(&str, &str, &str)> = body["machines"]
            .as_array()
            .unwrap()
            .iter()
            .map(|m| {
                (
                    m["name"].as_str().unwrap(),
                    m["route"].as_str().unwrap(),
                    m["state"].as_str().unwrap(),
                )
            })
            .collect();
        assert_eq!(
            routes,
            vec![
                ("ccs-oracle-1", "none", "no SSH runner answers"),
                ("david-mb", "none", "not paired"),
            ]
        );
    }

    #[tokio::test]
    async fn device_tokens_sign_a_phone_in_until_revoked() {
        let dir = tempfile::tempdir().expect("tempdir");
        let (app, client) = app(dir.path(), r#"{"android_publishers": ["builder"]}"#).await;
        let admin = admin_token(KEY).expect("admin");
        let (status, body) = call(
            &app,
            "POST",
            "/v1/devices/Bad_Name/token",
            &admin,
            Value::Null,
        )
        .await;
        assert_eq!(status, StatusCode::BAD_REQUEST);
        assert_eq!(body["error"], "invalid device name \"Bad_Name\"");

        let (status, body) =
            call(&app, "POST", "/v1/devices/pixel/token", &admin, Value::Null).await;
        assert_eq!(status, StatusCode::OK);
        let token = body["token"].as_str().expect("token").to_string();
        assert!(
            token.starts_with("prism_") && token.len() == 6 + 43,
            "{token}"
        );
        // Only the digest is stored.
        let stored = client
            .get_setting("device.token.pixel")
            .await
            .unwrap()
            .unwrap();
        assert!(!stored.contains(&token[6..]), "{stored}");

        let (status, body) = call(&app, "GET", "/v1/android/settings", &token, Value::Null).await;
        assert_eq!(status, StatusCode::NOT_FOUND);
        assert_eq!(
            body,
            json!({"error": "No settings are published for pixel.", "code": "not_found"})
        );

        // A second token replaces the first.
        let (_, body) = call(&app, "POST", "/v1/devices/pixel/token", &admin, Value::Null).await;
        let second = body["token"].as_str().unwrap().to_string();
        let (status, _) = call(&app, "GET", "/v1/android/settings", &token, Value::Null).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
        let (status, _) = call(&app, "GET", "/v1/android/settings", &second, Value::Null).await;
        assert_eq!(status, StatusCode::NOT_FOUND);

        let (_, body) = call(
            &app,
            "DELETE",
            "/v1/devices/pixel/token",
            &admin,
            Value::Null,
        )
        .await;
        assert_eq!(body, json!({"device": "pixel", "revoked": true}));
        let (_, body) = call(
            &app,
            "DELETE",
            "/v1/devices/pixel/token",
            &admin,
            Value::Null,
        )
        .await;
        assert_eq!(body, json!({"device": "pixel", "revoked": false}));
        let (status, body) = call(&app, "GET", "/v1/android/settings", &second, Value::Null).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
        assert_eq!(
            body,
            json!({"error": "Sign in again.", "code": "unauthorized"})
        );
    }

    #[tokio::test]
    async fn android_settings_are_checked_like_go_and_pushed_once_changed() {
        let dir = tempfile::tempdir().expect("tempdir");
        let (app, client) = app(dir.path(), r#"{"android_publishers": ["builder"]}"#).await;
        let builder = machine_token(KEY, "builder").unwrap();
        let other = machine_token(KEY, "home-mac").unwrap();
        let settings =
            json!({"device": "pixel", "settings": {"relay_url": "https://relay", "t3_url": null}});

        let (status, body) = call(
            &app,
            "PUT",
            "/v1/android/settings",
            &other,
            settings.clone(),
        )
        .await;
        assert_eq!(status, StatusCode::UNAUTHORIZED);
        assert_eq!(
            body,
            json!({"error": "Only a publisher machine may set a device's settings.", "code": "unauthorized"})
        );
        let long = "x".repeat(4097);
        for (bad, message) in [
            (
                json!({"device": "Pixel", "settings": {"relay_url": "x"}}),
                "device must be a device name.",
            ),
            (
                json!({"device": "pixel", "settings": {}}),
                "settings must name at least one key.",
            ),
            (
                json!({"device": "pixel", "settings": {"prism_token": "x"}}),
                "The server can't set \"prism_token\".",
            ),
            (
                json!({"device": "pixel", "settings": {"relay_token": long}}),
                "The value of \"relay_token\" is longer than 4096 bytes.",
            ),
        ] {
            let (status, body) = call(&app, "PUT", "/v1/android/settings", &builder, bad).await;
            assert_eq!(status, StatusCode::BAD_REQUEST);
            assert_eq!(body, json!({"error": message, "code": "invalid"}));
        }

        let (status, body) = call(
            &app,
            "PUT",
            "/v1/android/settings",
            &builder,
            settings.clone(),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body, json!({"device": "pixel", "changed": true}));
        let (_, body) = call(
            &app,
            "PUT",
            "/v1/android/settings",
            &builder,
            settings.clone(),
        )
        .await;
        assert_eq!(body, json!({"device": "pixel", "changed": false}));

        let (_, minted) = call(
            &app,
            "POST",
            "/v1/devices/pixel/token",
            &admin_token(KEY).unwrap(),
            Value::Null,
        )
        .await;
        let (status, body) = call(
            &app,
            "GET",
            "/v1/android/settings",
            minted["token"].as_str().unwrap(),
            Value::Null,
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(
            body,
            json!({"device": "pixel", "settings": {"relay_url": "https://relay", "t3_url": null},
                   "updatedAt": NOW, "updatedBy": "builder"})
        );

        let rows = client.list_settings().await.unwrap();
        let pushes = crate::android_settings::settings_pushes(&rows, NOW + 60);
        assert_eq!(pushes.len(), 1, "{pushes:?}");
        let p = &pushes[0];
        assert_eq!(
            (
                p.kind.as_str(),
                p.key.clone(),
                p.title.as_str(),
                p.body.as_str(),
                p.device.as_str()
            ),
            (
                "app.settings",
                format!("app.settings:pixel:{NOW}"),
                "Prism settings",
                "New settings for pixel.",
                "pixel"
            )
        );
        assert!(p.message().contains(r#""device":"pixel""#));
        assert!(crate::android_settings::settings_pushes(&rows, NOW + 86_400).is_empty());
    }
}
