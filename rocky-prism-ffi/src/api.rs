//! The apps' Prism API (`docs/prism-api.md`), answered from the local replica.
//!
//! `Replica::call` takes a request as `shared/`'s `PrismClient` sends it
//! (method, path with query, JSON body) and returns the status and JSON body
//! the Go server answered with, so the apps keep their client, their models
//! and their sample mode, and only swap the transport.
//!
//! Reads come from the replica. Each action writes a request row (an alert
//! action, an agent start, message or stop, a question or approval answer),
//! which the worker leading the cluster claims and runs; `call` then waits up
//! to `wait_seconds` for its outcome to replicate back and answers with it,
//! like the server did. While the leader is unreachable (offline, no worker
//! leading) it answers `202` with `"pending": true` and the row stays queued:
//! the leader runs it once it syncs.
//!
//! The workers are the way requests run. When this device can't reach them
//! (`prism_url`'s health check fails), it runs its own request itself, in T3
//! over NetBird, with its own T3 sessions and the leader's code
//! (`prism_worker::direct`), and claims the row as `device:<name>` so no
//! worker runs it again. Approvals, question answers and stops, which T3
//! takes only once anyway, also run on the device when the workers are
//! reachable but haven't finished them within [`IDEMPOTENT_WAIT`].

use crate::{ReplicaError, now_unix, retrying};
use rocky_prism::{
    ACTION_DISMISS, ACTION_FIX, ACTION_RESOLVE, AgentRow, AlertActionRequest, AlertFilter,
    AlertView, PlacementPolicy, PrismClient, PrismError, PutPlacementProfileRequest, QuestionRow,
    RequestOutcome, RequestTable, SetPlacementActionsRequest, sha256_hex,
};
use serde_json::{Map, Value, json};
use std::sync::Arc;
use std::time::Duration;

/// A request in the HTTP API's terms.
#[derive(Clone, Debug, uniffi::Record)]
pub struct ApiRequest {
    /// `GET`, `POST`, `PUT` or `DELETE`.
    pub method: String,
    /// The path with its query, e.g. `/v1/alerts?state=open`.
    pub path: String,
    /// The JSON body, if any.
    #[uniffi(default = None)]
    pub body: Option<String>,
    /// This device's name (`david-pixel`, `david-mb`): who requested an
    /// action, and whose key the worker checks an approval's signature with.
    pub device: String,
    /// The T3 host whose models count as the cluster's (placement, models);
    /// empty: the one the leader names (the `cluster_machine` setting).
    #[uniffi(default = "")]
    pub cluster_machine: String,
    /// How long an action waits for the leader's outcome before it answers
    /// `202` (pending).
    #[uniffi(default = 20)]
    pub wait_seconds: u32,
    /// The workers' public URL (`https://prism.quickme.at`), whose health
    /// decides whether this device runs a request itself; empty: never.
    #[uniffi(default = "")]
    pub prism_url: String,
}

/// The status and JSON body the server would have answered with.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ApiResponse {
    pub status: i32,
    pub body: String,
}

/// Agents and alerts that ended within this long count as recent.
const RECENT_SECS: i64 = 7 * 24 * 60 * 60;
const AGENT_LIMIT: usize = 200;
const CLUSTER_TARGET: &str = "cluster";
const AGENT_TASKS: [&str; 5] = ["alert", "fix", "deploy", "retire", "do"];
const MAX_TEXT_CHARS: usize = 10_000;
const MAX_REQUEST_ID_BYTES: usize = 200;
const POLL: Duration = Duration::from_millis(200);
/// How long an approval, a question's answer or a stop waits for the
/// workers before this device answers it in T3 itself.
const IDEMPOTENT_WAIT: Duration = Duration::from_secs(8);
/// How long a health check's answer holds: reachable, and unreachable.
const REACHABLE_FOR: Duration = Duration::from_secs(30);
const UNREACHABLE_FOR: Duration = Duration::from_secs(10);
/// The `settings` key of the model launches that name none start with.
const MODEL_SETTING: &str = "model";
/// The `settings` keys of each phone's published settings, by device name.
const ANDROID_SETTINGS: &str = "android.settings.";
/// The `settings` key the leader names the cluster machine with.
const CLUSTER_MACHINE_SETTING: &str = "cluster_machine";

struct Reply {
    status: i32,
    body: Value,
}

fn ok(body: Value) -> Result<Reply, ApiError> {
    Ok(Reply { status: 200, body })
}

/// An error as the API returns it: `{"error", "code"}` with its status.
#[derive(Debug)]
struct ApiError {
    status: i32,
    code: &'static str,
    message: String,
}

impl ApiError {
    fn invalid(message: impl Into<String>) -> Self {
        Self {
            status: 400,
            code: "invalid",
            message: message.into(),
        }
    }
    fn not_found() -> Self {
        Self {
            status: 404,
            code: "not_found",
            message: "Not found.".into(),
        }
    }
    fn conflict(message: impl Into<String>) -> Self {
        Self {
            status: 409,
            code: "conflict",
            message: message.into(),
        }
    }
    fn unavailable(message: impl Into<String>) -> Self {
        Self {
            status: 503,
            code: "unavailable",
            message: message.into(),
        }
    }

    /// The leader's error text for a request it ran, with the status the
    /// server gave the same failure.
    fn from_outcome(error: &str) -> Self {
        let lower = error.to_lowercase();
        if lower.contains("not found") || lower.contains("is gone") {
            Self {
                message: error.into(),
                ..Self::not_found()
            }
        } else if lower.contains("try again") || lower.contains("does not answer") {
            // The leader ran it and it failed: asking again with the same
            // request ID reads the same outcome, unlike "unavailable" from
            // the replica itself, so it carries its own code.
            Self {
                code: "failed",
                ..Self::unavailable(error)
            }
        } else if lower.contains("already ended")
            || lower.contains("no longer open")
            || lower.contains("can be resolved")
            || lower.contains("changed or expired")
        {
            Self::conflict(error)
        } else {
            Self::invalid(error)
        }
    }
}

