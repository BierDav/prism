//! Machine events (`POST /v1/events`), ported from the Go server's `events.go`.
//!
//! A failed check from `prism run <check> -- ...` opens or updates a
//! `HostCheckFailed` alert in the `alerts` table (`fingerprint =
//! "host-event:<machine>:<check>"`). The next `"ok"` report resolves it.

use crate::alerts::{ALERT_OPEN, AmAlert, EVENT_ALERT_NAME, first_line, truncate};
use crate::client::{PrismClient, storage_err};
use crate::types::PrismError;
use chrono::{DateTime, SecondsFormat, Utc};
use rocky_sdk::{SqlValue, params};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const MAX_EVENT_OUTPUT: usize = 4000;
pub const MAX_FIRING_EVENTS_PER_MACHINE: usize = 50;
const EVENT_SOURCE: &str = "prism";

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct HostEventReport {
    #[serde(default)]
    pub check: String,
    #[serde(default)]
    pub status: String,
    #[serde(default)]
    pub summary: String,
    #[serde(default)]
    pub output: String,
    #[serde(default)]
    pub command: String,
    #[serde(default)]
    pub exit_code: Option<i32>,
    #[serde(default)]
    pub severity: String,
}

#[derive(Debug, thiserror::Error)]
pub enum HostEventError {
    #[error("{0}")]
    Invalid(String),
    #[error("too many open events")]
    TooManyEvents,
    #[error(transparent)]
    Storage(#[from] PrismError),
}

pub fn is_valid_check_name(name: &str) -> bool {
    let bytes = name.as_bytes();
    if bytes.is_empty() || bytes.len() > 64 {
        return false;
    }
    if !(bytes[0].is_ascii_lowercase() || bytes[0].is_ascii_digit()) {
        return false;
    }
    bytes.iter().all(|&b| {
        b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'.' || b == b'_' || b == b'-'
    })
}

pub fn host_event_fingerprint(machine: &str, check: &str) -> String {
    format!("host-event:{machine}:{check}")
}

fn truncate_tail(s: &str, max_chars: usize) -> String {
    let chars: Vec<char> = s.chars().collect();
    if chars.len() <= max_chars {
        return s.to_string();
    }
    let tail: String = chars[chars.len() - max_chars..].iter().collect();
    format!("…{tail}")
}

impl HostEventReport {
    pub fn normalize(&mut self) -> Result<(), HostEventError> {
        self.check = self.check.trim().to_ascii_lowercase();
        if !is_valid_check_name(&self.check) {
            return Err(HostEventError::Invalid(
                "check must match ^[a-z0-9][a-z0-9._-]{0,63}$".into(),
            ));
        }
        match self.status.as_str() {
            "failed" | "ok" => {}
            _ => {
                return Err(HostEventError::Invalid(
                    r#"status must be "failed" or "ok""#.into(),
                ));
            }
        }
        match self.severity.as_str() {
            "" => self.severity = "warning".into(),
            "info" | "warning" | "critical" => {}
            _ => {
                return Err(HostEventError::Invalid(
                    "severity must be info, warning or critical".into(),
                ));
            }
        }
        self.summary = truncate(first_line(&self.summary), 300);
        self.command = truncate(&self.command, 500);
        self.output = truncate_tail(self.output.trim_end_matches('\n'), MAX_EVENT_OUTPUT);
        Ok(())
    }
}

fn build_event_payload(
    machine: &str,
    report: &HostEventReport,
    starts_at: &str,
) -> (String, String) {
    let summary = if !report.summary.is_empty() {
        report.summary.clone()
    } else {
        let mut s = format!("{} failed on {machine}", report.check);
        if let Some(code) = report.exit_code {
            s.push_str(&format!(" (exit code {code})"));
        }
        s
    };
    let mut labels = BTreeMap::new();
    labels.insert("alertname".into(), EVENT_ALERT_NAME.into());
    labels.insert("machine".into(), machine.into());
    labels.insert("check".into(), report.check.clone());
    labels.insert("severity".into(), report.severity.clone());
    labels.insert("source".into(), EVENT_SOURCE.into());

    let mut annotations = BTreeMap::new();
    annotations.insert("summary".into(), summary.clone());
    if !report.output.is_empty() {
        annotations.insert("output".into(), report.output.clone());
    }
    if !report.command.is_empty() {
        annotations.insert("command".into(), report.command.clone());
    }

    let am = AmAlert {
        status: "firing".into(),
        labels: Some(labels),
        annotations,
        starts_at: starts_at.into(),
        ends_at: String::new(),
    };
    let payload = serde_json::to_string(&am).unwrap_or_default();
    (summary, payload)
}

impl PrismClient {
    /// Records one machine check report (`POST /v1/events`) and returns the
    /// resulting state (`"firing"`, `"resolved"`, or `"ok"`).
    pub async fn record_host_event(
        &self,
        machine: &str,
        mut report: HostEventReport,
        now: i64,
    ) -> Result<String, HostEventError> {
        report.normalize()?;
        let _events = self.events_lock.lock().await;
        let fp = host_event_fingerprint(machine, &report.check);
        let open = self.list_alerts(crate::alerts::AlertFilter::Open).await?;
        // Oldest first. Two workers taking reports of one check before they
        // synced (a retried `prism run`) can each open an episode; the
        // oldest stays and the others end.
        let mut episodes: Vec<_> = open.iter().filter(|a| a.fingerprint == fp).collect();
        episodes.sort_by_key(|a| (a.starts_at, a.key.clone()));
        let existing = episodes.first().copied();

        if report.status == "ok" {
            if episodes.is_empty() {
                return Ok("ok".into());
            }
            for current in &episodes {
                self.resolve_host_event(&current.key, now).await?;
            }
            return Ok("resolved".into());
        }

        // status == "failed"
        if let Some(current) = existing {
            for duplicate in &episodes[1..] {
                self.resolve_host_event(&duplicate.key, now).await?;
            }
            let starts_at = DateTime::<Utc>::from_timestamp(current.starts_at, 0)
                .unwrap_or_default()
                .to_rfc3339_opts(SecondsFormat::Secs, true);
            let (summary, payload) = build_event_payload(machine, &report, &starts_at);
            let _guard = self.alerts_lock.lock().await;
            let mut tx = self.db.transaction().await.map_err(storage_err)?;
            tx.execute(
                "UPDATE alerts SET payload = ?, severity = ?, summary = ? WHERE key = ?",
                params![payload, report.severity, summary, current.key.clone()],
            )
            .await
            .map_err(storage_err)?;
            tx.commit().await.map_err(storage_err)?;
            return Ok("firing".into());
        }

        // Count open machine events for this machine.
        let firing_count = open
            .iter()
            .filter(|a| a.host == machine && a.fingerprint.starts_with("host-event:"))
            .count();
        if firing_count >= MAX_FIRING_EVENTS_PER_MACHINE {
            return Err(HostEventError::TooManyEvents);
        }

        let starts_at = DateTime::<Utc>::from_timestamp(now, 0)
            .unwrap_or_default()
            .to_rfc3339_opts(SecondsFormat::Secs, true);
        let key = format!("{fp}:{starts_at}");
        let (summary, payload) = build_event_payload(machine, &report, &starts_at);
        let _guard = self.alerts_lock.lock().await;
        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        let id = tx.next_id("alerts", vec![]).await.map_err(storage_err)?;
        tx.execute(
            "INSERT INTO alerts (id, key, fingerprint, episode, payload, name, check_name, severity, machine, summary, state, received_at, resolved_at, missing_since)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
            params![
                id,
                key,
                fp,
                starts_at,
                payload,
                EVENT_ALERT_NAME,
                report.check,
                report.severity,
                machine,
                summary,
                ALERT_OPEN,
                now,
                SqlValue::Null,
            ],
        )
        .await
        .map_err(storage_err)?;
        tx.commit().await.map_err(storage_err)?;
        Ok("firing".into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::alerts::AlertFilter;

    fn failed() -> HostEventReport {
        HostEventReport {
            check: "disk".into(),
            status: "failed".into(),
            ..HostEventReport::default()
        }
    }

    /// What a second worker writes for the same check before it saw the first's episode.
    async fn second_episode(client: &PrismClient, now: i64) {
        let fp = host_event_fingerprint("host-a", "disk");
        let starts_at = DateTime::<Utc>::from_timestamp(now, 0)
            .unwrap()
            .to_rfc3339_opts(SecondsFormat::Secs, true);
        let mut report = failed();
        report.normalize().unwrap();
        let (summary, payload) = build_event_payload("host-a", &report, &starts_at);
        let mut tx = client.db.transaction().await.unwrap();
        let id = tx.next_id("alerts", vec![]).await.unwrap();
        tx.execute(
            "INSERT INTO alerts (id, key, fingerprint, episode, payload, name, check_name, severity, machine, summary, state, received_at, resolved_at, missing_since)
             VALUES (?, ?, ?, ?, ?, ?, 'disk', 'warning', 'host-a', ?, ?, ?, ?, 0)",
            params![id, format!("{fp}:{starts_at}"), fp, starts_at, payload, EVENT_ALERT_NAME, summary, ALERT_OPEN, now, SqlValue::Null],
        )
        .await
        .unwrap();
        tx.commit().await.unwrap();
    }

    async fn open_keys(client: &PrismClient) -> Vec<String> {
        client
            .list_alerts(AlertFilter::Open)
            .await
            .unwrap()
            .into_iter()
            .map(|a| a.key)
            .collect()
    }

    #[tokio::test]
    async fn duplicate_episodes_from_two_workers_end_together() {
        let dir = tempfile::tempdir().unwrap();
        let client = PrismClient::open_or_create(dir.path().display().to_string(), None)
            .await
            .unwrap();
        const T: i64 = 1_800_000_000;
        client
            .record_host_event("host-a", failed(), T)
            .await
            .unwrap();
        second_episode(&client, T + 3).await;
        assert_eq!(open_keys(&client).await.len(), 2);
        let ok = HostEventReport {
            status: "ok".into(),
            ..failed()
        };
        assert_eq!(
            client
                .record_host_event("host-a", ok, T + 10)
                .await
                .unwrap(),
            "resolved"
        );
        assert!(open_keys(&client).await.is_empty(), "no episode stays open");

        // A failure with two open episodes keeps the oldest.
        client
            .record_host_event("host-a", failed(), T + 20)
            .await
            .unwrap();
        second_episode(&client, T + 23).await;
        client
            .record_host_event("host-a", failed(), T + 30)
            .await
            .unwrap();
        let open = open_keys(&client).await;
        assert_eq!(open.len(), 1);
        assert!(open[0].ends_with(":2027-01-15T08:00:20Z"), "{open:?}");
    }
}
