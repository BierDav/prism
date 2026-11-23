use chrono::{DateTime, Duration, TimeZone, Utc};
use chrono_tz::Tz;
use rocky_prism::{
    PlacementActiveHours, PlacementEntryInput, PlacementHostModel, PlacementLoad, PlacementMoveAck,
    PlacementPolicy, PlacementSample, PlacementSelection, PlacementSyncModel,
    PlacementSyncReasoning, PlacementSyncRequest, PlacementSyncThread, PlacementWindow,
    PrismClient, PrismError, PutPlacementEntryInput, PutPlacementProfileRequest,
    SetPlacementActionsRequest, compute_burn_rate, decide_entry, model_key, placement_drain_target,
    placement_move_target, placement_waits_for_reset, rank_placement_profile, same_placement_entry,
    usable_hours,
};
use std::collections::BTreeMap;

fn sel(instance_id: &str, model: &str) -> PlacementSelection {
    PlacementSelection {
        instance_id: instance_id.into(),
        model: model.into(),
        options: Vec::new(),
    }
}

fn utc_dt(y: i32, m: u32, d: u32, hh: u32, mm: u32, ss: u32) -> DateTime<Utc> {
    Utc.with_ymd_and_hms(y, m, d, hh, mm, ss)
        .single()
        .expect("valid test timestamp")
}

