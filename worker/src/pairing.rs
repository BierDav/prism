//! Pairing the worker with a machine's T3 (`POST /v1/t3/pair`, what `prism
//! pair` calls), ported from the Go server's `pairing.go`.
//!
//! The worker pairs the way any MCP client does: it registers as an OAuth
//! client, approves itself with the owner's pairing code and keeps the access
//! token as its session with that machine (a replicated `t3_sessions` row).

use crate::auth::is_valid_machine_name;
use crate::config::{CLUSTER_TARGET, Config};
use data_encoding::BASE64URL_NOPAD;
use rocky_prism::{PrismClient, T3SessionRow};
use serde::Deserialize;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::time::Duration;
use url::{Host, Url};

pub const PAIRING_HELP: &str = "**Pair Prism with a machine's T3**\n\n\
Create a fresh pairing link on the machine in T3's access settings, then pass it on stdin:\n\n\
`prism pair <machine> [access] < link.txt`\n\n\
Prism then calls that machine's T3 itself, the same way it calls the cluster machine's, and starts \
agents for it there. It uses the machine's configured `t3_url`; without one it replaces a LAN address \
in the link with the machine's NetBird name. Instead of the link, `[url] <code>` works too.\n\n\
Access defaults to `full-access`; alternatives are `read-only`, `approval-required`, \
`auto-accept-edits` and `auto`. The code must grant the requested access. \
The T3's label must match the machine name (or its configured environment label). \
Prism never echoes the code. Pair again with a fresh code to renew the session.\n\n\
A machine in Prism's config with a T3 of its own pairs itself: its hourly `prism register-t3-mcp` \
(fleet's prism-t3-client-session) registers a fresh session when the workers have none or theirs \
expires within 15 days, keeping the access paired here.\n\n\
Machines without T3 need no pairing: agents for them run on an SSH runner and use SSH. \
A machine that another paired machine links in T3 needs none either.";

const PAIR_USAGE: &str = "Usage: `prism pair <machine> [access] < link`, with the pairing link or `[url] <code>` on stdin.";

const ACCESS_LEVELS: [&str; 5] = [
    "read-only",
    "approval-required",
    "auto-accept-edits",
    "auto",
    "full-access",
];

/// Where T3 would send a browser; the worker reads the code from the
/// redirect T3 returns instead, so nothing listens there.
const PAIR_REDIRECT: &str = "http://127.0.0.1/prism/paired";

/// One pairing with a machine's T3.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct PairRequest {
    pub origin: String,
    pub code: String,
    pub access: String,
    pub expected_label: String,
}

/// The session a pairing created.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct PairedSession {
    pub token: String,
    pub environment_id: String,
    pub label: String,
    pub access: String,
    pub expires_in: i64,
}

/// Why a pairing failed. `Refused` is T3's message, meant for the owner.
#[derive(Clone, Debug, PartialEq)]
pub enum PairFailure {
    Refused(String),
    Other(String),
}

/// What `prism pair` prints: the answer, and whether the pairing worked.
#[derive(Clone, Debug, PartialEq)]
pub struct PairOutcome {
    pub paired: bool,
    pub answer: String,
}

fn user<T>(message: impl Into<String>) -> Result<T, String> {
    Err(message.into())
}

/// Go's `validOrigin`: an http(s) URL with a host and nothing but `/` after it.
fn valid_origin(raw: &str) -> bool {
    Url::parse(raw).is_ok_and(|u| {
        (u.scheme() == "http" || u.scheme() == "https")
            && u.host_str().is_some_and(|h| !h.is_empty())
            && u.username().is_empty()
            && u.password().is_none()
            && u.path() == "/"
            && u.query().is_none()
            && u.fragment().is_none()
    })
}

fn origin_of(u: &Url) -> String {
    let host = u.host_str().unwrap_or("");
    match u.port() {
        Some(port) => format!("{}://{host}:{port}", u.scheme()),
        None => format!("{}://{host}", u.scheme()),
    }
}

/// Reads a T3 pairing link (`<origin>/pair#token=<code>`) into its URL and code.
fn parse_pairing_link(field: &str) -> Option<(Url, String)> {
    let u = Url::parse(field).ok()?;
    if (u.scheme() != "http" && u.scheme() != "https")
        || u.host_str().is_none_or(str::is_empty)
        || u.path().trim_end_matches('/') != "/pair"
    {
        return None;
    }
    let token = |raw: Option<&str>| {
        url::form_urlencoded::parse(raw.unwrap_or("").as_bytes())
            .find(|(k, _)| k == "token")
            .map(|(_, v)| v.into_owned())
            .filter(|v| !v.is_empty())
    };
    let code = token(u.fragment()).or_else(|| token(u.query()))?;
    Some((u, code))
}

