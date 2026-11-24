//! A device's own route to T3, for when it cannot reach the workers.
//!
//! Every Prism device (the phone and the Macs) has its own T3 sessions on
//! each fleet T3 host: a client session, for approvals, and a full-access MCP
//! session, for launches, follow-ups, stops and answers. Each host mints them
//! itself (`prism register-t3-devices`, from fleet's hourly timer) and hands
//! them to the workers (`PUT /v1/t3/device-session`), which keep them in the
//! replicated `settings` table as `t3.device.<device>.<machine>.<kind>`. So
//! every device finds its own sessions in its replica, and nobody pairs by
//! hand. The leader also publishes the part of its config that decides
//! targets and prompts (`t3.config`, [`device_config`]).
//!
//! The workers stay the way requests normally run. When a device can't reach
//! them, it claims its own request row as `device:<name>`
//! (`rocky_prism::DEVICE_CLAIM_PREFIX`), which no worker reclaims, and runs
//! it with the code the leader runs, its own sessions standing in for the
//! workers'. The finished row syncs to the workers once they are back.

use crate::agents::{process_agent_message, process_agent_request};
use crate::approvals::process_approval_answer;
use crate::config::Config;
use crate::leader::{CLAIM_TIMEOUT_SECS, Clock, run_alert_action};
use crate::magic::AUTO_CHOICE;
use crate::push::Pusher;
use crate::questions::process_question_answer;
use crate::t3::{Secrets, T3Hub};
use rocky_prism::{DEVICE_CLAIM_PREFIX, PrismClient, PrismError, T3ClientSessionRow};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::sync::Arc;

/// The `settings` key of the config the devices run requests with.
pub const CONFIG_SETTING: &str = "t3.config";
const SESSION_PREFIX: &str = "t3.device.";
/// A T3 client API session: reads threads and answers approvals.
pub const KIND_CLIENT: &str = "client";
/// A T3 MCP session with full access: what the workers launch with.
pub const KIND_MCP: &str = "mcp";
pub const KINDS: [&str; 2] = [KIND_CLIENT, KIND_MCP];

/// One of a device's T3 sessions, as `settings` keeps it.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceSession {
    /// Where the device reaches this T3, e.g. `http://david-mb.netbird.cloud:3773`.
    pub origin: String,
    pub token: String,
    #[serde(default)]
    pub environment_id: String,
    pub expires_at: i64,
}

/// The `settings` key of `device`'s `kind` session with `machine`'s T3.
pub fn session_key(device: &str, machine: &str, kind: &str) -> String {
    format!("{SESSION_PREFIX}{device}.{machine}.{kind}")
}

/// `(device, machine, kind)` of a session key. Device and machine names are
/// DNS labels, so they hold no dots.
pub fn parse_session_key(key: &str) -> Option<(&str, &str, &str)> {
    let mut parts = key.strip_prefix(SESSION_PREFIX)?.split('.');
    let (device, machine, kind) = (parts.next()?, parts.next()?, parts.next()?);
    (parts.next().is_none() && KINDS.contains(&kind) && !device.is_empty() && !machine.is_empty())
        .then_some((device, machine, kind))
}

/// The leader's config as the devices run requests with it: the machines,
/// the cluster's workspace, the instructions and the devices' public keys,
/// without the workers' T3 URLs, credentials and server-only settings.
pub fn device_config(cfg: &Config) -> String {
    let mut c = cfg.clone();
    c.t3_url.clear();
    for m in c.machines.values_mut() {
        m.t3_url.clear();
        m.t3_token.clear();
    }
    c.alertmanager_url.clear();
    c.metrics_url.clear();
    c.metrics_user.clear();
    c.text_generation = Default::default();
    c.android_publishers.clear();
    serde_json::to_string(&c).unwrap_or_default()
}

/// `device`'s unexpired sessions, by machine and kind.
pub async fn device_sessions(
    client: &PrismClient,
    device: &str,
    now: i64,
) -> Result<BTreeMap<(String, String), DeviceSession>, PrismError> {
    let mut out = BTreeMap::new();
    for row in client.list_settings().await? {
        let Some((d, machine, kind)) = parse_session_key(&row.key) else {
            continue;
        };
        if d != device {
            continue;
        }
        let Ok(session) = serde_json::from_str::<DeviceSession>(&row.value) else {
            continue;
        };
        if session.expires_at > now && !session.token.is_empty() {
            out.insert((machine.to_string(), kind.to_string()), session);
        }
    }
    Ok(out)
}

