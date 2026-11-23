//! `prism register-t3-devices <origin>`: a T3 host pairs every Prism device
//! with its own T3, so nobody pairs by hand.
//!
//! Each device (the phone and the Macs, `devices` in the workers' config)
//! gets two sessions with this host's T3, which it uses only when it can't
//! reach the workers (prism worker/src/direct.rs):
//!
//! - `client`: a T3 client session (orchestration read and operate) for
//!   answering approvals, from `t3 auth session issue`;
//! - `mcp`: a full-access MCP session, as the workers launch with, for
//!   launches, follow-ups, stops and answers. `t3 auth session issue` can't
//!   give an MCP session full access, so this host mints a pairing code with
//!   a five-minute access session and completes T3's MCP OAuth itself.
//!
//! Both last 30 days. The workers say when each device's sessions with this
//! machine expire (`GET /v1/t3/device-sessions`), and the host mints only the
//! missing ones and those with under 15 days left, so a device always holds
//! one that outlives a two-week outage. Tokens go to the workers over stdin
//! and HTTP only, never in arguments.

use crate::client::{Client, text};
use serde::Deserialize;
use serde_json::{Value, json};
use std::io::Read;
use std::process::Command;
use std::time::Duration;

const TTL: &str = "30d";
const RENEW_BEFORE_SECS: i64 = 15 * 24 * 60 * 60;
const CLIENT_SCOPES: [&str; 2] = ["orchestration:read", "orchestration:operate"];
/// What minting a full-access MCP pairing code needs: access:write, and
/// every scope the full-access MCP session gets.
const PAIRING_SCOPES: [&str; 5] = [
    "access:write",
    "orchestration:read",
    "orchestration:operate",
    "settings:write",
    "source-control:write",
];
const REDIRECT: &str = "http://127.0.0.1/prism/paired";

/// A session `t3 auth session issue --json` printed.
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Issued {
    #[serde(default)]
    pub session_id: String,
    #[serde(default)]
    pub token: String,
    #[serde(default)]
    pub expires_at: String,
}

/// This host's T3: `t3 auth session issue` and `revoke`.
pub trait T3Sessions {
    fn issue(&self, scopes: &[&str], ttl: &str, label: &str) -> Result<Issued, String>;
    fn revoke(&self, session_id: &str);
}

/// The `t3` command (PRISM_T3, default `t3` on PATH).
pub struct T3Command(pub String);

impl T3Sessions for T3Command {
    fn issue(&self, scopes: &[&str], ttl: &str, label: &str) -> Result<Issued, String> {
        let mut cmd = Command::new(&self.0);
        cmd.args(["auth", "session", "issue"]);
        for s in scopes {
            cmd.args(["--scope", s]);
        }
        cmd.args([
            "--ttl",
            ttl,
            "--label",
            label,
            "--json",
            "--log-level",
            "none",
        ]);
        let out = cmd
            .output()
            .map_err(|err| format!("cannot run {}: {err}", self.0))?;
        if !out.status.success() {
            return Err(format!(
                "t3 auth session issue failed: {}",
                String::from_utf8_lossy(&out.stderr).trim()
            ));
        }
        let issued: Issued = serde_json::from_slice(&out.stdout)
            .map_err(|_| "t3 auth session issue printed no session JSON".to_string())?;
        if issued.token.is_empty() {
            return Err("t3 auth session issue printed no token".into());
        }
        Ok(issued)
    }

    fn revoke(&self, session_id: &str) {
        let _ = Command::new(&self.0)
            .args([
                "auth",
                "session",
                "revoke",
                "--log-level",
                "none",
                session_id,
            ])
            .output();
    }
}

fn http() -> Result<reqwest::blocking::Client, String> {
    reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(30))
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(|err| err.to_string())
}

fn answer(resp: reqwest::blocking::Response, what: &str) -> Result<Value, String> {
    let status = resp.status().as_u16();
    let mut data = Vec::new();
    let _ = resp.take(256 << 10).read_to_end(&mut data);
    if status >= 300 {
        return Err(format!(
            "T3 refused {what} ({status}): {}",
            String::from_utf8_lossy(&data).trim()
        ));
    }
    serde_json::from_slice(&data).map_err(|_| format!("T3 answered {what} without JSON"))
}

fn random_text(bytes: usize) -> Result<String, String> {
    let mut buf = vec![0u8; bytes];
    std::fs::File::open("/dev/urandom")
        .and_then(|mut f| f.read_exact(&mut buf))
        .map_err(|err| format!("no randomness: {err}"))?;
    Ok(base64url(&buf))
}

fn base64url(data: &[u8]) -> String {
    const A: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    let mut out = String::new();
    for chunk in data.chunks(3) {
        let n = chunk.iter().fold(0u32, |n, b| (n << 8) | u32::from(*b)) << (8 * (3 - chunk.len()));
        for i in 0..=chunk.len() {
            out.push(A[((n >> (18 - 6 * i)) & 63) as usize] as char);
        }
    }
    out
}

/// A full-access MCP session for `device` with the T3 at `origin`, through
/// T3's MCP OAuth with a pairing code this host mints itself. Returns the
/// token and when it expires (Unix seconds).
pub fn mcp_session(
    t3: &dyn T3Sessions,
    origin: &str,
    device: &str,
    now: i64,
) -> Result<(String, i64), String> {
    let admin = t3.issue(&PAIRING_SCOPES, "5m", "Prism device pairing")?;
    let result = mcp_oauth(&admin.token, origin, device, now);
    t3.revoke(&admin.session_id);
    result
}