/// Whether a link host only makes sense on the machine's own network: an IP
/// address, localhost, a .local name or a single label.
fn local_host(u: &Url) -> bool {
    match u.host() {
        Some(Host::Ipv4(_) | Host::Ipv6(_)) => true,
        Some(Host::Domain(d)) => {
            let d = d.to_ascii_lowercase();
            d == "localhost" || d.ends_with(".local") || !d.contains('.')
        }
        None => false,
    }
}

/// Parses `@machine <link or [url] code> [access]` into the machine and the
/// request (Go's `pairingInput`). The error is for the owner.
pub fn pairing_input(cfg: &Config, argument: &str) -> Result<(String, PairRequest), String> {
    let fields: Vec<&str> = argument.split_whitespace().collect();
    if fields.len() < 2 || !fields[0].starts_with('@') {
        return user(PAIRING_HELP);
    }
    let name = fields[0].trim_start_matches('@').to_lowercase();
    let name = name.as_str();
    if !is_valid_machine_name(name) || name == CLUSTER_TARGET {
        return user("Pair a machine with `pair @machine <pairing link> [access]`.");
    }
    let (machine, m, configured) = match cfg.machine_by_name(name) {
        Some((key, m)) => (key, m, true),
        None => (name.to_string(), Default::default(), false),
    };
    if configured && !m.has_t3() {
        return user(format!(
            "{machine} has no T3, so there is nothing to pair: agents for it run on an SSH runner and use SSH."
        ));
    }
    if machine.is_empty() || machine == cfg.cluster_machine {
        return user(format!(
            "{machine} is the cluster machine; Prism uses its configured T3 credential."
        ));
    }
    if !m.t3_token.is_empty() {
        return user(format!(
            "Prism uses the configured T3 credential ({}) for {machine}; it needs no pairing.",
            m.t3_token
        ));
    }
    let mut req = PairRequest {
        expected_label: m.link_label(&machine).to_string(),
        access: "full-access".into(),
        ..PairRequest::default()
    };
    let mut rest = &fields[1..];
    let mut origin: Option<String> = None;
    if let Some((mut link, code)) = parse_pairing_link(rest[0]) {
        if local_host(&link) {
            // The link names the machine on its own LAN; Prism reaches it over NetBird.
            let host = format!("{machine}.{}", cfg.netbird_domain);
            if link.set_host(Some(&host)).is_err() {
                return user(PAIR_USAGE);
            }
        }
        origin = Some(origin_of(&link));
        req.code = code;
        rest = &rest[1..];
        if rest.len() > 1 {
            return user(PAIR_USAGE);
        }
    } else {
        if rest[0].contains("://") {
            if !valid_origin(rest[0]) {
                return user(format!(
                    "Supply the T3's HTTP(S) origin or its pairing link: {PAIR_USAGE}"
                ));
            }
            origin = Url::parse(rest[0]).ok().map(|u| origin_of(&u));
            rest = &rest[1..];
        }
        if rest.is_empty() || rest.len() > 2 {
            return user(PAIR_USAGE);
        }
        req.code = rest[0].to_string();
        rest = &rest[1..];
    }
    if req.code.len() > 8192 {
        return user("The pairing code is too long. Create a fresh code in T3.");
    }
    if let [access] = rest {
        req.access = (*access).to_string();
    }
    if !ACCESS_LEVELS.contains(&req.access.as_str()) {
        return user(format!(
            "Access must be one of: {}.",
            ACCESS_LEVELS.join(", ")
        ));
    }
    if !m.t3_url.is_empty() {
        // The configured address is the one Prism reaches the machine at.
        origin = Url::parse(&m.t3_url).ok().map(|u| origin_of(&u));
    }
    match origin {
        Some(o) => req.origin = o,
        None => {
            return user(format!(
                "Supply the T3's HTTP(S) origin or its pairing link: {PAIR_USAGE}"
            ));
        }
    }
    Ok((machine, req))
}

fn random_string(n: usize) -> String {
    let mut raw = vec![0u8; n];
    getrandom::getrandom(&mut raw).expect("system randomness");
    BASE64URL_NOPAD.encode(&raw)
}

enum Body<'a> {
    None,
    Json(&'a Value),
    Form(String),
}