/// Runs this device's own requests in T3 with its own sessions.
pub struct Direct {
    client: Arc<PrismClient>,
    claimer: String,
    cfg: Arc<Config>,
    hub: T3Hub,
    pusher: Pusher,
    secrets: Secrets,
    approvals: Vec<T3ClientSessionRow>,
    clock: Clock,
}

fn storage(err: PrismError) -> String {
    format!("The replica failed: {err}")
}

impl Direct {
    /// The device's route from its replica: the published config and its
    /// sessions. Fails with the owner's text when it has neither yet.
    pub async fn open(
        client: Arc<PrismClient>,
        device: &str,
        clock: Clock,
    ) -> Result<Self, String> {
        let now = clock();
        let raw = client
            .get_setting(CONFIG_SETTING)
            .await
            .map_err(storage)?
            .ok_or("Prism's T3 settings have not reached this device yet.")?;
        let mut cfg = Config::parse(&raw)?;
        let sessions = device_sessions(&client, device, now)
            .await
            .map_err(storage)?;
        if sessions.is_empty() {
            return Err(format!(
                "{device} has no T3 sessions yet. Each T3 host registers them within an hour."
            ));
        }
        // The device's own MCP sessions stand in for the workers' T3 URLs
        // and credentials: T3Hub reads a machine's token through `secrets`.
        let mut tokens = BTreeMap::new();
        cfg.t3_url.clear();
        for (name, m) in cfg.machines.iter_mut() {
            m.t3_url.clear();
            m.t3_token.clear();
            if let Some(s) = sessions.get(&(name.clone(), KIND_MCP.to_string())) {
                let secret = format!("PRISM_DEVICE_MCP:{name}");
                m.t3_url = s.origin.trim_end_matches('/').to_string();
                m.t3_token = secret.clone();
                tokens.insert(secret, s.token.clone());
            }
        }
        let approvals = sessions
            .iter()
            .filter(|((_, kind), _)| kind == KIND_CLIENT)
            .map(|((machine, _), s)| T3ClientSessionRow {
                machine: machine.clone(),
                origin: s.origin.trim_end_matches('/').to_string(),
                token: s.token.clone(),
                environment_id: s.environment_id.clone(),
                expires_at: s.expires_at,
            })
            .collect();
        let tokens = Arc::new(tokens);
        // No push token: a device records its pushes unposted. It acted
        // itself, and the others see the result once they sync.
        let secrets: Secrets = Arc::new(move |name: &str| tokens.get(name).cloned());
        let cfg = Arc::new(cfg);
        Ok(Self {
            hub: T3Hub::new(Arc::clone(&cfg), Arc::clone(&secrets)),
            pusher: Pusher::new(&cfg.push.url, &cfg.push.topic, Arc::clone(&secrets)),
            claimer: format!("{DEVICE_CLAIM_PREFIX}{device}"),
            client,
            cfg,
            secrets,
            approvals,
            clock,
        })
    }

    /// The machines this device can launch on directly.
    pub fn machines(&self) -> Vec<String> {
        self.cfg
            .machines
            .iter()
            .filter(|(_, m)| !m.t3_url.is_empty())
            .map(|(n, _)| n.clone())
            .collect()
    }

    fn stale_before(&self, now: i64) -> i64 {
        now - CLAIM_TIMEOUT_SECS
    }

    /// Runs an alert action (fix, dismiss, resolve). False when it is not
    /// this device's to run: done, or held by a worker.
    pub async fn alert_action(&self, request_id: &str) -> Result<bool, String> {
        let now = (self.clock)();
        let Some(req) = self
            .client
            .alert_action(request_id)
            .await
            .map_err(storage)?
        else {
            return Ok(false);
        };
        if req.done_at.is_some()
            || !self
                .client
                .claim_alert_action(request_id, &self.claimer, now, self.stale_before(now))
                .await
                .map_err(storage)?
        {
            return Ok(false);
        }
        let outcome =
            run_alert_action(&self.client, &self.hub, &self.pusher, &self.clock, &req).await;
        self.client
            .finish_alert_action(request_id, &self.claimer, (self.clock)(), outcome)
            .await
            .map_err(storage)?;
        Ok(true)
    }

