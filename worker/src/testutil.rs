//! Local fakes for tests: a Kubernetes Lease API, ntfy and T3's MCP endpoint.

use axum::{
    Json, Router,
    extract::{Path, State},
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use serde_json::{Value, json};
use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};

/// Serves `app` on a loopback port and returns its base URL.
pub async fn serve(app: Router) -> String {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind");
    let addr = listener.local_addr().expect("addr");
    tokio::spawn(async move {
        let _ = axum::serve(listener, app).await;
    });
    format!("http://{addr}")
}

/// A Lease API with optimistic concurrency on `metadata.resourceVersion`.
#[derive(Clone, Default)]
pub struct FakeLeases {
    pub lease: Arc<Mutex<Option<Value>>>,
    pub version: Arc<Mutex<u64>>,
    /// While true every request fails with 500.
    pub broken: Arc<Mutex<bool>>,
}

impl FakeLeases {
    pub fn holder(&self) -> Option<String> {
        self.lease
            .lock()
            .expect("lease")
            .as_ref()
            .and_then(|l| l.pointer("/spec/holderIdentity"))
            .and_then(Value::as_str)
            .map(str::to_string)
    }

    /// Someone else takes the lease behind the holder's back.
    pub fn steal(&self, identity: &str) {
        let mut lease = self.lease.lock().expect("lease");
        let l = lease.as_mut().expect("a lease");
        l["spec"]["holderIdentity"] = identity.into();
        l["spec"]["renewTime"] = chrono::Utc::now()
            .to_rfc3339_opts(chrono::SecondsFormat::Micros, true)
            .into();
        l["spec"]["leaseDurationSeconds"] = 3600.into();
        let mut v = self.version.lock().expect("version");
        *v += 1;
        l["metadata"]["resourceVersion"] = v.to_string().into();
    }

    pub fn router(&self) -> Router {
        Router::new()
            .route(
                "/apis/coordination.k8s.io/v1/namespaces/{ns}/leases",
                post(create),
            )
            .route(
                "/apis/coordination.k8s.io/v1/namespaces/{ns}/leases/{name}",
                get(read).put(update),
            )
            .with_state(self.clone())
    }

    fn check(&self, headers: &HeaderMap) -> Option<Response> {
        if *self.broken.lock().expect("broken") {
            return Some(StatusCode::INTERNAL_SERVER_ERROR.into_response());
        }
        if headers.get("authorization").and_then(|v| v.to_str().ok()) != Some("Bearer sa-token") {
            return Some(StatusCode::UNAUTHORIZED.into_response());
        }
        None
    }
}

async fn create(
    State(f): State<FakeLeases>,
    headers: HeaderMap,
    Json(mut body): Json<Value>,
) -> Response {
    if let Some(r) = f.check(&headers) {
        return r;
    }
    let mut lease = f.lease.lock().expect("lease");
    if lease.is_some() {
        return StatusCode::CONFLICT.into_response();
    }
    let mut v = f.version.lock().expect("version");
    *v += 1;
    body["metadata"]["resourceVersion"] = v.to_string().into();
    *lease = Some(body.clone());
    (StatusCode::CREATED, Json(body)).into_response()
}

async fn read(
    State(f): State<FakeLeases>,
    headers: HeaderMap,
    Path(_): Path<(String, String)>,
) -> Response {
    if let Some(r) = f.check(&headers) {
        return r;
    }
    match f.lease.lock().expect("lease").clone() {
        Some(l) => Json(l).into_response(),
        None => StatusCode::NOT_FOUND.into_response(),
    }
}

async fn update(
    State(f): State<FakeLeases>,
    headers: HeaderMap,
    Path(_): Path<(String, String)>,
    Json(mut body): Json<Value>,
) -> Response {
    if let Some(r) = f.check(&headers) {
        return r;
    }
    let mut lease = f.lease.lock().expect("lease");
    let mut v = f.version.lock().expect("version");
    if body
        .pointer("/metadata/resourceVersion")
        .and_then(Value::as_str)
        != Some(&v.to_string())
    {
        return StatusCode::CONFLICT.into_response();
    }
    *v += 1;
    body["metadata"]["resourceVersion"] = v.to_string().into();
    *lease = Some(body.clone());
    Json(body).into_response()
}

/// ntfy: records every published JSON body with its Authorization header.
#[derive(Clone, Default)]
pub struct FakeNtfy {
    pub posts: Arc<Mutex<Vec<(String, Value)>>>,
}

impl FakeNtfy {
    pub fn router(&self) -> Router {
        Router::new()
            .route(
                "/",
                post(|State(f): State<FakeNtfy>, headers: HeaderMap, Json(body): Json<Value>| async move {
                    let auth = headers
                        .get("authorization")
                        .and_then(|v| v.to_str().ok())
                        .unwrap_or("")
                        .to_string();
                    f.posts.lock().expect("posts").push((auth, body));
                    Json(json!({"id": "x"}))
                }),
            )
            .with_state(self.clone())
    }
}

/// Pending user-input requests per thread: (request ID, payload).
pub type PendingByThread = BTreeMap<String, Vec<(String, Value)>>;