/// Sends one request of the OAuth flow and decodes T3's JSON answer. T3's
/// refusals carry a message for people (error_description or message).
async fn send(
    http: &reqwest::Client,
    method: reqwest::Method,
    target: &str,
    body: Body<'_>,
) -> Result<Value, PairFailure> {
    let mut req = http
        .request(method, target)
        .header("Accept", "application/json");
    req = match body {
        Body::None => req,
        Body::Json(v) => req
            .header("Content-Type", "application/json")
            .body(v.to_string()),
        Body::Form(f) => req
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body(f),
    };
    let resp = req
        .send()
        .await
        .map_err(|_| PairFailure::Other("T3 is unreachable".into()))?;
    let status = resp.status().as_u16();
    let data = resp.bytes().await.unwrap_or_default();
    let data = &data[..data.len().min(1 << 20)];
    if status >= 300 {
        #[derive(Default, Deserialize)]
        struct Refusal {
            #[serde(default)]
            error_description: String,
            #[serde(default)]
            message: String,
            #[serde(default)]
            error: String,
        }
        let r: Refusal = serde_json::from_slice(data).unwrap_or_default();
        let message = [r.error_description, r.message, r.error]
            .into_iter()
            .find(|m| !m.is_empty());
        return Err(match message {
            Some(m) => PairFailure::Refused(m),
            None => PairFailure::Other(format!("T3 answered {status}")),
        });
    }
    serde_json::from_slice(data).map_err(|err| PairFailure::Other(err.to_string()))
}

fn text(v: &Value, key: &str) -> String {
    v.get(key)
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string()
}

/// Runs T3's MCP OAuth flow with a pairing code (Go's `OAuthPairer.Pair`).
pub async fn oauth_pair(req: &PairRequest) -> Result<PairedSession, PairFailure> {
    let http = reqwest::Client::builder()
        .timeout(Duration::from_secs(30))
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(|err| PairFailure::Other(err.to_string()))?;
    let origin = &req.origin;
    // Check the machine before T3 consumes the single-use code.
    let env = match send(
        &http,
        reqwest::Method::GET,
        &format!("{origin}/.well-known/t3/environment"),
        Body::None,
    )
    .await
    {
        Ok(v) => v,
        Err(PairFailure::Refused(m)) => return Err(PairFailure::Refused(m)),
        Err(PairFailure::Other(_)) => {
            return Err(PairFailure::Refused(format!(
                "No T3 answers at {origin}. The code was not used."
            )));
        }
    };
    let label = text(&env, "label");
    if !req.expected_label.is_empty() && !label.eq_ignore_ascii_case(&req.expected_label) {
        return Err(PairFailure::Refused(format!(
            "The T3 at {origin} is {}, not {}. The code was not used.",
            Value::String(label.clone()),
            req.expected_label
        )));
    }
    let registration = json!({
        "client_name": "Prism", "software_id": "prism", "redirect_uris": [PAIR_REDIRECT],
        "token_endpoint_auth_method": "none",
    });
    let client = send(
        &http,
        reqwest::Method::POST,
        &format!("{origin}/oauth/mcp/register"),
        Body::Json(&registration),
    )
    .await?;
    let client_id = text(&client, "client_id");
    if client_id.is_empty() {
        return Err(PairFailure::Other("T3 registered no client".into()));
    }
    let (verifier, state) = (random_string(32), random_string(16));
    let challenge = BASE64URL_NOPAD.encode(&Sha256::digest(verifier.as_bytes()));
    let resource = format!("{origin}/mcp");
    let decision = json!({
        "authorization": {
            "response_type": "code", "client_id": client_id, "redirect_uri": PAIR_REDIRECT,
            "code_challenge": challenge, "code_challenge_method": "S256",
            "state": state, "resource": resource,
        },
        "decision": {"_tag": "pairing-code", "access": req.access, "code": req.code},
    });
    let decided = send(
        &http,
        reqwest::Method::POST,
        &format!("{origin}/oauth/mcp/decision"),
        Body::Json(&decision),
    )
    .await?;
    let redirect = Url::parse("http://127.0.0.1/")
        .and_then(|base| base.join(&text(&decided, "redirectTo")))
        .map_err(|_| PairFailure::Other("T3 returned an unreadable redirect".into()))?;
    let query = |key: &str| {
        redirect
            .query_pairs()
            .find(|(k, _)| k == key)
            .map(|(_, v)| v.into_owned())
            .unwrap_or_default()
    };
    let (description, error) = (query("error_description"), query("error"));
    if !description.is_empty() || !error.is_empty() {
        return Err(PairFailure::Refused(if description.is_empty() {
            error
        } else {
            description
        }));
    }
    let code = query("code");
    if query("state") != state || code.is_empty() {
        return Err(PairFailure::Other(
            "T3 returned no authorization code".into(),
        ));
    }
    let form = url::form_urlencoded::Serializer::new(String::new())
        .append_pair("client_id", &client_id)
        .append_pair("code", &code)
        .append_pair("code_verifier", &verifier)
        .append_pair("grant_type", "authorization_code")
        .append_pair("redirect_uri", PAIR_REDIRECT)
        .append_pair("resource", &resource)
        .finish();
    let token = send(
        &http,
        reqwest::Method::POST,
        &format!("{origin}/oauth/mcp/token"),
        Body::Form(form),
    )
    .await?;
    let access_token = text(&token, "access_token");
    if access_token.is_empty() {
        return Err(PairFailure::Other("T3 issued no access token".into()));
    }
    Ok(PairedSession {
        token: access_token,
        environment_id: text(&env, "environmentId"),
        label,
        access: req.access.clone(),
        expires_in: token.get("expires_in").and_then(Value::as_i64).unwrap_or(0),
    })
}

