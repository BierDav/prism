//! The commands against a fake Prism (Go's client tests in machines_test.go).

use super::*;
use std::collections::BTreeMap;
use std::io::{BufRead, BufReader};
use std::net::TcpListener;
use std::sync::{Arc, Mutex};

#[derive(Clone, Debug)]
struct Seen {
    method: String,
    path: String,
    auth: String,
    body: Value,
}

type Handler = dyn Fn(&Seen) -> (u16, String) + Send + Sync;

/// A one-thread HTTP server that answers each request with `handler`.
fn serve(handler: Arc<Handler>) -> (String, Arc<Mutex<Vec<Seen>>>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let seen = Arc::new(Mutex::new(Vec::new()));
    let log = Arc::clone(&seen);
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(mut stream) = stream else { return };
            let mut reader = BufReader::new(stream.try_clone().unwrap());
            let mut line = String::new();
            if reader.read_line(&mut line).is_err() {
                continue;
            }
            let mut parts = line.split_whitespace();
            let (method, path) = (
                parts.next().unwrap_or("").to_string(),
                parts.next().unwrap_or("").to_string(),
            );
            let mut headers = BTreeMap::new();
            loop {
                let mut h = String::new();
                if reader.read_line(&mut h).unwrap_or(0) == 0 || h.trim().is_empty() {
                    break;
                }
                if let Some((k, v)) = h.split_once(':') {
                    headers.insert(k.trim().to_ascii_lowercase(), v.trim().to_string());
                }
            }
            let len: usize = headers
                .get("content-length")
                .and_then(|v| v.parse().ok())
                .unwrap_or(0);
            let mut body = vec![0u8; len];
            let _ = reader.read_exact(&mut body);
            let req = Seen {
                method,
                path,
                auth: headers.get("authorization").cloned().unwrap_or_default(),
                body: serde_json::from_slice(&body).unwrap_or(Value::Null),
            };
            assert_eq!(
                headers.get("user-agent").map(String::as_str),
                Some("prism-client")
            );
            let (status, answer) = handler(&req);
            log.lock().unwrap().push(req);
            let _ = write!(
                stream,
                "HTTP/1.1 {status} X\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{answer}",
                answer.len()
            );
        }
    });
    (url, seen)
}

use std::io::Write;

fn s(v: &[&str]) -> Vec<String> {
    v.iter().map(|x| x.to_string()).collect()
}

