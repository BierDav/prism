use crate::types::{
    PlacementActiveHours, PlacementEntryDecision, PlacementEntryInput, PlacementEntryRow,
    PlacementHostModel, PlacementLoad, PlacementMoveTarget, PlacementOption, PlacementPolicy,
    PlacementProfileDecision, PlacementSample, PlacementSelection, PlacementWindow,
};
use chrono::{DateTime, Timelike, Utc};
use chrono_tz::Tz;
use std::collections::BTreeMap;

pub const PLACEMENT_SESSION_HOURS: f64 = 5.0;
pub const PLACEMENT_BURN_WINDOW_SECS: i64 = 45 * 60;
pub const PLACEMENT_MIN_BURN_SPAN_SECS: i64 = 10 * 60;
/// A weekly reset further ahead than this is implausible and treated as unknown.
pub const PLACEMENT_MAX_WEEKLY_RESET_SECS: i64 = 8 * 24 * 60 * 60;

pub fn vienna_tz() -> Tz {
    chrono_tz::Europe::Vienna
}

pub fn model_key(instance_id: &str, model: &str) -> String {
    format!("{instance_id}\0{model}")
}

pub fn same_placement_entry(a: &PlacementSelection, b: &PlacementSelection) -> bool {
    a.instance_id == b.instance_id && a.model == b.model
}

pub fn is_placement_runnable(state: &str) -> bool {
    matches!(state, "ok" | "promoted" | "full")
}

pub fn guess_driver(instance_id: &str) -> String {
    match instance_id {
        "claudeAgent" | "codex" | "opencode" | "cursor" => instance_id.to_string(),
        _ if instance_id.starts_with("agy") || instance_id.starts_with("antigravity") => {
            "antigravity".to_string()
        }
        _ => String::new(),
    }
}

pub fn selection_for_entry(
    entry: &PlacementEntryRow,
    option_ids: &BTreeMap<String, String>,
) -> PlacementSelection {
    let mut options = Vec::new();
    if !entry.reasoning.is_empty()
        && let Some(opt_id) = option_ids.get(&model_key(&entry.instance_id, &entry.model))
        && !opt_id.is_empty()
    {
        options.push(PlacementOption {
            id: opt_id.clone(),
            value: entry.reasoning.clone().into(),
        });
    }
    PlacementSelection {
        instance_id: entry.instance_id.clone(),
        model: entry.model.clone(),
        options,
    }
}

pub fn parse_placement_time(s: &str) -> Option<DateTime<Utc>> {
    if s.is_empty() {
        return None;
    }
    DateTime::parse_from_rfc3339(s)
        .ok()
        .map(|dt| dt.with_timezone(&Utc))
}

/// Returns a window's effective reading at `now`. A window whose reset has passed reads as 0%.
pub fn live_used(w: Option<&PlacementWindow>, now: DateTime<Utc>) -> Option<PlacementWindow> {
    let w = w?;
    let mut used = w.used_percent;
    if let Some(reset) = parse_placement_time(&w.resets_at)
        && reset <= now
    {
        used = 0.0;
    }
    let resets_at = if !w.resets_at.is_empty() && used != 0.0 {
        w.resets_at.clone()
    } else {
        String::new()
    };
    Some(PlacementWindow {
        used_percent: used,
        resets_at,
    })
}

fn worst_reading<'a>(
    a: Option<&'a PlacementWindow>,
    b: Option<&'a PlacementWindow>,
) -> Option<&'a PlacementWindow> {
    match (a, b) {
        (None, None) => None,
        (Some(w), None) | (None, Some(w)) => Some(w),
        (Some(wa), Some(wb)) => {
            if wb.used_percent > wa.used_percent {
                Some(wb)
            } else {
                Some(wa)
            }
        }
    }
}