/// Shortens T3's diagnostic text for an answer: its first line, at most 300 bytes.
fn brief(s: &str) -> String {
    let s = s.trim();
    let s = s.split('\n').next().unwrap_or("");
    if s.len() <= 300 {
        return s.to_string();
    }
    let mut end = 300;
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    format!("{}…", &s[..end])
}

/// Pairs from `@machine <link or [url] code> [access]` (Go's `PairArgument`)
/// and stores the session. The answer never contains the code. `Err` is a
/// storage failure; refusals are an unpaired outcome.
pub async fn pair_argument(
    client: &PrismClient,
    cfg: &Config,
    argument: &str,
    now: i64,
) -> Result<PairOutcome, String> {
    let (machine, req) = match pairing_input(cfg, argument) {
        Ok(v) => v,
        Err(answer) => {
            return Ok(PairOutcome {
                paired: false,
                answer,
            });
        }
    };
    let session = match oauth_pair(&req).await {
        Ok(s) => s,
        Err(failure) => {
            // A pairing code is single-use. Never retry the exchange automatically.
            let mut answer =
                format!("Could not pair {machine}. Create a fresh code and try again.");
            if let PairFailure::Refused(message) = failure {
                // T3 errors can quote submitted fields. Remove the code.
                answer += "\n\nT3: ";
                answer += &brief(&message.replace(&req.code, "[code removed]"));
            }
            eprintln!("prism-worker: T3 pairing failed for {machine}");
            return Ok(PairOutcome {
                paired: false,
                answer,
            });
        }
    };
    let expires_at = if session.expires_in > 0 {
        now + session.expires_in
    } else {
        0
    };
    client
        .upsert_t3_session(&T3SessionRow {
            machine: machine.clone(),
            origin: req.origin.clone(),
            token: session.token,
            environment_id: session.environment_id,
            label: session.label,
            access: session.access.clone(),
            paired_at: now,
            expires_at,
        })
        .await
        .map_err(|err| err.to_string())?;
    println!(
        "prism-worker: paired with {machine} ({} access)",
        session.access
    );
    let mut answer = format!(
        "Paired with {machine} ({} access): Prism now calls its T3 directly and starts agents for it there.",
        session.access
    );
    if expires_at > 0
        && let Some(date) = chrono::DateTime::<chrono::Utc>::from_timestamp(expires_at, 0)
    {
        answer += &format!(" The session expires on {}.", date.format("%Y-%m-%d"));
    }
    answer += " Run `prism machines` to check it.";
    Ok(PairOutcome {
        paired: true,
        answer,
    })
}

// --- The workers' session, registered by the machine itself -------------

/// Why the workers take no MCP session from `machine`'s own registration
/// (`prism register-t3-mcp`), or `None` when they do: every machine in the
/// config with a T3 of its own, except the cluster machine and machines with
/// a configured credential (Prism uses those) and machines another
/// machine's T3 links (`via`; Prism reaches them through it).
pub fn host_session_refusal(cfg: &Config, machine: &str) -> Option<String> {
    let Some(m) = cfg.machines.get(machine).filter(|m| m.has_t3()) else {
        return Some(format!("{machine} has no T3 in Prism's config."));
    };
    if machine == cfg.cluster_machine {
        return Some(format!(
            "{machine} is the cluster machine; Prism uses its configured T3 credential."
        ));
    }
    if !m.t3_token.is_empty() {
        return Some(format!(
            "Prism uses the configured T3 credential ({}) for {machine}.",
            m.t3_token
        ));
    }
    if !m.via.is_empty() {
        return Some(format!(
            "Prism reaches {machine} through {}'s T3, which links it.",
            m.via
        ));
    }
    None
}

/// `GET /v1/t3/mcp-session`: whether the workers want an MCP session from
/// `machine`, with which access, and when theirs expires, so the machine
/// mints one only when it is missing or due. An unexpired session that never
/// expires (`expiresAt` 0) is `paired` too.
pub async fn host_session_state(
    client: &PrismClient,
    cfg: &Config,
    machine: &str,
    now: i64,
) -> Result<Value, rocky_prism::PrismError> {
    if let Some(reason) = host_session_refusal(cfg, machine) {
        return Ok(json!({"machine": machine, "wanted": false, "reason": reason}));
    }
    let current = client
        .list_t3_sessions()
        .await?
        .into_iter()
        .find(|s| s.machine == machine && !s.token.is_empty());
    let paired = current
        .as_ref()
        .is_some_and(|s| s.expires_at == 0 || s.expires_at > now);
    // A renewal keeps the access the owner paired with.
    let access = current
        .as_ref()
        .map(|s| s.access.as_str())
        .filter(|a| ACCESS_LEVELS.contains(a))
        .unwrap_or("full-access");
    Ok(json!({
        "machine": machine,
        "wanted": true,
        "access": access,
        "paired": paired,
        "expiresAt": current.filter(|_| paired).map_or(0, |s| s.expires_at),
    }))
}