fn env(vars: Vec<(&'static str, String)>) -> impl Fn(&str) -> Option<String> {
    let vars: BTreeMap<&str, String> = vars.into_iter().collect();
    move |k| vars.get(k).cloned()
}

const KEY: &str = "prism-test-event-key-0123456789abcdef";
const TOKEN: &str = "david-mb.d6ed43973d8463ff02792f533d7462036fd62c2e8978a1eb5f6d1201ffff49f1";

fn events(seen: &Seen) -> (u16, String) {
    if seen.auth != format!("Bearer {TOKEN}") {
        return (401, "invalid token".into());
    }
    match (seen.method.as_str(), seen.path.as_str()) {
        ("GET", "/v1/ping") => (200, r#"{"machine":"david-mb"}"#.into()),
        ("POST", "/v1/events") => {
            let state = if seen.body["status"] == "failed" {
                "firing"
            } else {
                "resolved"
            };
            (
                202,
                json!({"machine": "david-mb", "state": state}).to_string(),
            )
        }
        ("PUT", "/v1/t3/client-session") => (200, r#"{"ok":true}"#.into()),
        _ => (404, "not found".into()),
    }
}

#[test]
fn run_check_reports_failures_and_successes() {
    let (url, seen) = serve(Arc::new(events));
    let client = Client::new(&url, TOKEN);
    let (mut out, mut err) = (Vec::new(), Vec::new());
    let argv = s(&[
        "sh",
        "-c",
        "echo updating; echo 'Error: it broke' >&2; exit 3",
    ]);
    let code = run::run_check(
        Some(&client),
        "brew-update",
        "",
        "",
        &argv,
        &mut out,
        &mut err,
    );
    assert_eq!(code, 3);
    assert_eq!(out, b"updating\n");
    assert!(String::from_utf8_lossy(&err).contains("it broke"));
    let report = seen.lock().unwrap()[0].body.clone();
    assert_eq!(report["check"], "brew-update");
    assert_eq!(report["status"], "failed");
    assert_eq!(report["exit_code"], 3);
    assert!(
        report["output"]
            .as_str()
            .unwrap()
            .contains("Error: it broke")
    );
    assert_eq!(
        report["command"],
        r#"sh -c 'echo updating; echo '\''Error: it broke'\'' >&2; exit 3'"#
    );

    let code = run::run_check(
        Some(&client),
        "brew-update",
        "info",
        "s",
        &s(&["true"]),
        &mut out,
        &mut err,
    );
    assert_eq!(code, 0);
    assert_eq!(
        seen.lock().unwrap()[1].body,
        json!({"check": "brew-update", "status": "ok", "command": "true", "severity": "info"})
    );
    // A command that can't start still reports, with exit code 127.
    let code = run::run_check(
        Some(&client),
        "missing",
        "",
        "",
        &s(&["/nonexistent/cmd"]),
        &mut out,
        &mut err,
    );
    assert_eq!(code, 127);
    assert_eq!(seen.lock().unwrap()[2].body["exit_code"], 127);
    // A rejected token is reported on stderr and doesn't change the result.
    let mut bad = Client::new(&url, "david-mb.00");
    bad.retry.clear();
    let mut err = Vec::new();
    let code = run::run_check(
        Some(&bad),
        "brew-update",
        "",
        "",
        &s(&["false"]),
        &mut out,
        &mut err,
    );
    assert_eq!(code, 1);
    assert_eq!(
        String::from_utf8_lossy(&err),
        "prism: could not report brew-update: Prism rejected the request (401): invalid token\n"
    );
}

#[test]
fn ping_report_and_register_t3() {
    let (url, seen) = serve(Arc::new(events));
    let e = env(vec![
        ("PRISM_CLIENT_CONFIG", "/nonexistent/client.json".into()),
        ("PRISM_URL", format!("{url}/")),
        ("PRISM_TOKEN", TOKEN.into()),
    ]);
    // An explicit settings file must exist.
    assert_eq!(dispatch(&s(&["prism", "ping"]), &e), 1);
    let e = env(vec![
        ("PRISM_CLIENT_CONFIG", "".into()),
        ("PRISM_URL", format!("{url}/")),
        ("PRISM_TOKEN", TOKEN.into()),
    ]);
    assert_eq!(dispatch(&s(&["prism", "ping"]), &e), 0);
    let c = Client::from_env(&e).unwrap();
    assert_eq!(c.ping().as_deref(), Ok("david-mb"));
    assert_eq!(
        dispatch(
            &s(&[
                "prism",
                "report",
                "--severity=critical",
                "--summary",
                "disk",
                "disk",
                "failed"
            ]),
            &e
        ),
        0
    );
    assert_eq!(
        seen.lock().unwrap().last().unwrap().body,
        json!({"check": "disk", "status": "failed", "summary": "disk", "severity": "critical"})
    );
    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("out");
    std::fs::write(&out, b"tail \xff of it").unwrap();
    let args = s(&[
        "prism",
        "report",
        "--output",
        out.to_str().unwrap(),
        "disk",
        "ok",
    ]);
    assert_eq!(dispatch(&args, &e), 0);
    assert_eq!(
        seen.lock().unwrap().last().unwrap().body["output"],
        "tail  of it"
    );
    assert_eq!(
        dispatch(
            &s(&[
                "prism",
                "report",
                "--output",
                "/nonexistent/x",
                "disk",
                "ok"
            ]),
            &e
        ),
        1
    );
    for bad in [
        &["prism", "report", "disk"][..],
        &["prism", "report", "--bogus", "a", "b"],
        &["prism", "run", "x", "true"],
        &["prism", "run", "x", "--"],
    ] {
        assert_eq!(dispatch(&s(bad), &e), 2, "{bad:?}");
    }

    let session =
        r#"{"token": "t3-session", "expiresAt": "2026-12-01T10:00:00.5Z", "other": 1} trailing"#;
    c.register_t3("http://100.64.0.2:3773", session.as_bytes())
        .unwrap();
    assert_eq!(
        seen.lock().unwrap().last().unwrap().body,
        json!({"origin": "http://100.64.0.2:3773", "token": "t3-session", "environmentId": "", "expiresAt": 1_796_119_200})
    );
    assert_eq!(
        c.register_t3("o", "nope".as_bytes()),
        Err("invalid T3 session JSON".into())
    );
    assert_eq!(
        c.register_t3("o", r#"{"token": "t", "expiresAt": "tomorrow"}"#.as_bytes()),
        Err("T3 session token and expiry are required".into())
    );
    assert_eq!(
        c.register_t3("o", r#"{"expiresAt": "2026-12-01T10:00:00Z"}"#.as_bytes()),
        Err("T3 session token and expiry are required".into())
    );
}

#[test]
fn server_errors_are_retried_and_refusals_are_not() {
    let calls = Arc::new(Mutex::new(0));
    let counter = Arc::clone(&calls);
    let (url, _) = serve(Arc::new(move |_: &Seen| {
        let mut n = counter.lock().unwrap();
        *n += 1;
        if *n < 3 {
            (503, "busy".into())
        } else {
            (200, r#"{"machine":"m"}"#.into())
        }
    }));
    let mut c = Client::new(&url, "t");
    c.retry = vec![Duration::from_millis(1), Duration::from_millis(1)];
    assert_eq!(c.ping().as_deref(), Ok("m"));
    assert_eq!(*calls.lock().unwrap(), 3);
    *calls.lock().unwrap() = -10;
    assert_eq!(c.ping(), Err("Prism answered 503".into()));

    let (url, seen) = serve(Arc::new(|_: &Seen| (429, "slow down".into())));
    let mut c = Client::new(&url, "t");
    c.retry = vec![Duration::from_millis(1)];
    assert_eq!(c.ping(), Err("Prism answered 429".into()));
    assert_eq!(seen.lock().unwrap().len(), 2);
}

fn admin_env(url: &str) -> impl Fn(&str) -> Option<String> {
    env(vec![
        ("PRISM_EVENT_KEY", KEY.into()),
        ("PRISM_URL", url.into()),
    ])
}

#[test]
fn pair_sends_the_link_once_with_the_admin_token() {
    let (url, seen) = serve(Arc::new(|req: &Seen| {
        if req.body["link"] == "one-use-code" {
            (
                200,
                json!({"paired": true, "answer": "Paired with david-mb (auto access): …"})
                    .to_string(),
            )
        } else if req.body["link"] == "boom" {
            (503, "{}".into())
        } else {
            (200, json!({"paired": false, "answer": "Could not pair david-mb. Create a fresh code and try again."}).to_string())
        }
    }));
    let e = admin_env(&url);
    assert_eq!(
        pair_command(&s(&["david-mb", "auto"]), " one-use-code \n".as_bytes(), &e),
        Ok("Paired with david-mb (auto access): …".into())
    );
    let req = seen.lock().unwrap()[0].clone();
    assert_eq!(
        (req.method.as_str(), req.path.as_str()),
        ("POST", "/v1/t3/pair")
    );
    assert_eq!(
        req.body,
        json!({"machine": "david-mb", "link": "one-use-code", "access": "auto"})
    );
    assert_eq!(
        req.auth,
        format!("Bearer {}", token::admin_token(KEY.as_bytes()).unwrap())
    );
    assert_eq!(
        pair_command(&s(&["david-mb"]), "stale".as_bytes(), &e),
        Err("Could not pair david-mb. Create a fresh code and try again.".into())
    );
    assert_eq!(
        seen.lock().unwrap()[1].body,
        json!({"machine": "david-mb", "link": "stale"})
    );
    // Never twice, even when the worker fails.
    assert_eq!(
        pair_command(&s(&["david-mb"]), "boom".as_bytes(), &e),
        Err("Prism answered 503".into())
    );
    assert_eq!(seen.lock().unwrap().len(), 3);
    for bad in ["", "  \n", "a\nb"] {
        assert_eq!(
            pair_command(&s(&["david-mb"]), bad.as_bytes(), &e),
            Err("pass the pairing link (or \"[url] <code>\") on one line on stdin".into())
        );
    }
    assert_eq!(seen.lock().unwrap().len(), 3);
    assert_eq!(dispatch(&s(&["prism", "pair"]), &e), 1);
    assert_eq!(dispatch(&s(&["prism", "pair", "a", "b", "c"]), &e), 1);
}

#[test]
fn device_tokens_and_refusals() {
    let (url, seen) = serve(Arc::new(|req: &Seen| {
        match (req.method.as_str(), req.path.as_str()) {
            ("POST", "/v1/devices/pixel/token") => {
                (200, r#"{"device":"pixel","token":"prism_abc"}"#.into())
            }
            ("DELETE", "/v1/devices/pixel/token") => {
                (200, r#"{"device":"pixel","revoked":true}"#.into())
            }
            (_, "/v1/devices/Bad%20Name/token") => (
                400,
                r#"{"error":"invalid device name \"Bad Name\"","code":"invalid"}"#.into(),
            ),
            _ => (401, r#"{"error":"no","code":"unauthorized"}"#.into()),
        }
    }));
    let e = admin_env(&url);
    assert_eq!(
        device_command("device-token", "pixel", &e),
        Ok("prism_abc".into())
    );
    assert_eq!(
        device_command("device-revoke", "pixel", &e),
        Ok("pixel: token revoked: true".into())
    );
    assert_eq!(
        device_command("device-token", "Bad Name", &e),
        Err("invalid device name \"Bad Name\"".into())
    );
    assert_eq!(
        device_command("device-token", "other", &e),
        Err(r#"Prism rejected the request (401): {"error":"no","code":"unauthorized"}"#.into())
    );
    assert_eq!(seen.lock().unwrap().len(), 4);
    assert_eq!(dispatch(&s(&["prism", "device-token"]), &e), 1);
}

#[test]
fn machines_render_as_go_did() {
    let view = json!({
        "cluster": {"name": "cluster", "runsOn": "david-server", "workspace": ""},
        "machines": [
            {"name": "ccs-oracle-1", "route": "ssh", "via": "david-server", "state": "over SSH from david-server", "detail": "", "sshRunner": false, "openAlerts": []},
            {"name": "david-mb", "route": "direct", "via": "", "state": "reachable", "detail": "session expires in 3 days", "sshRunner": false, "openAlerts": [2, 5]},
            {"name": "david-server", "route": "direct", "via": "", "state": "reachable", "detail": "", "sshRunner": true, "openAlerts": []},
            {"name": "reporter", "route": "reports", "via": "", "state": "", "detail": "", "sshRunner": false, "openAlerts": [4]}
        ]
    });
    assert_eq!(
        render_machines(&view),
        "cluster · default target · selfhosted-platform, agents run on david-server\n\
         ccs-oracle-1 · ssh · via david-server · over SSH from david-server\n\
         david-mb · direct · reachable · session expires in 3 days · open: #2, #5\n\
         david-server · direct · reachable · SSH runner\n\
         reporter · reports · open: #4"
    );
    assert_eq!(
        render_machines(&json!({"cluster": {"runsOn": ""}, "machines": []})),
        "cluster · default target · selfhosted-platform, agents run on ?"
    );
}

#[test]
fn usage_and_exit_codes() {
    let e = env(vec![]);
    assert_eq!(dispatch(&s(&["prism"]), &e), 2);
    assert_eq!(dispatch(&s(&["prism", "serve"]), &e), 2);
    assert_eq!(dispatch(&s(&["prism", "help"]), &e), 0);
    assert_eq!(dispatch(&s(&["prism", "token"]), &e), 1);
    assert_eq!(dispatch(&s(&["prism", "token", "david-mb"]), &e), 1);
    let e = env(vec![("PRISM_EVENT_KEY", KEY.into())]);
    assert_eq!(dispatch(&s(&["prism", "token", "david-mb"]), &e), 0);
    assert_eq!(dispatch(&s(&["prism", "register-t3"]), &e), 1);
    assert!(USAGE.contains("prism pair <machine> [access] < link"));
    assert!(!USAGE.contains("prism serve"));
}

#[derive(Default)]
struct FakeT3 {
    issued: Mutex<Vec<(Vec<String>, String, String)>>,
    revoked: Mutex<Vec<String>>,
}

impl devices::T3Sessions for FakeT3 {
    fn issue(&self, scopes: &[&str], ttl: &str, label: &str) -> Result<devices::Issued, String> {
        let mut issued = self.issued.lock().unwrap();
        issued.push((
            scopes.iter().map(|s| s.to_string()).collect(),
            ttl.into(),
            label.into(),
        ));
        Ok(devices::Issued {
            session_id: format!("s{}", issued.len()),
            token: format!("tok-{label}"),
            expires_at: "2027-02-14T08:00:00.000Z".into(),
        })
    }
    fn revoke(&self, session_id: &str) {
        self.revoked.lock().unwrap().push(session_id.into());
    }
}

#[test]
fn register_t3_devices_mints_only_what_is_missing_or_due() {
    const NOW: i64 = 1_800_000_000;
    const DAY: i64 = 24 * 60 * 60;
    let handler = |seen: &Seen| -> (u16, String) {
        match (seen.method.as_str(), seen.path.as_str()) {
            ("GET", "/v1/t3/device-sessions") if seen.auth == format!("Bearer {TOKEN}") => (
                200,
                json!({"sessions": [
                    {"device": "david-pixel", "kind": "client", "expiresAt": 0},
                    {"device": "david-pixel", "kind": "mcp", "expiresAt": 0},
                    {"device": "david-mb", "kind": "client", "expiresAt": NOW + 20 * DAY},
                    {"device": "david-mb", "kind": "mcp", "expiresAt": NOW + 3 * DAY},
                ]})
                .to_string(),
            ),
            ("PUT", "/v1/t3/device-session") if seen.auth == format!("Bearer {TOKEN}") => {
                (200, r#"{"ok":true}"#.into())
            }
            ("POST", "/api/auth/pairing-token")
                if seen.auth == "Bearer tok-Prism device pairing"
                    && seen.body["access"] == "full-access" =>
            {
                (200, r#"{"credential":"PAIR-CODE"}"#.into())
            }
            ("POST", "/oauth/mcp/register") => (201, r#"{"client_id":"cid"}"#.into()),
            ("POST", "/oauth/mcp/decision")
                if seen.body["decision"]["code"] == "PAIR-CODE"
                    && seen.body["decision"]["access"] == "full-access" =>
            {
                let state = seen.body["authorization"]["state"].as_str().unwrap_or("");
                (
                    200,
                    json!({"redirectTo": format!("http://127.0.0.1/prism/paired?code=AC&state={state}")})
                        .to_string(),
                )
            }
            ("POST", "/oauth/mcp/token") => (
                200,
                r#"{"access_token":"mcp-token","expires_in":2592000}"#.into(),
            ),
            _ => (404, "not found".into()),
        }
    };
    let (url, seen) = serve(Arc::new(handler));
    let prism = Client::new(&url, TOKEN);
    let t3 = FakeT3::default();
    let done = devices::register_devices(&prism, &t3, &format!("{url}/"), NOW).expect("register");
    assert_eq!(
        done,
        ["david-pixel client", "david-pixel mcp", "david-mb mcp"]
    );
    let puts: Vec<Value> = seen
        .lock()
        .unwrap()
        .iter()
        .filter(|s| s.path == "/v1/t3/device-session")
        .map(|s| s.body.clone())
        .collect();
    assert_eq!(puts.len(), 3);
    assert_eq!(puts[0]["token"], "tok-Prism on david-pixel");
    assert_eq!(puts[0]["kind"], "client");
    assert_eq!(puts[0]["origin"], url.as_str());
    // 2027-02-14T08:00:00Z, as `t3 auth session issue` printed it.
    assert_eq!(puts[0]["expiresAt"], NOW + 30 * DAY);
    assert_eq!(puts[1]["token"], "mcp-token");
    assert_eq!(puts[1]["expiresAt"], NOW + 2_592_000);
    assert_eq!(puts[2]["device"], "david-mb");
    // Client sessions: read and operate for 30 days; each MCP pairing used a
    // five-minute access session, revoked right after.
    let issued = t3.issued.lock().unwrap();
    assert_eq!(issued.len(), 3);
    assert_eq!(issued[0].0, ["orchestration:read", "orchestration:operate"]);
    assert_eq!(issued[0].1, "30d");
    assert!(issued[1].0.contains(&"access:write".to_string()));
    assert_eq!(issued[1].1, "5m");
    assert_eq!(*t3.revoked.lock().unwrap(), ["s2", "s3"]);
}

#[test]
fn account_limits_are_set_and_cleared() {
    let (url, seen) = serve(Arc::new(|req: &Seen| {
        match (req.method.as_str(), req.path.as_str()) {
            ("PUT", "/v1/placement/account-limits/claude-alex") => (
                200,
                json!({"account": "claude-alex", "limit": req.body}).to_string(),
            ),
            ("DELETE", "/v1/placement/account-limits/claude-alex") => {
                (200, r#"{"account":"claude-alex","limit":null}"#.into())
            }
            _ => (401, r#"{"error":"no","code":"unauthorized"}"#.into()),
        }
    }));
    let e = admin_env(&url);
    let out = account_limit_command(
        &s(&[
            "claude-alex",
            "--session",
            "70",
            "--weekly",
            "90",
            "--until",
            "2026-11-28T08:00:00Z",
        ]),
        &e,
    )
    .expect("set");
    assert!(out.starts_with("claude-alex: "), "{out}");
    {
        let seen = seen.lock().unwrap();
        assert_eq!(
            seen[0].body,
            json!({"sessionPercent": 70.0, "weeklyPercent": 90.0, "until": "2026-11-28T08:00:00Z"})
        );
    }
    assert_eq!(
        account_limit_command(&s(&["claude-alex", "--clear"]), &e),
        Ok("claude-alex: limit removed".into())
    );
    // Nothing to set, both at once, or a bad number: refused before sending.
    for bad in [
        &["claude-alex"][..],
        &["claude-alex", "--clear", "--session", "70"],
        &["claude-alex", "--session", "lots"],
        &["claude-alex", "--weekly"],
    ] {
        assert!(account_limit_command(&s(bad), &e).is_err(), "{bad:?}");
    }
    assert_eq!(seen.lock().unwrap().len(), 2);
}
