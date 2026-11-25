use crate::approvals::register_t3_client_session;
use crate::auth::verify_machine_token;
use crate::config::Config;
use crate::lease::Eligibility;
use crate::rocky_route::{RockyRoute, rocky_upgrade};
use axum::{
    Json, Router,
    body::Bytes,
    extract::{DefaultBodyLimit, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post, put},
};
use chrono::Utc;
use rocky_prism::{
    AlertWebhookError, HostEventError, HostEventReport, PlacementSyncRequest, PrismClient,
    T3ClientSessionRow,
};
use serde::Deserialize;
use serde_json::json;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::watch;

const MAX_SYNC_BODY_BYTES: usize = 4 << 20;
const MAX_WEBHOOK_BYTES: usize = 2 << 20;
const MAX_EVENT_BODY_BYTES: usize = 256 << 10;

#[derive(Clone)]
pub struct AppState {
    pub client: Arc<PrismClient>,
    pub event_key: Option<Vec<u8>>,
    pub now_fn: Arc<dyn Fn() -> i64 + Send + Sync>,
    pub rocky: Option<RockyRoute>,
    /// When set, Alertmanager's webhook must carry it as a bearer token.
    pub alerts_token: Option<String>,
    pub cfg: Arc<Config>,
    pub data_dir: PathBuf,
    /// This worker's node name (the pod name).
    pub node: String,
    /// Another worker's HTTP base URL by node name, to fetch an APK it stores.
    pub peer_http: Option<PeerHttp>,
    /// Reads the T3 credentials the config names (`prism machines`).
    pub secrets: crate::t3::Secrets,
    /// Whether this worker's replica can serve (`/ready`); ready unless set.
    pub eligible: watch::Receiver<Eligibility>,
    /// Where placement decisions are written, when configured.
    pub placement_log: Option<Arc<crate::placement_log::PlacementLog>>,
    /// Where placement reads the accounts' usage, when configured.
    pub usage: Option<Arc<crate::usage::UsageSource>>,
    /// The coordinator this worker's seals go to (`coordination.rs`).
    #[cfg(feature = "coordinator")]
    pub coordinator: Option<Arc<rocky_prism::coordinator::CoordinatorLink>>,
}

pub type PeerHttp = Arc<dyn Fn(&str) -> Option<String> + Send + Sync>;

impl AppState {
    pub fn new(client: Arc<PrismClient>, event_key: Option<Vec<u8>>) -> Self {
        Self {
            client,
            event_key,
            now_fn: Arc::new(|| Utc::now().timestamp()),
            rocky: None,
            alerts_token: None,
            cfg: Arc::new(Config::default()),
            data_dir: PathBuf::from("./local/prism"),
            node: "local".into(),
            peer_http: None,
            secrets: Arc::new(crate::auth::secret),
            eligible: watch::channel(Eligibility::Ready).1,
            placement_log: None,
            usage: None,
            #[cfg(feature = "coordinator")]
            coordinator: None,
        }
    }

    #[cfg(feature = "coordinator")]
    pub fn with_coordinator(
        mut self,
        link: Option<Arc<rocky_prism::coordinator::CoordinatorLink>>,
    ) -> Self {
        self.coordinator = link;
        self
    }

    pub fn with_eligibility(mut self, eligible: watch::Receiver<Eligibility>) -> Self {
        self.eligible = eligible;
        self
    }

    #[cfg(test)]
    pub fn with_secrets(mut self, secrets: crate::t3::Secrets) -> Self {
        self.secrets = secrets;
        self
    }

    pub fn with_alerts_token(mut self, token: Option<String>) -> Self {
        self.alerts_token = token.filter(|t| !t.is_empty());
        self
    }

    pub fn with_rocky(mut self, route: RockyRoute) -> Self {
        self.rocky = Some(route);
        self
    }

    pub fn with_config(mut self, cfg: Arc<Config>) -> Self {
        self.placement_log =
            crate::placement_log::PlacementLog::from_config(&cfg, &self.secrets, &self.node);
        self.usage = crate::usage::UsageSource::from_config(&cfg, &self.secrets);
        self.cfg = cfg;
        self
    }

    pub fn with_peer_http(mut self, peer_http: PeerHttp) -> Self {
        self.peer_http = Some(peer_http);
        self
    }

    pub fn with_node(mut self, node: impl Into<String>) -> Self {
        self.node = node.into();
        self
    }

