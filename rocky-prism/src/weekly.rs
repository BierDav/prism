//! Starting an account's next weekly window as soon as the last one ends.
//!
//! A subscription's weekly window starts with the first request after the
//! previous one ended, so every hour the account sits unused after its reset
//! moves the next reset that much later. The leader sends a very small prompt
//! to each profile account once its weekly window has ended, so the next one
//! starts right away (`docs/placement.md`, "Starting the next weekly window").

use crate::rank::parse_placement_time;
use crate::types::PlacementWindow;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

/// How long after a reset the first prompt waits, for the provider's clock.
pub const WEEKLY_ACTIVATION_GRACE_SECS: i64 = 60;
/// How long a prompt has to show as a new window in the hosts' readings
/// before Prism sends another.
pub const WEEKLY_ACTIVATION_RETRY_SECS: i64 = 10 * 60;
/// Prompts per ended window at most; then Prism waits for a reading that
/// shows a new window.
pub const WEEKLY_ACTIVATION_MAX_TRIES: u32 = 3;
/// Reset times that differ by less than this are the same window (providers
/// report the reset to the millisecond, and hosts round differently).
const SAME_RESET_SECS: i64 = 5 * 60;

/// What Prism keeps per account (the `policy` row
/// `weekly_activation:<account>`).
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct WeeklyActivationState {
    /// The latest weekly reset a host reported for the account (RFC 3339).
    #[serde(rename = "resetsAt", default)]
    pub resets_at: String,
    /// When Prism last sent the account a prompt (unix seconds).
    #[serde(rename = "pingedAt", default)]
    pub pinged_at: i64,
    /// The prompts sent since the window at `resets_at` ended.
    #[serde(default)]
    pub tries: u32,
}

#[derive(Clone, Debug, PartialEq)]
pub enum WeeklyActivationStep {
    /// Nothing to do: the window runs, or Prism waits.
    Wait,
    /// A window runs until this reset, which Prism has not stored yet.
    Remember(String),
    /// The window ended and no new one has started: send a prompt.
    Activate,
}

/// Decides for one account from the weekly readings the fresh host reports
/// carry for it and what Prism stored.
pub fn weekly_activation_step(
    readings: &[PlacementWindow],
    state: &WeeklyActivationState,
    now: DateTime<Utc>,
) -> WeeklyActivationStep {
    if readings.is_empty() {
        return WeeklyActivationStep::Wait;
    }
    let resets: Vec<(DateTime<Utc>, &str)> = readings
        .iter()
        .filter_map(|w| parse_placement_time(&w.resets_at).map(|t| (t, w.resets_at.as_str())))
        .collect();
    if let Some((latest, raw)) = resets
        .iter()
        .filter(|(t, _)| *t > now)
        .max_by_key(|(t, _)| *t)
    {
        let known = parse_placement_time(&state.resets_at)
            .is_some_and(|t| (t - *latest).num_seconds().abs() < SAME_RESET_SECS);
        return if known {
            WeeklyActivationStep::Wait
        } else {
            WeeklyActivationStep::Remember((*raw).to_string())
        };
    }
    // No running window. Wait out the grace after the reset that ended it.
    let ended = resets
        .iter()
        .map(|(t, _)| *t)
        .chain(parse_placement_time(&state.resets_at))
        .max();
    if ended.is_some_and(|t| (now - t).num_seconds() < WEEKLY_ACTIVATION_GRACE_SECS) {
        return WeeklyActivationStep::Wait;
    }
    if state.tries >= WEEKLY_ACTIVATION_MAX_TRIES
        || (state.tries > 0 && now.timestamp() - state.pinged_at < WEEKLY_ACTIVATION_RETRY_SECS)
    {
        return WeeklyActivationStep::Wait;
    }
    WeeklyActivationStep::Activate
}

/// The account a model key is billed to: the gateway routes `<account>/<model>`
/// to one account.
pub fn account_of(model: &str) -> &str {
    model.split_once('/').map_or(model, |(account, _)| account)
}

/// The gateway's provider name for Antigravity accounts (`AccountUsage::provider`).
pub const ANTIGRAVITY: &str = "antigravity";

/// Whether an Antigravity model bills the third-party allowance ("Claude and
/// GPT models"), which is what an Antigravity account's weekly window
/// (`seven_day`) measures. Gemini has an allowance of its own.
pub fn is_third_party_model(model: &str) -> bool {
    let name = model.split_once('/').map_or(model, |(_, name)| name);
    !name.is_empty() && !name.starts_with("gemini-")
}

/// The model to prompt an Antigravity account on so its third-party weekly
/// window starts: the first third-party model a profile entry names for the
/// account, else the first `<account>/<model>` the gateway lists.
pub fn antigravity_activation_model(
    account: &str,
    named: &[String],
    gateway_models: &[String],
) -> Option<String> {
    let own = |m: &&String| account_of(m) == account && m.len() > account.len();
    named
        .iter()
        .chain(gateway_models)
        .filter(own)
        .find(|m| is_third_party_model(m))
        .cloned()
}

