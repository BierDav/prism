//! Prism's placement decisions in ClickHouse
//! (`observability.placement_decisions`), so a review can tell why a profile
//! sent threads to an account. Prism keeps no history of its own: a ranking
//! lives only in each sync's answer.
//!
//! After each sync, the worker writes:
//! - `ranking`: a host's ranking of a profile, whenever its pick or any
//!   entry's state differs from the last one this worker wrote, with every
//!   entry's state, reason, readings, running threads and prediction;
//! - `move`: each move Prism asks for, once;
//! - `sync_failed`: a sync that failed, at most every 10 minutes per host.
//!
//! The rows go to `placement_log_url` (ClickHouse's HTTP interface) with the
//! telemetry credential (`telemetry_user`, PRISM_TELEMETRY_PASSWORD), which
//! may insert into `observability.*`. Writing never holds up a sync: rows are
//! sent in the background, and a failure is only logged.

use crate::config::Config;
use crate::t3::Secrets;
use chrono::DateTime;
use rocky_prism::{PlacementSelection, PlacementSyncResponse};
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};
use std::time::Duration;

const TABLE: &str = "observability.placement_decisions";
const FAILURE_EVERY_SECS: i64 = 10 * 60;
/// Moves remembered as written; the set starts over beyond this.
const MAX_REMEMBERED_MOVES: usize = 10_000;

pub struct PlacementLog {
    url: String,
    user: String,
    password: String,
    worker: String,
    http: reqwest::Client,
    seen: Mutex<Seen>,
}

#[derive(Default)]
struct Seen {
    /// (machine, profile) → the ranking's signature last written.
    rankings: HashMap<(String, String), String>,
    moves: HashSet<String>,
    /// machine → when its last failure was written.
    failures: HashMap<String, i64>,
}

impl PlacementLog {
    /// None when `placement_log_url` is not configured.
    pub fn from_config(cfg: &Config, secrets: &Secrets, worker: &str) -> Option<Arc<Self>> {
        let url = cfg.placement_log_url.trim_end_matches('/');
        if url.is_empty() {
            return None;
        }
        let http = reqwest::Client::builder()
            .timeout(Duration::from_secs(10))
            .connect_timeout(Duration::from_secs(3))
            .build()
            .ok()?;
        Some(Arc::new(Self {
            url: url.to_string(),
            user: cfg.telemetry_user.clone(),
            password: secrets(crate::telemetry::PASSWORD_SECRET).unwrap_or_default(),
            worker: worker.to_string(),
            http,
            seen: Mutex::default(),
        }))
    }

    /// Writes what a sync answered that this worker has not written yet.
    pub fn record_sync(self: &Arc<Self>, machine: &str, now: i64, resp: &PlacementSyncResponse) {
        let rows = {
            let mut seen = self.seen.lock().unwrap_or_else(|e| e.into_inner());
            sync_rows(&mut seen, &self.worker, machine, now, resp)
        };
        self.send(rows);
    }

    pub fn record_failure(self: &Arc<Self>, machine: &str, now: i64, error: &str) {
        let due = {
            let mut seen = self.seen.lock().unwrap_or_else(|e| e.into_inner());
            let last = seen.failures.get(machine).copied();
            let due = last.is_none_or(|at| now - at >= FAILURE_EVERY_SECS);
            if due {
                seen.failures.insert(machine.to_string(), now);
            }
            due
        };
        if due {
            let mut row = base_row(&self.worker, machine, now, "sync_failed");
            row["reason"] = json!(error);
            self.send(vec![row]);
        }
    }

    fn send(self: &Arc<Self>, rows: Vec<Value>) {
        if rows.is_empty() {
            return;
        }
        let this = Arc::clone(self);
        tokio::spawn(async move {
            let body: String = rows.iter().map(|r| format!("{r}\n")).collect();
            let result = this
                .http
                .post(&this.url)
                .query(&[("query", format!("INSERT INTO {TABLE} FORMAT JSONEachRow"))])
                .basic_auth(&this.user, Some(&this.password))
                .body(body)
                .send()
                .await;
            match result {
                Ok(resp) if resp.status().is_success() => {}
                Ok(resp) => {
                    let status = resp.status();
                    let text = resp.text().await.unwrap_or_default();
                    eprintln!(
                        "prism-worker: writing placement decisions failed: {status}: {}",
                        text.chars().take(200).collect::<String>()
                    );
                }
                Err(err) => eprintln!("prism-worker: writing placement decisions failed: {err}"),
            }
        });
    }
}