    /// Launches an agent request.
    pub async fn agent_request(&self, request_id: &str) -> Result<bool, String> {
        let now = (self.clock)();
        let Some(req) = self
            .client
            .claimable_agent_requests(i64::MAX)
            .await
            .map_err(storage)?
            .into_iter()
            .find(|r| r.request_id == request_id)
        else {
            return Ok(false);
        };
        // Picking the project asks the workers' text generation, which the
        // published config leaves out. Claimed here, the request would end
        // in that error, or, if the device never finished it, stay its own
        // and never run, since no worker takes a device's claim. So it waits
        // for the workers.
        let tg = &self.cfg.text_generation;
        if req.project_id == AUTO_CHOICE && (tg.url.is_empty() || tg.model.is_empty()) {
            return Err(
                "picking the project needs the workers, so the request waits for them.".into(),
            );
        }
        if !self
            .client
            .claim_agent_request(request_id, &self.claimer, now, self.stale_before(now))
            .await
            .map_err(storage)?
        {
            return Ok(false);
        }
        process_agent_request(
            &self.client,
            &self.cfg,
            &self.hub,
            &self.pusher,
            &self.secrets,
            &self.claimer,
            now,
            &req,
        )
        .await?;
        Ok(true)
    }

    /// Sends a follow-up, or stops an agent.
    pub async fn agent_message(&self, request_id: &str) -> Result<bool, String> {
        let now = (self.clock)();
        let Some(req) = self
            .client
            .claimable_agent_messages(i64::MAX)
            .await
            .map_err(storage)?
            .into_iter()
            .find(|r| r.request_id == request_id)
        else {
            return Ok(false);
        };
        if !self
            .client
            .claim_agent_message(request_id, &self.claimer, now, self.stale_before(now))
            .await
            .map_err(storage)?
        {
            return Ok(false);
        }
        process_agent_message(&self.client, &self.hub, &self.claimer, now, &req).await?;
        Ok(true)
    }

    /// Answers an agent's question.
    pub async fn question_answer(&self, request_id: &str) -> Result<bool, String> {
        let now = (self.clock)();
        let Some(req) = self
            .client
            .claimable_question_answers(i64::MAX)
            .await
            .map_err(storage)?
            .into_iter()
            .find(|r| r.request_id == request_id)
        else {
            return Ok(false);
        };
        if !self
            .client
            .claim_question_answer(request_id, &self.claimer, now, self.stale_before(now))
            .await
            .map_err(storage)?
        {
            return Ok(false);
        }
        let error = process_question_answer(&self.client, &self.hub, &self.pusher, &req, now)
            .await
            .err()
            .unwrap_or_default();
        self.client
            .finish_question_answer(request_id, &self.claimer, (self.clock)(), &error)
            .await
            .map_err(storage)?;
        Ok(true)
    }

    /// Answers an approval with this device's client session.
    pub async fn approval_answer(&self, request_id: &str) -> Result<bool, String> {
        let now = (self.clock)();
        let Some(req) = self
            .client
            .claimable_approval_answers(i64::MAX)
            .await
            .map_err(storage)?
            .into_iter()
            .find(|r| r.request_id == request_id)
        else {
            return Ok(false);
        };
        if !self
            .client
            .claim_approval_answer(request_id, &self.claimer, now, self.stale_before(now))
            .await
            .map_err(storage)?
        {
            return Ok(false);
        }
        let error = process_approval_answer(
            &self.client,
            &self.cfg,
            &self.pusher,
            &req,
            &self.approvals,
            now,
        )
        .await
        .err()
        .unwrap_or_default();
        self.client
            .finish_approval_answer(request_id, &self.claimer, (self.clock)(), &error)
            .await
            .map_err(storage)?;
        Ok(true)
    }
}

// --- Registering the devices' sessions (the T3 hosts) -------------------

/// What `PUT /v1/t3/device-session` takes from a T3 host.
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceSessionPayload {
    #[serde(default)]
    pub device: String,
    #[serde(default)]
    pub kind: String,
    #[serde(default)]
    pub origin: String,
    #[serde(default)]
    pub token: String,
    #[serde(default)]
    pub expires_at: i64,
}

/// When each device's sessions with `machine` expire (0: none), so the
/// host mints only what is missing or due (`GET /v1/t3/device-sessions`).
pub async fn device_session_expiries(
    client: &PrismClient,
    cfg: &Config,
    machine: &str,
    now: i64,
) -> Result<serde_json::Value, PrismError> {
    let settings: BTreeMap<String, String> = client
        .list_settings()
        .await?
        .into_iter()
        .map(|r| (r.key, r.value))
        .collect();
    let mut out = Vec::new();
    for device in cfg.devices.keys() {
        for kind in KINDS {
            let expires = settings
                .get(&session_key(device, machine, kind))
                .and_then(|v| serde_json::from_str::<DeviceSession>(v).ok())
                .map(|s| s.expires_at)
                .filter(|at| *at > now)
                .unwrap_or(0);
            out.push(serde_json::json!({"device": device, "kind": kind, "expiresAt": expires}));
        }
    }
    Ok(serde_json::json!({"sessions": out}))
}

