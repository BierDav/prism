//! The phone's own route to T3 (`api.rs`, `prism_worker::direct`): while
//! the workers are unreachable, `Replica::call` runs the phone's request in
//! T3 with the phone's own sessions; while they answer, it leaves the
//! request to them. A fake T3 (MCP and client API) and a fake health
//! endpoint stand in for T3 and the workers.

use axum::{
    Json, Router,
    extract::{State, ws::Message, ws::WebSocketUpgrade},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use prism_worker::direct::{
    CONFIG_SETTING, DeviceSession, KIND_CLIENT, KIND_MCP, device_config, session_key,
};
use rocky_prism::{PrismClient, T3ClientSessionRow};
use rocky_prism_ffi::{ApiRequest, Replica, open_replica};
use serde_json::{Value, json};
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicBool, Ordering},
};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const DEVICE: &str = "david-pixel";
const TOKEN: &str = "device-token";

fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("clock")
        .as_secs() as i64
}

async fn serve(app: Router) -> String {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind");
    let addr = listener.local_addr().expect("addr");
    tokio::spawn(async move {
        let _ = axum::serve(listener, app).await;
    });
    format!("http://{addr}")
}

/// T3 on david-server: MCP launches and stops, and one pending approval
/// on its client API.
#[derive(Clone, Default)]
struct FakeT3 {
    launches: Arc<Mutex<Vec<Value>>>,
    interrupts: Arc<Mutex<Vec<Value>>>,
    decisions: Arc<Mutex<Vec<String>>>,
    answered: Arc<AtomicBool>,
}

fn authed(h: &HeaderMap) -> bool {
    h.get("authorization").and_then(|v| v.to_str().ok()) == Some(&format!("Bearer {TOKEN}"))
}

