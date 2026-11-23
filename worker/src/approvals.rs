//! T3 command approvals and T3 client API sessions, ported from
//! the Go server's `approvals.go` and `t3rpc.go`.

use crate::config::Config;
use crate::push::Pusher;
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use futures_util::{SinkExt, StreamExt};
use rocky_prism::{ApprovalRow, Notification, PrismClient, T3ClientSessionRow, sha256_hex};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256, Sha512};
use std::collections::BTreeSet;
use std::time::Duration;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;

const APPROVAL_NAMESPACE: &str = "approval@prism.quickme.at";

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct ApprovalOption {
    pub decision: String,
    pub label: String,
    #[serde(default)]
    pub warning: String,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct ApprovalDetail {
    pub label: String,
    pub value: String,
    #[serde(default)]
    pub code: bool,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ApprovalThread {
    pub environment_id: String,
    pub thread_id: String,
    pub title: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ApprovalView {
    pub id: String,
    pub title: String,
    pub source: String,
    pub requested_at: i64,
    #[serde(default)]
    pub expires_at: i64,
    #[serde(default)]
    pub summary: String,
    #[serde(default)]
    pub details: Vec<ApprovalDetail>,
    pub thread: Option<ApprovalThread>,
    #[serde(default)]
    pub options: Vec<ApprovalOption>,
    #[serde(default)]
    pub decision: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct T3ApprovalProjection {
    #[serde(default)]
    runtime_requests: Vec<RuntimeRequest>,
    #[serde(default)]
    turn_items: Vec<TurnItem>,
}

#[derive(Deserialize)]
struct RuntimeRequest {
    id: String,
    kind: String,
    status: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct TurnItem {
    #[serde(rename = "type")]
    kind: String,
    #[serde(default)]
    request_id: String,
    #[serde(default)]
    prompt: String,
    #[serde(default)]
    reason: String,
    #[serde(default)]
    full_access_override: String,
    #[serde(default)]
    options: Option<Vec<ApprovalOption>>,
}

impl T3ApprovalProjection {
    fn approvals(
        &self,
        session: &T3ClientSessionRow,
        thread_id: &str,
        title: &str,
        now: i64,
    ) -> Vec<(ApprovalView, String)> {
        let mut out = Vec::new();
        for req in &self.runtime_requests {
            if req.status != "pending"
                || !matches!(
                    req.kind.as_str(),
                    "command" | "file-read" | "file-change" | "permission" | "mcp-elicitation"
                )
            {
                continue;
            }
            for item in &self.turn_items {
                if item.kind != "approval_request" || item.request_id != req.id {
                    continue;
                }
                let options = item.options.clone().unwrap_or_else(|| {
                    vec![
                        ApprovalOption {
                            decision: "accept".into(),
                            label: "Allow once".into(),
                            warning: String::new(),
                        },
                        ApprovalOption {
                            decision: "acceptForSession".into(),
                            label: "Allow for this session".into(),
                            warning: String::new(),
                        },
                        ApprovalOption {
                            decision: "decline".into(),
                            label: "Deny".into(),
                            warning: String::new(),
                        },
                        ApprovalOption {
                            decision: "cancel".into(),
                            label: "Cancel".into(),
                            warning: String::new(),
                        },
                    ]
                });
                let filtered: Vec<ApprovalOption> = options
                    .into_iter()
                    .filter(|o| {
                        matches!(
                            o.decision.as_str(),
                            "accept" | "acceptForSession" | "decline" | "cancel"
                        )
                    })
                    .collect();
                if filtered.is_empty() || item.prompt.trim().is_empty() {
                    continue;
                }
                let selected = if filtered.iter().any(|o| o.decision == "accept") {
                    "accept".to_string()
                } else {
                    String::new()
                };
                let mut details = vec![ApprovalDetail {
                    label: "Command or permission".into(),
                    value: item.prompt.clone(),
                    code: true,
                }];
                if item.full_access_override == "ask_rule" {
                    details.push(ApprovalDetail {
                        label: "Why approval is required".into(),
                        value: "A configured ask rule requires your approval, including in Full access.".into(),
                        code: false,
                    });
                } else if item.full_access_override == "provider_check" {
                    details.push(ApprovalDetail {
                        label: "Why approval is required".into(),
                        value: "The provider requires your approval, including in Full access."
                            .into(),
                        code: false,
                    });
                }
                let id = sha256_hex(&format!(
                    "{}\x00{thread_id}\x00{}",
                    session.environment_id, req.id
                ));
                let view = ApprovalView {
                    id,
                    title: format!("T3 approval: {title}"),
                    source: session.machine.clone(),
                    requested_at: now,
                    expires_at: 0,
                    summary: item.reason.clone(),
                    details,
                    thread: Some(ApprovalThread {
                        environment_id: session.environment_id.clone(),
                        thread_id: thread_id.to_string(),
                        title: Some(title.to_string()),
                    }),
                    options: filtered,
                    decision: selected,
                };
                out.push((view, req.id.clone()));
            }
        }
        out
    }
}

/// T3 client API (HTTP GET + WebSocket JSON RPC).
pub struct T3Rpc {
    origin: String,
    token: String,
    http: reqwest::Client,
}

impl T3Rpc {
    pub fn new(origin: &str, token: &str) -> Self {
        let http = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(Duration::from_secs(30))
            .build()
            .expect("reqwest client");
        Self {
            origin: origin.trim_end_matches('/').to_string(),
            token: token.to_string(),
            http,
        }
    }

    pub async fn get<T: serde::de::DeserializeOwned>(&self, path: &str) -> Result<T, String> {
        let started = std::time::Instant::now();
        let resp = self
            .http
            .get(format!("{}{path}", self.origin))
            .bearer_auth(&self.token)
            .header("x-t3-orchestration-protocol", "2")
            .send()
            .await
            .map_err(|e| format!("T3 client GET {path} failed: {e}"))?;
        if !resp.status().is_success() {
            return Err(format!("T3 client GET {path}: HTTP {}", resp.status()));
        }
        // Read and parse separately: reqwest reports a body cut off mid-read
        // (the 30 s timeout) and invalid JSON with the same message.
        let body = resp.bytes().await.map_err(|e| {
            let cause = std::error::Error::source(&e)
                .map(|c| format!(" ({c})"))
                .unwrap_or_default();
            format!(
                "T3 client GET {path} read body after {:.1}s: {e}{cause}",
                started.elapsed().as_secs_f64()
            )
        })?;
        serde_json::from_slice(&body)
            .map_err(|e| format!("T3 client GET {path} decode {} bytes: {e}", body.len()))
    }

    pub async fn call(&self, method: &str, payload: Value) -> Result<Value, String> {
        let ws_url = if let Some(rest) = self.origin.strip_prefix("https://") {
            format!("wss://{rest}/ws?orchestrationProtocol=2")
        } else if let Some(rest) = self.origin.strip_prefix("http://") {
            format!("ws://{rest}/ws?orchestrationProtocol=2")
        } else {
            return Err("invalid T3 client origin".into());
        };
        let mut req = ws_url
            .into_client_request()
            .map_err(|e| format!("invalid ws url: {e}"))?;
        req.headers_mut().insert(
            "Authorization",
            format!("Bearer {}", self.token)
                .parse()
                .map_err(|_| "invalid token header".to_string())?,
        );
        let (mut ws, _) = tokio::time::timeout(
            Duration::from_secs(30),
            tokio_tungstenite::connect_async(req),
        )
        .await
        .map_err(|_| "T3 client connection timed out".to_string())?
        .map_err(|_| "T3 client connection failed".to_string())?;

        let frame = json!({
            "_tag": "Request",
            "id": "1",
            "tag": method,
            "payload": payload,
            "headers": [],
        });
        ws.send(Message::Text(frame.to_string().into()))
            .await
            .map_err(|_| "T3 client request failed".to_string())?;

        let deadline = tokio::time::Instant::now() + Duration::from_secs(30);
        while let Ok(Some(msg)) = tokio::time::timeout_at(deadline, ws.next()).await {
            let msg = msg.map_err(|_| "T3 client response failed".to_string())?;
            let text = match msg {
                Message::Text(t) => t.to_string(),
                Message::Binary(b) => String::from_utf8_lossy(&b).to_string(),
                Message::Close(_) => break,
                _ => continue,
            };
            let trimmed = text.trim();
            let frames: Vec<Value> = if trimmed.starts_with('[') {
                serde_json::from_str(trimmed).map_err(|e| e.to_string())?
            } else {
                vec![serde_json::from_str(trimmed).map_err(|e| e.to_string())?]
            };
            for f in frames {
                let tag = f.get("_tag").and_then(Value::as_str).unwrap_or("");
                if tag == "Defect" || tag == "ClientProtocolError" {
                    return Err("T3 client protocol error".into());
                }
                let req_id = f.get("requestId");
                let id_matches = req_id.and_then(Value::as_str) == Some("1")
                    || req_id.and_then(Value::as_i64) == Some(1);
                if tag != "Exit" || !id_matches {
                    continue;
                }
                let exit = f.get("exit").cloned().unwrap_or(Value::Null);
                if exit.get("_tag").and_then(Value::as_str) != Some("Success") {
                    return Err("T3 refused the client request".into());
                }
                return Ok(exit.get("value").cloned().unwrap_or(Value::Null));
            }
        }
        Err("T3 client response timed out".into())
    }
}

/// Validates and stores a machine's T3 client session (`PUT /v1/t3/client-session`).
pub async fn register_t3_client_session(
    client: &PrismClient,
    cfg: &Config,
    machine: &str,
    mut session: T3ClientSessionRow,
    now: i64,
) -> Result<(), (axum::http::StatusCode, String)> {
    use axum::http::StatusCode;
    let parsed = url::Url::parse(&session.origin).map_err(|_| {
        (
            StatusCode::BAD_REQUEST,
            "A T3 origin and an unexpired client session are required.".into(),
        )
    })?;
    if !matches!(parsed.scheme(), "http" | "https")
        || parsed.path() != "/"
        || session.token.is_empty()
        || session.expires_at <= now
    {
        return Err((
            StatusCode::BAD_REQUEST,
            "A T3 origin and an unexpired client session are required.".into(),
        ));
    }
    let configured = cfg.machines.get(machine).cloned().unwrap_or_default();
    let expected_nb = format!("{machine}.{}:3773", cfg.netbird_domain);
    let host_port = format!(
        "{}{}",
        parsed.host_str().unwrap_or(""),
        parsed.port().map(|p| format!(":{p}")).unwrap_or_default()
    );
    let mut allowed = host_port == expected_nb && parsed.scheme() == "http" && configured.has_t3();
    if !configured.t3_url.is_empty()
        && let Ok(expected) = url::Url::parse(&configured.t3_url)
    {
        allowed = allowed
            || (expected.host_str() == parsed.host_str()
                && expected.port_or_known_default() == parsed.port_or_known_default()
                && expected.scheme() == parsed.scheme());
    }
    if !allowed {
        return Err((
            StatusCode::BAD_REQUEST,
            "The T3 origin must name this machine.".into(),
        ));
    }
    if !configured.t3_url.is_empty() {
        session.origin = configured.t3_url.clone();
    }
    let rpc = T3Rpc::new(&session.origin, &session.token);
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct EnvDesc {
        #[serde(default)]
        environment_id: String,
        #[serde(default)]
        label: String,
    }
    let desc: EnvDesc = rpc.get("/.well-known/t3/environment").await.map_err(|_| {
        (
            StatusCode::SERVICE_UNAVAILABLE,
            "The T3 client endpoint did not answer.".into(),
        )
    })?;
    if !desc
        .label
        .eq_ignore_ascii_case(configured.link_label(machine))
        || desc.environment_id.is_empty()
    {
        return Err((
            StatusCode::BAD_REQUEST,
            "The T3 environment does not match this machine.".into(),
        ));
    }
    #[derive(Deserialize)]
    struct AuthSession {
        #[serde(default)]
        authenticated: bool,
        #[serde(default)]
        scopes: Vec<String>,
        #[serde(default)]
        permissions: Option<Vec<String>>,
    }
    let auth: AuthSession = rpc.get("/api/auth/session").await.map_err(|_| {
        (
            StatusCode::BAD_REQUEST,
            "T3 refused this client session.".into(),
        )
    })?;
    if !auth.authenticated {
        return Err((
            StatusCode::BAD_REQUEST,
            "T3 refused this client session.".into(),
        ));
    }
    let perms = auth.permissions.unwrap_or(auth.scopes);
    if !perms.iter().any(|p| p == "orchestration:read")
        || !perms.iter().any(|p| p == "orchestration:operate")
    {
        return Err((
            StatusCode::BAD_REQUEST,
            "The client session needs orchestration read and operate permissions.".into(),
        ));
    }
    session.machine = machine.to_string();
    session.environment_id = desc.environment_id;
    client
        .upsert_t3_client_session(&session)
        .await
        .map_err(|e| (StatusCode::SERVICE_UNAVAILABLE, e.to_string()))?;
    Ok(())
}

/// Scans every unexpired T3 client session for pending approvals.
pub async fn scan_approvals(client: &PrismClient, pusher: &Pusher, now: i64) -> Result<(), String> {
    let sessions = client
        .list_t3_client_sessions(now)
        .await
        .map_err(|e| e.to_string())?;
    for session in sessions {
        if let Err(err) = scan_one_session(client, pusher, &session, now).await {
            eprintln!(
                "prism-worker: approval scan failed for {}: {err}",
                session.machine
            );
        }
    }
    Ok(())
}

async fn scan_one_session(
    client: &PrismClient,
    pusher: &Pusher,
    session: &T3ClientSessionRow,
    now: i64,
) -> Result<(), String> {
    let rpc = T3Rpc::new(&session.origin, &session.token);
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Shell {
        #[serde(default)]
        threads: Vec<ShellThread>,
    }
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct ShellThread {
        id: String,
        #[serde(default)]
        title: String,
        pending_runtime_request: Option<Value>,
    }
    let shell: Shell = rpc.get("/api/orchestration/shell").await?;
    let mut seen = BTreeSet::new();
    for thread in shell.threads {
        if thread.pending_runtime_request.is_none() {
            continue;
        }
        #[derive(Deserialize)]
        struct Snapshot {
            projection: T3ApprovalProjection,
        }
        let path = format!(
            "/api/orchestration/threads/{}",
            url::form_urlencoded::byte_serialize(thread.id.as_bytes()).collect::<String>()
        );
        let snap: Snapshot = rpc.get(&path).await?;
        for (approval, request_id) in
            snap.projection
                .approvals(session, &thread.id, &thread.title, now)
        {
            seen.insert(approval.id.clone());
            let payload = serde_json::to_string(&approval).unwrap_or_default();
            let opened = client
                .upsert_open_approval(&ApprovalRow {
                    approval_id: approval.id.clone(),
                    machine: session.machine.clone(),
                    thread_id: thread.id.clone(),
                    request_id,
                    payload,
                    state: "open".into(),
                    created_at: now,
                    updated_at: now,
                })
                .await
                .map_err(|e| e.to_string())?;
            if opened {
                let n = Notification {
                    kind: "approval.pending".into(),
                    key: format!("approval.pending:{}", approval.id),
                    time: now,
                    title: approval.title.clone(),
                    body: approval.summary.clone(),
                    host: session.machine.clone(),
                    thread_id: thread.id.clone(),
                    environment_id: session.environment_id.clone(),
                    ..Notification::default()
                };
                let _ = pusher.deliver(client, &[n], now).await;
            }
        }
    }
    let open = client
        .list_open_approvals(Some(&session.machine))
        .await
        .map_err(|e| e.to_string())?;
    for a in open {
        if !seen.contains(&a.approval_id) {
            client
                .close_approval(&a.approval_id, "closed", now)
                .await
                .map_err(|e| e.to_string())?;
            let n = Notification {
                kind: "approval.closed".into(),
                key: format!("approval.closed:{}", a.approval_id),
                time: now,
                title: "Approval closed".into(),
                ..Notification::default()
            };
            let _ = pusher.deliver(client, &[n], now).await;
        }
    }
    Ok(())
}

/// Claims and dispatches pending `approval_answers` rows.
pub async fn run_approval_answers(
    client: &PrismClient,
    cfg: &Config,
    pusher: &Pusher,
    worker: &str,
    now: i64,
    claim_timeout: i64,
) -> Result<(), String> {
    let items = client
        .claimable_approval_answers(now - claim_timeout)
        .await
        .map_err(|e| e.to_string())?;
    for item in items {
        if !client
            .claim_approval_answer(&item.request_id, worker, now, now - claim_timeout)
            .await
            .map_err(|e| e.to_string())?
        {
            continue;
        }
        let sessions = client
            .list_t3_client_sessions(now)
            .await
            .map_err(|e| e.to_string())?;
        let err_msg =
            match process_approval_answer(client, cfg, pusher, &item, &sessions, now).await {
                Ok(()) => String::new(),
                Err(e) => e,
            };
        client
            .finish_approval_answer(&item.request_id, worker, now, &err_msg)
            .await
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// Answers one claimed approval in T3 with the client session in `sessions`
/// for the approval's machine: the workers' own (`t3_client_sessions`), or a
/// device's when the workers are unreachable (`crate::direct`).
pub async fn process_approval_answer(
    client: &PrismClient,
    cfg: &Config,
    pusher: &Pusher,
    req: &rocky_prism::ApprovalAnswerRow,
    sessions: &[T3ClientSessionRow],
    now: i64,
) -> Result<(), String> {
    let Some(stored_row) = client
        .get_open_approval(&req.approval_id)
        .await
        .map_err(|e| e.to_string())?
    else {
        return Err("Approval not found or already closed.".into());
    };
    let stored: ApprovalView = serde_json::from_str(&stored_row.payload)
        .map_err(|_| "Invalid stored approval.".to_string())?;
    let message = data_encoding::BASE64
        .decode(req.message.as_bytes())
        .map_err(|_| "Invalid approval answer.".to_string())?;
    let mut answer: ApprovalView =
        serde_json::from_slice(&message).map_err(|_| "Invalid approval answer.".to_string())?;
    let decision = answer.decision.clone();
    answer.decision = stored.decision.clone();
    if answer != stored || (stored.expires_at != 0 && stored.expires_at <= now) {
        return Err("This approval changed or expired. Refresh it before answering.".into());
    }
    if !stored.options.iter().any(|o| o.decision == decision) {
        return Err("T3 did not offer this decision.".into());
    }
    // As in Go: an accept needs the answering device's signature over the
    // exact approval. A device without a configured key cannot accept.
    if decision.starts_with("accept") {
        let key = cfg
            .devices
            .get(&req.requested_by)
            .map(|d| d.key.trim())
            .unwrap_or_default();
        if key.is_empty()
            || verify_sshsig(key, APPROVAL_NAMESPACE, &message, &req.signature).is_err()
        {
            return Err("The approval signature is invalid.".into());
        }
    }
    let Some(session) = sessions
        .iter()
        .find(|s| s.machine == stored_row.machine && s.expires_at > now)
        .cloned()
    else {
        return Err("This machine's T3 client session expired.".into());
    };
    let rpc = T3Rpc::new(&session.origin, &session.token);
    // T3 must still ask exactly this before Prism answers it.
    #[derive(Deserialize)]
    struct Snapshot {
        projection: T3ApprovalProjection,
    }
    let path = format!(
        "/api/orchestration/threads/{}",
        url::form_urlencoded::byte_serialize(stored_row.thread_id.as_bytes()).collect::<String>()
    );
    let snap: Snapshot = rpc
        .get(&path)
        .await
        .map_err(|_| "T3 did not answer. Try again.".to_string())?;
    let title = stored
        .thread
        .as_ref()
        .and_then(|t| t.title.clone())
        .unwrap_or_default();
    let live =
        snap.projection
            .approvals(&session, &stored_row.thread_id, &title, stored.requested_at);
    if !live
        .iter()
        .any(|(view, _)| view.id == stored_row.approval_id && *view == stored)
    {
        client
            .close_approval(&stored_row.approval_id, "closed", now)
            .await
            .map_err(|e| e.to_string())?;
        let n = Notification {
            kind: "approval.closed".into(),
            key: format!("approval.closed:{}", stored_row.approval_id),
            time: now,
            title: "Approval closed".into(),
            ..Notification::default()
        };
        let _ = pusher.deliver(client, &[n], now).await;
        return Err("Approval not found or already closed.".into());
    }
    rpc.call(
        "orchestration.dispatchCommand",
        json!({
            "type": "runtime-request.respond",
            "commandId": format!("prism:approval:{}", stored_row.approval_id),
            "threadId": stored_row.thread_id,
            "requestId": stored_row.request_id,
            "decision": decision,
        }),
    )
    .await
    .map_err(|_| "T3 could not confirm the decision. Try again.".to_string())?;
    client
        .close_approval(&stored_row.approval_id, "answered", now)
        .await
        .map_err(|e| e.to_string())?;
    let n = Notification {
        kind: "approval.closed".into(),
        key: format!("approval.closed:{}", stored_row.approval_id),
        time: now,
        title: "Approval closed".into(),
        ..Notification::default()
    };
    let _ = pusher.deliver(client, &[n], now).await;
    Ok(())
}

/// Verifies an armored OpenSSH `SSHSIG` (the `ssh-keygen -Y sign` format,
/// PROTOCOL.sshsig) over `message` in `namespace`, made by the key in
/// `pubkey_line` (authorized_keys form). Like the Go server's `verifySSHSig`,
/// the signature must embed exactly that key. Both apps sign with
/// `ecdsa-sha2-nistp256` (`shared/.../SshSig.kt`); `ssh-ed25519` works too.
pub fn verify_sshsig(
    pubkey_line: &str,
    namespace: &str,
    message: &[u8],
    armored: &str,
) -> Result<(), String> {
    let mut fields = pubkey_line.split_whitespace();
    let (Some(line_type), Some(line_b64)) = (fields.next(), fields.next()) else {
        return Err("malformed public key".into());
    };
    let key_blob = data_encoding::BASE64
        .decode(line_b64.as_bytes())
        .map_err(|_| "bad public key base64")?;
    let mut cur = key_blob.as_slice();
    let key_type = read_ssh_string(&mut cur)?;
    if key_type != line_type.as_bytes() {
        return Err("public key type mismatch".into());
    }

    let armored = armored.trim();
    let body = armored
        .strip_prefix("-----BEGIN SSH SIGNATURE-----")
        .and_then(|b| b.strip_suffix("-----END SSH SIGNATURE-----"))
        .ok_or("not an SSH signature")?;
    let b64: String = body.split_whitespace().collect();
    let blob = data_encoding::BASE64
        .decode(b64.as_bytes())
        .map_err(|_| "signature is not base64")?;
    let mut cur = blob
        .strip_prefix(b"SSHSIG")
        .ok_or("not an SSHSIG signature")?;
    if cur.len() < 4 || u32::from_be_bytes([cur[0], cur[1], cur[2], cur[3]]) != 1 {
        return Err("not an SSHSIG v1 signature".into());
    }
    cur = &cur[4..];
    let sig_pubkey = read_ssh_string(&mut cur)?;
    let sig_ns = read_ssh_string(&mut cur)?;
    let reserved = read_ssh_string(&mut cur)?;
    let hash_alg = read_ssh_string(&mut cur)?;
    let sig_blob = read_ssh_string(&mut cur)?;
    if !cur.is_empty() {
        return Err("malformed signature".into());
    }
    if sig_pubkey != key_blob.as_slice() {
        return Err("signed by another key".into());
    }
    if sig_ns != namespace.as_bytes() {
        return Err("namespace mismatch".into());
    }
    let digest: Vec<u8> = match hash_alg {
        b"sha512" => Sha512::digest(message).to_vec(),
        b"sha256" => Sha256::digest(message).to_vec(),
        _ => return Err("unsupported hash algorithm".into()),
    };
    let mut signed = b"SSHSIG".to_vec();
    for f in [namespace.as_bytes(), reserved, hash_alg, &digest] {
        write_ssh_string(&mut signed, f);
    }

    let mut sc = sig_blob;
    let sig_type = read_ssh_string(&mut sc)?;
    let raw_sig = read_ssh_string(&mut sc)?;
    if sig_type != key_type {
        return Err("signature type mismatch".into());
    }
    match key_type {
        b"ssh-ed25519" => {
            let raw_key = read_ssh_string(&mut cur_of(&key_blob, 1)?)?;
            let vk_bytes: [u8; 32] = raw_key.try_into().map_err(|_| "bad ed25519 key")?;
            let vk = VerifyingKey::from_bytes(&vk_bytes).map_err(|_| "invalid ed25519 key")?;
            let sig_bytes: [u8; 64] = raw_sig.try_into().map_err(|_| "bad signature length")?;
            vk.verify(&signed, &Signature::from_bytes(&sig_bytes))
                .map_err(|_| "bad signature".to_string())
        }
        b"ecdsa-sha2-nistp256" => {
            let mut kc = cur_of(&key_blob, 1)?;
            if read_ssh_string(&mut kc)? != b"nistp256" {
                return Err("not a P-256 key".into());
            }
            let point = read_ssh_string(&mut kc)?;
            let vk = p256::ecdsa::VerifyingKey::from_sec1_bytes(point)
                .map_err(|_| "invalid P-256 key")?;
            let mut rs = raw_sig;
            let r = mpint_32(read_ssh_string(&mut rs)?)?;
            let s = mpint_32(read_ssh_string(&mut rs)?)?;
            let sig = p256::ecdsa::Signature::from_scalars(r, s)
                .map_err(|_| "malformed ECDSA signature")?;
            use p256::ecdsa::signature::Verifier as _;
            vk.verify(&signed, &sig)
                .map_err(|_| "bad signature".to_string())
        }
        _ => Err("unsupported key type".into()),
    }
}

/// The key blob after its first `skip` strings (the key type).
fn cur_of(blob: &[u8], skip: usize) -> Result<&[u8], String> {
    let mut cur = blob;
    for _ in 0..skip {
        read_ssh_string(&mut cur)?;
    }
    Ok(cur)
}

/// A non-negative SSH mpint as a 32-byte big-endian scalar.
fn mpint_32(v: &[u8]) -> Result<[u8; 32], String> {
    let v = match v {
        [0, rest @ ..] => rest,
        _ => v,
    };
    if v.len() > 32 {
        return Err("bad ECDSA scalar".into());
    }
    let mut out = [0u8; 32];
    out[32 - v.len()..].copy_from_slice(v);
    Ok(out)
}

fn read_ssh_string<'a>(cur: &mut &'a [u8]) -> Result<&'a [u8], String> {
    if cur.len() < 4 {
        return Err("truncated ssh string len".into());
    }
    let len = u32::from_be_bytes([cur[0], cur[1], cur[2], cur[3]]) as usize;
    *cur = &cur[4..];
    if cur.len() < len {
        return Err("truncated ssh string data".into());
    }
    let out = &cur[..len];
    *cur = &cur[len..];
    Ok(out)
}

fn write_ssh_string(buf: &mut Vec<u8>, data: &[u8]) {
    buf.extend_from_slice(&(data.len() as u32).to_be_bytes());
    buf.extend_from_slice(data);
}

#[cfg(test)]
mod tests {
    use super::*;

    // Made with OpenSSH: `ssh-keygen -Y sign -n approval@prism.quickme.at -f <key> msg`.
    const MESSAGE: &[u8] = br#"{"id":"abc","decision":"accept"}"#;
    const EC_KEY: &str = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBL9DsfHS38LkB2CUemv5oYiHlidj66gog+kA6mQWMEkaxVtGCQH6Hom6xGQpwkKuLvnLz9h5YhfX05SulPSeTxk=";
    const EC_OTHER_KEY: &str = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBOBCQu6ZbYL3Z5HKjlSVaJHCLnFmbwn+u6n9pu5vrg80zYc9OVNh8RGZFp0XpPNhp3AGBPhoNYmIwAoDpARc180=";
    const EC_SIG: &str = "-----BEGIN SSH SIGNATURE-----
U1NIU0lHAAAAAQAAAGgAAAATZWNkc2Etc2hhMi1uaXN0cDI1NgAAAAhuaXN0cDI1NgAAAE
EEv0Ox8dLfwuQHYJR6a/mhiIeWJ2PrqCiD6QDqZBYwSRrFW0YJAfoeibrEZCnCQq4u+cvP
2HliF9fTlK6U9J5PGQAAABlhcHByb3ZhbEBwcmlzbS5xdWlja21lLmF0AAAAAAAAAAZzaG
E1MTIAAABjAAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAABIAAAAIGtQu8uQZ8bbxZl31UGr
Uug1KoU28fSFYgDJgTvz6eZtAAAAIBJ17wkLWL2PqGIST8MUqcW7e2GhjTb0ohKiUT7wQb
lr
-----END SSH SIGNATURE-----
";
    const ED_KEY: &str =
        "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGlUy1LN6+DU20J/UptSKLTwCVbxfA0ySc6LsR9Sxkgb";
    const ED_SIG: &str = "-----BEGIN SSH SIGNATURE-----
U1NIU0lHAAAAAQAAADMAAAALc3NoLWVkMjU1MTkAAAAgaVTLUs3r4NTbQn9Sm1IotPAJVv
F8DTJJzouxH1LGSBsAAAAZYXBwcm92YWxAcHJpc20ucXVpY2ttZS5hdAAAAAAAAAAGc2hh
NTEyAAAAUwAAAAtzc2gtZWQyNTUxOQAAAEDELsjLRvOpXXC8Cy13X33n25VVzlDsJfc8mG
pMZ7xb55wXBX9xiwAX8SsvOGQ5CPFK5e709uzUNI0CAG3vTXsM
-----END SSH SIGNATURE-----
";

    #[test]
    fn openssh_ecdsa_p256_signature_verifies() {
        verify_sshsig(EC_KEY, APPROVAL_NAMESPACE, MESSAGE, EC_SIG).expect("valid");
    }

    #[test]
    fn openssh_ed25519_signature_verifies() {
        verify_sshsig(ED_KEY, APPROVAL_NAMESPACE, MESSAGE, ED_SIG).expect("valid");
    }

    #[test]
    fn a_changed_message_namespace_or_key_is_rejected() {
        let changed = br#"{"id":"abc","decision":"acceptForSession"}"#;
        assert!(verify_sshsig(EC_KEY, APPROVAL_NAMESPACE, changed, EC_SIG).is_err());
        assert!(verify_sshsig(EC_KEY, "enroll@prism.quickme.at", MESSAGE, EC_SIG).is_err());
        assert!(verify_sshsig(EC_OTHER_KEY, APPROVAL_NAMESPACE, MESSAGE, EC_SIG).is_err());
        assert!(verify_sshsig(ED_KEY, APPROVAL_NAMESPACE, MESSAGE, EC_SIG).is_err());
        assert!(verify_sshsig(EC_KEY, APPROVAL_NAMESPACE, MESSAGE, "").is_err());
        let unarmored: String = EC_SIG.lines().filter(|l| !l.starts_with("-----")).collect();
        assert!(verify_sshsig(EC_KEY, APPROVAL_NAMESPACE, MESSAGE, &unarmored).is_err());
    }

    /// An SSHSIG built the way the apps build one (`SshSig.kt`): a DER ECDSA
    /// signature over the signed data, re-encoded as two mpints.
    pub(crate) fn app_signature(
        key: &p256::ecdsa::SigningKey,
        namespace: &str,
        message: &[u8],
    ) -> (String, String) {
        use p256::ecdsa::signature::Signer as _;
        let point = key.verifying_key().to_encoded_point(false);
        let mut key_blob = Vec::new();
        write_ssh_string(&mut key_blob, b"ecdsa-sha2-nistp256");
        write_ssh_string(&mut key_blob, b"nistp256");
        write_ssh_string(&mut key_blob, point.as_bytes());
        let mut signed = b"SSHSIG".to_vec();
        for f in [
            namespace.as_bytes(),
            b"",
            b"sha512",
            Sha512::digest(message).as_slice(),
        ] {
            write_ssh_string(&mut signed, f);
        }
        let sig: p256::ecdsa::Signature = key.sign(&signed);
        let mpint = |b: &[u8]| {
            let b: Vec<u8> = b.iter().copied().skip_while(|&x| x == 0).collect();
            if b.first().is_some_and(|x| x & 0x80 != 0) {
                [vec![0], b].concat()
            } else {
                b
            }
        };
        let (r, s) = sig.split_bytes();
        let mut inner = Vec::new();
        write_ssh_string(&mut inner, &mpint(&r));
        write_ssh_string(&mut inner, &mpint(&s));
        let mut sig_blob = Vec::new();
        write_ssh_string(&mut sig_blob, b"ecdsa-sha2-nistp256");
        write_ssh_string(&mut sig_blob, &inner);
        let mut blob = b"SSHSIG".to_vec();
        blob.extend_from_slice(&1u32.to_be_bytes());
        for f in [
            &key_blob[..],
            namespace.as_bytes(),
            b"",
            b"sha512",
            &sig_blob,
        ] {
            write_ssh_string(&mut blob, f);
        }
        let b64 = data_encoding::BASE64.encode(&blob);
        let lines: Vec<&str> = b64
            .as_bytes()
            .chunks(70)
            .map(|c| std::str::from_utf8(c).unwrap())
            .collect();
        let armored = format!(
            "-----BEGIN SSH SIGNATURE-----\n{}\n-----END SSH SIGNATURE-----\n",
            lines.join("\n")
        );
        let line = format!(
            "ecdsa-sha2-nistp256 {}",
            data_encoding::BASE64.encode(&key_blob)
        );
        (line, armored)
    }

    #[test]
    fn app_built_signatures_verify_for_many_keys() {
        for _ in 0..32 {
            let key = p256::ecdsa::SigningKey::random(&mut rand_core::OsRng);
            let (line, sig) = app_signature(&key, APPROVAL_NAMESPACE, MESSAGE);
            verify_sshsig(&line, APPROVAL_NAMESPACE, MESSAGE, &sig).expect("valid");
        }
    }

    mod mock_t3 {
        use axum::{
            Router,
            extract::{
                State,
                ws::{Message as WsMessage, WebSocketUpgrade},
            },
            http::HeaderMap,
            response::{IntoResponse, Response},
            routing::get,
        };
        use std::sync::{
            Arc, Mutex,
            atomic::{AtomicBool, AtomicUsize, Ordering},
        };

        #[derive(Clone, Default)]
        pub struct T3 {
            pub decisions: Arc<Mutex<Vec<String>>>,
            pub gone: Arc<AtomicBool>,
            pub reads: Arc<AtomicUsize>,
        }

        fn authed(h: &HeaderMap) -> bool {
            h.get("authorization").and_then(|v| v.to_str().ok()) == Some("Bearer client-token")
        }

        pub async fn start(t3: T3) -> String {
            let app = Router::new()
                .route("/.well-known/t3/environment", get(|h: HeaderMap| async move {
                    if !authed(&h) { return (axum::http::StatusCode::UNAUTHORIZED, "").into_response(); }
                    r#"{"environmentId":"env","label":"david-server"}"#.into_response()
                }))
                .route("/api/auth/session", get(|| async {
                    r#"{"authenticated":true,"permissions":["orchestration:read","orchestration:operate"]}"#
                }))
                .route("/api/orchestration/shell", get(|| async {
                    r#"{"threads":[{"id":"thread:1","title":"Test","pendingRuntimeRequest":{"id":"request:1"}}]}"#
                }))
                .route("/api/orchestration/threads/{id}", get(|State(t3): State<T3>, h: HeaderMap| async move {
                    if !authed(&h) { return (axum::http::StatusCode::UNAUTHORIZED, "").into_response(); }
                    t3.reads.fetch_add(1, Ordering::SeqCst);
                    let status = if t3.gone.load(Ordering::SeqCst) { "resolved" } else { "pending" };
                    format!(r#"{{"projection":{{"runtimeRequests":[{{"id":"request:1","kind":"command","status":"{status}"}}],"turnItems":[{{"type":"approval_request","requestId":"request:1","prompt":"printf hello","options":[{{"decision":"accept","label":"Allow once"}},{{"decision":"acceptForSession","label":"Allow session"}},{{"decision":"decline","label":"Deny"}}]}}]}}}}"#).into_response()
                }))
                .route("/ws", get(|State(t3): State<T3>, h: HeaderMap, ws: WebSocketUpgrade| async move {
                    if !authed(&h) { return (axum::http::StatusCode::UNAUTHORIZED, "").into_response(); }
                    ws.on_upgrade(move |mut socket| async move {
                        if let Some(Ok(WsMessage::Text(raw))) = socket.recv().await {
                            let v: serde_json::Value = serde_json::from_str(&raw).unwrap();
                            let d = v["payload"]["decision"].as_str().unwrap_or("").to_string();
                            t3.decisions.lock().unwrap().push(d);
                            let _ = socket.send(WsMessage::Text(
                                r#"{"_tag":"Exit","requestId":"1","exit":{"_tag":"Success","value":{"sequence":1}}}"#.into(),
                            )).await;
                        }
                    }) as Response
                }))
                .with_state(t3);
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let addr = listener.local_addr().unwrap();
            tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
            format!("http://{addr}")
        }
    }

    async fn harness() -> (
        tempfile::TempDir,
        PrismClient,
        Config,
        Pusher,
        mock_t3::T3,
        p256::ecdsa::SigningKey,
    ) {
        let dir = tempfile::tempdir().unwrap();
        let client = PrismClient::open_or_create(dir.path().display().to_string(), None)
            .await
            .unwrap();
        let t3 = mock_t3::T3::default();
        let origin = mock_t3::start(t3.clone()).await;
        let key = p256::ecdsa::SigningKey::random(&mut rand_core::OsRng);
        let (line, _) = app_signature(&key, APPROVAL_NAMESPACE, b"");
        let mut cfg = Config::default();
        cfg.machines.insert(
            "david-server".into(),
            crate::config::MachineConfig {
                t3_url: origin.clone(),
                ..Default::default()
            },
        );
        cfg.devices.insert(
            "david-pixel".into(),
            crate::config::DeviceConfig { key: line },
        );
        cfg.devices
            .insert("keyless".into(), crate::config::DeviceConfig::default());
        let pusher = Pusher::new(
            "http://127.0.0.1:9",
            "t",
            std::sync::Arc::new(|_: &str| None),
        );
        let session = T3ClientSessionRow {
            machine: String::new(),
            origin,
            token: "client-token".into(),
            environment_id: String::new(),
            expires_at: NOW + 3600,
        };
        register_t3_client_session(&client, &cfg, "david-server", session, NOW)
            .await
            .expect("register");
        scan_approvals(&client, &pusher, NOW).await.unwrap();
        (dir, client, cfg, pusher, t3, key)
    }

    const NOW: i64 = 1_800_000_000;

    async fn sessions(client: &PrismClient) -> Vec<T3ClientSessionRow> {
        client.list_t3_client_sessions(NOW).await.unwrap()
    }

    fn answer_row(by: &str, message: &[u8], signature: String) -> rocky_prism::ApprovalAnswerRow {
        rocky_prism::ApprovalAnswerRow {
            request_id: "r".into(),
            approval_id: String::new(),
            message: data_encoding::BASE64.encode(message),
            signature,
            requested_by: by.into(),
            requested_at: NOW,
            claimed_by: None,
            claimed_at: None,
            done_at: None,
            error: String::new(),
        }
    }

    #[tokio::test]
    async fn only_a_signed_unchanged_accept_reaches_t3_once() {
        let (_dir, client, cfg, pusher, t3, key) = harness().await;
        let open = client.list_open_approvals(None).await.unwrap();
        assert_eq!(open.len(), 1);
        let id = open[0].approval_id.clone();
        let mut view: serde_json::Value = serde_json::from_str(&open[0].payload).unwrap();
        let once = serde_json::to_vec(&view).unwrap();
        view["decision"] = "acceptForSession".into();
        let session = serde_json::to_vec(&view).unwrap();
        let run = |row: rocky_prism::ApprovalAnswerRow| {
            let (client, cfg, pusher) = (&client, &cfg, &pusher);
            let id = id.clone();
            async move {
                let row = rocky_prism::ApprovalAnswerRow {
                    approval_id: id,
                    ..row
                };
                let sessions = client.list_t3_client_sessions(NOW).await.unwrap();
                process_approval_answer(client, cfg, pusher, &row, &sessions, NOW).await
            }
        };

        // Unsigned, from a device without a key, or signed for another scope.
        assert!(
            run(answer_row("david-pixel", &session, String::new()))
                .await
                .is_err()
        );
        let (_, sig) = app_signature(&key, APPROVAL_NAMESPACE, &session);
        assert!(
            run(answer_row("keyless", &session, sig.clone()))
                .await
                .is_err()
        );
        assert!(
            run(answer_row("stranger", &session, sig.clone()))
                .await
                .is_err()
        );
        let (_, once_sig) = app_signature(&key, APPROVAL_NAMESPACE, &once);
        assert!(
            run(answer_row("david-pixel", &session, once_sig))
                .await
                .is_err()
        );
        let (_, wrong_ns) = app_signature(&key, "enroll@prism.quickme.at", &session);
        assert!(
            run(answer_row("david-pixel", &session, wrong_ns))
                .await
                .is_err()
        );
        assert!(t3.decisions.lock().unwrap().is_empty());

        run(answer_row("david-pixel", &session, sig.clone()))
            .await
            .expect("signed answer");
        assert_eq!(*t3.decisions.lock().unwrap(), ["acceptForSession"]);
        assert!(client.get_open_approval(&id).await.unwrap().is_none());

        // A replay finds the approval closed.
        assert!(run(answer_row("david-pixel", &session, sig)).await.is_err());
        assert_eq!(t3.decisions.lock().unwrap().len(), 1);
    }

    #[tokio::test]
    async fn a_decline_needs_no_signature_and_a_gone_request_is_not_answered() {
        let (_dir, client, cfg, pusher, t3, _key) = harness().await;
        let open = client.list_open_approvals(None).await.unwrap();
        let id = open[0].approval_id.clone();
        let mut view: serde_json::Value = serde_json::from_str(&open[0].payload).unwrap();
        view["decision"] = "decline".into();
        let decline = serde_json::to_vec(&view).unwrap();

        // T3 no longer asks: the approval closes and nothing is dispatched.
        t3.gone.store(true, std::sync::atomic::Ordering::SeqCst);
        let row = rocky_prism::ApprovalAnswerRow {
            approval_id: id.clone(),
            ..answer_row("keyless", &decline, String::new())
        };
        assert!(
            process_approval_answer(&client, &cfg, &pusher, &row, &sessions(&client).await, NOW)
                .await
                .is_err()
        );
        assert!(t3.decisions.lock().unwrap().is_empty());
        assert!(client.get_open_approval(&id).await.unwrap().is_none());

        // Pending again: a scan reopens it, and an unsigned decline goes through.
        t3.gone.store(false, std::sync::atomic::Ordering::SeqCst);
        scan_approvals(&client, &pusher, NOW + 1).await.unwrap();
        let open = client.list_open_approvals(None).await.unwrap();
        let mut view: serde_json::Value = serde_json::from_str(&open[0].payload).unwrap();
        view["decision"] = "decline".into();
        let decline = serde_json::to_vec(&view).unwrap();
        let row = rocky_prism::ApprovalAnswerRow {
            approval_id: id,
            ..answer_row("keyless", &decline, String::new())
        };
        process_approval_answer(
            &client,
            &cfg,
            &pusher,
            &row,
            &sessions(&client).await,
            NOW + 1,
        )
        .await
        .expect("decline");
        assert_eq!(*t3.decisions.lock().unwrap(), ["decline"]);
    }
    /// The workers are unreachable: the phone answers with its own client
    /// session, not the workers', and the answer is its own request.
    #[tokio::test]
    async fn a_device_answers_with_its_own_client_session() {
        use crate::direct::{
            CONFIG_SETTING, DeviceSession, Direct, KIND_CLIENT, device_config, session_key,
        };
        let (_dir, client, cfg, _pusher, t3, key) = harness().await;
        let client = std::sync::Arc::new(client);
        client
            .set_setting(CONFIG_SETTING, &device_config(&cfg), NOW)
            .await
            .unwrap();
        let origin = cfg.machines["david-server"].t3_url.clone();
        // Registration records T3's environment, as for the workers' session.
        let environment = sessions(&client).await[0].environment_id.clone();
        assert!(!environment.is_empty());
        let session = |token: &str| DeviceSession {
            origin: origin.clone(),
            token: token.into(),
            environment_id: environment.clone(),
            expires_at: NOW + 3600,
        };
        let open = client.list_open_approvals(None).await.unwrap();
        let id = open[0].approval_id.clone();
        let mut view: serde_json::Value = serde_json::from_str(&open[0].payload).unwrap();
        view["decision"] = "accept".into();
        let message = serde_json::to_vec(&view).unwrap();
        let (_, sig) = app_signature(&key, APPROVAL_NAMESPACE, &message);
        let encoded = data_encoding::BASE64.encode(&message);
        let clock: crate::leader::Clock = std::sync::Arc::new(|| NOW);
        let key_name = session_key("david-pixel", "david-server", KIND_CLIENT);

        // A session T3 refuses: the answer fails and nothing is decided.
        client
            .set_setting(
                &key_name,
                &serde_json::to_string(&session("revoked")).unwrap(),
                NOW,
            )
            .await
            .unwrap();
        client
            .request_approval_answer("ans-1", &id, &encoded, &sig, "david-pixel", NOW)
            .await
            .unwrap();
        let direct = Direct::open(std::sync::Arc::clone(&client), "david-pixel", clock.clone())
            .await
            .unwrap();
        assert!(direct.approval_answer("ans-1").await.unwrap());
        let failed = client
            .request_outcome(rocky_prism::RequestTable::ApprovalAnswers, "ans-1")
            .await
            .unwrap()
            .unwrap();
        assert!(!failed.error.is_empty());
        assert!(t3.decisions.lock().unwrap().is_empty());

        // Its own valid session: T3 gets the decision once.
        client
            .set_setting(
                &key_name,
                &serde_json::to_string(&session("client-token")).unwrap(),
                NOW,
            )
            .await
            .unwrap();
        client
            .request_approval_answer("ans-2", &id, &encoded, &sig, "david-pixel", NOW)
            .await
            .unwrap();
        let direct = Direct::open(std::sync::Arc::clone(&client), "david-pixel", clock)
            .await
            .unwrap();
        assert!(direct.approval_answer("ans-2").await.unwrap());
        let done = client
            .request_outcome(rocky_prism::RequestTable::ApprovalAnswers, "ans-2")
            .await
            .unwrap()
            .unwrap();
        assert!(done.error.is_empty(), "{}", done.error);
        assert_eq!(done.claimed_by.as_deref(), Some("device:david-pixel"));
        assert_eq!(*t3.decisions.lock().unwrap(), ["accept"]);
        assert!(!direct.approval_answer("ans-2").await.unwrap());
    }
}