/// Returns the hours from `now` to `until` that fall inside `active_hours` in `tz`
/// (every hour when `active_hours` is `None`).
pub fn usable_hours(
    now: DateTime<Utc>,
    until: DateTime<Utc>,
    active_hours: Option<&PlacementActiveHours>,
    tz: Tz,
) -> f64 {
    if until <= now {
        return 0.0;
    }
    let Some(active) = active_hours else {
        return (until - now).num_milliseconds() as f64 / 3_600_000.0;
    };
    let inside = |hour: i32| -> bool {
        if active.start < active.end {
            hour >= active.start && hour < active.end
        } else {
            hour >= active.start || hour < active.end
        }
    };
    let mut total_ms: i64 = 0;
    let mut at = now;
    while at < until {
        let secs = at.timestamp();
        let next_hour_secs = secs - secs.rem_euclid(3600) + 3600;
        let next_hour = DateTime::from_timestamp(next_hour_secs, 0).unwrap_or(until);
        let step_end = if until < next_hour { until } else { next_hour };
        let hour = at.with_timezone(&tz).hour() as i32;
        if inside(hour) {
            total_ms += (step_end - at).num_milliseconds();
        }
        at = step_end;
    }
    total_ms as f64 / 3_600_000.0
}

fn format_percent(v: f64) -> String {
    format!("{}%", v.round() as i64)
}

/// How long before a limit runs out, at the current pace, a running thread is
/// interrupted and compacted so it can move before the limit is hit.
pub const PLACEMENT_PREEMPT_LEAD_SECS: i64 = 10 * 60;

fn resets_within_wait(window: &PlacementWindow, now: DateTime<Utc>) -> bool {
    parse_placement_time(&window.resets_at)
        .is_some_and(|reset| (reset - now).num_seconds() <= PLACEMENT_LIMIT_WAIT_SECS)
}

/// Minutes until a window at `used` percent reaches 100% at `percent_per_hour`.
fn minutes_to_limit(used: f64, percent_per_hour: f64) -> Option<f64> {
    (percent_per_hour > 0.0).then(|| (100.0 - used).max(0.0) / percent_per_hour * 60.0)
}

fn preempt_reason(
    session: Option<&PlacementWindow>,
    weekly: Option<&PlacementWindow>,
    entry: &PlacementEntryInput,
    policy: &PlacementPolicy,
    load: &PlacementLoad,
    now: DateTime<Utc>,
) -> Option<String> {
    let lead_minutes = PLACEMENT_PREEMPT_LEAD_SECS as f64 / 60.0;
    // Session percent per hour at the current load, counting at least this thread.
    let session_rate = load
        .burn_per_thread_per_hour
        .map(|burn| burn * f64::from(load.running.max(1)));
    if let Some(sess) = session
        && !resets_within_wait(sess, now)
    {
        if sess.used_percent >= policy.switch_at_percent {
            return Some(format!(
                "The session limit is at {}, at or past the {} switch point.",
                format_percent(sess.used_percent),
                format_percent(policy.switch_at_percent)
            ));
        }
        if let Some(minutes) =
            session_rate.and_then(|rate| minutes_to_limit(sess.used_percent, rate))
            && minutes <= lead_minutes
        {
            return Some(format!(
                "The session limit is at {} and runs out in about {} min at the current pace.",
                format_percent(sess.used_percent),
                minutes.round().max(1.0)
            ));
        }
    }
    if let (Some(wk), Some(rate), Some(share)) =
        (weekly, session_rate, entry.session_share_of_weekly)
        && !resets_within_wait(wk, now)
        && let Some(minutes) = minutes_to_limit(wk.used_percent, rate * share / 100.0)
        && minutes <= lead_minutes
    {
        return Some(format!(
            "The weekly limit is at {} and runs out in about {} min at the current pace.",
            format_percent(wk.used_percent),
            minutes.round().max(1.0)
        ));
    }
    None
}

