//! Account usage from ClickHouse (`ai_usage.account_limits`), which the AI
//! gateway's collector fills every few minutes for every account.
//!
//! Placement ranks with these readings instead of storing each host's in
//! Rocky: usage changes all the time, and every stored change replicated to
//! every worker, phone and Mac and stayed in Rocky's history, which Prism
//! can't compact yet. A model `<account>/<model>` uses its account's windows;
//! pooled names (`agy-pro`, `jetbrains`) are no single account and keep the
//! host's reading (`rocky_prism::PrismClient::sync_placement_with_usage`).
//!
//! The worker reads them from `usage_url` (ClickHouse's HTTP interface) as
//! `metrics_user` with PRISM_METRICS_PASSWORD, at most every
//! [`REFRESH_EVERY`]; a failed read keeps the last readings.

use crate::config::Config;
use crate::t3::Secrets;
use chrono::{DateTime, SecondsFormat};
use rocky_prism::{PlacementWindow, UsageByAccount};
use serde::Deserialize;
use std::sync::Arc;
use std::time::{Duration, Instant};

pub const REFRESH_EVERY: Duration = Duration::from_secs(60);
/// The gateway's collector reads each account every few minutes; older
/// readings are not worth ranking with (`rocky_prism::USAGE_FRESH_SECS`).
const QUERY: &str = "SELECT
    replaceRegexpOne(account_id, '\\\\.json$', '') AS account,
    window,
    argMax((used_percent, reset_at), observed_at) AS last,
    tupleElement(last, 1) AS used,
    toUnixTimestamp(tupleElement(last, 2)) AS reset,
    toUnixTimestamp(max(observed_at)) AS observed,
    any(provider) AS provider
FROM ai_usage.account_limits
WHERE observed_at > now() - INTERVAL 30 MINUTE
    AND status = 'ok'
    AND used_percent IS NOT NULL
    AND window IN ('five_hour', 'seven_day', 'primary_window', 'secondary_window')
GROUP BY account, window
FORMAT JSONEachRow";

pub struct UsageSource {
    url: String,
    user: String,
    password: Option<String>,
    http: reqwest::Client,
    last: tokio::sync::Mutex<Option<(Instant, Arc<UsageByAccount>)>>,
}

impl UsageSource {
    /// None when `usage_url` is not configured.
    pub fn from_config(cfg: &Config, secrets: &Secrets) -> Option<Arc<Self>> {
        let url = cfg.usage_url.trim_end_matches('/');
        if url.is_empty() {
            return None;
        }
        let http = reqwest::Client::builder()
            .timeout(Duration::from_secs(5))
            .connect_timeout(Duration::from_secs(3))
            .build()
            .ok()?;
        Some(Arc::new(Self {
            url: format!("{url}/"),
            user: cfg.metrics_user.clone(),
            password: secrets("PRISM_METRICS_PASSWORD").filter(|p| !p.is_empty()),
            http,
            last: tokio::sync::Mutex::new(None),
        }))
    }

    /// The latest readings, read again once [`REFRESH_EVERY`] passed. One
    /// caller reads at a time; a failed read keeps the last readings (none
    /// before the first success).
    pub async fn current(&self) -> Arc<UsageByAccount> {
        let mut last = self.last.lock().await;
        if let Some((at, usage)) = last.as_ref()
            && at.elapsed() < REFRESH_EVERY
        {
            return Arc::clone(usage);
        }
        match self.read().await {
            Ok(usage) => {
                let usage = Arc::new(usage);
                *last = Some((Instant::now(), Arc::clone(&usage)));
                usage
            }
            Err(err) => {
                eprintln!("prism-worker: reading account usage from ClickHouse failed: {err}");
                let kept = last
                    .as_ref()
                    .map(|(_, u)| Arc::clone(u))
                    .unwrap_or_default();
                // Try again at the next refresh, not on every sync.
                *last = Some((Instant::now(), Arc::clone(&kept)));
                kept
            }
        }
    }