impl From<PrismError> for ApiError {
    fn from(err: PrismError) -> Self {
        match err {
            PrismError::InvalidInput(m) => Self::invalid(m),
            PrismError::Conflict(m) => Self::conflict(m),
            PrismError::NotFound => Self::not_found(),
            PrismError::Storage(m) => Self::unavailable(format!("The replica failed: {m}")),
            PrismError::Unavailable(m) => Self {
                status: 503,
                code: BUSY,
                message: format!("The replica is still applying synced changes; try again. ({m})"),
            },
        }
    }
}

impl From<ReplicaError> for ApiError {
    fn from(err: ReplicaError) -> Self {
        match err {
            ReplicaError::InvalidInput(m) => Self::invalid(m),
            ReplicaError::Conflict(m) => Self::conflict(m),
            ReplicaError::NotFound => Self::not_found(),
            ReplicaError::Json(m) => {
                Self::invalid(format!("The request body is not valid JSON: {m}"))
            }
            other => Self::unavailable(other.to_string()),
        }
    }
}

impl From<serde_json::Error> for ApiError {
    fn from(_: serde_json::Error) -> Self {
        Self::invalid("The request body is not valid JSON.")
    }
}

/// The `code` of an ApiError for `PrismError::Unavailable`.
const BUSY: &str = "busy";
/// How long `call` retries a route that fails with BUSY.
const BUSY_RETRY_FOR: Duration = Duration::from_secs(6);

/// Answers `request` from `client`'s replica.
pub(crate) async fn call(client: Arc<PrismClient>, request: ApiRequest) -> ApiResponse {
    let cluster_machine = match request.cluster_machine.as_str() {
        "" => client
            .get_setting(CLUSTER_MACHINE_SETTING)
            .await
            .ok()
            .flatten()
            .unwrap_or_default(),
        name => name.to_string(),
    };
    let api = Api {
        client,
        device: request.device.clone(),
        cluster_machine,
        wait: Duration::from_secs(u64::from(request.wait_seconds)),
        prism_url: request.prism_url.trim_end_matches('/').to_string(),
    };
    // A replica refuses reads and new rows of a table while it applies synced
    // changes to it (Rocky's `Incomplete`), which passes within moments. Every
    // route is safe to run again: reads have no effect, and actions are keyed
    // by their request ID.
    let started = std::time::Instant::now();
    let mut delay = Duration::from_millis(100);
    let reply = loop {
        match api.route(&request).await {
            Err(e) if e.code == BUSY && started.elapsed() + delay < BUSY_RETRY_FOR => {
                tokio::time::sleep(delay).await;
                delay = (delay * 2).min(Duration::from_secs(1));
            }
            other => break other,
        }
    };
    match reply {
        Ok(r) => ApiResponse {
            status: r.status,
            body: r.body.to_string(),
        },
        Err(e) => ApiResponse {
            status: e.status,
            body: json!({"error": e.message, "code": e.code}).to_string(),
        },
    }
}

/// The path's segments (percent-decoded) and its query.
fn split(path: &str) -> (Vec<String>, Vec<(String, String)>) {
    let (path, query) = path.split_once('?').unwrap_or((path, ""));
    let segments = path
        .trim_matches('/')
        .split('/')
        .filter(|s| !s.is_empty())
        .map(decode)
        .collect();
    let query = query
        .split('&')
        .filter(|s| !s.is_empty())
        .map(|pair| {
            let (k, v) = pair.split_once('=').unwrap_or((pair, ""));
            (decode(k), decode(&v.replace('+', " ")))
        })
        .collect();
    (segments, query)
}

fn decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%'
            && i + 2 < bytes.len()
            && let Ok(hex) = std::str::from_utf8(&bytes[i + 1..i + 3])
            && let Ok(b) = u8::from_str_radix(hex, 16)
        {
            out.push(b);
            i += 3;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn query<'a>(q: &'a [(String, String)], key: &str) -> Option<&'a str> {
    q.iter().find(|(k, _)| k == key).map(|(_, v)| v.as_str())
}

fn str_field(body: &Value, key: &str) -> String {
    body.get(key)
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string()
}

fn parse(json: &str) -> Value {
    serde_json::from_str(json).unwrap_or(Value::Null)
}

struct Api {
    client: Arc<PrismClient>,
    device: String,
    cluster_machine: String,
    wait: Duration,
    prism_url: String,
}

/// A request this device can run itself (`prism_worker::direct::Direct`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Direct {
    AlertAction,
    AgentRequest,
    AgentMessage,
    QuestionAnswer,
    ApprovalAnswer,
}

/// The last health check: for which URL, when, and its answer.
static HEALTH: std::sync::Mutex<Option<(String, std::time::Instant, bool)>> =
    std::sync::Mutex::new(None);

/// Whether the workers answer at `url` (`GET /v1/health`), remembered
/// briefly so a burst of actions checks once.
async fn workers_reachable(url: &str) -> bool {
    if url.is_empty() {
        return true;
    }
    if let Some((checked, at, ok)) = HEALTH.lock().expect("health").clone()
        && checked == url
        && at.elapsed() < if ok { REACHABLE_FOR } else { UNREACHABLE_FOR }
    {
        return ok;
    }
    static HTTP: std::sync::LazyLock<Option<reqwest::Client>> = std::sync::LazyLock::new(|| {
        reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(4))
            .timeout(Duration::from_secs(5))
            .build()
            .ok()
    });
    let ok = match HTTP.as_ref() {
        Some(http) => match http.get(format!("{url}/v1/health")).send().await {
            Ok(resp) if resp.status().is_success() => resp
                .text()
                .await
                .ok()
                .and_then(|t| serde_json::from_str::<Value>(&t).ok())
                .is_some_and(|v| v["ok"] == json!(true)),
            _ => false,
        },
        None => false,
    };
    *HEALTH.lock().expect("health") = Some((url.to_string(), std::time::Instant::now(), ok));
    ok
}