async fn mcp(State(t3): State<FakeT3>, h: HeaderMap, Json(req): Json<Value>) -> Response {
    if !authed(&h) {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    match req["method"].as_str().unwrap_or("") {
        "initialize" => {
            return (
                [("Mcp-Session-Id", "s1")],
                Json(json!({"jsonrpc": "2.0", "id": 1, "result": {}})),
            )
                .into_response();
        }
        "notifications/initialized" => return StatusCode::ACCEPTED.into_response(),
        _ => {}
    }
    let args = req["params"]["arguments"].clone();
    let result = match req["params"]["name"].as_str().unwrap_or("") {
        "orchestrator_capabilities" => {
            json!({"providers": [], "placement": {"profiles": [{"id": "default", "label": "Default"}]}})
        }
        "t3_project_list" => json!({"projects": [{
            "id": "project-platform", "workspaceRoot": "/srv/selfhosted-platform",
            "repositoryIdentity": {"locator": {"source": "git-remote", "remoteName": "origin"}}
        }]}),
        "t3_environment_links" => json!({"links": []}),
        "t3_thread_launch" => {
            let mut launches = t3.launches.lock().expect("launches");
            launches.push(args);
            let thread = format!("thread-{}", launches.len());
            json!({"threadId": thread, "link": format!("t3-thread://v2/env/{thread}")})
        }
        "t3_thread_interrupt" => {
            t3.interrupts.lock().expect("interrupts").push(args);
            json!({"interrupted": true})
        }
        other => json!({"error": other}),
    };
    Json(json!({"jsonrpc": "2.0", "id": 1, "result": {"structuredContent": result}}))
        .into_response()
}

fn projection(t3: &FakeT3) -> String {
    let status = if t3.answered.load(Ordering::SeqCst) {
        "resolved"
    } else {
        "pending"
    };
    json!({"projection": {
        "runtimeRequests": [{"id": "request:1", "kind": "command", "status": status}],
        "turnItems": [{"type": "approval_request", "requestId": "request:1", "prompt": "printf hello",
            "options": [{"decision": "accept", "label": "Allow once"}, {"decision": "decline", "label": "Deny"}]}],
    }})
    .to_string()
}

impl FakeT3 {
    fn router(&self) -> Router {
        Router::new()
            .route("/mcp", post(mcp))
            .route(
                "/api/orchestration/shell",
                get(|h: HeaderMap| async move {
                    if !authed(&h) {
                        return StatusCode::UNAUTHORIZED.into_response();
                    }
                    Json(json!({"threads": [{"id": "thread:1", "title": "Test",
                        "pendingRuntimeRequest": {"id": "request:1"}}]}))
                    .into_response()
                }),
            )
            .route(
                "/api/orchestration/threads/{id}",
                get(|State(t3): State<FakeT3>, h: HeaderMap| async move {
                    if !authed(&h) {
                        return StatusCode::UNAUTHORIZED.into_response();
                    }
                    projection(&t3).into_response()
                }),
            )
            .route(
                "/ws",
                get(
                    |State(t3): State<FakeT3>, h: HeaderMap, ws: WebSocketUpgrade| async move {
                        if !authed(&h) {
                            return StatusCode::UNAUTHORIZED.into_response();
                        }
                        ws.on_upgrade(move |mut socket| async move {
                            if let Some(Ok(Message::Text(raw))) = socket.recv().await {
                                let v: Value = serde_json::from_str(&raw).expect("frame");
                                let d = v["payload"]["decision"].as_str().unwrap_or("");
                                t3.decisions.lock().expect("decisions").push(d.into());
                                t3.answered.store(true, Ordering::SeqCst);
                                let _ = socket
                                    .send(Message::Text(
                                        r#"{"_tag":"Exit","requestId":"1","exit":{"_tag":"Success","value":{"sequence":1}}}"#.into(),
                                    ))
                                    .await;
                            }
                        })
                    },
                ),
            )
            .with_state(self.clone())
    }
}

/// The workers' health endpoint, up or down.
async fn workers(up: Arc<AtomicBool>) -> String {
    serve(Router::new().route(
        "/v1/health",
        get(move || {
            let up = up.load(Ordering::SeqCst);
            async move {
                if up {
                    (StatusCode::OK, Json(json!({"ok": true})))
                } else {
                    (StatusCode::SERVICE_UNAVAILABLE, Json(json!({"ok": false})))
                }
            }
        }),
    ))
    .await
}

/// A URL nothing listens on.
async fn nobody() -> String {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind");
    let addr = listener.local_addr().expect("addr");
    drop(listener);
    format!("http://{addr}")
}

struct Phone {
    replica: Arc<Replica>,
    t3: FakeT3,
    _dir: tempfile::TempDir,
}

/// The phone's replica as the workers left it: alert #1 open, an approval
/// pending on david-server, the published config and, with `sessions`, the
/// phone's own sessions with david-server's T3.
async fn phone(sessions: bool) -> Phone {
    let t3 = FakeT3::default();
    let origin = serve(t3.router()).await;
    let dir = tempfile::tempdir().expect("tempdir");
    let path = dir.path().display().to_string();
    let worker = PrismClient::open_or_create(path.clone(), None)
        .await
        .expect("open");
    let at = now();
    let body = json!({"status": "firing", "alerts": [{
        "labels": {"alertname": "NodeNotReady", "severity": "critical"},
        "annotations": {"summary": "NodeNotReady fired"},
        "startsAt": "2026-11-21T10:00:00Z",
    }]});
    worker
        .record_alert_webhook(body.to_string().as_bytes(), at)
        .await
        .expect("webhook");
    worker.assign_alert_numbers(at).await.expect("numbers");
    let cfg = prism_worker::config::Config::parse(
        &json!({
            "cluster_machine": "david-server",
            "machines": {"david-server": {"t3_url": "http://10.250.0.1:3773", "t3_token": "T3_ACCESS_TOKEN"}},
            "t3_workspace": "/srv/selfhosted-platform",
            "devices": {DEVICE: {"key": ""}},
        })
        .to_string(),
    )
    .expect("config");
    worker
        .set_setting(CONFIG_SETTING, &device_config(&cfg), at)
        .await
        .expect("config");
    // The workers' approval scan, with their own client session.
    worker
        .upsert_t3_client_session(&T3ClientSessionRow {
            machine: "david-server".into(),
            origin: origin.clone(),
            token: TOKEN.into(),
            environment_id: "env".into(),
            expires_at: at + 3600,
        })
        .await
        .expect("worker session");
    let pusher =
        prism_worker::push::Pusher::new("http://127.0.0.1:9", "t", Arc::new(|_: &str| None));
    prism_worker::approvals::scan_approvals(&worker, &pusher, at)
        .await
        .expect("scan");
    // The workers' session expires, so only the phone's own can answer.
    worker
        .upsert_t3_client_session(&T3ClientSessionRow {
            machine: "david-server".into(),
            origin: origin.clone(),
            token: "expired".into(),
            environment_id: "env".into(),
            expires_at: at - 1,
        })
        .await
        .expect("expire");
    if sessions {
        for kind in [KIND_CLIENT, KIND_MCP] {
            let s = DeviceSession {
                origin: origin.clone(),
                token: TOKEN.into(),
                environment_id: "env".into(),
                expires_at: at + 3600,
            };
            worker
                .set_setting(
                    &session_key(DEVICE, "david-server", kind),
                    &serde_json::to_string(&s).expect("json"),
                    at,
                )
                .await
                .expect("session");
        }
    }
    worker.close().await.expect("close");
    let replica = open_replica(path, false).await.expect("replica");
    Phone {
        replica,
        t3,
        _dir: dir,
    }
}

impl Phone {
    async fn call(
        &self,
        prism_url: &str,
        method: &str,
        path: &str,
        body: Value,
        wait: u32,
    ) -> (i32, Value) {
        let r = self
            .replica
            .call(ApiRequest {
                method: method.into(),
                path: path.into(),
                body: Some(body.to_string()),
                device: DEVICE.into(),
                cluster_machine: String::new(),
                wait_seconds: wait,
                prism_url: prism_url.into(),
            })
            .await;
        (r.status, serde_json::from_str(&r.body).expect("JSON"))
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn without_the_workers_the_phone_fixes_starts_and_stops_in_t3() {
    let phone = phone(true).await;
    let down = nobody().await;

    let started = Instant::now();
    let (status, body) = phone
        .call(
            &down,
            "POST",
            "/v1/alerts/1/fix",
            json!({"requestId": "fix-1"}),
            20,
        )
        .await;
    assert_eq!(status, 201, "{body}");
    assert!(
        started.elapsed() < Duration::from_secs(10),
        "no wait for the workers"
    );
    let thread = body["agent"]["id"].as_str().expect("agent").to_string();
    assert_eq!(thread, "thread-1");
    let launches = phone.t3.launches.lock().expect("launches").clone();
    assert_eq!(launches.len(), 1);
    assert_eq!(launches[0]["runtimeMode"], "full-access");
    assert_eq!(launches[0]["placementProfile"], "default");

    // The same request again is the same launch.
    let (status, _) = phone
        .call(
            &down,
            "POST",
            "/v1/alerts/1/fix",
            json!({"requestId": "fix-1"}),
            20,
        )
        .await;
    assert!(status == 201 || status == 200, "{status}");
    assert_eq!(phone.t3.launches.lock().expect("launches").len(), 1);

    let (status, body) = phone
        .call(
            &down,
            "POST",
            "/v1/agents",
            json!({"task": "do", "request": "clean up", "requestId": "do-1"}),
            20,
        )
        .await;
    assert_eq!(status, 201, "{body}");
    assert_eq!(phone.t3.launches.lock().expect("launches").len(), 2);

    let (status, body) = phone
        .call(
            &down,
            "POST",
            &format!("/v1/agents/{thread}/stop"),
            json!({"requestId": "stop-1"}),
            20,
        )
        .await;
    assert_eq!(status, 200, "{body}");
    assert_eq!(phone.t3.interrupts.lock().expect("interrupts").len(), 1);
}

#[tokio::test(flavor = "multi_thread")]
async fn without_the_workers_the_phone_answers_an_approval_itself() {
    let phone = phone(true).await;
    let (_, list) = phone.call("", "GET", "/v1/approvals", json!({}), 0).await;
    let mut view = list["approvals"][0].clone();
    let id = view["id"].as_str().expect("approval").to_string();
    view["decision"] = "decline".into();
    let message = data_encoding_base64(&serde_json::to_vec(&view).expect("json"));
    let (status, body) = phone
        .call(
            &nobody().await,
            "POST",
            &format!("/v1/approvals/{}/answer", encode(&id)),
            json!({"message": message}),
            20,
        )
        .await;
    assert_eq!(status, 200, "{body}");
    assert_eq!(*phone.t3.decisions.lock().expect("decisions"), ["decline"]);
    let (_, list) = phone.call("", "GET", "/v1/approvals", json!({}), 0).await;
    assert_eq!(list["approvals"], json!([]));
}

#[tokio::test(flavor = "multi_thread")]
async fn slow_workers_leave_an_approval_to_the_phone_but_not_a_fix() {
    let phone = phone(true).await;
    let up = Arc::new(AtomicBool::new(true));
    let workers = workers(Arc::clone(&up)).await;

    // Reachable workers run a fix; the phone only waits for them.
    let (status, body) = phone
        .call(
            &workers,
            "POST",
            "/v1/alerts/1/fix",
            json!({"requestId": "fix-2"}),
            1,
        )
        .await;
    assert_eq!(status, 202, "{body}");
    assert_eq!(body["pending"], true);
    assert!(phone.t3.launches.lock().expect("launches").is_empty());

    // An approval they haven't answered after a while, the phone answers.
    let (_, list) = phone.call("", "GET", "/v1/approvals", json!({}), 0).await;
    let mut view = list["approvals"][0].clone();
    let id = view["id"].as_str().expect("approval").to_string();
    view["decision"] = "decline".into();
    let message = data_encoding_base64(&serde_json::to_vec(&view).expect("json"));
    let started = Instant::now();
    let (status, body) = phone
        .call(
            &workers,
            "POST",
            &format!("/v1/approvals/{}/answer", encode(&id)),
            json!({"message": message}),
            20,
        )
        .await;
    assert_eq!(status, 200, "{body}");
    assert!(
        started.elapsed() >= Duration::from_secs(7),
        "the workers came first"
    );
    assert!(started.elapsed() < Duration::from_secs(15));
    assert_eq!(*phone.t3.decisions.lock().expect("decisions"), ["decline"]);
}

#[tokio::test(flavor = "multi_thread")]
async fn without_sessions_the_request_waits_for_the_workers() {
    let phone = phone(false).await;
    let started = Instant::now();
    let (status, body) = phone
        .call(
            &nobody().await,
            "POST",
            "/v1/alerts/1/fix",
            json!({"requestId": "fix-3"}),
            20,
        )
        .await;
    assert_eq!(status, 202, "{body}");
    assert_eq!(body["pending"], true);
    assert!(
        body["detail"]
            .as_str()
            .unwrap_or("")
            .contains("no T3 sessions"),
        "{body}"
    );
    assert!(
        started.elapsed() < Duration::from_secs(10),
        "no wait for the unreachable workers"
    );
    assert!(phone.t3.launches.lock().expect("launches").is_empty());
}

fn encode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                (b as char).to_string()
            }
            _ => format!("%{b:02X}"),
        })
        .collect()
}

fn data_encoding_base64(data: &[u8]) -> String {
    const A: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::new();
    for chunk in data.chunks(3) {
        let n = chunk.iter().fold(0u32, |n, b| (n << 8) | u32::from(*b)) << (8 * (3 - chunk.len()));
        for i in 0..4 {
            if i <= chunk.len() {
                out.push(A[((n >> (18 - 6 * i)) & 63) as usize] as char);
            } else {
                out.push('=');
            }
        }
    }
    out
}
