//! Alerts, ported from the Go server (`alerts.go`,
//! `alert_catalog.go`, `push.go` and the alert half of `actions.go`).
//!
//! Every worker accepts Alertmanager's webhook and writes episode rows. The
//! leader numbers new episodes, posts their ntfy wake-ups and runs the
//! owner's fix requests, which any device writes as `alert_actions` rows.
//!
//! An alert shows a problem that is still there: it ends only when its
//! condition does (the rule recovers, the CronJob reports it resolved, the
//! machine's check passes, or Alertmanager no longer has it). Nothing
//! dismisses or resolves one by hand.
//!
//! Several workers may write the same episode, so reads keep one row per key:
//! the one with the smallest Rocky id.

use crate::client::{PrismClient, hex_string, id_bytes, storage_err};
use crate::types::PrismError;
use chrono::{DateTime, Datelike};
use rocky_sdk::{SqlValue, params};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Deserializer, Serialize};
use serde_json::value::RawValue;
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, BTreeSet};

pub const ALERT_OPEN: &str = "open";
pub const ALERT_RESOLVED: &str = "resolved";
/// An episode the owner dismissed, before alerts could only end by
/// themselves. Older rows keep it; nothing writes it any more.
pub const ALERT_DISMISSED: &str = "dismissed";
/// Alertmanager no longer had the alert (reconciliation).
pub const ALERT_GONE: &str = "gone";

/// What a device's claim on its own request starts with (`device:<name>`):
/// it runs the request directly in T3 while the workers are unreachable, and
/// no worker reclaims it as stale.
pub const DEVICE_CLAIM_PREFIX: &str = "device:";

pub const ACTION_FIX: &str = "fix";

/// The alert name of a machine event (`prism run`), a check's alert.
pub const EVENT_ALERT_NAME: &str = "HostCheckFailed";
const EVENT_SOURCE: &str = "prism";

/// The longest instruction a fix request carries.
pub const MAX_INSTRUCTION_CHARS: usize = 10_000;
const MAX_REQUEST_ID_BYTES: usize = 200;

/// A push undelivered for this long is dropped instead of flooding the phone.
pub const PUSH_STALE_SECS: i64 = 60 * 60;
/// ntfy keeps a week; the leader remembers posted keys a bit longer.
pub const PUSH_KEEP_SECS: i64 = 8 * 24 * 60 * 60;

/// Go decodes JSON null into a zero value; serde refuses it without this.
fn nullable<'de, D, T>(deserializer: D) -> Result<T, D::Error>
where
    D: Deserializer<'de>,
    T: Default + DeserializeOwned,
{
    Ok(Option::<T>::deserialize(deserializer)?.unwrap_or_default())
}

/// One alert of Alertmanager's webhook, or a stored payload.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct AmAlert {
    #[serde(default, deserialize_with = "nullable")]
    pub status: String,
    /// `None` when absent or null, which the webhook refuses.
    #[serde(default)]
    pub labels: Option<BTreeMap<String, String>>,
    #[serde(default, deserialize_with = "nullable")]
    pub annotations: BTreeMap<String, String>,
    #[serde(default, rename = "startsAt", deserialize_with = "nullable")]
    pub starts_at: String,
    #[serde(default, rename = "endsAt", deserialize_with = "nullable")]
    pub ends_at: String,
}

impl AmAlert {
    pub fn label(&self, name: &str) -> &str {
        self.labels
            .as_ref()
            .and_then(|l| l.get(name))
            .map(String::as_str)
            .unwrap_or("")
    }

    pub fn annotation(&self, name: &str) -> &str {
        self.annotations.get(name).map(String::as_str).unwrap_or("")
    }

    /// The `alertname` label, or "Alert".
    pub fn name(&self) -> &str {
        match self.label("alertname") {
            "" => "Alert",
            name => name,
        }
    }

    /// Whether this is a machine event (a failed `prism run` check).
    pub fn is_host_event(&self) -> bool {
        let source = self.label("source");
        source == EVENT_SOURCE
            && self.label("alertname") == EVENT_ALERT_NAME
            && !self.label("machine").is_empty()
            && !self.label("check").is_empty()
    }

    fn parse(payload: &str) -> Self {
        serde_json::from_str(payload).unwrap_or_default()
    }
}