fn model(sel: Option<&PlacementSelection>) -> String {
    sel.map(|s| s.model.clone()).unwrap_or_default()
}

fn base_row(worker: &str, machine: &str, now: i64, kind: &str) -> Value {
    let at = DateTime::from_timestamp(now, 0)
        .map(|t| t.format("%Y-%m-%d %H:%M:%S").to_string())
        .unwrap_or_default();
    json!({
        "at": at,
        "kind": kind,
        "worker": worker,
        "machine": machine,
        "profile": "",
        "next": "",
        "thread_id": "",
        "from_model": "",
        "to_model": "",
        "reason": "",
        "ranked": "",
    })
}

/// The rows a sync adds, remembering them in `seen`.
fn sync_rows(
    seen: &mut Seen,
    worker: &str,
    machine: &str,
    now: i64,
    resp: &PlacementSyncResponse,
) -> Vec<Value> {
    let mut rows = Vec::new();
    for p in &resp.profiles {
        let next = model(p.next.as_ref());
        let signature = std::iter::once(next.clone())
            .chain(
                p.ranked
                    .iter()
                    .map(|d| format!("{}={}", d.selection.model, d.state)),
            )
            .collect::<Vec<_>>()
            .join(",");
        let key = (machine.to_string(), p.id.clone());
        if seen.rankings.get(&key) == Some(&signature) {
            continue;
        }
        seen.rankings.insert(key, signature);
        let mut row = base_row(worker, machine, now, "ranking");
        row["profile"] = json!(p.id);
        row["next"] = json!(next);
        row["ranked"] = json!(serde_json::to_string(&p.ranked).unwrap_or_default());
        rows.push(row);
    }
    if seen.moves.len() > MAX_REMEMBERED_MOVES {
        seen.moves.clear();
    }
    for m in &resp.moves {
        if !seen.moves.insert(m.id.clone()) {
            continue;
        }
        let kind = if m.from.model == m.to.model {
            "compact"
        } else {
            "move"
        };
        let mut row = base_row(worker, machine, now, kind);
        row["thread_id"] = json!(m.thread_id);
        row["from_model"] = json!(m.from.model);
        row["to_model"] = json!(m.to.model);
        row["reason"] = json!(m.reason);
        rows.push(row);
    }
    rows
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{Router, extract::Query, routing::post};
    use rocky_prism::{
        PlacementEntryDecision, PlacementPolicy, PlacementSyncActions, PlacementSyncMoveResp,
        PlacementSyncProfileResp,
    };
    use std::collections::BTreeMap;
    use tokio::net::TcpListener;

    fn sel(model: &str) -> PlacementSelection {
        PlacementSelection {
            instance_id: "claudeAgent".into(),
            model: model.into(),
            options: Vec::new(),
        }
    }

    fn entry(model: &str, state: &str) -> PlacementEntryDecision {
        PlacementEntryDecision {
            selection: sel(model),
            state: state.into(),
            reason: format!("{model} is {state}"),
            session: None,
            weekly: None,
            running_threads: 1,
            predicted_session_percent: None,
            weekly_slack_sessions: None,
            compact_before_limit: None,
        }
    }

    fn resp(alex: &str, moves: Vec<PlacementSyncMoveResp>) -> PlacementSyncResponse {
        let ranked = vec![
            entry("claude-alex/claude-opus-5-5", alex),
            entry("claude-1/claude-opus-5-5", "ok"),
        ];
        let next = ranked
            .iter()
            .find(|d| d.state == "ok")
            .map(|d| d.selection.clone());
        PlacementSyncResponse {
            v: 1,
            checked_at: String::new(),
            policy: PlacementPolicy::default(),
            profiles: vec![PlacementSyncProfileResp {
                id: "default".into(),
                label: "Default".into(),
                entries: Vec::new(),
                next,
                ranked,
            }],
            actions: PlacementSyncActions::default(),
            moves,
        }
    }

    #[test]
    fn rankings_are_written_when_they_change_and_moves_once() {
        let mut seen = Seen::default();
        let first = sync_rows(&mut seen, "w0", "david-mb", 100, &resp("ok", Vec::new()));
        assert_eq!(first.len(), 1);
        assert_eq!(first[0]["kind"], "ranking");
        assert_eq!(first[0]["next"], "claude-alex/claude-opus-5-5");
        assert_eq!(first[0]["at"], "1970-01-01 00:01:40");
        let ranked: Vec<Value> =
            serde_json::from_str(first[0]["ranked"].as_str().unwrap()).unwrap();
        assert_eq!(ranked[0]["reason"], "claude-alex/claude-opus-5-5 is ok");

        // Unchanged: nothing.
        assert!(sync_rows(&mut seen, "w0", "david-mb", 130, &resp("ok", Vec::new())).is_empty());
        // Another host's ranking is its own.
        assert_eq!(
            sync_rows(&mut seen, "w0", "david-tp", 130, &resp("ok", Vec::new())).len(),
            1
        );

        // claude-alex turns full: the pick changes, with a move.
        let mv = PlacementSyncMoveResp {
            id: "david-mb:thr-1:claude-1/claude-opus-5-5:160".into(),
            thread_id: "thr-1".into(),
            from: sel("claude-alex/claude-opus-5-5"),
            to: sel("claude-1/claude-opus-5-5"),
            reason: "full".into(),
        };
        let rows = sync_rows(
            &mut seen,
            "w0",
            "david-mb",
            160,
            &resp("full", vec![mv.clone()]),
        );
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0]["next"], "claude-1/claude-opus-5-5");
        assert_eq!(rows[1]["kind"], "move");
        assert_eq!(rows[1]["to_model"], "claude-1/claude-opus-5-5");
        // The same move asked again is not written again.
        assert!(sync_rows(&mut seen, "w0", "david-mb", 190, &resp("full", vec![mv])).is_empty());
    }

    #[tokio::test]
    async fn rows_reach_clickhouse_with_the_telemetry_credential() {
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<(String, String, String)>();
        let app = Router::new().route(
            "/",
            post(
                move |Query(q): Query<BTreeMap<String, String>>,
                      headers: axum::http::HeaderMap,
                      body: String| {
                    let tx = tx.clone();
                    async move {
                        let auth = headers
                            .get("authorization")
                            .and_then(|v| v.to_str().ok())
                            .unwrap_or_default()
                            .to_string();
                        let _ = tx.send((q.get("query").cloned().unwrap_or_default(), auth, body));
                        ""
                    }
                },
            ),
        );
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let addr = listener.local_addr().expect("addr");
        tokio::spawn(async move {
            let _ = axum::serve(listener, app).await;
        });
        let mut cfg = Config::parse("{}").expect("cfg");
        cfg.placement_log_url = format!("http://{addr}/");
        let secrets: Secrets = Arc::new(|_| Some("pw".into()));
        let log = PlacementLog::from_config(&cfg, &secrets, "w0").expect("configured");

        log.record_sync("david-mb", 100, &resp("ok", Vec::new()));
        let (query, auth, body) = rx.recv().await.expect("insert");
        assert_eq!(
            query,
            "INSERT INTO observability.placement_decisions FORMAT JSONEachRow"
        );
        assert!(auth.starts_with("Basic "), "{auth}");
        let row: Value = serde_json::from_str(body.lines().next().unwrap()).unwrap();
        assert_eq!(row["machine"], "david-mb");

        // Failures are written at most every 10 minutes per host.
        log.record_failure("david-mb", 200, "intent frame bytes exceed 131072");
        log.record_failure("david-mb", 300, "again");
        log.record_failure("david-mb", 200 + FAILURE_EVERY_SECS, "later");
        let (_, _, first) = rx.recv().await.expect("failure");
        let (_, _, second) = rx.recv().await.expect("failure");
        assert!(first.contains("intent frame"), "{first}");
        assert!(second.contains("later"), "{second}");

        assert!(PlacementLog::from_config(&Config::parse("{}").unwrap(), &secrets, "w0").is_none());
    }
}