#[test]
fn test_placement_ranking() {
    let now = utc_dt(2026, 11, 21, 14, 30, 0);
    let hours = |h: f64| -> String {
        (now + Duration::milliseconds((h * 3_600_000.0) as i64))
            .to_rfc3339_opts(chrono::SecondsFormat::Secs, true)
    };
    let david = sel("claudeAgent", "claude-david/claude-opus-5-5");
    let one = sel("claudeAgent", "claude-1/claude-opus-5-5");
    let alex = sel("claudeAgent", "claude-alex/claude-opus-5-5");

    let host_model = |session: f64, weekly: f64, weekly_reset_hours: f64| PlacementHostModel {
        blocked: None,
        session: Some(PlacementWindow {
            used_percent: session,
            resets_at: hours(3.0),
        }),
        weekly: Some(PlacementWindow {
            used_percent: weekly,
            resets_at: hours(weekly_reset_hours),
        }),
    };
    let zero_loads = BTreeMap::new();
    let policy = PlacementPolicy::default();

    // 1. keeps profile order while every account has room
    {
        let mut hm = BTreeMap::new();
        hm.insert(
            model_key(&one.instance_id, &one.model),
            host_model(10.0, 20.0, 72.0),
        );
        hm.insert(
            model_key(&david.instance_id, &david.model),
            host_model(5.0, 10.0, 72.0),
        );
        let dec = rank_placement_profile(
            "coding",
            "Coding",
            &[
                PlacementEntryInput {
                    selection: one.clone(),
                    enabled: true,
                    session_share_of_weekly: None,
                },
                PlacementEntryInput {
                    selection: david.clone(),
                    enabled: true,
                    session_share_of_weekly: None,
                },
            ],
            &policy,
            &hm,
            &zero_loads,
            now,
            Tz::UTC,
        );
        assert!(
            dec.next
                .as_ref()
                .is_some_and(|n| same_placement_entry(n, &one))
        );
        assert_eq!(dec.ranked[0].state, "ok");
        assert_eq!(dec.ranked[1].state, "ok");
    }

    // 2. skips account over switch threshold
    {
        let mut hm = BTreeMap::new();
        hm.insert(
            model_key(&david.instance_id, &david.model),
            host_model(97.0, 50.0, 72.0),
        );
        hm.insert(
            model_key(&one.instance_id, &one.model),
            host_model(10.0, 20.0, 72.0),
        );
        let dec = rank_placement_profile(
            "coding",
            "Coding",
            &[
                PlacementEntryInput {
                    selection: david.clone(),
                    enabled: true,
                    session_share_of_weekly: None,
                },
                PlacementEntryInput {
                    selection: one.clone(),
                    enabled: true,
                    session_share_of_weekly: None,
                },
            ],
            &policy,
            &hm,
            &zero_loads,
            now,
            Tz::UTC,
        );
        assert!(
            dec.next
                .as_ref()
                .is_some_and(|n| same_placement_entry(n, &one))
        );
        assert_eq!(dec.ranked[1].state, "over_threshold");
        assert_eq!(
            dec.ranked[0].session.as_ref().map(|s| s.used_percent),
            Some(10.0)
        );
    }

    // 2b. the weekly window has no switch point: past 95% it is still
    // spent, whenever it resets, and only 100% stops.
    for (weekly, reset, want_david) in [(97.0, 1.0, true), (97.0, 72.0, true), (100.0, 1.0, false)]
    {
        let mut hm = BTreeMap::new();
        hm.insert(
            model_key(&one.instance_id, &one.model),
            host_model(10.0, 20.0, 72.0),
        );
        hm.insert(
            model_key(&david.instance_id, &david.model),
            host_model(10.0, weekly, reset),
        );
        let dec = rank_placement_profile(
            "coding",
            "Coding",
            &[
                PlacementEntryInput {
                    selection: one.clone(),
                    enabled: true,
                    session_share_of_weekly: Some(13.2),
                },
                PlacementEntryInput {
                    selection: david.clone(),
                    enabled: true,
                    session_share_of_weekly: Some(13.7),
                },
            ],
            &policy,
            &hm,
            &zero_loads,
            now,
            Tz::UTC,
        );
        let state = &dec
            .ranked
            .iter()
            .find(|r| same_placement_entry(&r.selection, &david))
            .unwrap()
            .state;
        assert_eq!(
            state != "over_threshold" && state != "exhausted",
            want_david,
            "weekly {weekly}% resetting in {reset}h: david is {state}"
        );
    }

    // 3. promotes account when weekly allowance needs nearly every window left
    {
        let mut hm = BTreeMap::new();
        hm.insert(
            model_key(&one.instance_id, &one.model),
            host_model(0.0, 65.0, 34.5),
        );
        hm.insert(
            model_key(&david.instance_id, &david.model),
            host_model(9.0, 74.0, 19.5),
        );
        let dec = rank_placement_profile(
            "coding",
            "Coding",
            &[
                PlacementEntryInput {
                    selection: one.clone(),
                    enabled: true,
                    session_share_of_weekly: Some(13.2),
                },
                PlacementEntryInput {
                    selection: david.clone(),
                    enabled: true,
                    session_share_of_weekly: Some(13.7),
                },
            ],
            &policy,
            &hm,
            &zero_loads,
            now,
            Tz::UTC,
        );
        assert!(
            dec.next
                .as_ref()
                .is_some_and(|n| same_placement_entry(n, &david))
        );
        assert_eq!(dec.ranked[0].state, "promoted");
        assert_eq!(dec.ranked[0].weekly_slack_sessions, Some(2.0));
        assert_eq!(dec.ranked[1].state, "ok");
        assert_eq!(dec.ranked[1].weekly_slack_sessions, Some(4.2));
    }

    // 4. usableHours respects activeHours
    {
        assert_eq!(
            usable_hours(now, now + Duration::hours(24), None, Tz::UTC),
            24.0
        );
        let midnight = utc_dt(2026, 11, 21, 0, 0, 0);
        let day = midnight + Duration::hours(24);
        assert_eq!(
            usable_hours(
                midnight,
                day,
                Some(&PlacementActiveHours { start: 7, end: 24 }),
                Tz::UTC
            ),
            17.0
        );
        assert_eq!(
            usable_hours(
                midnight,
                day,
                Some(&PlacementActiveHours { start: 22, end: 6 }),
                Tz::UTC
            ),
            8.0
        );
    }

    // 5. marks account full when running threads plus one more would exceed threshold
    {
        let mut hm = BTreeMap::new();
        hm.insert(
            model_key(&david.instance_id, &david.model),
            host_model(50.0, 20.0, 72.0),
        );
        hm.insert(
            model_key(&one.instance_id, &one.model),
            host_model(10.0, 20.0, 72.0),
        );
        let mut loads = BTreeMap::new();
        loads.insert(
            david.model.clone(),
            PlacementLoad {
                running: 2,
                burn_per_thread_per_hour: Some(6.0),
            },
        );
        loads.insert(
            one.model.clone(),
            PlacementLoad {
                running: 0,
                burn_per_thread_per_hour: Some(6.0),
            },
        );
        let dec = rank_placement_profile(
            "coding",
            "Coding",
            &[
                PlacementEntryInput {
                    selection: david.clone(),
                    enabled: true,
                    session_share_of_weekly: None,
                },
                PlacementEntryInput {
                    selection: one.clone(),
                    enabled: true,
                    session_share_of_weekly: None,
                },
            ],
            &policy,
            &hm,
            &loads,
            now,
            Tz::UTC,
        );
        assert!(
            dec.next
                .as_ref()
                .is_some_and(|n| same_placement_entry(n, &one))
        );
        assert_eq!(dec.ranked[1].state, "full");
        let pred = dec.ranked[1]
            .predicted_session_percent
            .expect("predicted session percent");
        assert!((pred - 104.0).abs() < 0.01);
    }

    // 6. falls back to full account rather than none
    {
        let mut hm = BTreeMap::new();
        hm.insert(
            model_key(&david.instance_id, &david.model),
            host_model(80.0, 20.0, 72.0),
        );
        let mut loads = BTreeMap::new();
        loads.insert(
            david.model.clone(),
            PlacementLoad {
                running: 3,
                burn_per_thread_per_hour: Some(10.0),
            },
        );
        let dec = rank_placement_profile(
            "coding",
            "Coding",
            &[PlacementEntryInput {
                selection: david.clone(),
                enabled: true,
                session_share_of_weekly: None,
            }],
            &policy,
            &hm,
            &loads,
            now,
            Tz::UTC,
        );
        assert!(
            dec.next
                .as_ref()
                .is_some_and(|n| same_placement_entry(n, &david))
        );
    }

    // 7. skips disabled and exhausted entries
    {
        let mut hm = BTreeMap::new();
        hm.insert(
            model_key(&david.instance_id, &david.model),
            host_model(0.0, 0.0, 72.0),
        );
        hm.insert(
            model_key(&one.instance_id, &one.model),
            host_model(100.0, 40.0, 72.0),
        );
        hm.insert(
            model_key(&alex.instance_id, &alex.model),
            host_model(30.0, 4.0, 72.0),
        );
        let dec = rank_placement_profile(
            "coding",
            "Coding",
            &[
                PlacementEntryInput {
                    selection: david.clone(),
                    enabled: false,
                    session_share_of_weekly: None,
                },
                PlacementEntryInput {
                    selection: one.clone(),
                    enabled: true,
                    session_share_of_weekly: None,
                },
                PlacementEntryInput {
                    selection: alex.clone(),
                    enabled: true,
                    session_share_of_weekly: None,
                },
            ],
            &policy,
            &hm,
            &zero_loads,
            now,
            Tz::UTC,
        );
        assert!(
            dec.next
                .as_ref()
                .is_some_and(|n| same_placement_entry(n, &alex))
        );
        assert_eq!(dec.ranked[0].state, "ok");
        assert_eq!(dec.ranked[1].state, "disabled");
        assert_eq!(dec.ranked[2].state, "exhausted");
    }

    // 8. reads expired reset window as unused
    {
        let mut hm = BTreeMap::new();
        hm.insert(
            model_key(&david.instance_id, &david.model),
            PlacementHostModel {
                blocked: None,
                session: Some(PlacementWindow {
                    used_percent: 100.0,
                    resets_at: hours(-1.0),
                }),
                weekly: None,
            },
        );
        let dec = rank_placement_profile(
            "coding",
            "Coding",
            &[PlacementEntryInput {
                selection: david.clone(),
                enabled: true,
                session_share_of_weekly: None,
            }],
            &policy,
            &hm,
            &zero_loads,
            now,
            Tz::UTC,
        );
        assert_eq!(dec.ranked[0].state, "ok");
        assert_eq!(
            dec.ranked[0].session.as_ref().map(|s| s.used_percent),
            Some(0.0)
        );
    }

    // 9. placementMoveTarget
    {
        let mut hm = BTreeMap::new();
        hm.insert(
            model_key(&david.instance_id, &david.model),
            host_model(96.0, 50.0, 72.0),
        );
        hm.insert(
            model_key(&one.instance_id, &one.model),
            host_model(10.0, 20.0, 72.0),
        );
        let dec = rank_placement_profile(
            "coding",
            "Coding",
            &[
                PlacementEntryInput {
                    selection: david.clone(),
                    enabled: true,
                    session_share_of_weekly: None,
                },
                PlacementEntryInput {
                    selection: one.clone(),
                    enabled: true,
                    session_share_of_weekly: None,
                },
            ],
            &policy,
            &hm,
            &zero_loads,
            now,
            Tz::UTC,
        );
        let m = placement_move_target(&dec, &david).expect("move david");
        assert!(same_placement_entry(&m.to, &one));
        assert!(placement_move_target(&dec, &one).is_none());
        let m_alex = placement_move_target(&dec, &alex).expect("move alex");
        assert!(!m_alex.reason.is_empty());
    }

    // 10. computeBurnRate handles window reset
    {
        let base = now.timestamp();
        let samples = vec![
            PlacementSample {
                account: david.model.clone(),
                at: base - 1800,
                used: 80.0,
                running: 2,
            },
            PlacementSample {
                account: david.model.clone(),
                at: base - 1200,
                used: 10.0,
                running: 2,
            },
            PlacementSample {
                account: david.model.clone(),
                at: base,
                used: 22.0,
                running: 2,
            },
        ];
        let burn = compute_burn_rate(&samples, base).expect("burn rate");
        assert!((burn - 18.0).abs() < 0.01);
    }
}