/// Why a webhook was refused: Alertmanager gets a 400 and does not retry.
#[derive(Debug, thiserror::Error)]
pub enum AlertWebhookError {
    #[error("{0}")]
    Invalid(&'static str),
    #[error(transparent)]
    Storage(#[from] PrismError),
}

pub fn sha256_hex(value: &str) -> String {
    hex_string(&Sha256::digest(value.as_bytes()))
}

/// Identifies an alert by its labels: the SHA-256 of their compact JSON with
/// sorted keys and non-ASCII escaped, exactly as the Go server (and the
/// Python bot before it) computed it.
pub fn label_fingerprint(labels: &BTreeMap<String, String>) -> String {
    let mut s = String::from("{");
    for (i, (k, v)) in labels.iter().enumerate() {
        if i > 0 {
            s.push(',');
        }
        py_string(&mut s, k);
        s.push(':');
        py_string(&mut s, v);
    }
    s.push('}');
    sha256_hex(&s)
}

/// Writes `value` as a JSON string with every non-ASCII and control character
/// escaped, as Python's `json.dumps(ensure_ascii=True)` does.
fn py_string(out: &mut String, value: &str) {
    use std::fmt::Write;
    out.push('"');
    for c in value.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '\u{8}' => out.push_str("\\b"),
            '\u{c}' => out.push_str("\\f"),
            c if (c as u32) < 0x20 || ((c as u32) >= 0x7f && (c as u32) < 0x10000) => {
                let _ = write!(out, "\\u{:04x}", c as u32);
            }
            c if (c as u32) >= 0x10000 => {
                let mut units = [0u16; 2];
                for unit in c.encode_utf16(&mut units) {
                    let _ = write!(out, "\\u{:04x}", unit);
                }
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

/// An episode's key: its fingerprint and Alertmanager's `startsAt`.
pub fn alert_key(fingerprint: &str, episode: &str) -> String {
    sha256_hex(&format!("{fingerprint}\n{episode}"))[..32].to_string()
}

/// Parses an RFC 3339 time; times before 2000 (Go's zero time) are absent.
pub fn parse_time(value: &str) -> Option<i64> {
    let t = DateTime::parse_from_rfc3339(value).ok()?;
    (t.year() >= 2000).then(|| t.timestamp())
}

fn parse_time_nanos(value: &str) -> Option<i128> {
    let t = DateTime::parse_from_rfc3339(value).ok()?;
    parse_time(value)?;
    Some(t.timestamp() as i128 * 1_000_000_000 + t.timestamp_subsec_nanos() as i128)
}

/// `#42`, or "" for an unnumbered episode.
pub fn alert_id(number: i64) -> String {
    if number <= 0 {
        String::new()
    } else {
        format!("#{number}")
    }
}

/// Puts an alert's ID in front of its name.
pub fn with_id(number: i64, name: &str) -> String {
    match alert_id(number) {
        id if id.is_empty() => name.to_string(),
        id => format!("{id} {name}"),
    }
}

pub fn first_line(s: &str) -> &str {
    let s = s.trim();
    s.split('\n').next().unwrap_or("")
}

pub fn truncate(s: &str, n: usize) -> String {
    s.chars().take(n).collect()
}

// --- The catalog (alert_catalog.go) -------------------------------------

pub const CATEGORY_ORDER: &[&str] = &[
    "Machines",
    "Cluster nodes",
    "etcd and DNS",
    "Workloads",
    "Storage",
    "Databases",
    "Backups",
    "Public websites",
    "Monitoring",
    "CI and updates",
    "Other",
];

const CATALOG: &[(&str, &str, &str)] = &[
    (
        "NodeNotReady",
        "Cluster nodes",
        "A cluster node is down or not ready",
    ),
    (
        "NodeMemoryLow",
        "Cluster nodes",
        "A node is running low on memory",
    ),
    (
        "RootDiskLow",
        "Cluster nodes",
        "A node's root disk is under 20% free",
    ),
    (
        "RootDiskCritical",
        "Cluster nodes",
        "A node's root disk is nearly full; pods get evicted",
    ),
    (
        "EtcdVoterUnavailable",
        "etcd and DNS",
        "An etcd member on Oracle is unreachable",
    ),
    ("EtcdNoLeader", "etcd and DNS", "etcd has no leader"),
    ("EtcdSlowFsync", "etcd and DNS", "etcd disk writes are slow"),
    (
        "DNSRedundancyLost",
        "etcd and DNS",
        "Fewer than two CoreDNS replicas are up",
    ),
    (
        "DeploymentDown",
        "Workloads",
        "A deployment has no running replicas",
    ),
    (
        "DeploymentUnavailable",
        "Workloads",
        "A deployment is missing replicas",
    ),
    ("PodCrashLooping", "Workloads", "A container keeps crashing"),
    ("BridgeUnavailable", "Workloads", "A Matrix bridge is down"),
    (
        "PersistentVolumeLowSpace",
        "Storage",
        "A volume is under 20% free",
    ),
    (
        "PersistentVolumeCriticalSpace",
        "Storage",
        "A volume is under 10% free",
    ),
    (
        "PersistentVolumeMetricsMissing",
        "Storage",
        "A volume reports no usage",
    ),
    ("SeaweedDiskError", "Storage", "SeaweedFS hit a disk error"),
    (
        "SeaweedS3ServerErrors",
        "Storage",
        "SeaweedFS S3 fails over 5% of requests",
    ),
    (
        "PostgresNoStreamingReplica",
        "Databases",
        "PostgreSQL has no replica",
    ),
    (
        "PostgresConnectionsHigh",
        "Databases",
        "PostgreSQL is near its connection limit",
    ),
    (
        "PostgresBlockedClients",
        "Databases",
        "PostgreSQL clients are stuck waiting",
    ),
    (
        "PostgresMetricsMissing",
        "Databases",
        "PostgreSQL health is not being collected",
    ),
    (
        "ClickHouseReplicaReadonly",
        "Databases",
        "A ClickHouse replica went read-only",
    ),
    (
        "BackupCheckStale",
        "Backups",
        "Nothing has verified the backups for an hour",
    ),
    (
        "BackupCheckError",
        "Backups",
        "A backup check could not run",
    ),
    (
        "BackupDiskNotMounted",
        "Backups",
        "The backup disk is not mounted",
    ),
    (
        "BackupDiskLow",
        "Backups",
        "The backup disk is under 15% free",
    ),
    (
        "BackupDiskCritical",
        "Backups",
        "The backup disk is almost full",
    ),
    (
        "BackupAgentUnavailable",
        "Backups",
        "A node's backup agent is not running",
    ),
    (
        "BackupCronJobNeverSucceeded",
        "Backups",
        "A backup job has never succeeded",
    ),
    (
        "LocalVolumeMaintenanceStale",
        "Backups",
        "The weekly backup prune and check is overdue",
    ),
    (
        "PostgresWALArchiveStale",
        "Backups",
        "PostgreSQL WAL archiving is behind",
    ),
    (
        "PostgresBaseBackupStale",
        "Backups",
        "The PostgreSQL base backup is over a day old",
    ),
    (
        "SeaweedFSMirrorStale",
        "Backups",
        "The SeaweedFS backup mirror is behind",
    ),
    (
        "GitMirrorStale",
        "Backups",
        "The GitHub mirror of the deploy repositories is stale",
    ),
    (
        "PublicEndpointDown",
        "Public websites",
        "A public website is down",
    ),
    (
        "PublicProbeMissing",
        "Public websites",
        "A public website is not being checked",
    ),
    (
        "AlertEvaluationFailing",
        "Monitoring",
        "Alert rules are not being evaluated",
    ),
    (
        "ObservabilityCheckStale",
        "Monitoring",
        "The alerting watchdog stopped running",
    ),
    (
        "CollectorMetricsMissing",
        "Monitoring",
        "A node stopped sending metrics",
    ),
    (
        "MetricsDeliveryFailed",
        "Monitoring",
        "Metrics are being dropped",
    ),
    (
        "MetricsDeliveryBacklog",
        "Monitoring",
        "Metrics are piling up undelivered",
    ),
    (
        "LogCollectorMissing",
        "Monitoring",
        "A node's log collector is not running",
    ),
    ("LogDeliveryFailed", "Monitoring", "Logs are being dropped"),
    (
        "ForkWorkflowFailed",
        "CI and updates",
        "A fork's CI workflow failed",
    ),
    (
        "ForkSyncStale",
        "CI and updates",
        "A fork has not synced from upstream",
    ),
    (
        "ForkUpstreamSyncConflict",
        "CI and updates",
        "A fork conflicts with upstream",
    ),
    (
        "ForkBuildCheckError",
        "CI and updates",
        "The fork build check could not check a repository",
    ),
    (
        "ForkBuildCheckStale",
        "CI and updates",
        "The fork build check stopped running",
    ),
    (
        "RenovatePRCIFailed",
        "CI and updates",
        "A Renovate PR's CI is failing",
    ),
    (
        "RenovateCIMonitorUnavailable",
        "CI and updates",
        "Renovate CI monitoring is down",
    ),
];

const MACHINE_CHECKS: &[(&str, &str)] = &[
    (
        "disk-space",
        "Under 30 GB free on a dev machine (often Rust build caches)",
    ),
    ("brew-update", "The nightly Homebrew update failed on a Mac"),
    (
        "bun-clients",
        "Installing or updating the agent CLIs failed",
    ),
    (
        "forge-ssh-keys",
        "Syncing the dev hosts' SSH keys to the forges failed",
    ),
];

/// An alert's category and brief. For a machine check the check name stands
/// in for the alert name; `summary` is the fallback brief.
pub fn classify(alertname: &str, check: &str, summary: &str) -> (String, String) {
    if alertname == EVENT_ALERT_NAME {
        let brief = MACHINE_CHECKS
            .iter()
            .find(|(c, _)| *c == check)
            .map(|(_, b)| *b)
            .unwrap_or("");
        return ("Machines".into(), brief.into());
    }
    if let Some((_, category, brief)) = CATALOG.iter().find(|(n, _, _)| *n == alertname) {
        return ((*category).into(), (*brief).into());
    }
    let summary = if summary.contains("{{") { "" } else { summary };
    ("Other".into(), first_line(summary).into())
}

// --- Views ----------------------------------------------------------------

/// An alert episode as the phone and the Mac show it (docs/prism-api.md,
/// "The Alert object"), with its key and the requests on it.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AlertView {
    /// The owner's `#42`; 0 until the leader numbers it.
    pub id: i64,
    /// What requests name the episode by.
    pub key: String,
    pub fingerprint: String,
    pub kind: String,
    pub name: String,
    pub alertname: String,
    pub category: String,
    pub brief: String,
    pub severity: String,
    pub host: String,
    pub check: String,
    pub summary: String,
    pub description: String,
    pub command: String,
    pub output: String,
    pub labels: BTreeMap<String, String>,
    pub state: String,
    pub starts_at: i64,
    pub created_at: i64,
    pub updated_at: i64,
    pub ended_at: i64,
    pub actions: Vec<AlertActionView>,
}

/// A request on an alert and how far it got.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AlertActionView {
    pub request_id: String,
    /// The alert's key.
    pub alert: String,
    pub kind: String,
    pub instruction: String,
    pub requested_by: String,
    pub requested_at: i64,
    pub claimed_by: Option<String>,
    pub claimed_at: Option<i64>,
    pub done_at: Option<i64>,
    /// The launched fix thread, when there is one.
    pub thread_id: String,
    pub link: String,
    /// Shown to the owner when the request failed.
    pub error: String,
}

/// A request a device writes: fix one alert.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AlertActionRequest {
    /// Retrying with the same id returns the same request. Empty: Rocky picks one.
    #[serde(default)]
    pub request_id: String,
    /// The alert's key.
    pub alert: String,
    pub kind: String,
    #[serde(default)]
    pub instruction: String,
    pub requested_by: String,
}

/// What running a request came to.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct AlertActionOutcome {
    pub thread_id: String,
    pub link: String,
    pub error: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AlertFilter {
    /// Every open alert.
    Open,
    /// Every open alert and those that ended at or after the time.
    Recent {
        since: i64,
    },
    All,
}

/// One stored episode.
#[derive(Clone, Debug, PartialEq)]
pub struct AlertRow {
    pub key: String,
    pub fingerprint: String,
    pub episode: String,
    pub payload: String,
    pub state: String,
    pub received_at: i64,
    pub resolved_at: Option<i64>,
    pub missing_since: i64,
    pub number: i64,
    row_id: Vec<u8>,
}

impl AlertRow {
    pub fn alert(&self) -> AmAlert {
        AmAlert::parse(&self.payload)
    }