    async fn read(&self) -> Result<UsageByAccount, String> {
        let mut req = self
            .http
            .post(&self.url)
            .query(&[("output_format_json_quote_64bit_integers", "0")])
            .body(QUERY);
        if let Some(password) = &self.password {
            req = req.basic_auth(&self.user, Some(password));
        }
        let resp = req.send().await.map_err(|err| err.to_string())?;
        let status = resp.status();
        let body = resp.text().await.map_err(|err| err.to_string())?;
        if !status.is_success() {
            return Err(format!(
                "HTTP {status}: {}",
                body.chars().take(200).collect::<String>()
            ));
        }
        Ok(parse_rows(&body))
    }
}

#[derive(Deserialize)]
struct Row {
    account: String,
    window: String,
    used: f64,
    reset: Option<i64>,
    observed: i64,
    #[serde(default)]
    provider: String,
}

/// An untouched window reports a rolling reset one full window after the
/// reading (Antigravity: a bucket nothing used yet); it has not started.
const ROLLING_RESET_SLACK_SECS: i64 = 120;

fn window_secs(window: &str) -> Option<i64> {
    match window {
        "five_hour" | "primary_window" => Some(5 * 3600),
        "seven_day" | "secondary_window" => Some(7 * 24 * 3600),
        _ => None,
    }
}