fn mcp_oauth(admin: &str, origin: &str, device: &str, now: i64) -> Result<(String, i64), String> {
    let http = http()?;
    let ua = "prism-client";
    let label = format!("Prism on {device}");
    let code = answer(
        http.post(format!("{origin}/api/auth/pairing-token"))
            .header("User-Agent", ua)
            .bearer_auth(admin)
            .json(&json!({"access": "full-access", "label": label}))
            .send()
            .map_err(|err| format!("T3 at {origin} did not answer: {err}"))?,
        "the pairing code",
    )?;
    let code = text(&code, "credential");
    if code.is_empty() {
        return Err("T3 issued no pairing code".into());
    }
    let registered = answer(
        http.post(format!("{origin}/oauth/mcp/register"))
            .header("User-Agent", ua)
            .json(&json!({
                "client_name": label,
                "software_id": "prism-device",
                "redirect_uris": [REDIRECT],
                "token_endpoint_auth_method": "none",
            }))
            .send()
            .map_err(|err| err.to_string())?,
        "the client registration",
    )?;
    let client_id = text(&registered, "client_id");
    let verifier = random_text(32)?;
    let state = random_text(16)?;
    let challenge = {
        use sha2::{Digest, Sha256};
        base64url(&Sha256::digest(verifier.as_bytes()))
    };
    let resource = format!("{origin}/mcp");
    let decided = answer(
        http.post(format!("{origin}/oauth/mcp/decision"))
            .header("User-Agent", ua)
            .json(&json!({
                "authorization": {
                    "response_type": "code",
                    "client_id": client_id,
                    "redirect_uri": REDIRECT,
                    "code_challenge": challenge,
                    "code_challenge_method": "S256",
                    "state": state,
                    "resource": resource,
                },
                "decision": {"_tag": "pairing-code", "access": "full-access", "code": code},
            }))
            .send()
            .map_err(|err| err.to_string())?,
        "the pairing",
    )?;
    let redirect = text(&decided, "redirectTo");
    let location = reqwest::Url::parse(&redirect)
        .or_else(|_| reqwest::Url::parse(&format!("http://127.0.0.1{redirect}")))
        .map_err(|_| "T3 answered the pairing without a redirect".to_string())?;
    let param = |name: &str| {
        location
            .query_pairs()
            .find(|(k, _)| k == name)
            .map(|(_, v)| v.into_owned())
            .unwrap_or_default()
    };
    if location.path() != "/prism/paired" || param("state") != state || param("code").is_empty() {
        return Err("T3 refused the pairing code".into());
    }
    let token = answer(
        http.post(format!("{origin}/oauth/mcp/token"))
            .header("User-Agent", ua)
            .form(&[
                ("grant_type", "authorization_code"),
                ("client_id", client_id.as_str()),
                ("code", param("code").as_str()),
                ("code_verifier", verifier.as_str()),
                ("redirect_uri", REDIRECT),
                ("resource", resource.as_str()),
            ])
            .send()
            .map_err(|err| err.to_string())?,
        "the token",
    )?;
    let access = text(&token, "access_token");
    let lifetime = token.get("expires_in").and_then(Value::as_i64).unwrap_or(0);
    if access.is_empty() || lifetime <= 0 {
        return Err("T3 issued no MCP token".into());
    }
    Ok((access, now + lifetime))
}

/// Mints the sessions the workers report missing or due and registers them.
/// Returns one line per device and kind it registered; every failure is
/// tried independently and reported together.
pub fn register_devices(
    prism: &Client,
    t3: &dyn T3Sessions,
    origin: &str,
    now: i64,
) -> Result<Vec<String>, String> {
    let origin = origin.trim_end_matches('/');
    let listed = prism.json("GET", "/v1/t3/device-sessions", None)?;
    let mut done = Vec::new();
    let mut failed = Vec::new();
    for s in listed
        .get("sessions")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default()
    {
        let (device, kind) = (text(&s, "device"), text(&s, "kind"));
        let expires = s.get("expiresAt").and_then(Value::as_i64).unwrap_or(0);
        if device.is_empty() || expires - now >= RENEW_BEFORE_SECS {
            continue;
        }
        let minted = match kind.as_str() {
            "client" => t3
                .issue(&CLIENT_SCOPES, TTL, &format!("Prism on {device}"))
                .and_then(|i| {
                    chrono::DateTime::parse_from_rfc3339(&i.expires_at)
                        .map(|at| (i.token, at.timestamp()))
                        .map_err(|_| "t3 auth session issue printed no expiry".to_string())
                }),
            "mcp" => mcp_session(t3, origin, &device, now),
            other => Err(format!("unknown session kind {other:?}")),
        };
        let registered = minted.and_then(|(token, expires_at)| {
            prism
                .send(
                    "PUT",
                    "/v1/t3/device-session",
                    Some(&json!({
                        "device": device,
                        "kind": kind,
                        "origin": origin,
                        "token": token,
                        "expiresAt": expires_at,
                    })),
                )
                .map(|_| ())
        });
        match registered {
            Ok(()) => done.push(format!("{device} {kind}")),
            Err(err) => failed.push(format!("{device} {kind}: {err}")),
        }
    }
    if failed.is_empty() {
        Ok(done)
    } else {
        Err(failed.join("; "))
    }
}

#[cfg(test)]
mod tests {
    use super::base64url;

    #[test]
    fn base64url_without_padding() {
        assert_eq!(base64url(b""), "");
        assert_eq!(base64url(b"f"), "Zg");
        assert_eq!(base64url(b"fo"), "Zm8");
        assert_eq!(base64url(b"foo"), "Zm9v");
        assert_eq!(base64url(&[0xfb, 0xff]), "-_8");
    }
}
