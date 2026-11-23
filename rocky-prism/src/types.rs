use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const DEFAULT_ALERT_FIX_PROFILE: &str = "default";

pub const PLACEMENT_ROLES: &[&str] = &[
    "implementation",
    "research",
    "review",
    "design",
    "test",
    "general",
];

#[derive(Debug, thiserror::Error)]
pub enum PrismError {
    #[error("{0}")]
    InvalidInput(String),
    #[error("{0}")]
    Conflict(String),
    #[error("not found")]
    NotFound,
    #[error("storage error: {0}")]
    Storage(String),
    /// A failure Rocky marks retryable, such as a table that is not writable
    /// while synced rows are still being imported (`Incomplete`). The same
    /// write usually succeeds moments later; see `retry_unavailable`.
    #[error("storage busy: {0}")]
    Unavailable(String),
}

impl PrismError {
    /// Rocky refused a read or write because this replica doesn't hold
    /// everything the table depends on yet (`Unavailable::Incomplete`). Unlike
    /// a busy database this lasts until the missing operations arrive, and for
    /// a node whose author log forked, forever. Rocky's SQL failure carries the
    /// reason only in its message.
    pub fn is_incomplete(&self) -> bool {
        matches!(self, PrismError::Unavailable(message) | PrismError::Storage(message)
            if message.contains("is not available: Incomplete"))
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlacementOption {
    pub id: String,
    /// A string, or a boolean for a T3 toggle option (fast mode).
    pub value: serde_json::Value,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlacementSelection {
    #[serde(rename = "instanceId")]
    pub instance_id: String,
    pub model: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub options: Vec<PlacementOption>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlacementActiveHours {
    pub start: i32,
    pub end: i32,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PlacementPolicy {
    #[serde(rename = "switchAtPercent")]
    pub switch_at_percent: f64,
    /// An entry with at most this many session windows of weekly slack is
    /// preferred for new threads ("promoted").
    #[serde(rename = "weeklySlackSessions")]
    pub weekly_slack_sessions: f64,
    /// At most this many, and running threads move onto it from entries with
    /// more slack. None means DEFAULT_WEEKLY_MOVE_SLACK_SESSIONS.
    #[serde(
        rename = "weeklyMoveSlackSessions",
        default,
        skip_serializing_if = "Option::is_none"
    )]
    pub weekly_move_slack_sessions: Option<f64>,
    #[serde(
        rename = "activeHours",
        default,
        skip_serializing_if = "Option::is_none"
    )]
    pub active_hours: Option<PlacementActiveHours>,
    #[serde(
        rename = "roleProfiles",
        default,
        skip_serializing_if = "BTreeMap::is_empty"
    )]
    pub role_profiles: BTreeMap<String, String>,
}

pub const DEFAULT_WEEKLY_MOVE_SLACK_SESSIONS: f64 = 1.5;

impl PlacementPolicy {
    pub fn weekly_move_slack(&self) -> f64 {
        self.weekly_move_slack_sessions
            .unwrap_or(DEFAULT_WEEKLY_MOVE_SLACK_SESSIONS)
    }
}

