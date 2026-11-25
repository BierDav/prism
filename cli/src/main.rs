//! `prism`: the command machines report check results with (`prism run`,
//! `prism report`) and register their T3 sessions with (`prism
//! register-t3*`), and the owner's tool for pairing and listing machines. It
//! replaces the Go server's client half
//! (`main.go` and `client.go`) with the same
//! arguments, settings, output and exit codes. `prism serve` is gone: the
//! server is prism-worker. On a Mac it also passes the Prism app's own
//! subcommands (pubkey, self-test, sign, preview) on to the app.

mod client;
mod devices;
mod flags;
mod run;
mod token;

use client::{Client, Env, EventReport, process_env, text};
use serde_json::{Value, json};
use std::io::Read;
use std::time::Duration;

const USAGE: &str = r#"Usage:
  prism run [--severity S] [--summary TEXT] <check> -- <command> [args...]
      Run a command and report it as <check>: a failure opens an event in
      Prism with the output's tail, the next success resolves it. Exits with
      the command's exit code.
  prism report [--severity S] [--summary TEXT] [--output FILE|-] <check> failed|ok
      Report a check result without running anything.
  prism ping
      Check that the Prism server accepts this machine's token.
  prism token <machine>
      Print a machine's token (needs PRISM_EVENT_KEY or PRISM_EVENT_KEY_FILE).
  prism register-t3 <origin> < session.json
      Register this machine's T3 client session (t3 session issue JSON on
      stdin, so the token never appears in arguments or logs).
  prism register-t3-devices <origin>
      Pair every Prism device with this machine's T3 (at <origin>, its
      NetBird address): mint the sessions the workers report missing or due
      and register them. Needs t3 (PRISM_T3, default t3 on PATH).
  prism register-t3-mcp <origin>
      Pair the workers with this machine's T3 (at <origin>, its NetBird
      address), as prism pair does by hand: mint their MCP session when they
      want one from this machine and theirs is missing or due. Needs t3.
  prism pair <machine> [access] < link
      Pair the server with a machine's T3. Reads the pairing link (or
      "[url] <code>") from stdin, so it never appears in arguments or logs.
  prism machines
      Print how the server reaches each machine.
  prism account-limit <account> [--session N] [--weekly N] [--until TIME]
  prism account-limit <account> --clear
      Limit how much of an account's session and weekly windows placement
      uses (percent used, 1-100), until TIME (RFC 3339) or until cleared.
      "Stop with 30% of the session left" is --session 70.

Settings come from /etc/prism/client.json ({"url": …, "token_file": …}),
or PRISM_CLIENT_CONFIG, PRISM_URL, PRISM_TOKEN and PRISM_TOKEN_FILE.
Severity is info, warning (default) or critical.

pair, machines and account-limit need PRISM_EVENT_KEY (or
PRISM_EVENT_KEY_FILE): run them in a prism-worker pod. They call the worker
at PRISM_URL, default http://127.0.0.1:8080.

On a Mac, prism pubkey | self-test | sign | preview run the Prism app
(PRISM_APP, default /Applications/Prism.app/Contents/MacOS/Prism).
"#;

/// The Prism app's own subcommands (macos/main.swift).
const APP_COMMANDS: [&str; 4] = ["pubkey", "self-test", "sign", "preview"];

/// Where fleet installs the Mac app (traits/prism).
const DEFAULT_APP: &str = "/Applications/Prism.app/Contents/MacOS/Prism";

fn main() {
    let args: Vec<String> = std::env::args().collect();
    std::process::exit(dispatch(&args, &process_env));
}

fn usage_error() -> i32 {
    eprint!("{USAGE}");
    2
}

fn fail(err: impl std::fmt::Display) -> i32 {
    eprintln!("prism: {err}");
    1
}