#[test]
fn test_session_switch_point_skips_a_close_reset() {
    let now = utc_dt(2026, 11, 21, 14, 30, 0);
    let entry = PlacementEntryInput {
        selection: sel("claudeAgent", "claude-david/claude-opus-5-5"),
        enabled: true,
        session_share_of_weekly: None,
    };
    for (reset_minutes, want) in [(24, "ok"), (36, "over_threshold")] {
        let host_model = PlacementHostModel {
            blocked: None,
            session: Some(PlacementWindow {
                used_percent: 97.0,
                resets_at: (now + Duration::minutes(reset_minutes))
                    .to_rfc3339_opts(chrono::SecondsFormat::Secs, true),
            }),
            weekly: Some(PlacementWindow {
                used_percent: 97.0,
                resets_at: (now + Duration::hours(72))
                    .to_rfc3339_opts(chrono::SecondsFormat::Secs, true),
            }),
        };
        let dec = decide_entry(
            &entry,
            &PlacementPolicy::default(),
            Some(&host_model),
            &PlacementLoad::default(),
            now,
            Tz::UTC,
        );
        assert_eq!(dec.state, want, "session reset in {reset_minutes} min");
    }
}

fn paced_state(
    session: (f64, i64),
    weekly: (f64, i64),
    share: f64,
    load: PlacementLoad,
) -> rocky_prism::PlacementEntryDecision {
    let now = utc_dt(2026, 11, 21, 14, 30, 0);
    let window = |(used_percent, minutes): (f64, i64)| PlacementWindow {
        used_percent,
        resets_at: (now + Duration::minutes(minutes))
            .to_rfc3339_opts(chrono::SecondsFormat::Secs, true),
    };
    let entry = PlacementEntryInput {
        selection: sel("claudeAgent", "claude-david/claude-opus-5-5"),
        enabled: true,
        session_share_of_weekly: Some(share),
    };
    let host_model = PlacementHostModel {
        blocked: None,
        session: Some(window(session)),
        weekly: Some(window(weekly)),
    };
    decide_entry(
        &entry,
        &PlacementPolicy::default(),
        Some(&host_model),
        &load,
        now,
        Tz::UTC,
    )
}