impl Api {
    async fn route(&self, request: &ApiRequest) -> Result<Reply, ApiError> {
        let (segments, q) = split(&request.path);
        let body = match request.body.as_deref().map(str::trim) {
            None | Some("") => Value::Object(Map::new()),
            Some(text) => serde_json::from_str::<Value>(text)?,
        };
        if !body.is_object() {
            return Err(ApiError::invalid("The request body is not a JSON object."));
        }
        let s: Vec<&str> = segments.iter().map(String::as_str).collect();
        let method = request.method.to_uppercase();
        match (method.as_str(), s.as_slice()) {
            ("GET", ["v1", "health"]) => ok(json!({"ok": true})),
            ("GET", ["v1", "alerts"]) => self.alerts(query(&q, "state").unwrap_or("open")).await,
            ("GET", ["v1", "alerts", id]) => {
                let row = self.alert_row(id).await?;
                ok(self.alert_json(row).await?)
            }
            ("POST", ["v1", "alerts", id, action @ ("fix" | "dismiss" | "resolve")]) => {
                self.alert_action(id, action, &body).await
            }
            ("GET", ["v1", "agents"]) => self.agents(query(&q, "state").unwrap_or("active")).await,
            ("POST", ["v1", "agents"]) => self.start_agent(&body).await,
            ("GET", ["v1", "agents", id]) => {
                let agent = self.agent(id).await?;
                ok(self.agent_view(&agent))
            }
            ("GET", ["v1", "agents", id, "activity"]) => self.activity(id).await,
            ("POST", ["v1", "agents", id, "messages"]) => self.message(id, &body).await,
            ("POST", ["v1", "agents", id, "stop"]) => self.stop(id, &body).await,
            ("GET", ["v1", "agent-options"]) => {
                self.agent_options(query(&q, "target").unwrap_or("")).await
            }
            ("GET", ["v1", "projects"]) => self.projects().await,
            ("GET", ["v1", "questions"]) => self.questions().await,
            ("POST", ["v1", "questions", id, "answer"]) => self.answer_question(id, &body).await,
            ("GET", ["v1", "approvals"]) => self.approvals().await,
            ("POST", ["v1", "approvals", id, "answer"]) => self.answer_approval(id, &body).await,
            ("GET", ["v1", "machines"]) => self.machines().await,
            ("GET", ["v1", "machines", "metrics"]) => self.host_metrics().await,
            ("GET", ["v1", "settings", "model"]) => self.model_settings().await,
            ("PUT", ["v1", "settings", "model"]) => self.set_model(&body).await,
            ("PUT", ["v1", "settings", "reasoning"]) => self.set_reasoning(&body).await,
            ("GET", ["v1", "android", "settings"]) => self.android_settings().await,
            ("GET", ["v1", "placement"]) => self.placement().await,
            ("PUT", ["v1", "placement", "profiles", id]) => {
                let req: PutPlacementProfileRequest = serde_json::from_value(body)?;
                let (c, id) = (&self.client, id.to_string());
                retrying(|| c.put_placement_profile(id.clone(), req.clone(), now_unix())).await?;
                self.placement().await
            }
            ("DELETE", ["v1", "placement", "profiles", id]) => {
                let (c, id) = (&self.client, id.to_string());
                retrying(|| c.delete_placement_profile(id.clone())).await?;
                self.placement().await
            }
            ("PUT", ["v1", "placement", "order"]) => {
                let ids: Vec<String> =
                    serde_json::from_value(body.get("ids").cloned().unwrap_or(json!([])))?;
                let c = &self.client;
                retrying(|| c.order_placement_profiles(ids.clone())).await?;
                self.placement().await
            }
            ("PUT", ["v1", "placement", "alert-fix-profile"]) => {
                let profile = str_field(&body, "profile");
                let c = &self.client;
                retrying(|| c.set_alert_fix_profile(profile.clone())).await?;
                self.placement().await
            }
            ("PUT", ["v1", "placement", "actions"]) => {
                let req: SetPlacementActionsRequest = serde_json::from_value(body)?;
                let c = &self.client;
                retrying(|| c.set_placement_actions(req.clone())).await?;
                self.placement().await
            }
            ("PUT", ["v1", "placement", "account-limits", account]) => {
                let limit: rocky_prism::PlacementAccountLimit = serde_json::from_value(body)?;
                let (c, account) = (&self.client, account.to_string());
                retrying(|| c.set_account_limit(&account, Some(limit.clone()), now_unix())).await?;
                self.placement().await
            }
            ("DELETE", ["v1", "placement", "account-limits", account]) => {
                let (c, account) = (&self.client, account.to_string());
                retrying(|| c.set_account_limit(&account, None, now_unix())).await?;
                self.placement().await
            }
            ("PUT", ["v1", "placement", "policy"]) => {
                let policy: PlacementPolicy = serde_json::from_value(body)?;
                let c = &self.client;
                retrying(|| c.set_placement_policy(policy.clone())).await?;
                self.placement().await
            }
            _ => Err(ApiError::not_found()),
        }
    }

    // --- This device's own route ---------------------------------------

    /// Runs request `id` on this device when the workers are unreachable.
    /// `Ok(None)`: the workers run it. `Ok(Some(why))`: they are
    /// unreachable and this device could not run it either (no sessions
    /// yet, say), so it waits for them.
    async fn unless_reachable(&self, kind: Direct, id: &str) -> Result<Option<String>, ApiError> {
        if workers_reachable(&self.prism_url).await {
            return Ok(None);
        }
        Ok(match self.run_direct(kind, id).await {
            Ok(true) => None,
            Ok(false) => Some("Prism is unreachable; the request waits for it.".into()),
            Err(why) => Some(format!("Prism is unreachable, and {why}")),
        })
    }

    /// Claims request `id` for this device and runs it in T3. True when this
    /// device ran it; false when it is done or a worker holds it.
    async fn run_direct(&self, kind: Direct, id: &str) -> Result<bool, String> {
        let direct = prism_worker::direct::Direct::open(
            Arc::clone(&self.client),
            &self.device,
            Arc::new(now_unix),
        )
        .await
        .map_err(|why| {
            let mut why = why;
            if let Some(first) = why.get(..1) {
                why = first.to_lowercase() + &why[1..];
            }
            why
        })?;
        match kind {
            Direct::AlertAction => direct.alert_action(id).await,
            Direct::AgentRequest => direct.agent_request(id).await,
            Direct::AgentMessage => direct.agent_message(id).await,
            Direct::QuestionAnswer => direct.question_answer(id).await,
            Direct::ApprovalAnswer => direct.approval_answer(id).await,
        }
    }