fn dispatch(argv: &[String], env: Env) -> i32 {
    if argv.len() < 2 {
        return usage_error();
    }
    let args = &argv[2..];
    let command = argv[1].as_str();
    match command {
        "run" => run_command(args, env),
        "report" => report_command(args, env),
        "ping" => {
            let pinged = Client::from_env(env).and_then(|c| c.ping().map(|m| (c.url, m)));
            match pinged {
                Ok((url, machine)) => {
                    println!("Prism at {url} accepts this token as {machine}.");
                    0
                }
                Err(err) => fail(err),
            }
        }
        "token" => {
            if args.len() != 1 {
                return fail("usage: prism token <machine>");
            }
            let key = token::secret("PRISM_EVENT_KEY", env);
            match token::machine_token(key.as_bytes(), &args[0]) {
                Ok(t) => {
                    println!("{t}");
                    0
                }
                Err(err) => fail(err),
            }
        }
        "pair" => {
            if args.is_empty() || args.len() > 2 {
                return fail("usage: prism pair <machine> [access] < link");
            }
            match pair_command(args, std::io::stdin(), env) {
                Ok(answer) => {
                    println!("{answer}");
                    0
                }
                Err(err) => fail(err),
            }
        }
        "register-t3" => {
            if args.len() != 1 {
                return fail("usage: prism register-t3 <origin>");
            }
            match Client::from_env(env).and_then(|c| c.register_t3(&args[0], std::io::stdin())) {
                Ok(()) => 0,
                Err(err) => fail(err),
            }
        }
        "register-t3-devices" => {
            if args.len() != 1 {
                return fail("usage: prism register-t3-devices <origin>");
            }
            let now = chrono::Utc::now().timestamp();
            match Client::from_env(env)
                .and_then(|c| devices::register_devices(&c, &t3_command(env), &args[0], now))
            {
                Ok(done) => {
                    for line in done {
                        println!("registered {line}");
                    }
                    0
                }
                Err(err) => fail(err),
            }
        }
        "register-t3-mcp" => {
            if args.len() != 1 {
                return fail("usage: prism register-t3-mcp <origin>");
            }
            let now = chrono::Utc::now().timestamp();
            match Client::from_env(env)
                .and_then(|c| devices::register_workers(&c, &t3_command(env), &args[0], now))
            {
                Ok(line) => {
                    println!("{line}");
                    0
                }
                Err(err) => fail(err),
            }
        }
        "machines" => match machines_command(env) {
            Ok(text) => {
                println!("{text}");
                0
            }
            Err(err) => fail(err),
        },
        "account-limit" => match account_limit_command(args, env) {
            Ok(line) => {
                println!("{line}");
                0
            }
            Err(err) => fail(err),
        },
        "help" | "-h" | "--help" => {
            print!("{USAGE}");
            0
        }
        _ => {
            run_app(&argv[1..], env);
            usage_error()
        }
    }
}

/// This host's `t3` (PRISM_T3, default `t3` on PATH).
fn t3_command(env: Env) -> devices::T3Command {
    devices::T3Command(
        env("PRISM_T3")
            .filter(|p| !p.is_empty())
            .unwrap_or_else(|| "t3".into()),
    )
}

/// Replaces this process with the Prism app for one of its subcommands on a
/// Mac. It returns only when the arguments are not for the app.
fn run_app(args: &[String], env: Env) {
    if !cfg!(target_os = "macos") || args.is_empty() || !APP_COMMANDS.contains(&args[0].as_str()) {
        return;
    }
    let app = env("PRISM_APP")
        .filter(|a| !a.is_empty())
        .unwrap_or_else(|| DEFAULT_APP.into());
    use std::os::unix::process::CommandExt;
    let err = std::process::Command::new(&app)
        .arg0(&app)
        .args(args)
        .exec();
    eprintln!(
        "prism: cannot run the Prism app ({app}): {}",
        client::errno_text(&err)
    );
    std::process::exit(1);
}

fn check_flags(
    args: &[String],
    extra: &[&str],
) -> Option<(EventReport, Option<String>, Vec<String>)> {
    let mut names = vec!["severity", "summary"];
    names.extend_from_slice(extra);
    let (values, rest) = flags::parse(args, &names).ok()?;
    let get = |k: &str| values.get(k).cloned().unwrap_or_default();
    let report = EventReport {
        severity: get("severity"),
        summary: get("summary"),
        ..EventReport::default()
    };
    Some((report, values.get("output").cloned(), rest))
}

fn run_command(args: &[String], env: Env) -> i32 {
    let Some((flags, _, rest)) = check_flags(args, &[]) else {
        return usage_error();
    };
    if rest.len() < 3 || rest[1] != "--" {
        return usage_error();
    }
    let client = match Client::from_env(env) {
        Ok(c) => Some(c),
        Err(err) => {
            // Run the command anyway: a missing configuration must not stop the job.
            eprintln!("prism: not reporting: {err}");
            None
        }
    };
    run::run_check(
        client.as_ref(),
        &rest[0],
        &flags.severity,
        &flags.summary,
        &rest[2..],
        &mut std::io::stdout(),
        &mut std::io::stderr(),
    )
}

fn report_command(args: &[String], env: Env) -> i32 {
    let Some((mut report, output, rest)) = check_flags(args, &["output"]) else {
        return usage_error();
    };
    if rest.len() != 2 {
        return usage_error();
    }
    (report.check, report.status) = (rest[0].clone(), rest[1].clone());
    if let Some(path) = output.filter(|p| !p.is_empty()) {
        let data = if path == "-" {
            let mut data = Vec::new();
            match std::io::stdin().take(1 << 20).read_to_end(&mut data) {
                Ok(_) => Ok(data),
                Err(err) => Err(format!("read /dev/stdin: {}", client::errno_text(&err))),
            }
        } else {
            client::read_file(&path)
        };
        match data {
            Ok(data) => report.output = run::valid_utf8(&data),
            Err(err) => return fail(err),
        }
    }
    match Client::from_env(env).and_then(|c| c.report(&report)) {
        Ok(state) => {
            println!("{} on this machine: {state}", report.check);
            0
        }
        Err(err) => fail(err),
    }
}