/// Checks a device session a T3 host minted for itself and stores it. The
/// origin must be the host's NetBird name, which the devices reach; T3 must
/// answer as that machine and accept the token for what its kind needs.
pub async fn register_device_session(
    client: &PrismClient,
    cfg: &Config,
    machine: &str,
    p: DeviceSessionPayload,
    now: i64,
) -> Result<(), (axum::http::StatusCode, String)> {
    use axum::http::StatusCode;
    let bad = |m: &str| (StatusCode::BAD_REQUEST, m.to_string());
    if !cfg.devices.contains_key(&p.device) {
        return Err(bad("Prism has no such device."));
    }
    if !KINDS.contains(&p.kind.as_str()) {
        return Err(bad("The kind is client or mcp."));
    }
    if p.token.is_empty() || p.expires_at <= now {
        return Err(bad("An unexpired session is required."));
    }
    let configured = match cfg.machines.get(machine) {
        Some(m) if m.has_t3() => m.clone(),
        _ => return Err(bad("This machine has no T3 in Prism's config.")),
    };
    let origin = url::Url::parse(&p.origin).map_err(|_| bad("The origin is not a URL."))?;
    let host_ok = origin
        .host_str()
        .is_some_and(|h| h.eq_ignore_ascii_case(&format!("{machine}.{}", cfg.netbird_domain)));
    if !matches!(origin.scheme(), "http" | "https") || origin.path() != "/" || !host_ok {
        return Err(bad("The T3 origin must be this machine's NetBird name."));
    }
    let origin = origin.as_str().trim_end_matches('/').to_string();
    // The workers check the session where they reach this T3.
    let check = if configured.t3_url.is_empty() {
        origin.clone()
    } else {
        configured.t3_url.trim_end_matches('/').to_string()
    };
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct EnvDesc {
        #[serde(default)]
        environment_id: String,
        #[serde(default)]
        label: String,
    }
    let unavailable = || {
        (
            StatusCode::SERVICE_UNAVAILABLE,
            "The machine's T3 did not answer.".to_string(),
        )
    };
    let rpc = crate::approvals::T3Rpc::new(&check, &p.token);
    let desc: EnvDesc = rpc
        .get("/.well-known/t3/environment")
        .await
        .map_err(|_| unavailable())?;
    if desc.environment_id.is_empty()
        || !desc
            .label
            .eq_ignore_ascii_case(configured.link_label(machine))
    {
        return Err(bad("The T3 environment does not match this machine."));
    }
    if p.kind == KIND_CLIENT {
        #[derive(Deserialize)]
        struct AuthSession {
            #[serde(default)]
            authenticated: bool,
            #[serde(default)]
            scopes: Vec<String>,
            #[serde(default)]
            permissions: Option<Vec<String>>,
        }
        let auth: AuthSession = rpc
            .get("/api/auth/session")
            .await
            .map_err(|_| bad("T3 refused this client session."))?;
        let perms = auth.permissions.unwrap_or(auth.scopes);
        if !auth.authenticated
            || !perms.iter().any(|s| s == "orchestration:read")
            || !perms.iter().any(|s| s == "orchestration:operate")
        {
            return Err(bad(
                "The client session needs orchestration read and operate permissions.",
            ));
        }
    } else {
        let token = p.token.clone();
        let mcp =
            crate::t3::McpClient::new(format!("{check}/mcp"), Box::new(move || token.clone()));
        if let Err(err) = mcp
            .call("t3_environment_links", serde_json::json!({}))
            .await
        {
            return Err(match err {
                crate::t3::T3Error::Status(401 | 403) | crate::t3::T3Error::Tool(_) => {
                    bad("T3 refused this MCP session.")
                }
                _ => unavailable(),
            });
        }
    }
    let session = DeviceSession {
        origin,
        token: p.token,
        environment_id: desc.environment_id,
        expires_at: p.expires_at,
    };
    let value = serde_json::to_string(&session).unwrap_or_default();
    client
        .set_setting(&session_key(&p.device, machine, &p.kind), &value, now)
        .await
        .map_err(|e| (StatusCode::SERVICE_UNAVAILABLE, e.to_string()))
}