    pub fn is_open(&self) -> bool {
        self.state == ALERT_OPEN
    }

    pub fn view(&self, actions: Vec<AlertActionView>) -> AlertView {
        let alert = self.alert();
        let mut v = AlertView {
            id: self.number,
            key: self.key.clone(),
            fingerprint: self.fingerprint.clone(),
            kind: "alert".into(),
            name: alert.name().into(),
            alertname: alert.label("alertname").into(),
            category: String::new(),
            brief: String::new(),
            severity: alert.label("severity").into(),
            host: alert.label("machine").into(),
            check: String::new(),
            summary: alert.annotation("summary").into(),
            description: alert.annotation("description").into(),
            command: alert.annotation("command").into(),
            output: alert.annotation("output").into(),
            labels: alert.labels.clone().unwrap_or_default(),
            state: self.state.clone(),
            starts_at: parse_time(&alert.starts_at).unwrap_or(0),
            created_at: self.received_at,
            updated_at: 0,
            ended_at: if self.is_open() {
                0
            } else {
                self.resolved_at.unwrap_or(0)
            },
            actions,
        };
        if alert.is_host_event() {
            v.kind = "machine_event".into();
            v.check = alert.label("check").into();
            v.name = v.check.clone();
        }
        (v.category, v.brief) = classify(alert.name(), &v.check, "");
        // An episode cannot start after the worker first saw it: CronJobs that
        // post only an endsAt get a startsAt a day ahead.
        if v.created_at > 0 && (v.starts_at == 0 || v.starts_at > v.created_at) {
            v.starts_at = v.created_at;
        }
        v.updated_at = v.created_at.max(v.ended_at);
        v
    }

    /// The earliest trustworthy start of the episode, in nanoseconds: its
    /// start, bounded by when it was received.
    fn episode_ref(&self) -> Option<i128> {
        let start = parse_time_nanos(&self.episode);
        if self.received_at > 0 {
            let created = self.received_at as i128 * 1_000_000_000;
            if start.is_none_or(|s| created < s) {
                return Some(created);
            }
        }
        start
    }