pub fn decide_entry(
    entry: &PlacementEntryInput,
    policy: &PlacementPolicy,
    host_model: Option<&PlacementHostModel>,
    load: &PlacementLoad,
    now: DateTime<Utc>,
    tz: Tz,
) -> PlacementEntryDecision {
    let mut base = PlacementEntryDecision {
        selection: entry.selection.clone(),
        state: String::new(),
        reason: String::new(),
        session: None,
        weekly: None,
        running_threads: load.running,
        predicted_session_percent: None,
        weekly_slack_sessions: None,
        compact_before_limit: None,
    };
    if !entry.enabled {
        base.state = "disabled".into();
        base.reason = "Turned off in the profile.".into();
        return base;
    }
    let Some(host_model) = host_model else {
        base.state = "unavailable".into();
        base.reason = "Skipped: its provider instance is not configured.".into();
        return base;
    };
    if let Some(blocked) = &host_model.blocked
        && !blocked.is_empty()
    {
        base.state = "unavailable".into();
        base.reason = format!("Skipped: {blocked}.");
        return base;
    }

    let session = live_used(host_model.session.as_ref(), now);
    let weekly = live_used(host_model.weekly.as_ref(), now);
    let all = worst_reading(session.as_ref(), weekly.as_ref()).cloned();
    base.session = session.clone();
    base.weekly = weekly.clone();

    if let Some(all_win) = &all
        && all_win.used_percent >= 100.0
    {
        base.state = "exhausted".into();
        if !all_win.resets_at.is_empty() {
            base.reason = format!("Out of usage until {}.", all_win.resets_at);
        } else {
            base.reason = "Out of usage.".into();
        }
        return base;
    }
    let mut weekly_slack_sessions = None;
    if let (Some(share), Some(wk)) = (entry.session_share_of_weekly, &weekly)
        && share > 0.0
        && !wk.resets_at.is_empty()
        && let Some(reset) = parse_placement_time(&wk.resets_at)
        && (reset - now).num_seconds() <= PLACEMENT_MAX_WEEKLY_RESET_SECS
    {
        let needed = (100.0 - wk.used_percent) / share;
        let left =
            usable_hours(now, reset, policy.active_hours.as_ref(), tz) / PLACEMENT_SESSION_HOURS;
        let mut slack = ((left - needed) * 10.0).round() / 10.0;
        if slack == 0.0 {
            slack = 0.0;
        }
        weekly_slack_sessions = Some(slack);
        base.weekly_slack_sessions = weekly_slack_sessions;
    }

    // Interrupt and compact before a limit is hit, while the account can still
    // run the compaction: when the session reaches the switch point, or when
    // the session or weekly window would run out within PLACEMENT_PREEMPT_LEAD_SECS
    // at the current pace. Two cases run into the limit instead. An entry whose
    // weekly allowance still has to be spent (slack at most weeklyMoveSlackSessions)
    // keeps going until it is exhausted, and then moves without compaction (the
    // spent account cannot run one); its running threads are compacted in place
    // at the same point instead (compact_before_limit), so that move hands over
    // a short context. A window that resets within PLACEMENT_LIMIT_WAIT_SECS is
    // waited for (placement_waits_for_reset), which keeps the prompt cache.
    let fills_weekly = weekly_slack_sessions.is_some_and(|s| s <= policy.weekly_move_slack());
    if let Some(reason) =
        preempt_reason(session.as_ref(), weekly.as_ref(), entry, policy, load, now)
    {
        if !fills_weekly {
            base.state = "over_threshold".into();
            base.reason = reason;
            return base;
        }
        base.compact_before_limit = Some(reason);
    }

    let mut predicted_session_percent = None;
    if let (Some(sess), Some(burn)) = (&session, load.burn_per_thread_per_hour) {
        let hours_left = parse_placement_time(&sess.resets_at)
            .map(|reset| ((reset - now).num_milliseconds() as f64 / 3_600_000.0).max(0.0))
            .unwrap_or(PLACEMENT_SESSION_HOURS);
        let pred = sess.used_percent + burn * f64::from(load.running + 1) * hours_left;
        predicted_session_percent = Some(pred);
        base.predicted_session_percent = predicted_session_percent;
    }

    if let Some(pred) = predicted_session_percent
        && pred > policy.switch_at_percent
    {
        base.state = "full".into();
        let plural = if load.running == 1 { "" } else { "s" };
        base.reason = format!(
            "{} running thread{plural} plus one more would reach about {} of the session window before it resets.",
            load.running,
            format_percent(pred)
        );
        return base;
    }

    if let Some(slack) = weekly_slack_sessions
        && slack <= policy.weekly_slack_sessions
    {
        base.state = "promoted".into();
        base.reason = format!(
            "Preferred: using the rest of the weekly allowance takes all but {slack} of the session windows left before the weekly reset."
        );
        return base;
    }

    base.state = "ok".into();
    if let Some(sess) = &session {
        base.reason = format!("Session window at {}.", format_percent(sess.used_percent));
    } else {
        base.reason = "No usage reported; used in profile order.".into();
    }
    base
}

