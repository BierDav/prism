//! `prism run`: run a command with its output passed through and report it
//! as a check (Go's `RunCheck`).

use crate::client::{Client, EventReport, errno_text};
use std::io::{Read, Write};
use std::process::{Command, Stdio};
use std::sync::Mutex;
use std::sync::atomic::{AtomicI32, Ordering};

/// The last bytes of the output a failure report carries.
const TAIL: usize = 64 << 10;

/// The running child, for the signal handler.
static CHILD: AtomicI32 = AtomicI32::new(0);

extern "C" fn forward(signal: libc::c_int) {
    let pid = CHILD.load(Ordering::SeqCst);
    if pid > 0 {
        // SAFETY: kill is async-signal-safe.
        unsafe {
            libc::kill(pid, signal);
        }
    }
}

/// Passes SIGINT, SIGTERM and SIGHUP on to the child while it runs.
struct Forwarding {
    previous: Vec<(libc::c_int, libc::sigaction)>,
}

impl Forwarding {
    fn start() -> Self {
        let mut previous = Vec::new();
        for signal in [libc::SIGINT, libc::SIGTERM, libc::SIGHUP] {
            // SAFETY: a zeroed sigaction with a handler is a valid argument.
            unsafe {
                let mut action: libc::sigaction = std::mem::zeroed();
                action.sa_sigaction = forward as *const () as libc::sighandler_t;
                action.sa_flags = libc::SA_RESTART;
                libc::sigemptyset(&mut action.sa_mask);
                let mut old: libc::sigaction = std::mem::zeroed();
                if libc::sigaction(signal, &action, &mut old) == 0 {
                    previous.push((signal, old));
                }
            }
        }
        Self { previous }
    }
}

impl Drop for Forwarding {
    fn drop(&mut self) {
        CHILD.store(0, Ordering::SeqCst);
        for (signal, old) in &self.previous {
            // SAFETY: restores the action saved in `start`.
            unsafe {
                libc::sigaction(*signal, old, std::ptr::null_mut());
            }
        }
    }
}

/// Keeps the last bytes written to it.
#[derive(Default)]
struct Tail(Vec<u8>);

impl Tail {
    fn push(&mut self, data: &[u8]) {
        self.0.extend_from_slice(data);
        if self.0.len() > TAIL {
            self.0.drain(..self.0.len() - TAIL);
        }
    }
}

/// Go's `strings.ToValidUTF8(s, "")`: drops invalid byte sequences.
pub fn valid_utf8(data: &[u8]) -> String {
    data.utf8_chunks().map(|c| c.valid()).collect()
}

