//! The Prism client a machine reports with (Go's `client.go`): its settings,
//! and requests with Go's retries and error texts.

use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::io::Read;
use std::time::Duration;

/// Where fleet installs the client settings.
pub const DEFAULT_CLIENT_CONFIG: &str = "/etc/prism/client.json";

/// Reads a variable; tests pass their own.
pub type Env<'a> = &'a dyn Fn(&str) -> Option<String>;

pub fn process_env(name: &str) -> Option<String> {
    std::env::var(name).ok()
}

/// Go's text for an OS error: "no such file or directory", not
/// "No such file or directory (os error 2)".
pub fn errno_text(err: &std::io::Error) -> String {
    let Some(code) = err.raw_os_error() else {
        return err.to_string();
    };
    // SAFETY: strerror returns a valid C string for any errno.
    let text = unsafe { std::ffi::CStr::from_ptr(libc::strerror(code)) }
        .to_string_lossy()
        .into_owned();
    let mut chars = text.chars();
    match chars.next() {
        Some(first) => first.to_lowercase().chain(chars).collect(),
        None => text,
    }
}

/// Go's `*PathError` text for reading a file: `open <path>: <errno>`.
pub fn path_error(path: &str, err: &std::io::Error) -> String {
    let op = if err.raw_os_error() == Some(libc::EISDIR) {
        "read"
    } else {
        "open"
    };
    format!("{op} {path}: {}", errno_text(err))
}

/// Reads a whole file as Go's `os.ReadFile` does, with its error text.
pub fn read_file(path: &str) -> Result<Vec<u8>, String> {
    std::fs::read(path).map_err(|err| path_error(path, &err))
}

#[derive(Debug, Default, Deserialize)]
struct ClientConfig {
    #[serde(default)]
    url: String,
    #[serde(default)]
    token_file: String,
}

/// The client settings file: PRISM_CLIENT_CONFIG, or /etc/prism/client.json,
/// which may be missing. Returns the path and what it holds.
fn client_config(env: Env) -> Result<(String, ClientConfig), String> {
    let explicit = env("PRISM_CLIENT_CONFIG").filter(|p| !p.is_empty());
    let path = explicit
        .clone()
        .unwrap_or_else(|| DEFAULT_CLIENT_CONFIG.into());
    match std::fs::read(&path) {
        Ok(raw) => {
            let cfg = serde_json::from_slice(&raw).map_err(|err| format!("{path}: {err}"))?;
            Ok((path, cfg))
        }
        Err(err) if explicit.is_some() => Err(path_error(&path, &err)),
        Err(_) => Ok((path, ClientConfig::default())),
    }
}

/// Reports check results of this machine to Prism's event API.
pub struct Client {
    pub url: String,
    pub token: String,
    pub timeout: Duration,
    /// Waits between attempts: a laptop that just woke up may need a moment
    /// before its network is back.
    pub retry: Vec<Duration>,
    /// Answer a refusal with its `error` text alone (the admin API's
    /// refusals are meant for the owner as they are).
    pub plain_refusals: bool,
}

/// One check result (Go's `EventReport`).
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct EventReport {
    pub check: String,
    pub status: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub summary: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub output: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub command: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub exit_code: Option<i32>,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub severity: String,
}

impl Client {
    pub fn new(url: &str, token: &str) -> Self {
        Self {
            url: url.trim_end_matches('/').to_string(),
            token: token.to_string(),
            timeout: Duration::from_secs(20),
            retry: vec![Duration::from_secs(2), Duration::from_secs(10)],
            plain_refusals: false,
        }
    }

    /// Go's `NewClientFromEnv`: PRISM_URL and PRISM_TOKEN(_FILE), falling
    /// back to the client settings file.
    pub fn from_env(env: Env) -> Result<Self, String> {
        let (path, cfg) = client_config(env)?;
        let var = |name: &str| env(name).filter(|v| !v.is_empty());
        let url = var("PRISM_URL").unwrap_or(cfg.url);
        let mut token = var("PRISM_TOKEN").unwrap_or_default();
        let token_file = var("PRISM_TOKEN_FILE").unwrap_or(cfg.token_file);
        if token.is_empty() && !token_file.is_empty() {
            let raw = read_file(&token_file)
                .map_err(|err| format!("cannot read the Prism token: {err}"))?;
            token = String::from_utf8_lossy(&raw).trim().to_string();
        }
        if url.is_empty() || token.is_empty() {
            return Err(format!(
                "Prism is not configured: set url and token_file in {path}, or PRISM_URL and PRISM_TOKEN_FILE"
            ));
        }
        Ok(Self::new(&url, &token))
    }