/// T3's MCP endpoint: sessions, capabilities, projects, links and launches
/// deduplicated by `clientRequestId`, as T3 does.
#[derive(Clone, Default)]
pub struct FakeT3 {
    pub launches: Arc<Mutex<Vec<Value>>>,
    pub threads: Arc<Mutex<BTreeMap<String, String>>>,
    pub profiles: Arc<Mutex<Vec<String>>>,
    pub pending: Arc<Mutex<PendingByThread>>,
    /// `t3_pending_request` respond calls, as sent.
    pub responds: Arc<Mutex<Vec<Value>>>,
    /// Makes respond fail with this tool error text.
    pub respond_error: Arc<Mutex<Option<String>>>,
    /// Threads whose pending list fails.
    pub broken_threads: Arc<Mutex<Vec<String>>>,
    /// Makes every tool call hang, as an unreachable T3 does.
    pub stalled: Arc<std::sync::atomic::AtomicBool>,
    /// `t3_thread_interrupt` calls, as sent.
    pub interrupts: Arc<Mutex<Vec<Value>>>,
}

impl FakeT3 {
    pub fn router(&self) -> Router {
        Router::new()
            .route("/mcp", post(mcp))
            .with_state(self.clone())
    }

    pub fn distinct_threads(&self) -> usize {
        self.threads.lock().expect("threads").len()
    }
}

async fn mcp(State(f): State<FakeT3>, headers: HeaderMap, Json(req): Json<Value>) -> Response {
    if headers.get("authorization").and_then(|v| v.to_str().ok()) != Some("Bearer t3-token") {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    let method = req["method"].as_str().unwrap_or("");
    if method == "initialize" {
        return (
            [("Mcp-Session-Id", "s1")],
            Json(json!({"jsonrpc": "2.0", "id": 1, "result": {}})),
        )
            .into_response();
    }
    if method == "notifications/initialized" {
        return StatusCode::ACCEPTED.into_response();
    }
    if headers.get("mcp-session-id").and_then(|v| v.to_str().ok()) != Some("s1") {
        return StatusCode::NOT_FOUND.into_response();
    }
    if f.stalled.load(std::sync::atomic::Ordering::SeqCst) {
        tokio::time::sleep(std::time::Duration::from_secs(3600)).await;
    }
    let args = &req["params"]["arguments"];
    let result = match req["params"]["name"].as_str().unwrap_or("") {
        "orchestrator_capabilities" => {
            let profiles: Vec<Value> = f
                .profiles
                .lock()
                .expect("profiles")
                .iter()
                .map(|p| json!({"id": p, "label": p}))
                .collect();
            json!({"providers": [], "placement": {"profiles": profiles}})
        }
        "t3_project_list" => json!({"projects": [{
            "id": "project-platform", "title": "selfhosted-platform",
            "workspaceRoot": "/srv/selfhosted-platform",
            "repositoryIdentity": {"canonicalKey": "gitea.quickme.at/x/selfhosted-platform",
                "locator": {"source": "git-remote", "remoteName": "origin"}}
        }]}),
        "t3_environment_links" => json!({"links": []}),
        "t3_thread_list" => {
            let threads: Vec<Value> = f
                .pending
                .lock()
                .expect("pending")
                .keys()
                .map(|t| json!({"threadId": t, "title": format!("Thread {t}"), "link": format!("t3-thread://v2/threads/env/{t}")}))
                .collect();
            json!({"threads": threads})
        }
        "t3_pending_request" => {
            let thread = args["threadId"].as_str().unwrap_or("").to_string();
            let pending = f
                .pending
                .lock()
                .expect("pending")
                .get(&thread)
                .cloned()
                .unwrap_or_default();
            match args["action"].as_str().unwrap_or("") {
                "list" if f.broken_threads.lock().expect("broken").contains(&thread) => {
                    return tool_error("thread unavailable");
                }
                "list" => {
                    json!({"requestIds": pending.iter().map(|(id, _)| id.clone()).collect::<Vec<_>>()})
                }
                "read" => {
                    let id = args["requestId"].as_str().unwrap_or("");
                    match pending.iter().find(|(p, _)| p == id) {
                        Some((_, payload)) => payload.clone(),
                        None => return tool_error("The user-input request was not found."),
                    }
                }
                "respond" => {
                    if let Some(err) = f.respond_error.lock().expect("respond error").clone() {
                        return tool_error(&err);
                    }
                    f.responds.lock().expect("responds").push(args.clone());
                    let id = args["requestId"].as_str().unwrap_or("").to_string();
                    if let Some(list) = f.pending.lock().expect("pending").get_mut(&thread) {
                        list.retain(|(p, _)| *p != id);
                    }
                    json!({"sequence": 1})
                }
                _ => return tool_error("unknown action"),
            }
        }
        "t3_thread_interrupt" => {
            f.interrupts.lock().expect("interrupts").push(args.clone());
            json!({"interrupted": true})
        }
        "t3_thread_launch" => {
            f.launches.lock().expect("launches").push(args.clone());
            let key = args["clientRequestId"].as_str().unwrap_or("").to_string();
            let mut threads = f.threads.lock().expect("threads");
            let n = threads.len() + 1;
            let thread = threads
                .entry(key)
                .or_insert_with(|| format!("thread-{n}"))
                .clone();
            json!({"threadId": thread, "link": format!("t3-thread://v2/env/{thread}")})
        }
        other => return tool_error(&format!("unknown tool {other}")),
    };
    // Answer as SSE, as Streamable HTTP may, with a progress event first.
    let reply = json!({"jsonrpc": "2.0", "id": 1, "result": {"structuredContent": result}});
    (
        [("content-type", "text/event-stream")],
        format!("event: message\ndata: {{\"jsonrpc\":\"2.0\",\"method\":\"progress\"}}\n\ndata: {reply}\n\n"),
    )
        .into_response()
}

fn tool_error(text: &str) -> Response {
    Json(json!({"jsonrpc": "2.0", "id": 1, "result":
        {"isError": true, "content": [{"type": "text", "text": text}]}}))
    .into_response()
}