#[allow(clippy::too_many_arguments)]
pub fn rank_placement_profile(
    id: &str,
    label: &str,
    entries: &[PlacementEntryInput],
    policy: &PlacementPolicy,
    host_models: &BTreeMap<String, PlacementHostModel>,
    loads: &BTreeMap<String, PlacementLoad>,
    now: DateTime<Utc>,
    tz: Tz,
) -> PlacementProfileDecision {
    let mut decided: Vec<(usize, PlacementEntryDecision)> = entries
        .iter()
        .enumerate()
        .map(|(index, entry)| {
            let key = model_key(&entry.selection.instance_id, &entry.selection.model);
            let hm = host_models.get(&key);
            let load = loads
                .get(&entry.selection.model)
                .cloned()
                .unwrap_or_default();
            (index, decide_entry(entry, policy, hm, &load, now, tz))
        })
        .collect();

    let group = |state: &str| -> u8 {
        match state {
            "promoted" => 0,
            "ok" => 1,
            "full" => 2,
            _ => 3,
        }
    };

    decided.sort_by(|(idx_a, dec_a), (idx_b, dec_b)| {
        let ga = group(&dec_a.state);
        let gb = group(&dec_b.state);
        if ga != gb {
            return ga.cmp(&gb);
        }
        if dec_a.state == "promoted" && dec_b.state == "promoted" {
            let sa = dec_a.weekly_slack_sessions.unwrap_or(0.0);
            let sb = dec_b.weekly_slack_sessions.unwrap_or(0.0);
            if let Some(ord) = sa.partial_cmp(&sb)
                && ord != std::cmp::Ordering::Equal
            {
                return ord;
            }
        }
        idx_a.cmp(idx_b)
    });

    let mut next = None;
    let mut ranked = Vec::with_capacity(decided.len());
    for (_, decision) in decided {
        if next.is_none() && is_placement_runnable(&decision.state) {
            next = Some(decision.selection.clone());
        }
        ranked.push(decision);
    }

    PlacementProfileDecision {
        id: id.to_string(),
        label: label.to_string(),
        next,
        ranked,
    }
}

/// How soon a spent limit must reset for a thread to wait for it on the same
/// account, which keeps the provider's prompt cache.
pub const PLACEMENT_LIMIT_WAIT_SECS: i64 = 30 * 60;

/// Whether a thread on an exhausted entry waits for its limits to reset
/// instead of moving: every spent window resets within PLACEMENT_LIMIT_WAIT_SECS
/// and no other entry is about to waste its weekly allowance (slack at most
/// weeklyMoveSlackSessions).
pub fn placement_waits_for_reset(
    decision: &PlacementProfileDecision,
    current: &PlacementSelection,
    policy: &PlacementPolicy,
    now: DateTime<Utc>,
) -> bool {
    let limit = policy.weekly_move_slack();
    let mut own = None;
    for e in &decision.ranked {
        if same_placement_entry(&e.selection, current) {
            own = Some(e);
        } else if e.state == "promoted" && e.weekly_slack_sessions.is_some_and(|s| s <= limit) {
            return false;
        }
    }
    let Some(own) = own.filter(|o| o.state == "exhausted") else {
        return false;
    };
    let mut spent = false;
    for w in [own.session.as_ref(), own.weekly.as_ref()]
        .into_iter()
        .flatten()
    {
        if w.used_percent < 100.0 {
            continue;
        }
        match parse_placement_time(&w.resets_at) {
            Some(reset) if (reset - now).num_seconds() <= PLACEMENT_LIMIT_WAIT_SECS => spent = true,
            _ => return false,
        }
    }
    spent
}