    /// Waits for a request T3 takes only once (an approval, an answer, a
    /// stop): through the workers, or, when they haven't finished it within
    /// [`IDEMPOTENT_WAIT`], on this device too, and then for the rest of
    /// the wait.
    async fn idempotent_finished(
        &self,
        kind: Direct,
        table: RequestTable,
        id: &str,
        unreachable: Option<String>,
    ) -> Result<Option<RequestOutcome>, ApiError> {
        if unreachable.is_some() {
            return Ok(None);
        }
        let c = &self.client;
        let first = self.wait.min(IDEMPOTENT_WAIT);
        if let Some(done) = self
            .finished_within(first, || c.request_outcome(table, id))
            .await?
        {
            return Ok(Some(done));
        }
        if self.wait > first {
            let _ = self.run_direct(kind, id).await;
        }
        self.finished_within(self.wait - first, || c.request_outcome(table, id))
            .await
    }

    // --- Waiting for the leader -----------------------------------------

    /// Polls `outcome` until the leader finished the request or `wait`
    /// passed: `Some(outcome)` once it is done.
    async fn finished_within<F, Fut>(
        &self,
        wait: Duration,
        mut outcome: F,
    ) -> Result<Option<RequestOutcome>, ApiError>
    where
        F: FnMut() -> Fut,
        Fut: std::future::Future<Output = Result<Option<RequestOutcome>, PrismError>>,
    {
        let deadline = tokio::time::Instant::now() + wait;
        loop {
            if let Some(o) = outcome().await?
                && o.done_at.is_some()
            {
                if !o.error.is_empty() {
                    return Err(ApiError::from_outcome(&o.error));
                }
                return Ok(Some(o));
            }
            if tokio::time::Instant::now() >= deadline {
                return Ok(None);
            }
            tokio::time::sleep(POLL).await;
        }
    }

    /// How long to wait for the workers: not at all while they are
    /// unreachable and this device couldn't run the request.
    fn wait_for(&self, unreachable: &Option<String>) -> Duration {
        if unreachable.is_some() {
            Duration::ZERO
        } else {
            self.wait
        }
    }

    fn request_id(&self, body: &Value, fallback: &str) -> Result<String, ApiError> {
        let id = str_field(body, "requestId");
        if id.len() > MAX_REQUEST_ID_BYTES {
            return Err(ApiError::invalid("requestId is too long."));
        }
        Ok(if id.is_empty() {
            // Without one, the same request from this device is the same row.
            sha256_hex(&format!("{}:{fallback}", self.device))
        } else {
            id
        })
    }

    // --- Alerts ----------------------------------------------------------

    async fn alerts(&self, state: &str) -> Result<Reply, ApiError> {
        let filter = match state {
            "open" => AlertFilter::Open,
            "recent" => AlertFilter::Recent {
                since: now_unix() - RECENT_SECS,
            },
            "all" => AlertFilter::All,
            _ => return Err(ApiError::invalid("state must be open, recent or all.")),
        };
        let agents = self.client.list_agents().await?;
        let mut out = Vec::new();
        for alert in self.client.list_alerts(filter).await? {
            // An episode gets its number from the leader; until then it is not shown.
            if alert.id == 0 {
                continue;
            }
            out.push(self.alert_value(alert, &agents));
        }
        out.sort_by_key(|a| std::cmp::Reverse(a.get("id").and_then(Value::as_i64).unwrap_or(0)));
        ok(json!({"alerts": out}))
    }

    async fn alert_row(&self, id: &str) -> Result<AlertView, ApiError> {
        let number: i64 = id
            .trim_start_matches('#')
            .parse()
            .map_err(|_| ApiError::not_found())?;
        self.client
            .list_alerts(AlertFilter::All)
            .await?
            .into_iter()
            .find(|a| a.id == number && number > 0)
            .ok_or_else(ApiError::not_found)
    }

    async fn alert_json(&self, alert: AlertView) -> Result<Value, ApiError> {
        let agents = self.client.list_agents().await?;
        Ok(self.alert_value(alert, &agents))
    }

    /// The app's Alert: the replica's view with its agents instead of its
    /// requests.
    fn alert_value(&self, alert: AlertView, agents: &[AgentRow]) -> Value {
        let number = alert.id;
        let mut v = serde_json::to_value(&alert).unwrap_or_else(|_| json!({}));
        if let Some(map) = v.as_object_mut() {
            map.remove("actions");
            let list: Vec<Value> = agents
                .iter()
                .filter(|a| a.alert_id == number)
                .map(|a| self.agent_view(a))
                .collect();
            map.insert("agents".into(), Value::Array(list));
        }
        v
    }

    async fn alert_action(&self, id: &str, action: &str, body: &Value) -> Result<Reply, ApiError> {
        let alert = self.alert_row(id).await?;
        let instruction = str_field(body, "instruction");
        let request_id = self.request_id(body, &format!("{action}:{}", alert.key))?;
        let kind = match action {
            "fix" => ACTION_FIX,
            "dismiss" => ACTION_DISMISS,
            _ => ACTION_RESOLVE,
        };
        let c = &self.client;
        let req = AlertActionRequest {
            request_id: request_id.clone(),
            alert: alert.key.clone(),
            kind: kind.into(),
            instruction,
            requested_by: self.device.clone(),
        };
        retrying(|| c.request_alert_action(req.clone(), now_unix())).await?;
        let unreachable = self
            .unless_reachable(Direct::AlertAction, &request_id)
            .await?;
        let done = self
            .finished_within(self.wait_for(&unreachable), || async {
                Ok(c.alert_action(&request_id).await?.map(|a| RequestOutcome {
                    requested_at: a.requested_at,
                    claimed_by: a.claimed_by,
                    done_at: a.done_at,
                    error: a.error,
                    thread_id: a.thread_id,
                    link: a.link,
                }))
            })
            .await?;
        if kind == ACTION_FIX {
            return Ok(match done {
                Some(o) => Reply {
                    status: 201,
                    body: json!({"agent": self.started_agent(&o, alert.id).await?}),
                },
                None => Reply {
                    status: 202,
                    body: detailed(
                        json!({"agent": self.pending_agent(alert.id), "pending": true}),
                        &unreachable,
                    ),
                },
            });
        }
        let current = self.alert_row(id).await?;
        let alert = self.alert_json(current).await?;
        Ok(Reply {
            status: if done.is_some() { 200 } else { 202 },
            body: match done {
                Some(_) => json!({"alert": alert}),
                None => detailed(json!({"alert": alert, "pending": true}), &unreachable),
            },
        })
    }