    /// The worker's admin API, with the token derived from the event key. It
    /// is the worker this runs next to (`kubectl exec` into a prism-worker
    /// pod) unless PRISM_URL or the client settings name another.
    pub fn admin_from_env(env: Env) -> Result<Self, String> {
        let key = crate::token::secret("PRISM_EVENT_KEY", env);
        let token = crate::token::admin_token(key.as_bytes())?;
        let url = env("PRISM_URL")
            .filter(|v| !v.is_empty())
            .or_else(|| {
                let bind = env("PRISM_WORKER_HTTP_BIND").filter(|v| !v.is_empty())?;
                let port = bind.rsplit_once(':').map_or("8080", |(_, p)| p).to_string();
                Some(format!("http://127.0.0.1:{port}"))
            })
            .or_else(|| {
                client_config(env)
                    .ok()
                    .map(|(_, c)| c.url)
                    .filter(|u| !u.is_empty())
            })
            .unwrap_or_else(|| "http://127.0.0.1:8080".into());
        let mut client = Self::new(&url, &token);
        client.plain_refusals = true;
        Ok(client)
    }

    /// Sends a request and returns the answer's body; Go's `do`.
    pub fn send(&self, method: &str, path: &str, body: Option<&Value>) -> Result<Vec<u8>, String> {
        let http = reqwest::blocking::Client::builder()
            .timeout(self.timeout)
            .build()
            .map_err(|err| err.to_string())?;
        let payload = body.map(Value::to_string).unwrap_or_default();
        let target = format!("{}{path}", self.url);
        let method = reqwest::Method::from_bytes(method.as_bytes()).map_err(|e| e.to_string())?;
        let mut attempt = 0;
        loop {
            let result = http
                .request(method.clone(), &target)
                .header("Authorization", format!("Bearer {}", self.token))
                .header("Content-Type", "application/json")
                .header("User-Agent", "prism-client")
                .body(payload.clone())
                .send();
            let last = match result {
                Ok(resp) => {
                    let status = resp.status().as_u16();
                    let mut data = Vec::new();
                    let _ = resp.take(64 << 10).read_to_end(&mut data);
                    if status < 300 {
                        return Ok(data);
                    }
                    if status < 500 && status != 429 {
                        return Err(self.refusal(status, &data));
                    }
                    format!("Prism answered {status}")
                }
                Err(err) => go_request_error(&method, &target, &err),
            };
            if attempt >= self.retry.len() {
                return Err(last);
            }
            std::thread::sleep(self.retry[attempt]);
            attempt += 1;
        }
    }

    fn refusal(&self, status: u16, data: &[u8]) -> String {
        if self.plain_refusals
            && let Ok(v) = serde_json::from_slice::<Value>(data)
            && let Some(message) = v.get("error").and_then(Value::as_str)
            && status == 400
        {
            return message.to_string();
        }
        format!(
            "Prism rejected the request ({status}): {}",
            String::from_utf8_lossy(data).trim()
        )
    }

    /// Sends a request and decodes the JSON answer.
    pub fn json(&self, method: &str, path: &str, body: Option<&Value>) -> Result<Value, String> {
        let data = self.send(method, path, body)?;
        serde_json::from_slice(&data).map_err(|err| err.to_string())
    }

    /// The machine the token belongs to (`GET /v1/ping`).
    pub fn ping(&self) -> Result<String, String> {
        let out = self.json("GET", "/v1/ping", None)?;
        Ok(text(&out, "machine"))
    }

    /// Sends one check result and returns the event's state.
    pub fn report(&self, report: &EventReport) -> Result<String, String> {
        let body = serde_json::to_value(report).map_err(|e| e.to_string())?;
        let out = self.json("POST", "/v1/events", Some(&body))?;
        Ok(text(&out, "state"))
    }

    /// Registers this machine's T3 client session from `t3 session issue`
    /// JSON on `input`, so the token is never in argv or logs.
    pub fn register_t3(&self, origin: &str, input: impl Read) -> Result<(), String> {
        #[derive(Deserialize)]
        struct Issued {
            #[serde(default)]
            token: String,
            #[serde(default, rename = "expiresAt")]
            expires_at: String,
        }
        let mut stream = serde_json::Deserializer::from_reader(input.take(64 << 10)).into_iter();
        let issued: Issued = match stream.next() {
            Some(Ok(v)) => v,
            _ => return Err("invalid T3 session JSON".into()),
        };
        let expires = chrono::DateTime::parse_from_rfc3339(&issued.expires_at);
        let (Ok(expires), false) = (expires, issued.token.is_empty()) else {
            return Err("T3 session token and expiry are required".into());
        };
        let body = serde_json::json!({
            "origin": origin,
            "token": issued.token,
            "environmentId": "",
            "expiresAt": expires.timestamp(),
        });
        self.send("PUT", "/v1/t3/client-session", Some(&body))
            .map(|_| ())
    }
}

pub fn text(v: &Value, key: &str) -> String {
    v.get(key)
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string()
}