#[test]
fn test_pace_interrupts_before_a_limit_runs_out() {
    // Two threads at 30% per hour each spend 60% of the session per hour.
    let load = PlacementLoad {
        running: 2,
        burn_per_thread_per_hour: Some(30.0),
    };
    // 15% left lasts 15 minutes: keep going.
    let dec = paced_state((85.0, 180), (20.0, 72 * 60), 10.0, load.clone());
    assert_ne!(dec.state, "over_threshold", "{}", dec.reason);
    // 8% left lasts 8 minutes, under the lead time: interrupt and compact.
    let dec = paced_state((92.0, 180), (20.0, 72 * 60), 10.0, load.clone());
    assert_eq!(dec.state, "over_threshold");
    assert!(
        dec.reason.contains("session limit is at 92%"),
        "{}",
        dec.reason
    );
    // The same pace near a session reset runs into the limit and waits.
    let dec = paced_state((92.0, 20), (20.0, 72 * 60), 10.0, load.clone());
    assert_ne!(dec.state, "over_threshold", "{}", dec.reason);
    // Weekly: 6% per hour leaves 0.8% for 8 minutes.
    let dec = paced_state((20.0, 180), (99.2, 72 * 60), 10.0, load.clone());
    assert_eq!(dec.state, "over_threshold");
    assert!(
        dec.reason.contains("weekly limit is at 99%"),
        "{}",
        dec.reason
    );
    // Without a known pace only the session switch point applies.
    let dec = paced_state((92.0, 180), (99.2, 72 * 60), 10.0, PlacementLoad::default());
    assert_ne!(dec.state, "over_threshold", "{}", dec.reason);
}

#[test]
fn test_weekly_to_spend_runs_into_the_limit() {
    // Half the weekly allowance is left four hours before its reset: it cannot all
    // be spent, so the thread keeps going past the switch point until the limit.
    let load = PlacementLoad {
        running: 1,
        burn_per_thread_per_hour: Some(30.0),
    };
    let dec = paced_state((97.0, 180), (50.0, 240), 10.0, load);
    assert_ne!(dec.state, "over_threshold", "{}", dec.reason);
    assert!(dec.weekly_slack_sessions.is_some_and(|s| s < 0.0));
}

#[test]
fn test_implausible_weekly_reset_is_unknown() {
    let now = utc_dt(2026, 11, 21, 14, 30, 0);
    let policy = PlacementPolicy {
        active_hours: Some(PlacementActiveHours { start: 8, end: 20 }),
        ..PlacementPolicy::default()
    };
    let host_model = PlacementHostModel {
        blocked: None,
        session: None,
        weekly: Some(PlacementWindow {
            used_percent: 40.0,
            resets_at: "9999-01-01T00:00:00Z".into(),
        }),
    };
    let entry = PlacementEntryInput {
        selection: sel("claudeAgent", "claude-david/claude-opus-5-5"),
        enabled: true,
        session_share_of_weekly: Some(13.7),
    };
    let started = std::time::Instant::now();
    let dec = decide_entry(
        &entry,
        &policy,
        Some(&host_model),
        &PlacementLoad::default(),
        now,
        chrono_tz::Europe::Vienna,
    );
    assert_eq!(dec.weekly_slack_sessions, None);
    assert!(started.elapsed() < std::time::Duration::from_secs(1));
}