    // --- Agents ----------------------------------------------------------

    async fn agent(&self, id: &str) -> Result<AgentRow, ApiError> {
        self.client
            .get_agent(id)
            .await?
            .ok_or_else(ApiError::not_found)
    }

    /// Go's `agentView`: an agent's state and where its thread is.
    fn agent_view(&self, a: &AgentRow) -> Value {
        let state = match a.state.as_str() {
            "done" => "stale",
            "active" if a.question_pending => "waiting",
            "active" => "running",
            _ if matches!(
                a.run_status.as_str(),
                "failed" | "interrupted" | "cancelled" | "rolled_back"
            ) =>
            {
                "failed"
            }
            _ => "finished",
        };
        let target = if a.target.is_empty() {
            CLUSTER_TARGET
        } else {
            &a.target
        };
        let environment = link_environment(&a.link).unwrap_or_else(|| a.environment_id.clone());
        let host = if !a.host.is_empty() {
            a.host.clone()
        } else if !a.environment_id.is_empty() && target != CLUSTER_TARGET {
            target.to_string()
        } else if !a.client.is_empty() {
            a.client.clone()
        } else {
            self.cluster_machine.clone()
        };
        json!({
            "id": a.thread_id,
            "task": a.kind,
            "title": a.title,
            "alertId": a.alert_id,
            "target": target,
            "state": state,
            "runStatus": a.run_status,
            "questionPending": a.question_pending,
            "lastMessage": a.last_message,
            "createdAt": if a.created_at == 0 { a.updated_at } else { a.created_at },
            "updatedAt": a.updated_at,
            "requestedBy": a.requested_by,
            "t3": {
                "environmentId": environment,
                "threadId": a.thread_id,
                "host": host,
                "link": a.link,
            },
        })
    }

    /// The agent a finished start launched: its row once it replicated,
    /// else what the request recorded.
    async fn started_agent(&self, o: &RequestOutcome, alert_id: i64) -> Result<Value, ApiError> {
        if let Some(agent) = self.client.get_agent(&o.thread_id).await? {
            return Ok(self.agent_view(&agent));
        }
        Ok(json!({
            "id": o.thread_id,
            "alertId": alert_id,
            "state": "running",
            "runStatus": "running",
            "requestedBy": self.device,
            "createdAt": now_unix(),
            "t3": {"threadId": o.thread_id, "link": o.link,
                   "environmentId": link_environment(&o.link).unwrap_or_default()},
        }))
    }

    /// The agent of a start the leader has not run yet. Its `id` is empty:
    /// there is no thread yet.
    fn pending_agent(&self, alert_id: i64) -> Value {
        json!({
            "id": "",
            "title": "Waiting for Prism",
            "alertId": alert_id,
            "state": "running",
            "runStatus": "pending",
            "requestedBy": self.device,
            "createdAt": now_unix(),
        })
    }

    async fn agents(&self, state: &str) -> Result<Reply, ApiError> {
        let since = match state {
            "active" | "recent" => now_unix() - RECENT_SECS,
            "all" => 0,
            _ => return Err(ApiError::invalid("state must be active, recent or all.")),
        };
        let mut out = Vec::new();
        for a in self.client.list_agents().await? {
            if a.updated_at.max(a.created_at) < since {
                continue;
            }
            let v = self.agent_view(&a);
            let s = v["state"].as_str().unwrap_or_default();
            if state == "active" && s != "running" && s != "waiting" {
                continue;
            }
            if state == "recent" && s == "stale" {
                continue;
            }
            out.push(v);
            if out.len() == AGENT_LIMIT {
                break;
            }
        }
        ok(json!({"agents": out}))
    }

    async fn activity(&self, id: &str) -> Result<Reply, ApiError> {
        let agent = self.agent(id).await?;
        let activity = self.client.get_agent_activity(id).await?;
        let (status, items) = match activity {
            Some(a) => (a.status, parse(&a.items_json)),
            None => (String::new(), json!([])),
        };
        let items = if items.is_array() { items } else { json!([]) };
        ok(json!({"agent": self.agent_view(&agent), "status": status, "items": items}))
    }

    async fn start_agent(&self, body: &Value) -> Result<Reply, ApiError> {
        let task = str_field(body, "task");
        let request = str_field(body, "request");
        if !AGENT_TASKS.contains(&task.as_str()) {
            return Err(ApiError::invalid(format!(
                "Unknown task {task:?}: use fix, do, alert, deploy or retire."
            )));
        }
        if request.trim().is_empty() {
            return Err(ApiError::invalid("Say what the agent should do."));
        }
        if request.trim().chars().count() > MAX_TEXT_CHARS {
            return Err(ApiError::invalid(
                "Please keep the request under 10,000 characters.",
            ));
        }
        let target = str_field(body, "target");
        let project = str_field(body, "projectId");
        let model = match body.get("model") {
            Some(m) if m.is_object() => m.to_string(),
            _ => String::new(),
        };
        let request_id = self.request_id(body, &format!("agent:{task}:{target}:{request}"))?;
        let c = &self.client;
        retrying(|| {
            c.request_agent(
                &request_id,
                &task,
                &target,
                &request,
                &model,
                &project,
                &self.device,
                now_unix(),
            )
        })
        .await?;
        let unreachable = self
            .unless_reachable(Direct::AgentRequest, &request_id)
            .await?;
        Ok(
            match self
                .finished_within(self.wait_for(&unreachable), || {
                    c.request_outcome(RequestTable::AgentRequests, &request_id)
                })
                .await?
            {
                Some(o) => Reply {
                    status: 201,
                    body: json!({"agent": self.started_agent(&o, 0).await?}),
                },
                None => Reply {
                    status: 202,
                    body: detailed(
                        json!({"agent": self.pending_agent(0), "pending": true}),
                        &unreachable,
                    ),
                },
            },
        )
    }