    /// Whether a resolved notification ends this episode: matching start
    /// times, or any recovery that ended after the episode began.
    fn resolution_applies(&self, alert: &AmAlert) -> bool {
        if alert.starts_at == self.episode {
            return true;
        }
        let Some(reference) = self.episode_ref() else {
            return true;
        };
        match parse_time_nanos(&alert.ends_at) {
            Some(ended) => ended > reference,
            None => false,
        }
    }
}

// --- Notifications (push.go) ---------------------------------------------

/// One push message: the JSON body the apps read (docs/prism-api.md, "Push").
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Notification {
    #[serde(rename = "type")]
    pub kind: String,
    pub key: String,
    pub time: i64,
    pub title: String,
    pub body: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub severity: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub host: String,
    #[serde(default, skip_serializing_if = "is_zero")]
    pub alert_id: i64,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub state: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub agent_id: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub environment_id: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub thread_id: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub question_id: String,
    /// app.release: the published version and its package.
    #[serde(default, skip_serializing_if = "is_zero")]
    pub version_code: i64,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub package: String,
    /// app.settings: the device whose settings changed.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub device: String,
}

fn is_zero(v: &i64) -> bool {
    *v == 0
}

impl Notification {
    /// The message body: `{"v": 1, ...}` with the title and body trimmed.
    pub fn message(&self) -> String {
        let mut n = self.clone();
        n.title = truncate(first_line(&n.title), 200);
        n.body = truncate(n.body.trim(), 1000);
        let mut value = serde_json::to_value(&n).unwrap_or_default();
        if let Some(map) = value.as_object_mut() {
            map.insert("v".into(), 1.into());
        }
        value.to_string()
    }

    /// ntfy's priority (docs/prism-api.md, "Messages").
    pub fn priority(&self) -> i64 {
        match (self.kind.as_str(), self.severity.as_str()) {
            ("alert.firing", "critical") => 5,
            ("alert.firing", "warning") | ("agent.question", _) => 4,
            (
                "alert.ended" | "agent.started" | "app.release" | "app.settings"
                | "question.closed" | "placement.changed",
                _,
            ) => 1,
            _ => 3,
        }
    }
}

pub fn alert_title(alert: &AmAlert, number: i64) -> String {
    let name = if alert.is_host_event() {
        alert.label("check")
    } else {
        alert.name()
    };
    let mut title = with_id(number, name);
    if !alert.label("machine").is_empty() {
        title += " · ";
        title += alert.label("machine");
    }
    title
}

/// Identifies one appearance of an episode for push dedupe.
fn push_key(alert: &AmAlert, number: i64, created_at: i64) -> String {
    if number > 0 {
        format!("{number}:{created_at}")
    } else {
        format!(
            "{}:{}",
            label_fingerprint(&alert.labels.clone().unwrap_or_default()),
            alert.starts_at
        )
    }
}

pub fn firing_notification(alert: &AmAlert, number: i64, created_at: i64) -> Notification {
    let mut body = alert.annotation("summary").to_string();
    if body.contains("{{") || body.is_empty() {
        body = classify(alert.name(), alert.label("check"), "").1;
    }
    Notification {
        kind: "alert.firing".into(),
        key: format!("alert.firing:{}", push_key(alert, number, created_at)),
        time: created_at,
        title: alert_title(alert, number),
        body,
        severity: alert.label("severity").into(),
        host: alert.label("machine").into(),
        alert_id: number,
        ..Notification::default()
    }
}

pub fn ended_notification(
    alert: &AmAlert,
    number: i64,
    state: &str,
    created_at: i64,
    ended_at: i64,
) -> Notification {
    Notification {
        kind: "alert.ended".into(),
        key: format!("alert.ended:{}", push_key(alert, number, created_at)),
        time: ended_at,
        title: format!("{} {state}", alert_title(alert, number)),
        alert_id: number,
        state: state.into(),
        host: alert.label("machine").into(),
        ..Notification::default()
    }
}

// --- Storage ----------------------------------------------------------------

const ALERT_COLUMNS: &str =
    "id, key, fingerprint, episode, payload, state, received_at, resolved_at, missing_since";
const ACTION_COLUMNS: &str = "id, request_id, alert, kind, instruction, requested_by, requested_at, \
     claimed_by, claimed_at, done_at, thread_id, link, error";

impl PrismClient {
    /// Handles one Alertmanager webhook (`POST /alerts/cluster`). Retries are
    /// safe: a firing episode is stored once per key, and a resolution only
    /// ends the episode it applies to.
    pub async fn record_alert_webhook(
        &self,
        body: &[u8],
        now: i64,
    ) -> Result<(), AlertWebhookError> {
        #[derive(Deserialize)]
        struct Payload<'a> {
            #[serde(default, deserialize_with = "nullable")]
            status: String,
            #[serde(default, borrow)]
            alerts: Option<Vec<&'a RawValue>>,
        }
        let payload: Payload =
            serde_json::from_slice(body).map_err(|_| AlertWebhookError::Invalid("invalid JSON"))?;
        let raws = payload
            .alerts
            .ok_or(AlertWebhookError::Invalid("missing alert list"))?;
        let mut alerts = Vec::with_capacity(raws.len());
        for raw in raws {
            let alert: AmAlert = serde_json::from_str(raw.get())
                .map_err(|_| AlertWebhookError::Invalid("invalid alert"))?;
            if alert.labels.is_none() {
                return Err(AlertWebhookError::Invalid("invalid alert"));
            }
            let status = if alert.status.is_empty() {
                payload.status.clone()
            } else {
                alert.status.clone()
            };
            alerts.push((status, alert, raw.get().to_string()));
        }

        let _guard = self.alerts_lock.lock().await;
        // Alerts before an invalid one are stored, as in Go; Alertmanager
        // does not retry a 400.
        for (status, alert, raw) in alerts {
            if alert.is_host_event() {
                // Machine events are the worker's own; these are what
                // Alertmanager still has from when they went through it.
                continue;
            }
            let labels = alert.labels.clone().unwrap_or_default();
            let fingerprint = label_fingerprint(&labels);
            let row = self.current_alert(&fingerprint).await?;
            match status.as_str() {
                "resolved" => {
                    if let Some(row) = row
                        && row.is_open()
                        && row.resolution_applies(&alert)
                    {
                        self.end_alert(&row.key, ALERT_RESOLVED, now).await?;
                    }
                }
                "firing" => self.fire(&fingerprint, row, &alert, &raw, now).await?,
                _ => {
                    return Err(AlertWebhookError::Invalid(
                        "alert status must be firing or resolved",
                    ));
                }
            }
        }
        Ok(())
    }