/// One account Prism should send a prompt to now, with the model to send it to.
#[derive(Clone, Debug, PartialEq)]
pub struct WeeklyActivation {
    pub account: String,
    pub model: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn at(s: &str) -> DateTime<Utc> {
        parse_placement_time(s).expect("time")
    }

    fn window(used: f64, resets_at: &str) -> PlacementWindow {
        PlacementWindow {
            used_percent: used,
            resets_at: resets_at.into(),
        }
    }

    #[test]
    fn a_running_window_is_remembered_once() {
        let now = at("2026-11-25T08:00:00Z");
        let readings = [window(37.0, "2026-11-29T10:00:00Z")];
        assert_eq!(
            weekly_activation_step(&readings, &WeeklyActivationState::default(), now),
            WeeklyActivationStep::Remember("2026-11-29T10:00:00Z".into())
        );
        // A host that reports the same reset a minute earlier is the same window.
        let state = WeeklyActivationState {
            resets_at: "2026-11-29T09:59:00Z".into(),
            ..Default::default()
        };
        assert_eq!(
            weekly_activation_step(&readings, &state, now),
            WeeklyActivationStep::Wait
        );
    }

    #[test]
    fn an_ended_window_is_activated_after_the_grace() {
        let state = WeeklyActivationState {
            resets_at: "2026-11-23T20:50:56Z".into(),
            ..Default::default()
        };
        // The host still reports the ended window.
        let readings = [window(100.0, "2026-11-23T20:50:56Z")];
        assert_eq!(
            weekly_activation_step(&readings, &state, at("2026-11-23T20:51:30Z")),
            WeeklyActivationStep::Wait
        );
        assert_eq!(
            weekly_activation_step(&readings, &state, at("2026-11-23T20:52:00Z")),
            WeeklyActivationStep::Activate
        );
        // Or reports no window at all.
        let idle = [window(0.0, "")];
        assert_eq!(
            weekly_activation_step(&idle, &state, at("2026-11-23T20:52:00Z")),
            WeeklyActivationStep::Activate
        );
    }

    #[test]
    fn prompts_are_spaced_and_capped() {
        let now = at("2026-11-23T21:00:00Z");
        let idle = [window(0.0, "")];
        let mut state = WeeklyActivationState {
            resets_at: "2026-11-23T20:50:56Z".into(),
            pinged_at: now.timestamp() - 5 * 60,
            tries: 1,
        };
        assert_eq!(
            weekly_activation_step(&idle, &state, now),
            WeeklyActivationStep::Wait
        );
        state.pinged_at = now.timestamp() - WEEKLY_ACTIVATION_RETRY_SECS;
        assert_eq!(
            weekly_activation_step(&idle, &state, now),
            WeeklyActivationStep::Activate
        );
        state.tries = WEEKLY_ACTIVATION_MAX_TRIES;
        assert_eq!(
            weekly_activation_step(&idle, &state, now),
            WeeklyActivationStep::Wait
        );
        // The new window shows up: remembered, which starts the count over.
        let started = [window(1.0, "2026-11-30T21:00:00Z")];
        assert_eq!(
            weekly_activation_step(&started, &state, now),
            WeeklyActivationStep::Remember("2026-11-30T21:00:00Z".into())
        );
    }

    #[test]
    fn no_reading_no_prompt() {
        assert_eq!(
            weekly_activation_step(
                &[],
                &WeeklyActivationState::default(),
                at("2026-11-25T08:00:00Z")
            ),
            WeeklyActivationStep::Wait
        );
    }

    #[test]
    fn accounts() {
        assert_eq!(account_of("codex-2/gpt-6.2-sol"), "codex-2");
        assert_eq!(account_of("claude-sonnet-5-5"), "claude-sonnet-5-5");
    }

    #[test]
    fn antigravity_accounts_are_prompted_on_a_third_party_model() {
        assert!(is_third_party_model("agy/claude-opus-5-5"));
        assert!(!is_third_party_model("agy-pro/gemini-4-neon"));
        let gateway: Vec<String> = [
            "agy-david/gemini-4-neon",
            "agy-david/claude-opus-4-6-thinking",
            "agy-1/claude-opus-4-6-thinking",
            "agy/claude-opus-5-5",
        ]
        .map(String::from)
        .into();
        assert_eq!(
            antigravity_activation_model("agy-david", &[], &gateway).as_deref(),
            Some("agy-david/claude-opus-4-6-thinking")
        );
        // A third-party model an entry names for the account comes first.
        let named = [
            "agy-david/gemini-4-neon".to_string(),
            "agy-david/gpt-oss".to_string(),
        ];
        assert_eq!(
            antigravity_activation_model("agy-david", &named, &gateway).as_deref(),
            Some("agy-david/gpt-oss")
        );
        // An account the gateway lists no model of its own for.
        assert_eq!(antigravity_activation_model("agy-2", &[], &gateway), None);
    }
}