#[tokio::test]
async fn test_placement_sync_and_moves() {
    let dir = tempfile::tempdir().expect("tempdir");
    let client = PrismClient::open_or_create(dir.path().display().to_string(), None)
        .await
        .expect("open_or_create");
    let now = utc_dt(2026, 11, 21, 18, 0, 0).timestamp();
    let resets_at = (utc_dt(2026, 11, 21, 18, 0, 0) + Duration::hours(2))
        .to_rfc3339_opts(chrono::SecondsFormat::Secs, true);

    let models = vec![
        PlacementSyncModel {
            instance_id: "claudeAgent".into(),
            model: "claude-david/claude-opus-5-5".into(),
            label: "Opus 5.5 · claude-david".into(),
            driver: String::new(),
            hidden: false,
            provider: String::new(),
            reasoning: Some(PlacementSyncReasoning {
                option_id: "effort".into(),
                levels: vec!["low".into(), "medium".into(), "high".into(), "max".into()],
                default: "high".into(),
            }),
            blocked: None,
            session: Some(PlacementWindow {
                used_percent: 96.0,
                resets_at: resets_at.clone(),
            }),
            weekly: None,
        },
        PlacementSyncModel {
            instance_id: "claudeAgent".into(),
            model: "claude-1/claude-opus-5-5".into(),
            label: "Opus 5.5 · claude-1".into(),
            driver: String::new(),
            hidden: false,
            provider: String::new(),
            reasoning: None,
            blocked: None,
            session: Some(PlacementWindow {
                used_percent: 10.0,
                resets_at,
            }),
            weekly: None,
        },
    ];

    let mut req_a = PlacementSyncRequest {
        v: 1,
        environment_id: "env-a".into(),
        models: models.clone(),
        threads: vec![PlacementSyncThread {
            thread_id: "thr-1".into(),
            title: "Fix something".into(),
            profile: "default".into(),
            selection: sel("claudeAgent", "claude-david/claude-opus-5-5"),
            running: true,
        }],
        moves: Vec::new(),
    };

    let resp_a = client
        .sync_placement("david-mb".into(), req_a.clone(), now)
        .await
        .expect("sync A");
    assert_eq!(resp_a.moves.len(), 1);
    let move_id = resp_a.moves[0].id.clone();
    assert_eq!(resp_a.moves[0].to.model, "claude-1/claude-opus-5-5");

    // Host B also reports a running thread on claude-david -> fleet-wide runningThreads == 2.
    let mut req_b = PlacementSyncRequest {
        v: 1,
        environment_id: "env-b".into(),
        models,
        threads: vec![PlacementSyncThread {
            thread_id: "thr-2".into(),
            title: "Other task".into(),
            profile: "default".into(),
            selection: sel("claudeAgent", "claude-david/claude-opus-5-5"),
            running: true,
        }],
        moves: Vec::new(),
    };
    let resp_b = client
        .sync_placement("david-server".into(), req_b.clone(), now)
        .await
        .expect("sync B");
    assert_eq!(resp_b.moves.len(), 1);
    let move_b = resp_b.moves[0].id.clone();
    let david_ranked = resp_b.profiles[0]
        .ranked
        .iter()
        .find(|r| r.selection.model == "claude-david/claude-opus-5-5")
        .expect("claude-david in ranked");
    assert_eq!(david_ranked.running_threads, 2);

    // Host A acknowledges the move as failed; Prism must not ask again within 2 minutes.
    req_a.moves = vec![PlacementMoveAck {
        id: move_id,
        state: "failed".into(),
        detail: "busy".into(),
    }];
    let resp_a2 = client
        .sync_placement("david-mb".into(), req_a, now)
        .await
        .expect("sync A2");
    assert!(resp_a2.moves.is_empty());

    // An unanswered move is asked again, with its original id, until the thread
    // moves, also after the cooldown; once the host stops reporting the thread,
    // it is dropped rather than left asked.
    for (i, later) in [30, 90, 300].into_iter().enumerate() {
        let resp = client
            .sync_placement("david-server".into(), req_b.clone(), now + later)
            .await
            .unwrap_or_else(|err| panic!("sync B{}: {err}", i + 2));
        let ids: Vec<&str> = resp.moves.iter().map(|m| m.id.as_str()).collect();
        assert_eq!(ids, [move_b.as_str()], "resend at +{later}s");
    }

    req_b.threads.clear();
    client
        .sync_placement("david-server".into(), req_b, now + 330)
        .await
        .expect("sync B4");
    let rows = client
        .machine_placement_moves("david-server".into(), 0)
        .await
        .expect("machine_placement_moves");
    assert!(rows.iter().all(|m| m.state != "asked"));

    client.close().await.expect("close");
}