impl Default for PlacementPolicy {
    fn default() -> Self {
        let mut role_profiles = BTreeMap::new();
        role_profiles.insert("review".into(), "review".into());
        Self {
            switch_at_percent: 95.0,
            weekly_slack_sessions: 3.0,
            weekly_move_slack_sessions: None,
            active_hours: None,
            role_profiles,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PlacementWindow {
    #[serde(rename = "usedPercent")]
    pub used_percent: f64,
    #[serde(rename = "resetsAt", default, skip_serializing_if = "String::is_empty")]
    pub resets_at: String,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct PlacementLoad {
    pub running: i32,
    pub burn_per_thread_per_hour: Option<f64>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct PlacementEntryInput {
    pub selection: PlacementSelection,
    pub enabled: bool,
    pub session_share_of_weekly: Option<f64>,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct PlacementHostModel {
    pub blocked: Option<String>,
    pub session: Option<PlacementWindow>,
    pub weekly: Option<PlacementWindow>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PlacementEntryDecision {
    pub selection: PlacementSelection,
    pub state: String,
    pub reason: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub session: Option<PlacementWindow>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub weekly: Option<PlacementWindow>,
    #[serde(rename = "runningThreads")]
    pub running_threads: i32,
    #[serde(
        rename = "predictedSessionPercent",
        default,
        skip_serializing_if = "Option::is_none"
    )]
    pub predicted_session_percent: Option<f64>,
    #[serde(
        rename = "weeklySlackSessions",
        default,
        skip_serializing_if = "Option::is_none"
    )]
    pub weekly_slack_sessions: Option<f64>,
    /// Why a running thread on this entry is compacted in place: the entry
    /// runs into its limit on purpose (its weekly allowance still has to be
    /// spent), and the limit is close. The thread compacts on this account and
    /// continues here, so the move at the limit hands over a short context.
    /// Not sent: T3 sees it only as a move whose `to` is its `from`.
    #[serde(skip)]
    pub compact_before_limit: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PlacementProfileDecision {
    pub id: String,
    pub label: String,
    pub next: Option<PlacementSelection>,
    pub ranked: Vec<PlacementEntryDecision>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PlacementMoveTarget {
    pub to: PlacementSelection,
    pub reason: String,
}

#[derive(Clone, Debug, PartialEq)]
pub struct PlacementSample {
    pub account: String,
    pub at: i64,
    pub used: f64,
    pub running: i32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct PlacementProfileRow {
    pub id: String,
    pub label: String,
    pub position: i32,
    pub entries: Vec<PlacementEntryRow>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct PlacementEntryRow {
    pub profile_id: String,
    pub position: i32,
    pub instance_id: String,
    pub model: String,
    pub reasoning: String,
    pub enabled: bool,
    pub session_share_of_weekly: Option<f64>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct PlacementMoveRow {
    pub id: String,
    pub machine: String,
    pub thread_id: String,
    pub from: PlacementSelection,
    pub to: PlacementSelection,
    pub reason: String,
    pub at: i64,
    pub state: String,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct PlacementSyncRequest {
    #[serde(default)]
    pub v: i32,
    #[serde(rename = "environmentId", default)]
    pub environment_id: String,
    #[serde(default)]
    pub models: Vec<PlacementSyncModel>,
    #[serde(default)]
    pub threads: Vec<PlacementSyncThread>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub moves: Vec<PlacementMoveAck>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PlacementSyncModel {
    #[serde(rename = "instanceId")]
    pub instance_id: String,
    pub model: String,
    #[serde(default)]
    pub label: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub driver: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub provider: String,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub hidden: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reasoning: Option<PlacementSyncReasoning>,
    #[serde(default)]
    pub blocked: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub session: Option<PlacementWindow>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub weekly: Option<PlacementWindow>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlacementSyncReasoning {
    #[serde(rename = "optionId")]
    pub option_id: String,
    #[serde(default)]
    pub levels: Vec<String>,
    #[serde(default)]
    pub default: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlacementSyncThread {
    #[serde(rename = "threadId")]
    pub thread_id: String,
    #[serde(default)]
    pub title: String,
    pub profile: String,
    pub selection: PlacementSelection,
    #[serde(default)]
    pub running: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlacementMoveAck {
    pub id: String,
    pub state: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub detail: String,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PlacementSyncResponse {
    pub v: i32,
    #[serde(rename = "checkedAt")]
    pub checked_at: String,
    pub policy: PlacementPolicy,
    pub profiles: Vec<PlacementSyncProfileResp>,
    pub actions: PlacementSyncActions,
    pub moves: Vec<PlacementSyncMoveResp>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlacementSyncActions {
    #[serde(
        rename = "textGeneration",
        default,
        skip_serializing_if = "String::is_empty"
    )]
    pub text_generation: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlacementActions {
    #[serde(rename = "alertFix")]
    pub alert_fix: String,
    #[serde(
        rename = "textGeneration",
        default,
        skip_serializing_if = "String::is_empty"
    )]
    pub text_generation: String,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PlacementSyncProfileResp {
    pub id: String,
    pub label: String,
    pub entries: Vec<PlacementSyncEntryResp>,
    pub next: Option<PlacementSelection>,
    pub ranked: Vec<PlacementEntryDecision>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PlacementSyncEntryResp {
    pub selection: PlacementSelection,
    pub enabled: bool,
    #[serde(
        rename = "sessionShareOfWeekly",
        default,
        skip_serializing_if = "Option::is_none"
    )]
    pub session_share_of_weekly: Option<f64>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlacementSyncMoveResp {
    pub id: String,
    #[serde(rename = "threadId")]
    pub thread_id: String,
    pub from: PlacementSelection,
    pub to: PlacementSelection,
    pub reason: String,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PlacementView {
    pub profiles: Vec<PlacementProfileView>,
    pub models: Vec<PlacementModelView>,
    #[serde(rename = "alertFixProfile")]
    pub alert_fix_profile: String,
    pub actions: PlacementActions,
    pub roles: Vec<String>,
    pub policy: PlacementPolicy,
    pub moves: Vec<PlacementMoveView>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PlacementProfileView {
    pub id: String,
    pub label: String,
    /// The configured entries precede suggestions from the catalog.
    #[serde(rename = "configuredEntryCount", default)]
    pub configured_entry_count: usize,
    pub entries: Vec<PlacementEntryView>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PlacementEntryView {
    #[serde(rename = "instanceId")]
    pub instance_id: String,
    pub model: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub label: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub driver: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub provider: String,
    pub reasoning: String,
    pub enabled: bool,
    #[serde(
        rename = "sessionShareOfWeekly",
        default,
        skip_serializing_if = "Option::is_none"
    )]
    pub session_share_of_weekly: Option<f64>,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub state: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub reason: String,
    #[serde(
        rename = "runningThreads",
        default,
        skip_serializing_if = "Option::is_none"
    )]
    pub running_threads: Option<i32>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlacementModelView {
    #[serde(rename = "instanceId")]
    pub instance_id: String,
    pub model: String,
    pub label: String,
    pub driver: String,
    pub provider: String,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub hidden: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reasoning: Option<PlacementModelReasoningView>,
    pub machines: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlacementModelReasoningView {
    pub levels: Vec<String>,
    pub default: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlacementMoveView {
    pub machine: String,
    #[serde(rename = "threadId")]
    pub thread_id: String,
    pub from: String,
    pub to: String,
    pub reason: String,
    pub at: i64,
    pub state: String,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PutPlacementProfileRequest {
    pub label: String,
    #[serde(default)]
    pub entries: Vec<PutPlacementEntryInput>,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PutPlacementEntryInput {
    #[serde(rename = "instanceId")]
    pub instance_id: String,
    pub model: String,
    #[serde(default)]
    pub reasoning: String,
    #[serde(default)]
    pub enabled: bool,
    #[serde(rename = "sessionShareOfWeekly", default)]
    pub session_share_of_weekly: Option<f64>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct SetPlacementActionsRequest {
    #[serde(rename = "alertFix", default)]
    pub alert_fix: Option<String>,
    #[serde(rename = "textGeneration", default)]
    pub text_generation: Option<String>,
    #[serde(rename = "roleProfiles", default)]
    pub role_profiles: Option<BTreeMap<String, Option<String>>>,
}