    pub fn with_data_dir(mut self, data_dir: PathBuf) -> Self {
        self.data_dir = data_dir;
        self
    }
}

pub fn router(state: AppState) -> Router {
    let routes = Router::new();
    #[cfg(feature = "coordinator")]
    let routes = routes
        .route("/v1/rocky/seal", get(crate::coordination::get_seal))
        .route("/v1/rocky/trust", post(crate::coordination::post_trust))
        .route(
            "/v1/rocky/coordinate",
            post(crate::coordination::post_coordinate),
        );
    routes
        .route("/health", get(health))
        .route("/ready", get(ready))
        .route("/v1/health", get(v1_health))
        .route("/rocky/{node}", get(rocky_upgrade))
        .route(
            "/alerts/cluster",
            post(alerts_cluster).layer(DefaultBodyLimit::max(MAX_WEBHOOK_BYTES + 1)),
        )
        .route(
            "/v1/placement/sync",
            post(placement_sync).layer(DefaultBodyLimit::max(MAX_SYNC_BODY_BYTES + 1)),
        )
        .route(
            "/v1/events",
            post(post_event).layer(DefaultBodyLimit::max(MAX_EVENT_BODY_BYTES + 1)),
        )
        .route("/v1/ping", get(ping))
        .route("/v1/t3/client-session", put(put_t3_client_session))
        .route("/v1/t3/device-session", put(put_t3_device_session))
        .route("/v1/t3/device-sessions", get(get_t3_device_sessions))
        .route(
            "/v1/android/release",
            get(crate::android::get_release).put(crate::android::put_release),
        )
        .route(
            "/v1/android/release.apk",
            get(crate::android::get_release_apk),
        )
        .route(
            "/v1/android/settings",
            get(crate::android_settings::get_settings).put(crate::android_settings::put_settings),
        )
        .route(
            "/v1/telemetry/write",
            post(crate::telemetry::post_write)
                .layer(DefaultBodyLimit::max(crate::telemetry::MAX_BODY + 1)),
        )
        .route("/v1/t3/pair", post(crate::admin::post_pair))
        .route("/v1/machines", get(crate::admin::get_machines))
        .route(
            "/v1/devices/{device}/token",
            post(crate::admin::post_device_token).delete(crate::admin::delete_device_token),
        )
        .route(
            "/v1/placement/account-limits/{account}",
            put(crate::admin::put_account_limit).delete(crate::admin::delete_account_limit),
        )
        .with_state(state)
}

async fn health() -> StatusCode {
    StatusCode::OK
}

/// The readiness probe: an incomplete replica takes no traffic (webhooks,
/// the apps' API), so Alertmanager's retries reach a worker that can store
/// them. A merely busy one stays ready, or a slow cluster would take none.
async fn ready(State(state): State<AppState>) -> StatusCode {
    if *state.eligible.borrow() == Eligibility::Incomplete {
        StatusCode::SERVICE_UNAVAILABLE
    } else {
        StatusCode::OK
    }
}

/// Whether this worker takes the devices' requests. A device that gets no
/// `ok` within its 5 s runs its request itself (`docs/direct-fix.md`), so this
/// answers at once from the replica's last verdict, as `/ready` does, and
/// never waits for a read: a busy replica (Preempted, Timeout, a query that
/// takes seconds) still runs the requests, only an incomplete one doesn't.
async fn v1_health(State(state): State<AppState>) -> Response {
    if *state.eligible.borrow() == Eligibility::Incomplete {
        (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(json!({ "ok": false })),
        )
            .into_response()
    } else {
        (StatusCode::OK, Json(json!({ "ok": true }))).into_response()
    }
}