#[tokio::test]
async fn test_placement_phone_api() {
    let dir = tempfile::tempdir().expect("tempdir");
    let client = PrismClient::open_or_create(dir.path().display().to_string(), None)
        .await
        .expect("open_or_create");
    let now = utc_dt(2026, 11, 21, 18, 0, 0).timestamp();

    let mut report = PlacementSyncRequest {
        v: 1,
        environment_id: String::new(),
        models: vec![PlacementSyncModel {
            instance_id: "claudeAgent".into(),
            model: "claude-david/claude-opus-5-5".into(),
            label: "Opus 5.5 · claude-david".into(),
            driver: String::new(),
            hidden: false,
            provider: String::new(),
            reasoning: Some(PlacementSyncReasoning {
                option_id: "effort".into(),
                levels: vec!["low".into(), "medium".into(), "high".into(), "max".into()],
                default: "high".into(),
            }),
            blocked: None,
            session: Some(PlacementWindow {
                used_percent: 25.0,
                resets_at: String::new(),
            }),
            weekly: None,
        }],
        threads: Vec::new(),
        moves: Vec::new(),
    };
    client
        .sync_placement("david-server".into(), report.clone(), now)
        .await
        .expect("sync");

    let view = client
        .placement_view("david-server".into(), now)
        .await
        .expect("view");
    assert_eq!(view.alert_fix_profile, "default");
    assert_eq!(view.profiles.len(), 3);

    // Reject invalid reasoning level.
    let err = client
        .put_placement_profile(
            "custom".into(),
            PutPlacementProfileRequest {
                label: "Custom".into(),
                entries: vec![PutPlacementEntryInput {
                    instance_id: "claudeAgent".into(),
                    model: "claude-david/claude-opus-5-5".into(),
                    reasoning: "bogus".into(),
                    enabled: true,
                    session_share_of_weekly: None,
                }],
            },
            now,
        )
        .await
        .expect_err("invalid reasoning");
    assert!(matches!(err, PrismError::InvalidInput(_)));

    // Create valid profile.
    client
        .put_placement_profile(
            "custom".into(),
            PutPlacementProfileRequest {
                label: "Custom".into(),
                entries: vec![PutPlacementEntryInput {
                    instance_id: "claudeAgent".into(),
                    model: "claude-david/claude-opus-5-5".into(),
                    reasoning: "high".into(),
                    enabled: true,
                    session_share_of_weekly: None,
                }],
            },
            now,
        )
        .await
        .expect("put custom");
    let view = client
        .placement_view("david-server".into(), now)
        .await
        .expect("view");
    assert_eq!(view.profiles.len(), 4);
    let custom = view.profiles.iter().find(|p| p.id == "custom").unwrap();
    assert_eq!(custom.configured_entry_count, 1);

    // Keep the selected level when a hidden model's reported options change.
    report.models[0].hidden = true;
    report.models[0].reasoning.as_mut().unwrap().levels = vec!["low".into()];
    client
        .sync_placement("david-server".into(), report, now + 1)
        .await
        .expect("refresh model options");
    let rename = PutPlacementProfileRequest {
        label: "Renamed".into(),
        entries: vec![PutPlacementEntryInput {
            instance_id: "claudeAgent".into(),
            model: "claude-david/claude-opus-5-5".into(),
            reasoning: "high".into(),
            enabled: true,
            session_share_of_weekly: None,
        }],
    };
    client
        .put_placement_profile("custom".into(), rename.clone(), now + 1)
        .await
        .expect("rename keeps the existing level");
    assert!(matches!(
        client
            .put_placement_profile("other".into(), rename, now + 1)
            .await,
        Err(PrismError::InvalidInput(_))
    ));

    // Cannot delete alert-fix profile -> Conflict.
    let err = client
        .delete_placement_profile("default".into())
        .await
        .expect_err("delete coding");
    assert!(matches!(err, PrismError::Conflict(_)));

    // Change alert-fix profile to custom.
    client
        .set_alert_fix_profile("custom".into())
        .await
        .expect("set alert fix");
    let view = client
        .placement_view("david-server".into(), now)
        .await
        .expect("view");
    assert_eq!(view.alert_fix_profile, "custom");

    // Reorder profiles.
    client
        .order_placement_profiles(vec![
            "custom".into(),
            "default".into(),
            "review".into(),
            "minor".into(),
        ])
        .await
        .expect("order");
    let view = client
        .placement_view("david-server".into(), now)
        .await
        .expect("view");
    assert_eq!(view.profiles[0].id, "custom");

    // Update policy.
    let mut role_profiles = BTreeMap::new();
    role_profiles.insert("review".into(), "review".into());
    client
        .set_placement_policy(PlacementPolicy {
            switch_at_percent: 90.0,
            weekly_slack_sessions: 2.0,
            weekly_move_slack_sessions: None,
            active_hours: Some(PlacementActiveHours { start: 8, end: 23 }),
            role_profiles,
        })
        .await
        .expect("set policy");
    let view = client
        .placement_view("david-server".into(), now)
        .await
        .expect("view");
    assert_eq!(view.policy.switch_at_percent, 90.0);

    // Delete minor.
    client
        .delete_placement_profile("minor".into())
        .await
        .expect("delete minor");
    let view = client
        .placement_view("david-server".into(), now)
        .await
        .expect("view");
    assert_eq!(view.profiles.len(), 3);

    client.close().await.expect("close");
}