/// Each account's session window (`five_hour`, Codex's `primary_window`) and
/// weekly window (`seven_day`, Codex's `secondary_window`). Resets are rounded
/// to the minute: the gateway reports them a second apart from one read to
/// the next, and an unchanged reading should stay unchanged. A window at 0%
/// whose reset is one full window after the reading has not started and has
/// no reset.
pub fn parse_rows(body: &str) -> UsageByAccount {
    let mut out = UsageByAccount::new();
    for line in body.lines().filter(|l| !l.trim().is_empty()) {
        let Ok(row) = serde_json::from_str::<Row>(line) else {
            continue;
        };
        let untouched = |reset: &i64| {
            row.used == 0.0
                && window_secs(&row.window).is_some_and(|len| {
                    (reset - row.observed - len).abs() <= ROLLING_RESET_SLACK_SECS
                })
        };
        let window = PlacementWindow {
            used_percent: row.used,
            resets_at: row
                .reset
                .filter(|r| *r > 0 && !untouched(r))
                .and_then(|r| DateTime::from_timestamp((r + 30) / 60 * 60, 0))
                .map(|t| t.to_rfc3339_opts(SecondsFormat::Secs, true))
                .unwrap_or_default(),
        };
        let account = out.entry(row.account).or_default();
        account.observed_at = account.observed_at.max(row.observed);
        if account.provider.is_empty() {
            account.provider = row.provider;
        }
        match row.window.as_str() {
            "five_hour" | "primary_window" => account.session = Some(window),
            "seven_day" | "secondary_window" => account.weekly = Some(window),
            _ => {}
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{Router, routing::post};
    use tokio::net::TcpListener;

    #[test]
    fn rows_become_session_and_weekly_windows() {
        let body = r#"{"account":"claude-alex","window":"five_hour","used":39,"reset":1795599000,"observed":1795596000}
{"account":"claude-alex","window":"seven_day","used":66,"reset":1795701600,"observed":1795596010}
{"account":"codex-2","window":"primary_window","used":0,"reset":1795599734,"observed":1795596000}
{"account":"codex-2","window":"secondary_window","used":5,"reset":1796073990,"observed":1795596000}
{"account":"claude-1","window":"five_hour","used":0,"reset":null,"observed":1795596000}
"#;
        let usage = parse_rows(body);
        let alex = &usage["claude-alex"];
        assert_eq!(alex.observed_at, 1795596010);
        assert_eq!(alex.session.as_ref().unwrap().used_percent, 39.0);
        // 1795599000 is 09:30:00 UTC on 2026-11-25.
        assert_eq!(
            alex.session.as_ref().unwrap().resets_at,
            "2026-11-25T09:30:00Z"
        );
        assert_eq!(alex.weekly.as_ref().unwrap().used_percent, 66.0);
        // Codex's windows; a reset a few seconds off rounds to the minute.
        assert_eq!(
            usage["codex-2"].session.as_ref().unwrap().resets_at,
            "2026-11-25T09:42:00Z"
        );
        assert_eq!(usage["codex-2"].weekly.as_ref().unwrap().used_percent, 5.0);
        // A window without a reset (nothing used yet).
        assert_eq!(usage["claude-1"].session.as_ref().unwrap().resets_at, "");
        assert!(usage["claude-1"].weekly.is_none());
    }

    #[test]
    fn an_untouched_rolling_window_has_no_reset() {
        // Antigravity reports a bucket nothing used yet as resetting a full
        // window after each reading.
        let body = r#"{"account":"agy-1","window":"seven_day","used":0,"reset":1796200856,"observed":1795596056,"provider":"antigravity"}
{"account":"agy-david","window":"seven_day","used":0,"reset":1796200000,"observed":1795596056,"provider":"antigravity"}
{"account":"agy-natalie","window":"seven_day","used":1,"reset":1796200856,"observed":1795596056,"provider":"antigravity"}
"#;
        let usage = parse_rows(body);
        assert_eq!(usage["agy-1"].provider, "antigravity");
        assert_eq!(usage["agy-1"].weekly.as_ref().unwrap().resets_at, "");
        // A started window at 0% resets at a fixed time.
        assert_ne!(usage["agy-david"].weekly.as_ref().unwrap().resets_at, "");
        assert_ne!(usage["agy-natalie"].weekly.as_ref().unwrap().resets_at, "");
    }

    #[tokio::test]
    async fn reads_with_the_metrics_credential_and_keeps_readings_on_failure() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        let calls = Arc::new(AtomicUsize::new(0));
        let seen = Arc::clone(&calls);
        let app = Router::new().route(
            "/",
            post(move |headers: axum::http::HeaderMap, body: String| {
                let seen = Arc::clone(&seen);
                async move {
                    let n = seen.fetch_add(1, Ordering::SeqCst);
                    assert!(body.contains("ai_usage.account_limits"));
                    assert!(headers.contains_key("authorization"));
                    if n == 0 {
                        (
                            axum::http::StatusCode::OK,
                            r#"{"account":"codex-2","window":"secondary_window","used":5,"reset":1796073990,"observed":1795596000}"#
                                .to_string(),
                        )
                    } else {
                        (axum::http::StatusCode::INTERNAL_SERVER_ERROR, "down".into())
                    }
                }
            }),
        );
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let addr = listener.local_addr().expect("addr");
        tokio::spawn(async move {
            let _ = axum::serve(listener, app).await;
        });
        let mut cfg = Config::parse(r#"{"metrics_user": "telemetry_reader"}"#).expect("cfg");
        cfg.usage_url = format!("http://{addr}");
        let secrets: Secrets =
            Arc::new(|name| (name == "PRISM_METRICS_PASSWORD").then(|| "pw".into()));
        let source = UsageSource::from_config(&cfg, &secrets).expect("configured");

        let usage = source.current().await;
        assert_eq!(usage["codex-2"].weekly.as_ref().unwrap().used_percent, 5.0);
        // Cached: no second request within REFRESH_EVERY.
        source.current().await;
        assert_eq!(calls.load(Ordering::SeqCst), 1);

        // Past the refresh the read fails, and the last readings stay.
        *source.last.lock().await = Some((Instant::now() - REFRESH_EVERY, Arc::clone(&usage)));
        let kept = source.current().await;
        assert_eq!(calls.load(Ordering::SeqCst), 2);
        assert_eq!(kept, usage);

        assert!(UsageSource::from_config(&Config::parse("{}").unwrap(), &secrets).is_none());
    }
}