/// Go's `*url.Error` text: `Post "https://…/v1/events": <cause>`.
fn go_request_error(method: &reqwest::Method, target: &str, err: &reqwest::Error) -> String {
    let mut cause: &dyn std::error::Error = err;
    while let Some(next) = cause.source() {
        cause = next;
    }
    let m = method.as_str();
    let m = format!("{}{}", &m[..1], m[1..].to_lowercase());
    let cause = if err.is_timeout() {
        "context deadline exceeded (Client.Timeout exceeded while awaiting headers)".to_string()
    } else if let Some(io) = cause.downcast_ref::<std::io::Error>() {
        errno_text(io)
    } else {
        cause.to_string()
    };
    format!("{m} \"{target}\": {cause}")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    fn env(vars: &[(&str, String)]) -> impl Fn(&str) -> Option<String> + use<> {
        let vars: BTreeMap<String, String> = vars
            .iter()
            .map(|(k, v)| ((*k).to_string(), v.clone()))
            .collect();
        move |key| vars.get(key).cloned()
    }

    #[test]
    fn settings_come_from_the_environment_then_the_file() {
        let dir = tempfile::tempdir().unwrap();
        let token_file = dir.path().join("token");
        std::fs::write(&token_file, " file-token\n").unwrap();
        let config = dir.path().join("client.json");
        std::fs::write(
            &config,
            serde_json::json!({"url": "https://prism.example/", "token_file": token_file})
                .to_string(),
        )
        .unwrap();
        let cfg = config.display().to_string();

        let c = Client::from_env(&env(&[("PRISM_CLIENT_CONFIG", cfg.clone())])).unwrap();
        assert_eq!(
            (c.url.as_str(), c.token.as_str()),
            ("https://prism.example", "file-token")
        );
        let c = Client::from_env(&env(&[
            ("PRISM_CLIENT_CONFIG", cfg.clone()),
            ("PRISM_URL", "http://other".into()),
            ("PRISM_TOKEN", "env-token".into()),
        ]))
        .unwrap();
        assert_eq!(
            (c.url.as_str(), c.token.as_str()),
            ("http://other", "env-token")
        );

        let missing = dir.path().join("missing.json").display().to_string();
        assert_eq!(
            Client::from_env(&env(&[("PRISM_CLIENT_CONFIG", missing.clone())])).err(),
            Some(format!("open {missing}: no such file or directory"))
        );
        let e = env(&[
            ("PRISM_CLIENT_CONFIG", cfg.clone()),
            ("PRISM_TOKEN_FILE", missing.clone()),
        ]);
        assert_eq!(
            Client::from_env(&e).err(),
            Some(format!(
                "cannot read the Prism token: open {missing}: no such file or directory"
            ))
        );
        let empty = dir.path().join("empty.json");
        std::fs::write(&empty, "{}").unwrap();
        let empty = empty.display().to_string();
        assert_eq!(
            Client::from_env(&env(&[("PRISM_CLIENT_CONFIG", empty.clone())])).err(),
            Some(format!(
                "Prism is not configured: set url and token_file in {empty}, or PRISM_URL and PRISM_TOKEN_FILE"
            ))
        );
        let bad = dir.path().join("bad.json");
        std::fs::write(&bad, "{").unwrap();
        let bad = bad.display().to_string();
        assert!(
            Client::from_env(&env(&[("PRISM_CLIENT_CONFIG", bad.clone())]))
                .err()
                .unwrap()
                .starts_with(&format!("{bad}: "))
        );
    }

    #[test]
    fn the_admin_client_finds_the_worker_next_to_it() {
        let key = (
            "PRISM_EVENT_KEY",
            "prism-test-event-key-0123456789abcdef".to_string(),
        );
        let none = (
            "PRISM_CLIENT_CONFIG",
            "/nonexistent/client.json".to_string(),
        );
        let c = Client::admin_from_env(&env(&[key.clone(), none.clone()])).unwrap();
        assert_eq!(c.url, "http://127.0.0.1:8080");
        assert!(c.token.starts_with("admin:"));
        let c = Client::admin_from_env(&env(&[
            key.clone(),
            none.clone(),
            ("PRISM_WORKER_HTTP_BIND", "0.0.0.0:9090".into()),
        ]))
        .unwrap();
        assert_eq!(c.url, "http://127.0.0.1:9090");
        let c =
            Client::admin_from_env(&env(&[key, ("PRISM_URL", "https://prism.example/".into())]))
                .unwrap();
        assert_eq!(c.url, "https://prism.example");
        assert_eq!(
            Client::admin_from_env(&env(&[none])).err(),
            Some("the event key must be at least 32 bytes".into())
        );
    }

    #[test]
    fn reports_serialize_as_go_does() {
        let r = EventReport {
            check: "brew".into(),
            status: "ok".into(),
            ..EventReport::default()
        };
        assert_eq!(
            serde_json::to_string(&r).unwrap(),
            r#"{"check":"brew","status":"ok"}"#
        );
        let r = EventReport {
            exit_code: Some(0),
            ..r
        };
        assert!(
            serde_json::to_string(&r)
                .unwrap()
                .contains(r#""exit_code":0"#)
        );
    }
}