#[tokio::test]
async fn test_placement_full_selection_and_actions() {
    let dir = tempfile::tempdir().expect("tempdir");
    let client = PrismClient::open_or_create(dir.path().display().to_string(), None)
        .await
        .expect("open_or_create");
    let now = utc_dt(2026, 11, 21, 18, 0, 0).timestamp();

    let report = PlacementSyncRequest {
        v: 1,
        environment_id: String::new(),
        models: vec![
            PlacementSyncModel {
                instance_id: "claudeAgent".into(),
                model: "claude-david/claude-opus-5-5".into(),
                label: "Opus 5.5 · claude-david".into(),
                driver: "claudeAgent".into(),
                hidden: true,
                provider: "Claude".into(),
                reasoning: None,
                blocked: None,
                session: None,
                weekly: None,
            },
            PlacementSyncModel {
                instance_id: "agy-pooled".into(),
                model: "agy/gemini-4-neon".into(),
                label: "Gemini 4 Neon".into(),
                driver: String::new(),
                hidden: false,
                provider: String::new(),
                reasoning: None,
                blocked: None,
                session: None,
                weekly: None,
            },
        ],
        threads: Vec::new(),
        moves: Vec::new(),
    };
    client
        .sync_placement("david-server".into(), report.clone(), now)
        .await
        .expect("sync");

    // A new profile with no entries lists every known model, switched off,
    // with its provider (reported, or guessed from the instance).
    client
        .put_placement_profile(
            "fresh".into(),
            PutPlacementProfileRequest {
                label: "Fresh".into(),
                entries: Vec::new(),
            },
            now,
        )
        .await
        .expect("put fresh");
    let view = client
        .placement_view("david-server".into(), now)
        .await
        .expect("view");
    assert!(
        view.models
            .iter()
            .find(|m| m.instance_id == "claudeAgent")
            .unwrap()
            .hidden
    );
    assert!(
        !view
            .models
            .iter()
            .find(|m| m.instance_id == "agy-pooled")
            .unwrap()
            .hidden
    );
    let fresh = view
        .profiles
        .iter()
        .find(|p| p.id == "fresh")
        .expect("fresh profile");
    assert_eq!(fresh.entries.len(), 2);
    for e in &fresh.entries {
        assert!(!e.enabled);
        assert!(e.reasoning.is_empty());
    }
    let claude = fresh
        .entries
        .iter()
        .find(|e| e.model == "claude-david/claude-opus-5-5")
        .expect("claude entry");
    assert_eq!(claude.driver, "claudeAgent");
    assert_eq!(claude.provider, "Claude");
    let gemini = fresh
        .entries
        .iter()
        .find(|e| e.model == "agy/gemini-4-neon")
        .expect("gemini entry");
    assert_eq!(gemini.driver, "antigravity");
    assert_eq!(gemini.provider, "agy-pooled");

    // Stored profile keeps its order.
    let coding = view
        .profiles
        .iter()
        .find(|p| p.id == "default")
        .expect("default profile");
    assert_eq!(coding.entries[0].model, "agy/gemini-4-neon");
    assert_ne!(
        coding.entries.last().expect("last").model,
        "agy/gemini-4-neon"
    );

    // Actions: text generation and a role get profiles; sync tells T3.
    let mut role_profiles = BTreeMap::new();
    role_profiles.insert("research".into(), Some("fresh".into()));
    role_profiles.insert("review".into(), None);
    client
        .set_placement_actions(SetPlacementActionsRequest {
            alert_fix: None,
            text_generation: Some("minor".into()),
            role_profiles: Some(role_profiles),
        })
        .await
        .expect("set actions");
    let view = client
        .placement_view("david-server".into(), now)
        .await
        .expect("view");
    assert_eq!(view.actions.text_generation, "minor");
    assert_eq!(view.actions.alert_fix, "default");
    assert_eq!(
        view.policy
            .role_profiles
            .get("research")
            .map(String::as_str),
        Some("fresh")
    );
    assert!(!view.policy.role_profiles.contains_key("review"));
    assert_eq!(view.roles.len(), 6);

    // Unknown profile rejected.
    let err = client
        .set_placement_actions(SetPlacementActionsRequest {
            alert_fix: None,
            text_generation: Some("nope".into()),
            role_profiles: None,
        })
        .await
        .expect_err("unknown profile");
    assert!(matches!(err, PrismError::InvalidInput(_)));

    let sync_resp = client
        .sync_placement("david-server".into(), report, now)
        .await
        .expect("sync");
    assert_eq!(sync_resp.actions.text_generation, "minor");

    // Profiles in use cannot go; clearing text generation frees minor.
    for id in ["minor", "fresh"] {
        let err = client
            .delete_placement_profile(id.into())
            .await
            .expect_err("in use");
        assert!(matches!(err, PrismError::Conflict(_)));
    }
    client
        .set_placement_actions(SetPlacementActionsRequest {
            alert_fix: None,
            text_generation: Some(String::new()),
            role_profiles: None,
        })
        .await
        .expect("clear text gen");
    client
        .delete_placement_profile("minor".into())
        .await
        .expect("delete minor");

    // Reopen existing database and verify persistence.
    client.close().await.expect("close");
    let reopened = PrismClient::open_or_create(dir.path().display().to_string(), None)
        .await
        .expect("reopen");
    let view2 = reopened
        .placement_view("david-server".into(), now)
        .await
        .expect("view after reopen");
    assert!(view2.profiles.iter().any(|p| p.id == "fresh"));
    assert!(!view2.profiles.iter().any(|p| p.id == "minor"));
    reopened.close().await.expect("close reopened");
}