/// What `PUT /v1/t3/mcp-session` takes from a T3 host.
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostSessionPayload {
    /// The host's NetBird origin, e.g. `http://david-mb.netbird.cloud:3773`.
    #[serde(default)]
    pub origin: String,
    #[serde(default)]
    pub token: String,
    #[serde(default)]
    pub access: String,
    #[serde(default)]
    pub expires_at: i64,
}

/// Checks the MCP session a machine minted with its own T3 for the workers
/// and stores it as the machine's `t3_sessions` row, as `prism pair` does.
pub async fn register_host_session(
    client: &PrismClient,
    cfg: &Config,
    machine: &str,
    p: HostSessionPayload,
    now: i64,
) -> Result<(), (axum::http::StatusCode, String)> {
    use axum::http::StatusCode;
    if let Some(reason) = host_session_refusal(cfg, machine) {
        return Err((StatusCode::BAD_REQUEST, reason));
    }
    let access = if p.access.is_empty() {
        "full-access".to_string()
    } else {
        p.access
    };
    if !ACCESS_LEVELS.contains(&access.as_str()) {
        return Err((
            StatusCode::BAD_REQUEST,
            format!("Access must be one of: {}.", ACCESS_LEVELS.join(", ")),
        ));
    }
    if p.token.is_empty() || p.expires_at <= now {
        return Err((
            StatusCode::BAD_REQUEST,
            "An unexpired session is required.".into(),
        ));
    }
    let verified = crate::direct::verify_host_session(
        cfg,
        machine,
        &p.origin,
        &p.token,
        crate::direct::KIND_MCP,
    )
    .await?;
    client
        .upsert_t3_session(&T3SessionRow {
            machine: machine.to_string(),
            origin: verified.reach,
            token: p.token,
            environment_id: verified.environment_id,
            label: verified.label,
            access: access.clone(),
            paired_at: now,
            expires_at: p.expires_at,
        })
        .await
        .map_err(|e| (StatusCode::SERVICE_UNAVAILABLE, e.to_string()))?;
    println!("prism-worker: {machine} registered the workers' T3 session ({access} access)");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::MachineConfig;
    use axum::{Json, Router, extract::State, http::StatusCode, response::IntoResponse};
    use std::sync::{Arc, Mutex};

    fn config() -> Config {
        let mut c = Config::parse(
            r#"{"machines": {"david-server": {"hub": true, "t3_url": "http://10.0.0.1:3773"}}}"#,
        )
        .expect("config");
        c.machines.insert(
            "david-mb".into(),
            MachineConfig {
                aliases: vec!["mb".into()],
                t3_url: "http://100.64.0.2:3773".into(),
                ..MachineConfig::default()
            },
        );
        c.machines.insert(
            "builder".into(),
            MachineConfig {
                t3_url: "http://100.64.0.3:3773".into(),
                t3_token: "BUILDER_T3_TOKEN".into(),
                ..MachineConfig::default()
            },
        );
        c.machines.insert(
            "ccs-oracle-1".into(),
            MachineConfig {
                t3: Some(false),
                ..MachineConfig::default()
            },
        );
        c
    }

    /// Go's `TestPairing`, without the exchange: what each argument pairs.
    #[test]
    fn pairing_input_reads_what_go_reads() {
        let cfg = config();
        for (argument, want, origin, access) in [
            ("", "Pair Prism with a machine's T3", "", ""),
            (
                "@mb one-use-code",
                "",
                "http://100.64.0.2:3773",
                "full-access",
            ),
            (
                "@new-machine https://t3.example.test one-use-code read-only",
                "",
                "https://t3.example.test",
                "read-only",
            ),
            (
                "@david-mb http://192.168.0.36:3773/pair#token=one-use-code",
                "",
                "http://100.64.0.2:3773",
                "full-access",
            ),
            (
                "@mb http://192.168.0.36:3773/pair#token=one-use-code auto",
                "",
                "http://100.64.0.2:3773",
                "auto",
            ),
            (
                "@new-machine https://t3.example.test/pair#token=one-use-code",
                "",
                "https://t3.example.test",
                "full-access",
            ),
            (
                "@new-machine http://192.168.0.36:3773/pair#token=one-use-code",
                "",
                "http://new-machine.netbird.cloud:3773",
                "full-access",
            ),
            (
                "@new-machine http://10.0.0.5:3773 one-use-code",
                "",
                "http://10.0.0.5:3773",
                "full-access",
            ),
            (
                "@mb http://192.168.0.36:3773/pair#token=one-use-code auto extra",
                "Usage",
                "",
                "",
            ),
            ("@unknown one-use-code", "HTTP(S) origin", "", ""),
            (
                "@david-server one-use-code",
                "is the cluster machine",
                "",
                "",
            ),
            ("@builder one-use-code", "needs no pairing", "", ""),
            ("@ccs-oracle-1 one-use-code", "has no T3", "", ""),
            (
                "@cluster https://t3.example.test one-use-code",
                "Pair a machine",
                "",
                "",
            ),
            ("@mb https://t3.example.test", "Usage", "", ""),
            ("@mb one-use-code admin", "Access must be", "", ""),
            (
                "@new https://user:password@t3.example.test one-use-code",
                "HTTP(S) origin",
                "",
                "",
            ),
            (
                "@new https://t3.example.test/?token=secret one-use-code",
                "HTTP(S) origin",
                "",
                "",
            ),
        ] {
            match pairing_input(&cfg, argument) {
                Ok((_, req)) => {
                    assert!(want.is_empty(), "{argument}: paired, wanted {want}");
                    assert_eq!(
                        (req.origin.as_str(), req.access.as_str(), req.code.as_str()),
                        (origin, access, "one-use-code"),
                        "{argument}"
                    );
                }
                Err(answer) => {
                    assert!(
                        !want.is_empty() && answer.contains(want),
                        "{argument}: {answer}"
                    );
                    assert!(!answer.contains("one-use-code"), "{answer}");
                }
            }
        }
        let (machine, req) = pairing_input(&cfg, "@MB one-use-code").expect("alias");
        assert_eq!(
            (machine.as_str(), req.expected_label.as_str()),
            ("david-mb", "david-mb")
        );
        let long = format!("@mb {}", "x".repeat(8193));
        assert!(pairing_input(&cfg, &long).unwrap_err().contains("too long"));
    }

    #[test]
    fn brief_keeps_the_first_line_and_300_bytes() {
        assert_eq!(brief("  first\nsecond"), "first");
        let long = "é".repeat(200);
        let b = brief(&long);
        assert!(b.ends_with('…') && b.len() <= 300 + '…'.len_utf8(), "{b}");
    }

    #[derive(Clone, Default)]
    struct FakeOAuth {
        label: String,
        challenge: Arc<Mutex<String>>,
    }

    /// T3's pairing endpoints for one environment (Go's `fakeT3OAuth`).
    fn fake_oauth(label: &str) -> Router {
        use axum::routing::{get, post};
        let f = FakeOAuth {
            label: label.into(),
            ..FakeOAuth::default()
        };
        Router::new()
            .route(
                "/.well-known/t3/environment",
                get(|State(f): State<FakeOAuth>| async move {
                    Json(json!({"environmentId": "env-1", "label": f.label}))
                }),
            )
            .route(
                "/oauth/mcp/register",
                post(|Json(reg): Json<Value>| async move {
                    assert_eq!(reg["client_name"], "Prism");
                    assert_eq!(reg["redirect_uris"], json!([PAIR_REDIRECT]));
                    (StatusCode::CREATED, Json(json!({"client_id": "client-1"})))
                }),
            )
            .route(
                "/oauth/mcp/decision",
                post(
                    |State(f): State<FakeOAuth>,
                     headers: axum::http::HeaderMap,
                     Json(body): Json<Value>| async move {
                        let (a, d) = (&body["authorization"], &body["decision"]);
                        if d["code"] != "one-use-code" || d["_tag"] != "pairing-code" {
                            let message = format!(
                                "Invalid pairing code {}",
                                d["code"].as_str().unwrap_or("")
                            );
                            return (
                                StatusCode::BAD_REQUEST,
                                Json(json!({"_tag": "AuthMcpApprovalError", "message": message})),
                            )
                                .into_response();
                        }
                        let host = headers["host"].to_str().unwrap_or("");
                        assert_eq!(d["access"], "full-access");
                        assert_eq!(a["client_id"], "client-1");
                        assert_eq!(a["code_challenge_method"], "S256");
                        assert_eq!(a["resource"], format!("http://{host}/mcp"));
                        *f.challenge.lock().unwrap() =
                            a["code_challenge"].as_str().unwrap_or("").to_string();
                        let redirect = format!(
                            "{}?code=auth-code&state={}",
                            a["redirect_uri"].as_str().unwrap_or(""),
                            a["state"].as_str().unwrap_or("")
                        );
                        Json(json!({"redirectTo": redirect})).into_response()
                    },
                ),
            )
            .route(
                "/oauth/mcp/token",
                post(|State(f): State<FakeOAuth>, form: String| async move {
                    let form: BTreeMap<String, String> =
                        url::form_urlencoded::parse(form.as_bytes())
                            .into_owned()
                            .collect();
                    let verifier = form.get("code_verifier").cloned().unwrap_or_default();
                    let challenge = BASE64URL_NOPAD.encode(&Sha256::digest(verifier.as_bytes()));
                    if form.get("code").map(String::as_str) != Some("auth-code")
                        || form.get("client_id").map(String::as_str) != Some("client-1")
                        || *f.challenge.lock().unwrap() != challenge
                    {
                        return (
                            StatusCode::BAD_REQUEST,
                            Json(
                                json!({"error": "invalid_grant", "error_description": "bad grant"}),
                            ),
                        )
                            .into_response();
                    }
                    Json(
                        json!({"access_token": "session-token", "token_type": "Bearer",
                                "expires_in": 86400}),
                    )
                    .into_response()
                }),
            )
            .with_state(f)
    }

    use std::collections::BTreeMap;

    /// Go's `TestOAuthPairer`.
    #[tokio::test]
    async fn oauth_pairing_exchanges_the_code_for_a_session() {
        let origin = crate::testutil::serve(fake_oauth("david-mb")).await;
        let req = |code: &str, label: &str| PairRequest {
            origin: origin.clone(),
            code: code.into(),
            access: "full-access".into(),
            expected_label: label.into(),
        };
        let session = oauth_pair(&req("one-use-code", "david-mb"))
            .await
            .expect("paired");
        assert_eq!(
            session,
            PairedSession {
                token: "session-token".into(),
                environment_id: "env-1".into(),
                label: "david-mb".into(),
                access: "full-access".into(),
                expires_in: 86400,
            }
        );
        // A wrong machine is refused before the code is used.
        match oauth_pair(&req("one-use-code", "david-mm")).await {
            Err(PairFailure::Refused(m)) => assert!(m.contains("code was not used"), "{m}"),
            other => panic!("label mismatch = {other:?}"),
        }
        // T3's refusal reaches the owner as its message.
        assert_eq!(
            oauth_pair(&req("stale", "")).await,
            Err(PairFailure::Refused("Invalid pairing code stale".into()))
        );
        // Nothing answers.
        let gone = PairRequest {
            origin: "http://127.0.0.1:9".into(),
            ..req("one-use-code", "")
        };
        assert_eq!(
            oauth_pair(&gone).await,
            Err(PairFailure::Refused(
                "No T3 answers at http://127.0.0.1:9. The code was not used.".into()
            ))
        );
    }

    #[tokio::test]
    async fn pair_argument_stores_the_session_and_never_echoes_the_code() {
        let origin = crate::testutil::serve(fake_oauth("new-machine")).await;
        let dir = tempfile::tempdir().expect("tempdir");
        let client = PrismClient::open_or_create(dir.path().join("db").display().to_string(), None)
            .await
            .expect("client");
        let cfg = config();
        const NOW: i64 = 1_800_000_000;
        let outcome = pair_argument(
            &client,
            &cfg,
            &format!("@new-machine {origin} one-use-code"),
            NOW,
        )
        .await
        .expect("pair");
        assert_eq!(
            outcome,
            PairOutcome {
                paired: true,
                answer: "Paired with new-machine (full-access access): Prism now calls its T3 directly and starts agents for it there. The session expires on 2027-01-16. Run `prism machines` to check it.".into(),
            }
        );
        let sessions = client.list_t3_sessions().await.expect("sessions");
        assert_eq!(sessions.len(), 1);
        let s = &sessions[0];
        assert_eq!(
            (
                s.machine.as_str(),
                s.origin.as_str(),
                s.token.as_str(),
                s.access.as_str()
            ),
            (
                "new-machine",
                origin.as_str(),
                "session-token",
                "full-access"
            )
        );
        assert_eq!((s.paired_at, s.expires_at), (NOW, NOW + 86400));

        let refused = pair_argument(
            &client,
            &cfg,
            &format!("@new-machine {origin} stale-code"),
            NOW,
        )
        .await
        .expect("pair");
        assert_eq!(
            refused,
            PairOutcome {
                paired: false,
                answer: "Could not pair new-machine. Create a fresh code and try again.\n\nT3: Invalid pairing code [code removed]".into(),
            }
        );
    }

    #[tokio::test]
    async fn machines_register_the_workers_session_themselves() {
        let fake = crate::testutil::FakeT3::default().router().route(
            "/.well-known/t3/environment",
            axum::routing::get(|| async {
                Json(json!({"environmentId": "env-mb", "label": "david-mb"}))
            }),
        );
        let reach = crate::testutil::serve(fake).await;
        let cfg = Config::parse(&format!(
            r#"{{"cluster_machine": "david-server", "machines": {{
                "david-server": {{"t3_url": "http://10.0.0.1:3773", "t3_token": "T3_ACCESS_TOKEN"}},
                "david-mb": {{"t3_url": "{reach}"}},
                "david-mm-linz": {{"via": "david-mb"}},
                "builder": {{"t3_url": "http://10.0.0.3:3773", "t3_token": "BUILDER_T3_TOKEN"}},
                "ccs-oracle-1": {{"t3": false}}}}}}"#
        ))
        .expect("config");
        let dir = tempfile::tempdir().expect("tempdir");
        let client = PrismClient::open_or_create(dir.path().join("db").display().to_string(), None)
            .await
            .expect("client");
        const NOW: i64 = 1_800_000_000;
        const DAYS_30: i64 = 30 * 86_400;

        // Only machines Prism reaches with a session of their own want one.
        for (machine, reason) in [
            ("david-server", "is the cluster machine"),
            ("david-mm-linz", "through david-mb's T3"),
            ("builder", "configured T3 credential (BUILDER_T3_TOKEN)"),
            ("ccs-oracle-1", "has no T3"),
            ("david-tp", "has no T3"),
        ] {
            let state = host_session_state(&client, &cfg, machine, NOW)
                .await
                .expect("state");
            assert_eq!(state["wanted"], false, "{machine}");
            assert!(
                state["reason"].as_str().unwrap().contains(reason),
                "{machine}: {state}"
            );
            let refused = register_host_session(
                &client,
                &cfg,
                machine,
                HostSessionPayload {
                    origin: format!("http://{machine}.netbird.cloud:3773"),
                    token: "t3-token".into(),
                    expires_at: NOW + DAYS_30,
                    ..HostSessionPayload::default()
                },
                NOW,
            )
            .await
            .unwrap_err();
            assert!(refused.1.contains(reason), "{machine}: {refused:?}");
        }
        assert_eq!(
            host_session_state(&client, &cfg, "david-mb", NOW)
                .await
                .unwrap(),
            json!({"machine": "david-mb", "wanted": true, "access": "full-access",
                   "paired": false, "expiresAt": 0})
        );

        let payload = |origin: &str, token: &str, expires_at: i64| HostSessionPayload {
            origin: origin.into(),
            token: token.into(),
            access: String::new(),
            expires_at,
        };
        let netbird = "http://david-mb.netbird.cloud:3773";
        for (p, want) in [
            (
                payload("http://100.64.0.9:3773", "t3-token", NOW + DAYS_30),
                "NetBird name",
            ),
            (payload(netbird, "t3-token", NOW), "unexpired session"),
            (
                payload(netbird, "wrong-token", NOW + DAYS_30),
                "T3 refused this MCP session.",
            ),
            (
                HostSessionPayload {
                    access: "admin".into(),
                    ..payload(netbird, "t3-token", NOW + DAYS_30)
                },
                "Access must be one of",
            ),
        ] {
            let err = register_host_session(&client, &cfg, "david-mb", p, NOW)
                .await
                .unwrap_err();
            assert!(err.1.contains(want), "{err:?}");
        }
        assert!(client.list_t3_sessions().await.unwrap().is_empty());

        register_host_session(
            &client,
            &cfg,
            "david-mb",
            payload(netbird, "t3-token", NOW + DAYS_30),
            NOW,
        )
        .await
        .expect("registered");
        let sessions = client.list_t3_sessions().await.unwrap();
        assert_eq!(
            sessions,
            vec![T3SessionRow {
                machine: "david-mb".into(),
                // Where the workers reach it, as `prism pair` stores it.
                origin: reach.clone(),
                token: "t3-token".into(),
                environment_id: "env-mb".into(),
                label: "david-mb".into(),
                access: "full-access".into(),
                paired_at: NOW,
                expires_at: NOW + DAYS_30,
            }]
        );
        assert_eq!(
            host_session_state(&client, &cfg, "david-mb", NOW)
                .await
                .unwrap(),
            json!({"machine": "david-mb", "wanted": true, "access": "full-access",
                   "paired": true, "expiresAt": NOW + DAYS_30})
        );
        // Expired, it is missing again.
        let later = host_session_state(&client, &cfg, "david-mb", NOW + DAYS_30)
            .await
            .unwrap();
        assert_eq!(
            (later["paired"].clone(), later["expiresAt"].clone()),
            (json!(false), json!(0))
        );

        // A renewal keeps the access the owner paired with by hand.
        client
            .upsert_t3_session(&T3SessionRow {
                access: "read-only".into(),
                expires_at: 0,
                ..sessions[0].clone()
            })
            .await
            .unwrap();
        assert_eq!(
            host_session_state(&client, &cfg, "david-mb", NOW)
                .await
                .unwrap(),
            json!({"machine": "david-mb", "wanted": true, "access": "read-only",
                   "paired": true, "expiresAt": 0})
        );
    }
}