/// `prism pair`: the link comes on stdin so it never shows in argv or logs.
fn pair_command(args: &[String], input: impl Read, env: Env) -> Result<String, String> {
    let mut raw = Vec::new();
    input
        .take(16 << 10)
        .read_to_end(&mut raw)
        .map_err(|err| client::errno_text(&err))?;
    let link = String::from_utf8_lossy(&raw).trim().to_string();
    if link.is_empty() || link.contains('\n') {
        return Err("pass the pairing link (or \"[url] <code>\") on one line on stdin".into());
    }
    let mut client = Client::admin_from_env(env)?;
    // A pairing code is single-use: never send it twice.
    client.retry.clear();
    client.timeout = Duration::from_secs(150);
    let mut body = json!({"machine": args[0], "link": link});
    if let Some(access) = args.get(1) {
        body["access"] = json!(access);
    }
    let out = client.json("POST", "/v1/t3/pair", Some(&body))?;
    let answer = text(&out, "answer");
    if out.get("paired").and_then(Value::as_bool) == Some(true) {
        Ok(answer)
    } else {
        Err(answer)
    }
}

/// `prism machines`: the worker's machines view as Go's `Machines` text.
fn machines_command(env: Env) -> Result<String, String> {
    let mut client = Client::admin_from_env(env)?;
    client.timeout = Duration::from_secs(180);
    Ok(render_machines(&client.json(
        "GET",
        "/v1/machines",
        None,
    )?))
}

fn render_machines(view: &Value) -> String {
    let runs_on = view["cluster"]["runsOn"].as_str().unwrap_or("");
    let runs_on = if runs_on.is_empty() { "?" } else { runs_on };
    let mut lines = vec![format!(
        "cluster · default target · selfhosted-platform, agents run on {runs_on}"
    )];
    for m in view["machines"].as_array().into_iter().flatten() {
        let mut parts = vec![text(m, "name"), text(m, "route")];
        let via = text(m, "via");
        if !via.is_empty() {
            parts.push(format!("via {via}"));
        }
        for key in ["state", "detail"] {
            let p = text(m, key);
            if !p.is_empty() {
                parts.push(p);
            }
        }
        if m["sshRunner"].as_bool() == Some(true) {
            parts.push("SSH runner".into());
        }
        let ids: Vec<String> = m["openAlerts"]
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(Value::as_i64)
            .filter(|n| *n > 0)
            .map(|n| format!("#{n}"))
            .collect();
        if !ids.is_empty() {
            parts.push(format!("open: {}", ids.join(", ")));
        }
        lines.push(parts.join(" · "));
    }
    lines.join("\n")
}

/// `prism account-limit <account> [--session N] [--weekly N] [--until T] | --clear`.
fn account_limit_command(args: &[String], env: Env) -> Result<String, String> {
    const USE: &str =
        "usage: prism account-limit <account> [--session N] [--weekly N] [--until TIME] | --clear";
    let (account, flags) = args.split_first().ok_or(USE)?;
    let mut body = serde_json::Map::new();
    let mut clear = false;
    let mut it = flags.iter();
    while let Some(flag) = it.next() {
        let mut value = || {
            it.next()
                .cloned()
                .ok_or_else(|| format!("{flag} needs a value"))
        };
        match flag.as_str() {
            "--clear" => clear = true,
            "--session" | "--weekly" => {
                let v = value()?;
                let n: f64 = v
                    .parse()
                    .map_err(|_| format!("{flag}: not a number: {v}"))?;
                let key = if flag == "--session" {
                    "sessionPercent"
                } else {
                    "weeklyPercent"
                };
                body.insert(key.into(), serde_json::json!(n));
            }
            "--until" => {
                body.insert("until".into(), Value::String(value()?));
            }
            _ => return Err(USE.into()),
        }
    }
    if clear == !body.is_empty() {
        return Err(USE.into());
    }
    let client = Client::admin_from_env(env)?;
    let path = format!("/v1/placement/account-limits/{}", url_segment(account));
    if clear {
        client.json("DELETE", &path, None)?;
        return Ok(format!("{account}: limit removed"));
    }
    let out = client.json("PUT", &path, Some(&Value::Object(body)))?;
    Ok(format!("{account}: {}", out["limit"]))
}

/// Percent-encodes a path segment, so any name reaches the worker, which
/// refuses invalid ones.
fn url_segment(s: &str) -> String {
    s.bytes()
        .map(|b| {
            if b.is_ascii_alphanumeric() || b"-._~".contains(&b) {
                (b as char).to_string()
            } else {
                format!("%{b:02X}")
            }
        })
        .collect()
}

#[cfg(test)]
mod tests;