    async fn message(&self, id: &str, body: &Value) -> Result<Reply, ApiError> {
        self.agent(id).await?;
        let text = str_field(body, "text");
        if text.trim().is_empty() {
            return Err(ApiError::invalid("Say what the agent should do."));
        }
        if text.trim().chars().count() > MAX_TEXT_CHARS {
            return Err(ApiError::invalid(
                "Please keep the message under 10,000 characters.",
            ));
        }
        let request_id = self.request_id(body, &format!("message:{id}:{text}"))?;
        let c = &self.client;
        retrying(|| {
            c.request_agent_message(&request_id, id, text.trim(), &self.device, now_unix())
        })
        .await?;
        let unreachable = self
            .unless_reachable(Direct::AgentMessage, &request_id)
            .await?;
        let done = self
            .finished_within(self.wait_for(&unreachable), || {
                c.request_outcome(RequestTable::AgentMessages, &request_id)
            })
            .await?;
        let agent = self.agent_view(&self.agent(id).await?);
        Ok(match done {
            Some(_) => Reply {
                status: 200,
                body: json!({"agent": agent}),
            },
            None => Reply {
                status: 202,
                body: detailed(json!({"agent": agent, "pending": true}), &unreachable),
            },
        })
    }

    async fn stop(&self, id: &str, body: &Value) -> Result<Reply, ApiError> {
        let agent = self.agent(id).await?;
        if agent.state != "active" {
            return ok(json!({"ok": true}));
        }
        let request_id = self.request_id(body, &format!("stop:{id}"))?;
        let c = &self.client;
        retrying(|| c.request_agent_stop(&request_id, id, &self.device, now_unix())).await?;
        let unreachable = self
            .unless_reachable(Direct::AgentMessage, &request_id)
            .await?;
        let done = self
            .idempotent_finished(
                Direct::AgentMessage,
                RequestTable::AgentMessages,
                &request_id,
                unreachable.clone(),
            )
            .await?;
        Ok(match done {
            Some(_) => Reply {
                status: 200,
                body: json!({"ok": true}),
            },
            None => Reply {
                status: 202,
                body: detailed(json!({"ok": true, "pending": true}), &unreachable),
            },
        })
    }

    /// What the agent screen offers for `target`: the placement models on
    /// that machine, the placement profiles, and its projects.
    async fn agent_options(&self, target: &str) -> Result<Reply, ApiError> {
        let target = target.trim().trim_start_matches('@').to_lowercase();
        let picked = target == "auto";
        let machine = if target.is_empty() || target == CLUSTER_TARGET || picked {
            self.cluster_machine.clone()
        } else {
            target.clone()
        };
        let placement = self.placement_value().await?;
        let all: Vec<&Value> = placement["models"]
            .as_array()
            .into_iter()
            .flatten()
            .collect();
        let on_machine: Vec<&Value> = all
            .iter()
            .copied()
            .filter(|m| {
                m["machines"]
                    .as_array()
                    .is_some_and(|ms| ms.iter().any(|x| x.as_str() == Some(machine.as_str())))
            })
            .collect();
        let models: Vec<Value> = if on_machine.is_empty() {
            all
        } else {
            on_machine
        }
        .into_iter()
        .map(|m| {
            json!({
                "instanceId": m["instanceId"],
                "provider": m["provider"],
                "model": m["model"],
            })
        })
        .collect();
        let default_model = self
            .default_model()
            .await?
            .or_else(|| models.first().cloned())
            .unwrap_or_else(|| json!({}));
        let profiles: Vec<Value> = placement["profiles"]
            .as_array()
            .into_iter()
            .flatten()
            .map(|p| json!({"id": p["id"], "label": p["label"]}))
            .collect();
        let mut projects: Vec<Value> = self
            .client
            .list_projects()
            .await?
            .into_iter()
            .filter(|p| {
                serde_json::from_str::<Vec<String>>(&p.machines_json)
                    .unwrap_or_default()
                    .contains(&machine)
            })
            .map(|p| json!({"id": p.project_id, "name": p.name, "icon": icon(&p.icon_json)}))
            .collect();
        projects.push(json!({"id": "", "name": "Scratch folder"}));
        ok(json!({
            "machine": machine,
            "models": models,
            "defaultModel": default_model,
            "projects": projects,
            "profiles": profiles,
            "picked": if picked { Value::String(machine.clone()) } else { Value::Null },
        }))
    }

    async fn projects(&self) -> Result<Reply, ApiError> {
        let projects: Vec<Value> = self
            .client
            .list_projects()
            .await?
            .into_iter()
            .map(|p| {
                json!({
                    "id": p.project_id,
                    "name": p.name,
                    "icon": icon(&p.icon_json),
                    "machines": parse(&p.machines_json),
                    "repository": p.repository,
                })
            })
            .collect();
        ok(json!({"projects": projects}))
    }

    // --- Questions and approvals -------------------------------------------