/// Alertmanager's webhook. Any worker accepts it and writes the alert rows;
/// the leader numbers the episodes and wakes the phone.
async fn alerts_cluster(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    if let Some(expected) = state.alerts_token.as_deref() {
        let given = headers
            .get(axum::http::header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer "))
            .map(str::trim);
        if !given.is_some_and(|g| constant_time_eq(g.as_bytes(), expected.as_bytes())) {
            return StatusCode::UNAUTHORIZED.into_response();
        }
    }
    // As the Go server: a body without a length, empty or over 2 MiB is refused.
    let length = headers
        .get(axum::http::header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<usize>().ok())
        .unwrap_or(0);
    if length == 0 || length > MAX_WEBHOOK_BYTES || body.len() > MAX_WEBHOOK_BYTES {
        return StatusCode::PAYLOAD_TOO_LARGE.into_response();
    }
    match state
        .client
        .record_alert_webhook(&body, (state.now_fn)())
        .await
    {
        Ok(()) => StatusCode::OK.into_response(),
        Err(AlertWebhookError::Invalid(reason)) => {
            eprintln!("prism-worker: invalid webhook: {reason}");
            StatusCode::BAD_REQUEST.into_response()
        }
        Err(err) => {
            eprintln!("prism-worker: webhook delivery failed; Alertmanager must retry: {err}");
            StatusCode::SERVICE_UNAVAILABLE.into_response()
        }
    }
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

async fn placement_sync(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let Some(key) = state.event_key.as_deref().filter(|k| !k.is_empty()) else {
        return (StatusCode::SERVICE_UNAVAILABLE, "event API not configured").into_response();
    };
    let auth_header = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let Some(token) = auth_header.strip_prefix("Bearer ").map(str::trim) else {
        return (StatusCode::UNAUTHORIZED, "invalid token").into_response();
    };
    let Some(machine) = verify_machine_token(key, token) else {
        return (StatusCode::UNAUTHORIZED, "invalid token").into_response();
    };

    if body.len() > MAX_SYNC_BODY_BYTES {
        return (StatusCode::PAYLOAD_TOO_LARGE, "body too large").into_response();
    }
    let req: PlacementSyncRequest = match serde_json::from_slice(&body) {
        Ok(req) => req,
        Err(_) => return (StatusCode::BAD_REQUEST, "invalid JSON").into_response(),
    };

    let now = (state.now_fn)();
    let (threads, models) = (req.threads.len(), req.models.len());
    let usage = match &state.usage {
        Some(source) => source.current().await,
        None => Arc::default(),
    };
    match state
        .client
        .sync_placement_with_usage(machine.clone(), req, now, &usage)
        .await
    {
        Ok(resp) => {
            if let Some(log) = &state.placement_log {
                log.record_sync(&machine, now, &resp);
            }
            (StatusCode::OK, Json(resp)).into_response()
        }
        Err(err) => {
            if let Some(log) = &state.placement_log {
                log.record_failure(&machine, now, &err.to_string());
            }
            eprintln!(
                "prism-worker: placement sync failed for {machine} ({threads} threads, {models} models, {} bytes): {err}",
                body.len()
            );
            (StatusCode::SERVICE_UNAVAILABLE, "try again").into_response()
        }
    }
}

#[allow(clippy::result_large_err)]
pub(crate) fn require_machine_token(
    state: &AppState,
    headers: &HeaderMap,
) -> Result<String, Response> {
    let Some(key) = state.event_key.as_deref().filter(|k| !k.is_empty()) else {
        return Err((StatusCode::SERVICE_UNAVAILABLE, "event API not configured").into_response());
    };
    let auth_header = headers
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let Some(token) = auth_header.strip_prefix("Bearer ").map(str::trim) else {
        return Err((StatusCode::UNAUTHORIZED, "invalid token").into_response());
    };
    verify_machine_token(key, token)
        .ok_or_else(|| (StatusCode::UNAUTHORIZED, "invalid token").into_response())
}

/// Machine check reports (`prism run`).
async fn post_event(State(state): State<AppState>, headers: HeaderMap, body: Bytes) -> Response {
    let machine = match require_machine_token(&state, &headers) {
        Ok(m) => m,
        Err(resp) => return resp,
    };
    if body.len() > MAX_EVENT_BODY_BYTES {
        return (StatusCode::PAYLOAD_TOO_LARGE, "body too large").into_response();
    }
    let report: HostEventReport = match serde_json::from_slice(&body) {
        Ok(r) => r,
        Err(_) => return (StatusCode::BAD_REQUEST, "invalid JSON").into_response(),
    };
    let check = report.check.trim().to_ascii_lowercase();
    // As the Go server answers `prism run`: 202 with the event, plain-text errors.
    match state
        .client
        .record_host_event(&machine, report, (state.now_fn)())
        .await
    {
        Ok(s) => (
            StatusCode::ACCEPTED,
            Json(json!({ "machine": machine, "check": check, "state": s })),
        )
            .into_response(),
        Err(HostEventError::Invalid(msg)) => (StatusCode::BAD_REQUEST, msg).into_response(),
        Err(HostEventError::TooManyEvents) => (
            StatusCode::TOO_MANY_REQUESTS,
            "too many open events for this machine",
        )
            .into_response(),
        Err(err) => {
            eprintln!("prism-worker: event report failed: {err}");
            (StatusCode::SERVICE_UNAVAILABLE, "try again").into_response()
        }
    }
}

/// `prism` checks its machine token (`GET /v1/ping`).
async fn ping(State(state): State<AppState>, headers: HeaderMap) -> Response {
    match require_machine_token(&state, &headers) {
        Ok(machine) => (StatusCode::OK, Json(json!({ "machine": machine }))).into_response(),
        Err(resp) => resp,
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ClientSessionPayload {
    #[serde(default)]
    origin: String,
    #[serde(default)]
    token: String,
    #[serde(default)]
    environment_id: String,
    #[serde(default)]
    expires_at: i64,
}

/// Registers a machine's T3 client API session (`prism register-t3`).
async fn put_t3_client_session(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let machine = match require_machine_token(&state, &headers) {
        Ok(m) => m,
        Err(resp) => return resp,
    };
    let req: ClientSessionPayload = match serde_json::from_slice(&body) {
        Ok(r) => r,
        Err(_) => return (StatusCode::BAD_REQUEST, "invalid JSON").into_response(),
    };
    let row = T3ClientSessionRow {
        machine: machine.clone(),
        origin: req.origin,
        token: req.token,
        environment_id: req.environment_id,
        expires_at: req.expires_at,
    };
    match register_t3_client_session(&state.client, &state.cfg, &machine, row, (state.now_fn)())
        .await
    {
        Ok(()) => (StatusCode::OK, Json(json!({ "ok": true }))).into_response(),
        Err((status, msg)) => (status, Json(json!({ "error": msg }))).into_response(),
    }
}

/// Registers a device's session with this machine's T3 (`prism
/// register-t3-devices`, `crate::direct`).
async fn put_t3_device_session(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let machine = match require_machine_token(&state, &headers) {
        Ok(m) => m,
        Err(resp) => return resp,
    };
    let Ok(payload) = serde_json::from_slice(&body) else {
        return (StatusCode::BAD_REQUEST, "invalid JSON").into_response();
    };
    match crate::direct::register_device_session(
        &state.client,
        &state.cfg,
        &machine,
        payload,
        (state.now_fn)(),
    )
    .await
    {
        Ok(()) => (StatusCode::OK, Json(json!({ "ok": true }))).into_response(),
        Err((status, msg)) => (status, Json(json!({ "error": msg }))).into_response(),
    }
}

/// When the devices' sessions with this machine's T3 expire.
async fn get_t3_device_sessions(State(state): State<AppState>, headers: HeaderMap) -> Response {
    let machine = match require_machine_token(&state, &headers) {
        Ok(m) => m,
        Err(resp) => return resp,
    };
    match crate::direct::device_session_expiries(
        &state.client,
        &state.cfg,
        &machine,
        (state.now_fn)(),
    )
    .await
    {
        Ok(body) => (StatusCode::OK, Json(body)).into_response(),
        Err(err) => (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(json!({ "error": err.to_string() })),
        )
            .into_response(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth::machine_token;
    use axum::body::Body;
    use axum::http::Request;
    use http_body_util::BodyExt;
    use rocky_prism::PlacementSyncResponse;
    use tower::ServiceExt;

    #[tokio::test]
    async fn ready_follows_the_replica_and_health_does_not() {
        let dir = tempfile::tempdir().expect("tempdir");
        let client = Arc::new(
            PrismClient::open_or_create(dir.path().display().to_string(), None)
                .await
                .expect("open_or_create"),
        );
        let (eligible, rx) = watch::channel(Eligibility::Unknown);
        let app = router(AppState::new(Arc::clone(&client), None).with_eligibility(rx));
        let status = |uri: &'static str| {
            let app = app.clone();
            async move {
                app.oneshot(
                    Request::builder()
                        .uri(uri)
                        .body(Body::empty())
                        .expect("req"),
                )
                .await
                .expect("response")
                .status()
            }
        };
        assert_eq!(
            status("/ready").await,
            StatusCode::OK,
            "unknown still serves"
        );
        eligible.send_replace(Eligibility::Incomplete);
        assert_eq!(status("/ready").await, StatusCode::SERVICE_UNAVAILABLE);
        assert_eq!(
            status("/health").await,
            StatusCode::OK,
            "liveness is unaffected"
        );
        eligible.send_replace(Eligibility::Ready);
        assert_eq!(status("/ready").await, StatusCode::OK);
    }

    /// The devices' reachability check (`GET /v1/health`, 5 s) follows the
    /// replica's verdict like `/ready` and reads nothing. It used to run a
    /// `SELECT 1`, which a busy replica took over 5 s to answer (measured on
    /// all three workers on 2026-11-24), so the phone took workers that were
    /// up and leading for unreachable and ran its request itself.
    #[tokio::test]
    async fn v1_health_answers_from_the_replica_verdict() {
        let dir = tempfile::tempdir().expect("tempdir");
        let client = Arc::new(
            PrismClient::open_or_create(dir.path().display().to_string(), None)
                .await
                .expect("open_or_create"),
        );
        let (eligible, rx) = watch::channel(Eligibility::Unknown);
        let app = router(AppState::new(Arc::clone(&client), None).with_eligibility(rx));
        let health = || {
            let app = app.clone();
            async move {
                let res = app
                    .oneshot(
                        Request::builder()
                            .uri("/v1/health")
                            .body(Body::empty())
                            .expect("req"),
                    )
                    .await
                    .expect("response");
                let status = res.status();
                let bytes = res.into_body().collect().await.expect("body").to_bytes();
                let v: serde_json::Value = serde_json::from_slice(&bytes).expect("json");
                (status, v["ok"].clone())
            }
        };
        assert_eq!(health().await, (StatusCode::OK, json!(true)), "busy so far");
        eligible.send_replace(Eligibility::Incomplete);
        assert_eq!(
            health().await,
            (StatusCode::SERVICE_UNAVAILABLE, json!(false)),
            "an incomplete replica can't run the requests, though SELECT 1 works"
        );
        eligible.send_replace(Eligibility::Ready);
        assert_eq!(health().await, (StatusCode::OK, json!(true)));
    }

    #[tokio::test]
    async fn health_and_sync_endpoints() {
        let dir = tempfile::tempdir().expect("tempdir");
        let client = Arc::new(
            PrismClient::open_or_create(dir.path().display().to_string(), None)
                .await
                .expect("open_or_create"),
        );
        let key = b"test-event-key-0123456789abcdef0123456789".to_vec();
        let token = machine_token(&key, "david-mb").expect("machine_token");
        let app = router(AppState::new(Arc::clone(&client), Some(key)));

        // GET /health
        let res = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/health")
                    .body(Body::empty())
                    .expect("req"),
            )
            .await
            .expect("health");
        assert_eq!(res.status(), StatusCode::OK);

        // GET /v1/health
        let res = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/v1/health")
                    .body(Body::empty())
                    .expect("req"),
            )
            .await
            .expect("v1 health");
        assert_eq!(res.status(), StatusCode::OK);
        let bytes = res.into_body().collect().await.expect("body").to_bytes();
        let v: serde_json::Value = serde_json::from_slice(&bytes).expect("json");
        assert_eq!(v["ok"], true);

        // POST /v1/placement/sync without auth -> 401
        let res = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/placement/sync")
                    .body(Body::from("{}"))
                    .expect("req"),
            )
            .await
            .expect("unauth");
        assert_eq!(res.status(), StatusCode::UNAUTHORIZED);

        // POST /v1/placement/sync with bad JSON -> 400
        let res = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/placement/sync")
                    .header("authorization", format!("Bearer {token}"))
                    .body(Body::from("{not-json"))
                    .expect("req"),
            )
            .await
            .expect("bad json");
        assert_eq!(res.status(), StatusCode::BAD_REQUEST);

        // POST /v1/placement/sync with valid token and payload -> 200
        let payload = serde_json::json!({
            "v": 1,
            "environmentId": "env-a",
            "models": [{
                "instanceId": "claudeAgent",
                "model": "claude-david/claude-opus-5-5",
                "label": "Opus 5.5 · claude-david",
                "session": { "usedPercent": 42.0 }
            }],
            "threads": []
        });
        let res = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/placement/sync")
                    .header("authorization", format!("Bearer {token}"))
                    .header("content-type", "application/json")
                    .body(Body::from(payload.to_string()))
                    .expect("req"),
            )
            .await
            .expect("sync");
        assert_eq!(res.status(), StatusCode::OK);
        let bytes = res.into_body().collect().await.expect("body").to_bytes();
        let sync_resp: PlacementSyncResponse = serde_json::from_slice(&bytes).expect("resp json");
        assert_eq!(sync_resp.v, 1);
        // Nothing is built in: a new database has no profiles.
        assert!(sync_resp.profiles.is_empty());
    }

    async fn post_webhook(app: &Router, body: &str, auth: Option<&str>) -> StatusCode {
        let mut req = Request::builder()
            .method("POST")
            .uri("/alerts/cluster")
            .header("content-type", "application/json")
            .header("content-length", body.len().to_string());
        if let Some(auth) = auth {
            req = req.header("authorization", auth);
        }
        app.clone()
            .oneshot(req.body(Body::from(body.to_string())).expect("req"))
            .await
            .expect("webhook")
            .status()
    }

    #[tokio::test]
    async fn alertmanager_webhook() {
        let dir = tempfile::tempdir().expect("tempdir");
        let client = Arc::new(
            PrismClient::open_or_create(dir.path().display().to_string(), None)
                .await
                .expect("open_or_create"),
        );
        let app = router(AppState::new(Arc::clone(&client), None));
        let firing = serde_json::json!({"status": "firing", "alerts": [{
            "labels": {"alertname": "PodCrashLooping", "pod": "x"},
            "startsAt": "2026-11-21T10:00:00Z"}]})
        .to_string();
        assert_eq!(post_webhook(&app, &firing, None).await, StatusCode::OK);
        assert_eq!(post_webhook(&app, &firing, None).await, StatusCode::OK);
        let alerts = client
            .list_alerts(rocky_prism::AlertFilter::Open)
            .await
            .expect("list");
        assert_eq!(alerts.len(), 1);
        assert_eq!(alerts[0].category, "Workloads");
        assert_eq!(
            post_webhook(&app, "{nope", None).await,
            StatusCode::BAD_REQUEST
        );
        assert_eq!(
            post_webhook(&app, "", None).await,
            StatusCode::PAYLOAD_TOO_LARGE
        );
        let huge = format!("{{\"pad\": \"{}\"}}", "x".repeat(MAX_WEBHOOK_BYTES));
        assert_eq!(
            post_webhook(&app, &huge, None).await,
            StatusCode::PAYLOAD_TOO_LARGE
        );

        // With PRISM_ALERTS_TOKEN set, the webhook needs it.
        let app =
            router(AppState::new(Arc::clone(&client), None).with_alerts_token(Some("hook".into())));
        assert_eq!(
            post_webhook(&app, &firing, None).await,
            StatusCode::UNAUTHORIZED
        );
        assert_eq!(
            post_webhook(&app, &firing, Some("Bearer wrong")).await,
            StatusCode::UNAUTHORIZED
        );
        assert_eq!(
            post_webhook(&app, &firing, Some("Bearer hook")).await,
            StatusCode::OK
        );
    }

    #[tokio::test]
    async fn host_events_and_ping() {
        let dir = tempfile::tempdir().expect("tempdir");
        let client = Arc::new(
            PrismClient::open_or_create(dir.path().display().to_string(), None)
                .await
                .expect("open_or_create"),
        );
        let key = b"test-event-key-0123456789abcdef0123456789";
        let state = AppState::new(Arc::clone(&client), Some(key.to_vec()));
        let app = router(state);

        let host_token = machine_token(key, "home-mac").unwrap();

        // 1. POST /v1/events requires a valid token
        let res = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/events")
                    .header("content-type", "application/json")
                    .body(Body::from(
                        json!({"check": "disk", "status": "failed", "summary": "95% full"})
                            .to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::UNAUTHORIZED);

        // Valid POST /v1/events opens a HostCheckFailed alert
        let res = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/v1/events")
                    .header("authorization", format!("Bearer {host_token}"))
                    .header("content-type", "application/json")
                    .body(Body::from(
                        json!({"check": "disk", "status": "failed", "summary": "95% full"})
                            .to_string(),
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::ACCEPTED);
        let body = res.into_body().collect().await.unwrap().to_bytes();
        let body: serde_json::Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(
            body,
            json!({"machine": "home-mac", "check": "disk", "state": "firing"})
        );
        let res = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/v1/ping")
                    .header("authorization", format!("Bearer {host_token}"))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::OK);
        let body = res.into_body().collect().await.unwrap().to_bytes();
        assert_eq!(body.as_ref(), br#"{"machine":"home-mac"}"#);
        let res = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/v1/ping")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::UNAUTHORIZED);
        let open = client
            .list_alerts(rocky_prism::AlertFilter::Open)
            .await
            .unwrap();
        assert_eq!(open.len(), 1);
        assert_eq!(open[0].name, "disk");
    }
}