/// Where a running thread on a runnable entry should move so that a weekly
/// allowance is not wasted: the most urgent promoted entry whose slack is at
/// most the policy's weeklyMoveSlackSessions, when the thread's own entry has
/// more slack than that. None to stay.
pub fn placement_drain_target(
    decision: &PlacementProfileDecision,
    current: &PlacementSelection,
    policy: &PlacementPolicy,
) -> Option<PlacementMoveTarget> {
    let limit = policy.weekly_move_slack();
    let urgent = |d: &PlacementEntryDecision| d.weekly_slack_sessions.is_some_and(|s| s <= limit);
    if decision.ranked.iter().any(|d| {
        same_placement_entry(&d.selection, current)
            && (!is_placement_runnable(&d.state) || urgent(d))
    }) {
        return None;
    }
    let to = decision.ranked.iter().find(|d| {
        d.state == "promoted" && urgent(d) && !same_placement_entry(&d.selection, current)
    })?;
    let slack = to.weekly_slack_sessions.unwrap_or_default();
    Some(PlacementMoveTarget {
        to: to.selection.clone(),
        reason: format!(
            "{} would leave weekly allowance unused: using it takes all but {slack} of the session windows left before its weekly reset.",
            to.selection.model
        ),
    })
}

/// A move onto the thread's own entry, which T3 carries out as interrupt,
/// `/compact` on this account, and continue here: for a running thread on an
/// entry that runs into its limit on purpose and is close to it
/// (`compact_before_limit`). None when the entry is not close or not runnable.
pub fn placement_compact_target(
    decision: &PlacementProfileDecision,
    current: &PlacementSelection,
) -> Option<PlacementMoveTarget> {
    let own = decision
        .ranked
        .iter()
        .find(|d| same_placement_entry(&d.selection, current))
        .filter(|d| is_placement_runnable(&d.state))?;
    let why = own.compact_before_limit.as_ref()?;
    Some(PlacementMoveTarget {
        to: own.selection.clone(),
        reason: format!(
            "Compacting before the limit, to hand over a short context when it is reached: {why} {} still has weekly allowance to spend, so the thread stays on it until then.",
            current.model
        ),
    })
}

/// Since when an in-place compaction of a thread on `current` counts as done:
/// the start of the entry's session window, or one window back when it is
/// unknown.
pub fn placement_compact_window_start(
    decision: &PlacementProfileDecision,
    current: &PlacementSelection,
    now: DateTime<Utc>,
) -> i64 {
    let window_secs = (PLACEMENT_SESSION_HOURS * 3600.0) as i64;
    decision
        .ranked
        .iter()
        .find(|d| same_placement_entry(&d.selection, current))
        .and_then(|d| d.session.as_ref())
        .and_then(|w| parse_placement_time(&w.resets_at))
        .map_or(now.timestamp() - window_secs, |reset| {
            (reset.timestamp() - window_secs).min(now.timestamp())
        })
}

pub fn placement_move_target(
    decision: &PlacementProfileDecision,
    current: &PlacementSelection,
) -> Option<PlacementMoveTarget> {
    let own = decision
        .ranked
        .iter()
        .find(|d| same_placement_entry(&d.selection, current));
    if let Some(own) = own
        && is_placement_runnable(&own.state)
    {
        return None;
    }
    let to = decision
        .ranked
        .iter()
        .find(|d| is_placement_runnable(&d.state) && !same_placement_entry(&d.selection, current))
        .map(|d| d.selection.clone())?;
    let reason = match own {
        None => format!(
            "{} is not part of the {:?} profile",
            current.model, decision.label
        ),
        Some(own) => own.reason.clone(),
    };
    Some(PlacementMoveTarget { to, reason })
}

/// Calculates session percent burned per running thread per hour from samples ordered by `at`
/// ascending. A drop of > 0.5% indicates a window reset and discards earlier samples.
pub fn compute_burn_rate(samples: &[PlacementSample], now: i64) -> Option<f64> {
    let mut kept: Vec<&PlacementSample> = Vec::new();
    for s in samples {
        if now - s.at > PLACEMENT_BURN_WINDOW_SECS {
            continue;
        }
        if let Some(prev) = kept.last()
            && s.used + 0.5 < prev.used
        {
            kept.clear();
        }
        kept.push(s);
    }
    if kept.len() < 2 {
        return None;
    }
    let first = kept.first()?;
    let last = kept.last()?;
    let span = last.at - first.at;
    if span < PLACEMENT_MIN_BURN_SPAN_SECS {
        return None;
    }
    let sum_running: i32 = kept.iter().map(|s| s.running).sum();
    let mean_running = f64::from(sum_running) / (kept.len() as f64);
    if mean_running < 0.5 {
        return None;
    }
    let per_thread = (last.used - first.used) / (span as f64 / 3600.0) / mean_running;
    Some(per_thread.max(0.0))
}