#[test]
fn test_placement_drain_target() {
    let now = utc_dt(2026, 11, 21, 14, 30, 0);
    let hours = |h: f64| -> String {
        (now + Duration::milliseconds((h * 3_600_000.0) as i64))
            .to_rfc3339_opts(chrono::SecondsFormat::Secs, true)
    };
    let david = sel("claudeAgent", "claude-david/claude-opus-5-5");
    let one = sel("claudeAgent", "claude-1/claude-opus-5-5");
    let window = |used: f64, reset: f64| PlacementWindow {
        used_percent: used,
        resets_at: hours(reset),
    };
    let policy = PlacementPolicy::default();
    let rank = |david_reset: f64| {
        let mut hm = BTreeMap::new();
        hm.insert(
            model_key(&one.instance_id, &one.model),
            PlacementHostModel {
                blocked: None,
                session: Some(window(10.0, 3.0)),
                weekly: Some(window(20.0, 72.0)),
            },
        );
        hm.insert(
            model_key(&david.instance_id, &david.model),
            PlacementHostModel {
                blocked: None,
                session: Some(window(10.0, 3.0)),
                weekly: Some(window(80.0, david_reset)),
            },
        );
        rank_placement_profile(
            "default",
            "Default",
            &[
                PlacementEntryInput {
                    selection: one.clone(),
                    enabled: true,
                    session_share_of_weekly: Some(13.2),
                },
                PlacementEntryInput {
                    selection: david.clone(),
                    enabled: true,
                    session_share_of_weekly: Some(13.7),
                },
            ],
            &policy,
            &hm,
            &BTreeMap::new(),
            now,
            Tz::UTC,
        )
    };
    // 20% left at 13.7% per window needs 1.5 windows; 12 hours hold 2.4: slack 0.9.
    let urgent = rank(12.0);
    assert!(
        placement_drain_target(&urgent, &one, &policy)
            .is_some_and(|t| same_placement_entry(&t.to, &david))
    );
    assert!(placement_drain_target(&urgent, &david, &policy).is_none());
    // 19.5 hours hold 3.9 windows: slack 2.4, preferred for new threads but no move.
    let preferred = rank(19.5);
    assert_eq!(preferred.ranked[0].state, "promoted");
    assert!(placement_drain_target(&preferred, &one, &policy).is_none());
    let wider = PlacementPolicy {
        weekly_move_slack_sessions: Some(3.0),
        ..PlacementPolicy::default()
    };
    assert!(placement_drain_target(&preferred, &one, &wider).is_some());
}

#[test]
fn test_placement_waits_for_reset() {
    let now = utc_dt(2026, 11, 21, 14, 30, 0);
    let at = |h: f64| -> String {
        (now + Duration::milliseconds((h * 3_600_000.0) as i64))
            .to_rfc3339_opts(chrono::SecondsFormat::Secs, true)
    };
    let david = sel("claudeAgent", "claude-david/claude-opus-5-5");
    let one = sel("claudeAgent", "claude-1/claude-opus-5-5");
    let policy = PlacementPolicy::default();
    let rank = |one_session: f64, david_weekly: f64, david_reset: f64| {
        let mut hm = BTreeMap::new();
        hm.insert(
            model_key(&one.instance_id, &one.model),
            PlacementHostModel {
                blocked: None,
                session: Some(PlacementWindow {
                    used_percent: one_session,
                    resets_at: at(0.25),
                }),
                weekly: Some(PlacementWindow {
                    used_percent: 20.0,
                    resets_at: at(72.0),
                }),
            },
        );
        hm.insert(
            model_key(&david.instance_id, &david.model),
            PlacementHostModel {
                blocked: None,
                session: Some(PlacementWindow {
                    used_percent: 10.0,
                    resets_at: at(3.0),
                }),
                weekly: Some(PlacementWindow {
                    used_percent: david_weekly,
                    resets_at: at(david_reset),
                }),
            },
        );
        rank_placement_profile(
            "default",
            "Default",
            &[
                PlacementEntryInput {
                    selection: one.clone(),
                    enabled: true,
                    session_share_of_weekly: Some(13.2),
                },
                PlacementEntryInput {
                    selection: david.clone(),
                    enabled: true,
                    session_share_of_weekly: Some(13.7),
                },
            ],
            &policy,
            &hm,
            &BTreeMap::new(),
            now,
            Tz::UTC,
        )
    };
    assert!(placement_waits_for_reset(
        &rank(100.0, 50.0, 72.0),
        &one,
        &policy,
        now
    ));
    // claude-david at slack 0.9 must take work now.
    assert!(!placement_waits_for_reset(
        &rank(100.0, 80.0, 12.0),
        &one,
        &policy,
        now
    ));
    assert!(!placement_waits_for_reset(
        &rank(50.0, 50.0, 72.0),
        &one,
        &policy,
        now
    ));
    let earlier = now - Duration::minutes(20);
    assert!(!placement_waits_for_reset(
        &rank(100.0, 50.0, 72.0),
        &one,
        &policy,
        earlier
    ));
}