    async fn question_view(&self, q: &QuestionRow) -> Result<Value, ApiError> {
        let agent = self.client.get_agent(&q.thread_id).await?;
        let (mut environment, mut host, mut title) =
            (q.environment_id.clone(), q.host.clone(), q.title.clone());
        let mut agent_id = String::new();
        if let Some(a) = &agent {
            let v = self.agent_view(a);
            agent_id = a.thread_id.clone();
            if environment.is_empty() {
                environment = v["t3"]["environmentId"].as_str().unwrap_or_default().into();
            }
            if host.is_empty() {
                host = v["t3"]["host"].as_str().unwrap_or_default().into();
            }
            if title.is_empty() {
                title = if a.title.is_empty() {
                    a.kind.clone()
                } else {
                    a.title.clone()
                };
            }
        }
        let payload = parse(&q.payload);
        let items: Vec<Value> = payload["questions"]
            .as_array()
            .into_iter()
            .flatten()
            .map(|pq| {
                let options: Vec<Value> = pq["options"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .map(|o| {
                        json!({
                            "label": o["label"].as_str().unwrap_or_default(),
                            "description": o["description"].as_str().unwrap_or_default(),
                        })
                    })
                    .collect();
                json!({
                    "id": pq["id"].as_str().unwrap_or_default(),
                    "header": pq["header"].as_str().unwrap_or_default(),
                    "question": pq["question"].as_str().unwrap_or_default(),
                    "multiSelect": pq["multiSelect"].as_bool().unwrap_or(false),
                    "options": options,
                })
            })
            .collect();
        Ok(json!({
            "id": q.question_id,
            "threadId": q.thread_id,
            "environmentId": environment,
            "host": host,
            "title": title,
            "createdAt": q.created_at,
            "agentId": agent_id,
            "questions": items,
        }))
    }

    async fn questions(&self) -> Result<Reply, ApiError> {
        let mut out = Vec::new();
        let mut open = self.client.list_open_questions().await?;
        open.sort_by_key(|q| (q.created_at, q.question_id.clone()));
        for q in &open {
            out.push(self.question_view(q).await?);
        }
        ok(json!({"questions": out}))
    }

    async fn answer_question(&self, id: &str, body: &Value) -> Result<Reply, ApiError> {
        let answers = body.get("answers").cloned().unwrap_or(Value::Null);
        if answers.as_object().is_none_or(Map::is_empty) {
            return Err(ApiError::invalid("answers is required."));
        }
        if self.client.get_open_question(id).await?.is_none() {
            return Err(ApiError::not_found());
        }
        let request_id = self.request_id(body, &format!("answer:{id}:{answers}"))?;
        let c = &self.client;
        let text = answers.to_string();
        retrying(|| c.request_question_answer(&request_id, id, &text, &self.device, now_unix()))
            .await?;
        let unreachable = self
            .unless_reachable(Direct::QuestionAnswer, &request_id)
            .await?;
        let done = self
            .idempotent_finished(
                Direct::QuestionAnswer,
                RequestTable::QuestionAnswers,
                &request_id,
                unreachable.clone(),
            )
            .await?;
        Ok(pending_detail(
            done_or_pending(done.is_some()),
            &unreachable,
        ))
    }

    async fn approvals(&self) -> Result<Reply, ApiError> {
        let now = now_unix();
        let mut out: Vec<(i64, Value)> = Vec::new();
        for a in self.client.list_open_approvals(None).await? {
            let v = parse(&a.payload);
            let expires = v["expiresAt"].as_i64().unwrap_or(0);
            if !v.is_object() || (expires != 0 && expires <= now) {
                continue;
            }
            out.push((a.created_at, v));
        }
        out.sort_by_key(|(at, _)| *at);
        ok(json!({"approvals": out.into_iter().map(|(_, v)| v).collect::<Vec<_>>()}))
    }

    /// The answer as the device signed it; the worker checks the signature
    /// with this device's key (worker/src/approvals.rs).
    async fn answer_approval(&self, id: &str, body: &Value) -> Result<Reply, ApiError> {
        let message = str_field(body, "message");
        let signature = str_field(body, "signature");
        if message.is_empty() {
            return Err(ApiError::invalid("message is required."));
        }
        if self.client.get_open_approval(id).await?.is_none() {
            return Err(ApiError::not_found());
        }
        // The body has no request ID: the same signed answer is one request.
        let request_id = format!(
            "approval:{}",
            sha256_hex(&format!("{}:{id}:{message}:{signature}", self.device))
        );
        let c = &self.client;
        retrying(|| {
            c.request_approval_answer(
                &request_id,
                id,
                &message,
                &signature,
                &self.device,
                now_unix(),
            )
        })
        .await?;
        let unreachable = self
            .unless_reachable(Direct::ApprovalAnswer, &request_id)
            .await?;
        let done = self
            .idempotent_finished(
                Direct::ApprovalAnswer,
                RequestTable::ApprovalAnswers,
                &request_id,
                unreachable.clone(),
            )
            .await?;
        Ok(pending_detail(
            done_or_pending(done.is_some()),
            &unreachable,
        ))
    }

    // --- Machines -----------------------------------------------------------

    async fn machines(&self) -> Result<Reply, ApiError> {
        let open = self.client.list_alerts(AlertFilter::Open).await?;
        let mut out = Vec::new();
        for m in self.client.list_machines().await? {
            let mut v = parse(&m.status_json);
            if !v.is_object() {
                v = json!({});
            }
            v["name"] = json!(m.name);
            let alerts: Vec<i64> = open
                .iter()
                .filter(|a| a.id > 0 && a.host == m.name)
                .map(|a| a.id)
                .collect();
            v["openAlerts"] = json!(alerts);
            out.push(v);
        }
        ok(json!({"machines": out}))
    }

    async fn host_metrics(&self) -> Result<Reply, ApiError> {
        let mut time = 0;
        let mut hosts = Vec::new();
        for m in self.client.list_machines().await? {
            let v = parse(&m.metrics_json);
            if v.is_object() {
                time = time.max(m.updated_at);
                hosts.push(v);
            }
        }
        ok(json!({"time": time, "hosts": hosts}))
    }

    // --- Model and reasoning --------------------------------------------------

    async fn default_model(&self) -> Result<Option<Value>, ApiError> {
        Ok(self
            .client
            .get_setting(MODEL_SETTING)
            .await?
            .map(|s| parse(&s))
            .filter(Value::is_object))
    }

    /// `GET /v1/settings/model`: the model launches without one start with,
    /// its reasoning level, and the models the cluster machine offers (from
    /// the placement reports).
    async fn model_settings(&self) -> Result<Reply, ApiError> {
        let placement = self.placement_value().await?;
        let reported: Vec<&Value> = placement["models"]
            .as_array()
            .into_iter()
            .flatten()
            .collect();
        let models: Vec<Value> = reported
            .iter()
            .map(|m| json!({"instanceId": m["instanceId"], "provider": m["provider"], "model": m["model"]}))
            .collect();
        let model = self
            .default_model()
            .await?
            .or_else(|| models.first().cloned())
            .unwrap_or_else(|| json!({}));
        let reasoning = reported
            .iter()
            .find(|m| m["instanceId"] == model["instanceId"] && m["model"] == model["model"])
            .and_then(|m| m["reasoning"].as_object().cloned());
        let reasoning = match reasoning {
            Some(r)
                if r.get("levels")
                    .and_then(Value::as_array)
                    .is_some_and(|l| !l.is_empty()) =>
            {
                let current = self
                    .client
                    .get_setting(&reasoning_key(&model))
                    .await?
                    .unwrap_or_default();
                json!({
                    "current": current,
                    "default": r.get("default").cloned().unwrap_or(json!("")),
                    "levels": r.get("levels").cloned().unwrap_or(json!([])),
                })
            }
            _ => Value::Null,
        };
        ok(json!({"model": model, "reasoning": reasoning, "models": models}))
    }

    async fn set_model(&self, body: &Value) -> Result<Reply, ApiError> {
        let instance = str_field(body, "instanceId");
        let model = str_field(body, "model");
        if instance.is_empty() || model.is_empty() {
            return Err(ApiError::invalid("instanceId and model are required."));
        }
        let placement = self.placement_value().await?;
        let found = placement["models"]
            .as_array()
            .into_iter()
            .flatten()
            .find(|m| m["instanceId"] == json!(instance) && m["model"] == json!(model))
            .cloned();
        let Some(found) = found else {
            return Err(ApiError::invalid(format!(
                "{model} is not a model the cluster machine offers."
            )));
        };
        let value = json!({"instanceId": instance, "provider": found["provider"], "model": model})
            .to_string();
        let c = &self.client;
        retrying(|| c.set_setting(MODEL_SETTING, &value, now_unix())).await?;
        self.model_settings().await
    }

    async fn set_reasoning(&self, body: &Value) -> Result<Reply, ApiError> {
        let level = str_field(body, "level");
        let settings = self.model_settings().await?.body;
        let Some(reasoning) = settings["reasoning"].as_object() else {
            return Err(ApiError::invalid("This model has no reasoning level."));
        };
        let level = if level == "default" {
            String::new()
        } else {
            level
        };
        let known = reasoning["levels"]
            .as_array()
            .is_some_and(|l| l.iter().any(|x| x.as_str() == Some(level.as_str())));
        if !level.is_empty() && !known {
            return Err(ApiError::invalid(format!(
                "{level:?} is not a reasoning level of this model."
            )));
        }
        let key = reasoning_key(&settings["model"]);
        let c = &self.client;
        retrying(|| c.set_setting(&key, &level, now_unix())).await?;
        self.model_settings().await
    }

    // --- The phone's settings -------------------------------------------------

    /// What the publisher set for this device (worker/src/android_settings.rs
    /// stores it under `android.settings.<device>`), or 404.
    async fn android_settings(&self) -> Result<Reply, ApiError> {
        let key = format!("{ANDROID_SETTINGS}{}", self.device);
        match self.client.get_setting(&key).await?.map(|raw| parse(&raw)) {
            Some(v) if v.is_object() => ok(v),
            _ => Err(ApiError::not_found()),
        }
    }

    // --- Placement ------------------------------------------------------------

    async fn placement_value(&self) -> Result<Value, ApiError> {
        let view = self
            .client
            .placement_view(self.cluster_machine.clone(), now_unix())
            .await?;
        Ok(serde_json::to_value(view)?)
    }

    async fn placement(&self) -> Result<Reply, ApiError> {
        ok(self.placement_value().await?)
    }
}

fn done_or_pending(done: bool) -> Reply {
    if done {
        Reply {
            status: 200,
            body: json!({"ok": true}),
        }
    } else {
        Reply {
            status: 202,
            body: json!({"ok": true, "pending": true}),
        }
    }
}

/// A pending body with why this device couldn't run the request itself.
fn detailed(mut body: Value, unreachable: &Option<String>) -> Value {
    if let (Some(why), Some(map)) = (unreachable, body.as_object_mut()) {
        map.insert("detail".into(), json!(why));
    }
    body
}

fn pending_detail(reply: Reply, unreachable: &Option<String>) -> Reply {
    Reply {
        body: detailed(reply.body, unreachable),
        ..reply
    }
}

/// The `settings` key of a model's reasoning level: each model keeps its own.
fn reasoning_key(model: &Value) -> String {
    format!(
        "reasoning:{}/{}",
        model["instanceId"].as_str().unwrap_or_default(),
        model["model"].as_str().unwrap_or_default()
    )
}

/// A project icon as the apps take it, or null when T3 sent none.
fn icon(json: &str) -> Value {
    let v = parse(json);
    if v.get("kind").and_then(Value::as_str).is_some() {
        v
    } else {
        Value::Null
    }
}

/// The environment a T3 link names: `…/threads/<env>/…` for a thread on the
/// called machine, `t3-thread://v2/<env>/…` through a link.
fn link_environment(link: &str) -> Option<String> {
    for marker in ["/threads/", "t3-thread://v2/"] {
        if let Some(pos) = link.find(marker) {
            let rest = &link[pos + marker.len()..];
            if let Some((env, _)) = rest.split_once('/')
                && !env.is_empty()
                && env
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"._%-".contains(&b))
            {
                return Some(decode(env));
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn paths_split_into_decoded_segments_and_query() {
        let (s, q) = split("/v1/agents/a%2Fb/activity?state=recent&x=a+b");
        assert_eq!(s, ["v1", "agents", "a/b", "activity"]);
        assert_eq!(query(&q, "state"), Some("recent"));
        assert_eq!(query(&q, "x"), Some("a b"));
        assert_eq!(decode("100%"), "100%");
    }

    #[test]
    fn links_name_their_environment() {
        assert_eq!(
            link_environment("https://app.t3.codes/threads/env-1/t1").as_deref(),
            Some("env-1")
        );
        assert_eq!(
            link_environment("t3-thread://v2/env%2E2/t1").as_deref(),
            Some("env.2")
        );
        assert_eq!(link_environment("https://example.com/x"), None);
    }

    #[test]
    fn leader_errors_keep_the_servers_statuses() {
        assert_eq!(ApiError::from_outcome("Agent not found.").status, 404);
        assert_eq!(
            ApiError::from_outcome("T3 could not stop this agent. Try again later.").status,
            503
        );
        // A request the leader ran and that failed is told apart from a busy replica.
        assert_eq!(
            ApiError::from_outcome("T3 could not stop this agent. Try again later.").code,
            "failed"
        );
        assert_eq!(ApiError::from_outcome("#4 has already ended.").status, 409);
        assert_eq!(
            ApiError::from_outcome("Say what the agent should do.").status,
            400
        );
    }
}