/// Quotes argv for display as a POSIX shell would read it back.
pub fn shell_quote(args: &[String]) -> String {
    args.iter()
        .map(|a| {
            let bare = !a.is_empty()
                && a.chars()
                    .all(|r| r.is_ascii_alphanumeric() || "/-_.=:,@".contains(r));
            if bare {
                a.clone()
            } else {
                format!("'{}'", a.replace('\'', r"'\''"))
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
}

/// Go's text for a command that cannot start.
fn start_error(name: &str, err: &std::io::Error) -> String {
    if !name.contains('/') && err.kind() == std::io::ErrorKind::NotFound {
        format!(
            "exec: {}: executable file not found in $PATH",
            crate::token::go_quote(name)
        )
    } else {
        format!("fork/exec {name}: {}", errno_text(err))
    }
}

fn pump(mut from: impl Read, to: &Mutex<&mut (dyn Write + Send)>, tail: &Mutex<Tail>) {
    let mut buf = [0u8; 32 << 10];
    loop {
        match from.read(&mut buf) {
            Ok(0) => return,
            Ok(n) => {
                let mut to = to.lock().expect("writer");
                let _ = to.write_all(&buf[..n]);
                let _ = to.flush();
                tail.lock().expect("tail").push(&buf[..n]);
            }
            Err(err) if err.kind() == std::io::ErrorKind::Interrupted => {}
            Err(_) => return,
        }
    }
}

/// Runs `argv`, reports the result as `check` and returns the command's exit
/// code. A failed report is only a warning on stderr: it never changes the
/// command's result.
pub fn run_check(
    client: Option<&Client>,
    check: &str,
    severity: &str,
    summary: &str,
    argv: &[String],
    stdout: &mut (dyn Write + Send),
    stderr: &mut (dyn Write + Send),
) -> i32 {
    let tail = Mutex::new(Tail::default());
    let stdout = Mutex::new(stdout);
    let stderr = Mutex::new(stderr);
    let forwarding = Forwarding::start();
    let spawned = Command::new(&argv[0])
        .args(&argv[1..])
        .stdin(Stdio::inherit())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn();
    let code = match spawned {
        Err(err) => {
            let message = format!(
                "prism: cannot start {}: {}\n",
                argv[0],
                start_error(&argv[0], &err)
            );
            tail.lock().expect("tail").push(message.as_bytes());
            let _ = stderr.lock().expect("stderr").write_all(message.as_bytes());
            127
        }
        Ok(mut child) => {
            CHILD.store(child.id() as i32, Ordering::SeqCst);
            let (out, err) = (child.stdout.take(), child.stderr.take());
            std::thread::scope(|s| {
                if let Some(out) = out {
                    s.spawn(|| pump(out, &stdout, &tail));
                }
                if let Some(err) = err {
                    s.spawn(|| pump(err, &stderr, &tail));
                }
            });
            match child.wait() {
                Ok(status) => status.code().unwrap_or(128 + 15), // killed by a signal
                Err(_) => 1,
            }
        }
    };
    drop(forwarding);
    let mut report = EventReport {
        check: check.into(),
        status: "ok".into(),
        severity: severity.into(),
        command: shell_quote(argv),
        ..EventReport::default()
    };
    if code != 0 {
        report.status = "failed".into();
        report.summary = summary.into();
        report.output = valid_utf8(&tail.lock().expect("tail").0);
        report.exit_code = Some(code);
    }
    let Some(client) = client else {
        return code;
    };
    if let Err(err) = client.report(&report) {
        let _ = writeln!(
            stderr.lock().expect("stderr"),
            "prism: could not report {check}: {err}"
        );
    }
    code
}

#[cfg(test)]
mod tests {
    use super::*;

    fn s(v: &[&str]) -> Vec<String> {
        v.iter().map(|x| x.to_string()).collect()
    }

    #[test]
    fn quoting_is_gos() {
        assert_eq!(
            shell_quote(&s(&[
                "sh",
                "-c",
                "echo updating; echo 'Error: it broke' >&2; exit 3"
            ])),
            r#"sh -c 'echo updating; echo '\''Error: it broke'\'' >&2; exit 3'"#
        );
        assert_eq!(
            shell_quote(&s(&["/usr/bin/env", "A=b,c:d@e", "", "ü"])),
            "/usr/bin/env A=b,c:d@e '' 'ü'"
        );
    }

    #[test]
    fn the_tail_keeps_the_last_bytes_as_valid_utf8() {
        let mut t = Tail::default();
        t.push(&vec![b'a'; TAIL]);
        t.push("€nd".as_bytes());
        assert_eq!(t.0.len(), TAIL);
        // The cut went through the euro sign's first byte.
        let text = valid_utf8(&t.0);
        assert!(text.ends_with("€nd"), "{}", &text[text.len() - 10..]);
        assert_eq!(valid_utf8(b"a\xff\xfeb"), "ab");
    }

    #[test]
    fn exit_codes_without_a_server() {
        let (mut out, mut err) = (Vec::new(), Vec::new());
        let code = run_check(
            None,
            "x",
            "",
            "",
            &s(&[
                "sh",
                "-c",
                "echo updating; echo 'Error: it broke' >&2; exit 3",
            ]),
            &mut out,
            &mut err,
        );
        assert_eq!(code, 3);
        assert_eq!(out, b"updating\n");
        assert_eq!(err, b"Error: it broke\n");
        let code = run_check(
            None,
            "x",
            "",
            "",
            &s(&["/nonexistent/cmd"]),
            &mut out,
            &mut err,
        );
        assert_eq!(code, 127);
        assert!(
            String::from_utf8_lossy(&err).contains(
                "prism: cannot start /nonexistent/cmd: fork/exec /nonexistent/cmd: no such file or directory"
            ),
            "{}",
            String::from_utf8_lossy(&err)
        );
        let code = run_check(
            None,
            "x",
            "",
            "",
            &s(&["sh", "-c", "kill -TERM $$"]),
            &mut out,
            &mut err,
        );
        assert_eq!(code, 143);
    }
}