    async fn fire(
        &self,
        fingerprint: &str,
        row: Option<AlertRow>,
        alert: &AmAlert,
        raw: &str,
        now: i64,
    ) -> Result<(), PrismError> {
        let episode = alert.starts_at.as_str();
        if let Some(row) = &row {
            if row.episode != episode
                && let (Some(reference), Some(start)) =
                    (row.episode_ref(), parse_time_nanos(episode))
                && start < reference
            {
                return Ok(()); // A delayed retry of an older episode.
            }
            if row.episode == episode
                && (row.state == ALERT_RESOLVED || row.state == ALERT_DISMISSED)
            {
                return Ok(()); // A firing retry must not resurrect an ended episode.
            }
            if row.episode == episode && row.state != ALERT_GONE {
                if row.payload == raw && row.missing_since == 0 {
                    return Ok(());
                }
                return self.update_alert_payload(&row.key, alert, raw).await;
            }
            if row.is_open() {
                self.end_alert(&row.key, ALERT_RESOLVED, now).await?;
            }
        }
        let key = alert_key(fingerprint, episode);
        let fields = AlertFields::of(alert);
        let is_same_key = row.as_ref().is_some_and(|r| r.key == key);
        let fingerprint = fingerprint.to_owned();
        let episode = episode.to_owned();
        let raw = raw.to_owned();
        self.db
            .write(async move |tx| {
                if is_same_key {
                    // Back after reconciliation: the same episode (and number), seen anew.
                    tx.execute(
                        "UPDATE alerts SET state = ?, received_at = ?, resolved_at = NULL, missing_since = 0, payload = ? WHERE key = ?",
                        params![ALERT_OPEN, now, raw, key],
                    )
                    .await
                } else {
                    let id = tx.next_id("alerts").await?;
                    tx.execute(
                        "INSERT INTO alerts (id, key, fingerprint, episode, payload, name, check_name, severity, machine, summary, state, received_at, missing_since) \
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                        params![
                            id, key, fingerprint, episode, raw, fields.name, fields.check, fields.severity,
                            fields.machine, fields.summary, ALERT_OPEN, now
                        ],
                    )
                    .await
                }
            })
            .await
            .map_err(storage_err)?;
        Ok(())
    }

    async fn update_alert_payload(
        &self,
        key: &str,
        alert: &AmAlert,
        raw: &str,
    ) -> Result<(), PrismError> {
        let fields = AlertFields::of(alert);
        let key = key.to_owned();
        let raw = raw.to_owned();
        self.db
            .write(async move |tx| {
                tx.execute(
                    "UPDATE alerts SET payload = ?, name = ?, check_name = ?, severity = ?, machine = ?, summary = ?, missing_since = 0 WHERE key = ?",
                    params![raw, fields.name, fields.check, fields.severity, fields.machine, fields.summary, key],
                )
                .await
            })
            .await
            .map_err(storage_err)?;
        Ok(())
    }

    /// Ends an open episode with `state`.
    async fn end_alert(&self, key: &str, state: &str, now: i64) -> Result<(), PrismError> {
        let key = key.to_owned();
        let state = state.to_owned();
        self.db
            .write(async move |tx| {
                tx.execute(
                    "UPDATE alerts SET state = ?, resolved_at = ? WHERE key = ? AND state = ?",
                    params![state, now, key, ALERT_OPEN],
                )
                .await
            })
            .await
            .map_err(storage_err)?;
        Ok(())
    }

    async fn set_alert_missing(&self, key: &str, since: i64) -> Result<(), PrismError> {
        let key = key.to_owned();
        self.db
            .write(async move |tx| {
                tx.execute(
                    "UPDATE alerts SET missing_since = ? WHERE key = ?",
                    params![since, key],
                )
                .await
            })
            .await
            .map_err(storage_err)?;
        Ok(())
    }

    /// Every episode, one row per key, with its number, oldest first.
    pub async fn alert_rows(&self) -> Result<Vec<AlertRow>, PrismError> {
        self.alert_rows_where("", params![], true).await
    }

    /// The episodes `filter` selects, one row per key, oldest first. With
    /// `numbered`, each carries its number; without, `number` stays 0 and
    /// `alert_numbers` isn't read, so a replica that is still importing the
    /// numbers can take an alert.
    async fn alert_rows_where(
        &self,
        filter: &str,
        args: Vec<SqlValue>,
        numbered: bool,
    ) -> Result<Vec<AlertRow>, PrismError> {
        let sql = format!("SELECT {ALERT_COLUMNS} FROM alerts {filter}");
        let mut rows = self.db.query(&sql, args).await.map_err(storage_err)?;
        let mut by_key: BTreeMap<String, AlertRow> = BTreeMap::new();
        while let Some(row) = rows.next().await.map_err(storage_err)? {
            let a = AlertRow {
                row_id: id_bytes(&row.get::<SqlValue>("id").map_err(storage_err)?),
                key: row.get("key").map_err(storage_err)?,
                fingerprint: row.get("fingerprint").map_err(storage_err)?,
                episode: row.get("episode").map_err(storage_err)?,
                payload: row.get("payload").map_err(storage_err)?,
                state: row.get("state").map_err(storage_err)?,
                received_at: row.get("received_at").map_err(storage_err)?,
                resolved_at: row.get("resolved_at").map_err(storage_err)?,
                missing_since: row.get("missing_since").map_err(storage_err)?,
                number: 0,
            };
            match by_key.get(&a.key) {
                Some(kept) if kept.row_id <= a.row_id => {}
                _ => {
                    by_key.insert(a.key.clone(), a);
                }
            }
        }
        drop(rows);
        let numbers = if numbered {
            self.alert_numbers().await?
        } else {
            BTreeMap::new()
        };
        let mut out: Vec<AlertRow> = by_key
            .into_values()
            .map(|mut a| {
                a.number = numbers.get(&a.key).copied().unwrap_or(0);
                a
            })
            .collect();
        out.sort_by(|a, b| {
            a.received_at
                .cmp(&b.received_at)
                .then_with(|| a.row_id.cmp(&b.row_id))
        });
        Ok(out)
    }

    /// The newest episode of a fingerprint, without its number: the webhook
    /// intake needs only its key and state.
    async fn current_alert(&self, fingerprint: &str) -> Result<Option<AlertRow>, PrismError> {
        Ok(self
            .alert_rows_where("WHERE fingerprint = ?", params![fingerprint], false)
            .await?
            .pop())
    }

    /// Reads what a leader depends on: the alert numbers and the alerts.
    /// Fails with `is_incomplete` while this replica can't serve them.
    pub async fn leader_ready(&self) -> Result<(), PrismError> {
        for sql in [
            "SELECT number FROM alert_numbers LIMIT 1",
            "SELECT state FROM alerts LIMIT 1",
        ] {
            let mut rows = self.db.query(sql, params![]).await.map_err(storage_err)?;
            rows.next().await.map_err(storage_err)?;
        }
        Ok(())
    }

    /// The number each key holds: numbers are given in (at, row id) order, and
    /// a key or number met again is skipped.
    async fn alert_numbers(&self) -> Result<BTreeMap<String, i64>, PrismError> {
        let mut rows = self
            .db
            .query("SELECT id, key, number, at FROM alert_numbers", params![])
            .await
            .map_err(storage_err)?;
        let mut all = Vec::new();
        while let Some(row) = rows.next().await.map_err(storage_err)? {
            all.push((
                row.get::<i64>("at").map_err(storage_err)?,
                id_bytes(&row.get::<SqlValue>("id").map_err(storage_err)?),
                row.get::<String>("key").map_err(storage_err)?,
                row.get::<i64>("number").map_err(storage_err)?,
            ));
        }
        all.sort();
        let mut by_key = BTreeMap::new();
        let mut taken = BTreeSet::new();
        for (_, _, key, number) in all {
            if by_key.contains_key(&key) || !taken.insert(number) {
                continue;
            }
            by_key.insert(key, number);
        }
        Ok(by_key)
    }

    /// Leader only: numbers every unnumbered episode, oldest first, and
    /// returns how many it numbered.
    pub async fn assign_alert_numbers(&self, now: i64) -> Result<usize, PrismError> {
        let _guard = self.alerts_lock.lock().await;
        let rows = self.alert_rows().await?;
        let mut rows_numbers = self
            .db
            .query("SELECT MAX(number) AS last FROM alert_numbers", params![])
            .await
            .map_err(storage_err)?;
        let mut last: i64 = match rows_numbers.next().await.map_err(storage_err)? {
            Some(row) => row
                .get::<Option<i64>>("last")
                .map_err(storage_err)?
                .unwrap_or(0),
            None => 0,
        };
        drop(rows_numbers);
        let unnumbered: Vec<String> = rows
            .iter()
            .filter(|r| r.number == 0)
            .map(|r| r.key.clone())
            .collect();
        if unnumbered.is_empty() {
            return Ok(0);
        }
        let count = unnumbered.len();
        self.db
            .write(async move |tx| {
                for key in unnumbered {
                    last += 1;
                    let id = tx.next_id("alert_numbers").await?;
                    tx.execute(
                        "INSERT INTO alert_numbers (id, key, number, at) VALUES (?, ?, ?, ?)",
                        params![id, key, last, now],
                    )
                    .await?;
                }
                Ok::<_, rocky_sdk::Error>(())
            })
            .await
            .map_err(storage_err)?;
        Ok(count)
    }

    /// Alerts as the apps list them, newest first, with their requests.
    pub async fn list_alerts(&self, filter: AlertFilter) -> Result<Vec<AlertView>, PrismError> {
        let rows = self.alert_rows().await?;
        let mut actions = self.alert_actions_by_alert().await?;
        Ok(rows
            .into_iter()
            .rev()
            .filter(|r| match filter {
                AlertFilter::Open => r.is_open(),
                AlertFilter::Recent { since } => {
                    r.is_open() || r.resolved_at.is_some_and(|at| at >= since)
                }
                AlertFilter::All => true,
            })
            .map(|r| {
                let acts = actions.remove(&r.key).unwrap_or_default();
                r.view(acts)
            })
            .collect())
    }

    /// One episode by its key.
    pub async fn alert_by_key(&self, key: &str) -> Result<Option<AlertRow>, PrismError> {
        Ok(self
            .alert_rows_where("WHERE key = ?", params![key], true)
            .await?
            .pop())
    }

    /// One episode by its number (`#42`).
    pub async fn alert_by_number(&self, number: i64) -> Result<Option<AlertRow>, PrismError> {
        if number <= 0 {
            return Ok(None);
        }
        Ok(self
            .alert_rows()
            .await?
            .into_iter()
            .find(|r| r.number == number))
    }

    /// Ends the open episodes Alertmanager no longer has (Go's `Reconcile`):
    /// one absent for the grace period, while Alertmanager has been up long
    /// enough (`trusted`) to have every current alert, is `gone`. Returns the
    /// keys it ended.
    pub async fn reconcile_alerts(
        &self,
        present: &BTreeSet<String>,
        trusted: bool,
        grace_secs: i64,
        now: i64,
    ) -> Result<Vec<String>, PrismError> {
        let _guard = self.alerts_lock.lock().await;
        let mut ended = Vec::new();
        for row in self.alert_rows().await? {
            if !row.is_open() || row.alert().is_host_event() {
                continue;
            }
            if present.contains(&row.fingerprint) {
                if row.missing_since != 0 {
                    self.set_alert_missing(&row.key, 0).await?;
                }
                continue;
            }
            if row.missing_since == 0 {
                self.set_alert_missing(&row.key, now).await?;
                continue;
            }
            if !trusted
                || now - row.missing_since < grace_secs
                || (row.received_at > 0 && now - row.received_at < grace_secs)
            {
                continue;
            }
            self.end_alert(&row.key, ALERT_GONE, now).await?;
            ended.push(row.key);
        }
        Ok(ended)
    }

    /// Ends a machine event's open episode: its check passed
    /// (`crate::events`).
    pub(crate) async fn resolve_host_event(&self, key: &str, now: i64) -> Result<(), PrismError> {
        let _guard = self.alerts_lock.lock().await;
        self.end_alert(key, ALERT_RESOLVED, now).await
    }

    // --- Requests --------------------------------------------------------

    /// Writes a request on an alert (any device). The same `request_id`
    /// returns the request already written. The leader runs it later and
    /// checks again whether it still applies.
    pub async fn request_alert_action(
        &self,
        req: AlertActionRequest,
        now: i64,
    ) -> Result<AlertActionView, PrismError> {
        let instruction = req.instruction.trim().to_string();
        if req.kind != ACTION_FIX {
            return Err(PrismError::InvalidInput(unknown_action(&req.kind)));
        }
        if instruction.chars().count() > MAX_INSTRUCTION_CHARS {
            return Err(PrismError::InvalidInput(
                "Please keep the instruction under 10,000 characters.".into(),
            ));
        }
        if req.request_id.len() > MAX_REQUEST_ID_BYTES {
            return Err(PrismError::InvalidInput(
                "The request ID is too long.".into(),
            ));
        }
        if !req.request_id.is_empty()
            && let Some(existing) = self.alert_action(&req.request_id).await?
        {
            return Ok(existing);
        }
        let row = self
            .alert_by_key(&req.alert)
            .await?
            .ok_or(PrismError::NotFound)?;
        check_action(&row, &req.kind, &instruction)?;

        let req_id_in = req.request_id.clone();
        let req_alert = req.alert.clone();
        let req_kind = req.kind.clone();
        let req_by = req.requested_by.clone();
        let request_id = self
            .db
            .write(async move |tx| {
                let id = tx.next_id("alert_actions").await?;
                let request_id = if req_id_in.is_empty() {
                    id.to_string()
                } else {
                    req_id_in
                };
                tx.execute(
                    "INSERT INTO alert_actions (id, request_id, alert, kind, instruction, requested_by, requested_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    params![id, request_id.clone(), req_alert, req_kind, instruction, req_by, now],
                )
                .await?;
                Ok::<_, rocky_sdk::Error>(request_id)
            })
            .await
            .map_err(storage_err)?;
        self.alert_action(&request_id)
            .await?
            .ok_or(PrismError::NotFound)
    }

    pub async fn alert_action(
        &self,
        request_id: &str,
    ) -> Result<Option<AlertActionView>, PrismError> {
        Ok(self
            .alert_actions_where("WHERE request_id = ?", params![request_id])
            .await?
            .pop())
    }

    /// Requests no worker finished: unclaimed, or claimed before
    /// `stale_before` by a worker that never finished them. Oldest first. A
    /// device's own claim (`DEVICE_CLAIM_PREFIX`) never goes stale: the
    /// device may have run it directly in T3 already.
    pub async fn claimable_alert_actions(
        &self,
        stale_before: i64,
    ) -> Result<Vec<AlertActionView>, PrismError> {
        Ok(self
            .alert_actions_where("WHERE done_at IS NULL", params![])
            .await?
            .into_iter()
            .filter(|a| {
                a.claimed_at.is_none_or(|at| {
                    at < stale_before
                        && !a
                            .claimed_by
                            .as_deref()
                            .is_some_and(|by| by.starts_with(DEVICE_CLAIM_PREFIX))
                })
            })
            .collect())
    }

    /// Claims a request for `worker` (leader only). True when the worker now
    /// holds it: it was unclaimed, its claim went stale, or it was already
    /// this worker's.
    pub async fn claim_alert_action(
        &self,
        request_id: &str,
        worker: &str,
        now: i64,
        stale_before: i64,
    ) -> Result<bool, PrismError> {
        let worker_owned = worker.to_owned();
        let request_id_owned = request_id.to_owned();
        self.db
            .write(async move |tx| {
                tx.execute(
                    "UPDATE alert_actions SET claimed_by = ?, claimed_at = ? \
                     WHERE request_id = ? AND done_at IS NULL \
                     AND (claimed_by IS NULL OR claimed_by = ? \
                     OR (claimed_at < ? AND claimed_by NOT LIKE 'device:%'))",
                    params![worker_owned.clone(), now, request_id_owned, worker_owned, stale_before],
                )
                .await
            })
            .await
            .map_err(storage_err)?;
        Ok(self
            .alert_action(request_id)
            .await?
            .is_some_and(|a| a.done_at.is_none() && a.claimed_by.as_deref() == Some(worker)))
    }

    /// Marks a request this worker claimed as done with its outcome.
    pub async fn finish_alert_action(
        &self,
        request_id: &str,
        worker: &str,
        now: i64,
        outcome: AlertActionOutcome,
    ) -> Result<(), PrismError> {
        let request_id = request_id.to_owned();
        let worker = worker.to_owned();
        self.db
            .write(async move |tx| {
                tx.execute(
                    "UPDATE alert_actions SET done_at = ?, thread_id = ?, link = ?, error = ? \
                     WHERE request_id = ? AND claimed_by = ? AND done_at IS NULL",
                    params![
                        now,
                        outcome.thread_id,
                        outcome.link,
                        outcome.error,
                        request_id,
                        worker
                    ],
                )
                .await
            })
            .await
            .map_err(storage_err)?;
        Ok(())
    }

    async fn alert_actions_by_alert(
        &self,
    ) -> Result<BTreeMap<String, Vec<AlertActionView>>, PrismError> {
        let mut out: BTreeMap<String, Vec<AlertActionView>> = BTreeMap::new();
        for a in self.alert_actions_where("", params![]).await? {
            out.entry(a.alert.clone()).or_default().push(a);
        }
        Ok(out)
    }

    /// Requests, one per request id (the smallest Rocky id), oldest first.
    async fn alert_actions_where(
        &self,
        filter: &str,
        args: Vec<SqlValue>,
    ) -> Result<Vec<AlertActionView>, PrismError> {
        let sql = format!("SELECT {ACTION_COLUMNS} FROM alert_actions {filter}");
        let mut rows = self.db.query(&sql, args).await.map_err(storage_err)?;
        let mut by_id: BTreeMap<String, (Vec<u8>, AlertActionView)> = BTreeMap::new();
        while let Some(row) = rows.next().await.map_err(storage_err)? {
            let row_id = id_bytes(&row.get::<SqlValue>("id").map_err(storage_err)?);
            let a = AlertActionView {
                request_id: row.get("request_id").map_err(storage_err)?,
                alert: row.get("alert").map_err(storage_err)?,
                kind: row.get("kind").map_err(storage_err)?,
                instruction: row.get("instruction").map_err(storage_err)?,
                requested_by: row.get("requested_by").map_err(storage_err)?,
                requested_at: row.get("requested_at").map_err(storage_err)?,
                claimed_by: row.get("claimed_by").map_err(storage_err)?,
                claimed_at: row.get("claimed_at").map_err(storage_err)?,
                done_at: row.get("done_at").map_err(storage_err)?,
                thread_id: row.get("thread_id").map_err(storage_err)?,
                link: row.get("link").map_err(storage_err)?,
                error: row.get("error").map_err(storage_err)?,
            };
            match by_id.get(&a.request_id) {
                Some((kept, _)) if *kept <= row_id => {}
                _ => {
                    by_id.insert(a.request_id.clone(), (row_id, a));
                }
            }
        }
        let mut out: Vec<(Vec<u8>, AlertActionView)> = by_id.into_values().collect();
        out.sort_by(|a, b| {
            a.1.requested_at
                .cmp(&b.1.requested_at)
                .then_with(|| a.0.cmp(&b.0))
        });
        Ok(out.into_iter().map(|(_, a)| a).collect())
    }

    // --- Pushes ------------------------------------------------------------

    /// The alert pushes the leader has yet to post, oldest first: a firing
    /// push per numbered episode appearance and an ended push once it ends,
    /// each within `PUSH_STALE_SECS` of happening.
    pub async fn pending_alert_pushes(&self, now: i64) -> Result<Vec<Notification>, PrismError> {
        let sent = self.pushed_keys().await?;
        let mut out = Vec::new();
        for row in self.alert_rows().await? {
            if row.number <= 0 {
                continue;
            }
            let alert = row.alert();
            let firing = firing_notification(&alert, row.number, row.received_at);
            if now - firing.time <= PUSH_STALE_SECS && !sent.contains(&firing.key) {
                out.push(firing);
            }
            if let (false, Some(ended_at)) = (row.is_open(), row.resolved_at) {
                let ended =
                    ended_notification(&alert, row.number, &row.state, row.received_at, ended_at);
                if now - ended.time <= PUSH_STALE_SECS && !sent.contains(&ended.key) {
                    out.push(ended);
                }
            }
        }
        out.sort_by_key(|n| n.time);
        Ok(out)
    }

    pub async fn pushed_keys(&self) -> Result<BTreeSet<String>, PrismError> {
        let mut rows = self
            .db
            .query("SELECT key FROM pushes", params![])
            .await
            .map_err(storage_err)?;
        let mut out = BTreeSet::new();
        while let Some(row) = rows.next().await.map_err(storage_err)? {
            out.insert(row.get::<String>("key").map_err(storage_err)?);
        }
        Ok(out)
    }

    /// Records that the leader posted (or dropped) the push `key`, and forgets
    /// pushes older than `PUSH_KEEP_SECS`.
    pub async fn record_push(&self, key: &str, now: i64) -> Result<(), PrismError> {
        let key = key.to_owned();
        self.db
            .write(async move |tx| {
                tx.execute(
                    "DELETE FROM pushes WHERE at < ?",
                    params![now - PUSH_KEEP_SECS],
                )
                .await?;
                let id = tx.next_id("pushes").await?;
                tx.execute(
                    "INSERT INTO pushes (id, key, at) VALUES (?, ?, ?)",
                    params![id, key, now],
                )
                .await
            })
            .await
            .map_err(storage_err)?;
        Ok(())
    }
}

struct AlertFields {
    name: String,
    check: String,
    severity: String,
    machine: String,
    summary: String,
}

impl AlertFields {
    fn of(alert: &AmAlert) -> Self {
        Self {
            name: alert.name().into(),
            check: alert.label("check").into(),
            severity: alert.label("severity").into(),
            machine: alert.label("machine").into(),
            summary: alert.annotation("summary").into(),
        }
    }
}

fn display_id(row: &AlertRow) -> String {
    match alert_id(row.number) {
        id if id.is_empty() => "This alert".into(),
        id => id,
    }
}

/// Why a request other than fix is refused: an older app's dismiss or
/// resolve, still in the replica, ends with this.
pub fn unknown_action(kind: &str) -> String {
    match kind {
        "dismiss" | "resolve" => "Alerts can't be dismissed or resolved: each one ends by \
             itself when the problem it reports is gone."
            .into(),
        other => format!("Unknown action {other:?}: the only one is fix."),
    }
}

/// Whether a request still applies to the alert as it is now.
pub fn check_action(row: &AlertRow, kind: &str, instruction: &str) -> Result<(), PrismError> {
    match kind {
        ACTION_FIX if instruction.is_empty() && !row.is_open() => {
            Err(PrismError::Conflict(format!(
                "{} is no longer open. Give an instruction to work on it anyway.",
                display_id(row)
            )))
        }
        ACTION_FIX => Ok(()),
        other => Err(PrismError::InvalidInput(unknown_action(other))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn labels(pairs: &[(&str, &str)]) -> BTreeMap<String, String> {
        pairs
            .iter()
            .map(|(k, v)| ((*k).to_string(), (*v).to_string()))
            .collect()
    }

    #[test]
    fn fingerprint_matches_python_json_dumps() {
        // hashlib.sha256(json.dumps(labels, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        let l = labels(&[("alertname", "NodeNotReady"), ("node", "oracle-1")]);
        assert_eq!(
            label_fingerprint(&l),
            sha256_hex(r#"{"alertname":"NodeNotReady","node":"oracle-1"}"#)
        );
        let mut s = String::new();
        py_string(&mut s, "ä\u{1f600}\u{7f}\"\n");
        assert_eq!(s, "\"\\u00e4\\ud83d\\ude00\\u007f\\\"\\n\"");
    }

    #[test]
    fn times_before_2000_are_absent() {
        assert_eq!(parse_time("0001-01-01T00:00:00Z"), None);
        assert_eq!(parse_time("2026-11-21T10:00:00.5Z"), Some(1_795_255_200));
        assert_eq!(parse_time("nope"), None);
    }

    #[test]
    fn classify_uses_the_catalog_then_the_summary() {
        assert_eq!(
            classify("NodeNotReady", "", "").0,
            "Cluster nodes".to_string()
        );
        assert_eq!(
            classify(EVENT_ALERT_NAME, "disk-space", ""),
            ("Machines".into(), MACHINE_CHECKS[0].1.into())
        );
        assert_eq!(
            classify("Mystery", "", "line one\nline two"),
            ("Other".into(), "line one".into())
        );
        assert_eq!(classify("Mystery", "", "{{ $labels }}").1, "");
    }

    #[test]
    fn notifications_match_the_go_server() {
        let alert = AmAlert {
            labels: Some(labels(&[
                ("alertname", "NodeNotReady"),
                ("severity", "critical"),
            ])),
            annotations: labels(&[("summary", "oracle-1 is down")]),
            starts_at: "2026-11-21T10:00:00Z".into(),
            ..AmAlert::default()
        };
        let n = firing_notification(&alert, 42, 1_763_470_012);
        assert_eq!(n.key, "alert.firing:42:1763470012");
        assert_eq!(n.title, "#42 NodeNotReady");
        assert_eq!(n.priority(), 5);
        let msg: serde_json::Value = serde_json::from_str(&n.message()).expect("json");
        assert_eq!(msg["v"], 1);
        assert_eq!(msg["type"], "alert.firing");
        assert_eq!(msg["alertId"], 42);
        assert!(msg.get("state").is_none());
        let e = ended_notification(&alert, 42, ALERT_GONE, 1_763_470_012, 1_763_470_100);
        assert_eq!(e.title, "#42 NodeNotReady gone");
        assert_eq!(e.priority(), 1);
    }

    #[test]
    fn an_older_apps_dismiss_or_resolve_ends_with_why() {
        let row = AlertRow {
            key: "k".into(),
            fingerprint: "f".into(),
            episode: "2026-11-21T10:00:00Z".into(),
            payload: "{}".into(),
            state: ALERT_OPEN.into(),
            received_at: 1,
            resolved_at: None,
            missing_since: 0,
            number: 7,
            row_id: vec![1],
        };
        for kind in ["dismiss", "resolve"] {
            match check_action(&row, kind, "") {
                Err(PrismError::InvalidInput(msg)) => {
                    assert!(msg.contains("ends by itself"), "{kind}: {msg}")
                }
                other => panic!("{kind}: {other:?}"),
            }
        }
        assert!(check_action(&row, ACTION_FIX, "").is_ok());
    }
}
