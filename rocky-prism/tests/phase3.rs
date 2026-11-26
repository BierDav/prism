use rocky_prism::{
    AgentActivityRow, AgentRow, AlertFilter, AndroidReleaseRow, ApprovalRow, HostEventError,
    HostEventReport, MachineRow, PrismClient, ProjectRow, QuestionRow, T3ClientSessionRow,
    T3SessionRow,
};

#[tokio::test]
async fn host_events_open_update_resolve_and_limit() {
    let dir = tempfile::tempdir().expect("tempdir");
    let client = PrismClient::open_or_create(dir.path().display().to_string(), None)
        .await
        .expect("open");

    let state = client
        .record_host_event(
            "david-mb",
            HostEventReport {
                check: "brew-update".into(),
                status: "failed".into(),
                summary: "brew failed".into(),
                output: "line1\nline2\n".into(),
                command: "brew upgrade".into(),
                exit_code: Some(1),
                severity: "warning".into(),
            },
            1_763_470_000,
        )
        .await
        .expect("record failed");
    assert_eq!(state, "firing");

    let open = client.list_alerts(AlertFilter::Open).await.expect("list");
    assert_eq!(open.len(), 1);
    assert_eq!(open[0].host, "david-mb");
    assert_eq!(open[0].check, "brew-update");

    // Repeated failure updates in place without creating a second alert.
    let state = client
        .record_host_event(
            "david-mb",
            HostEventReport {
                check: "brew-update".into(),
                status: "failed".into(),
                summary: "brew still failing".into(),
                ..Default::default()
            },
            1_763_470_100,
        )
        .await
        .expect("record update");
    assert_eq!(state, "firing");
    assert_eq!(
        client
            .list_alerts(AlertFilter::Open)
            .await
            .expect("list")
            .len(),
        1
    );

    // "ok" resolves it.
    let state = client
        .record_host_event(
            "david-mb",
            HostEventReport {
                check: "brew-update".into(),
                status: "ok".into(),
                ..Default::default()
            },
            1_763_470_200,
        )
        .await
        .expect("record ok");
    assert_eq!(state, "resolved");
    assert!(
        client
            .list_alerts(AlertFilter::Open)
            .await
            .expect("list")
            .is_empty()
    );

    // Invalid check name is refused.
    let err = client
        .record_host_event(
            "david-mb",
            HostEventReport {
                check: "BAD CHECK!".into(),
                status: "failed".into(),
                ..Default::default()
            },
            1_763_470_300,
        )
        .await
        .unwrap_err();
    assert!(matches!(err, HostEventError::Invalid(_)));
}

#[tokio::test]
async fn phase3_tables_round_trip() {
    let dir = tempfile::tempdir().expect("tempdir");
    let client = PrismClient::open_or_create(dir.path().display().to_string(), None)
        .await
        .expect("open");

    // T3 sessions & client sessions
    client
        .upsert_t3_session(&T3SessionRow {
            machine: "david-mb".into(),
            origin: "http://david-mb:3773".into(),
            token: "tok-1".into(),
            environment_id: "env-1".into(),
            label: "david-mb".into(),
            access: "full-access".into(),
            paired_at: 100,
            expires_at: 200,
        })
        .await
        .expect("t3 session");
    assert_eq!(client.list_t3_sessions().await.expect("list").len(), 1);

    client
        .upsert_t3_client_session(&T3ClientSessionRow {
            machine: "david-mb".into(),
            origin: "http://david-mb:3773".into(),
            token: "ctok-1".into(),
            environment_id: "env-1".into(),
            expires_at: 500,
        })
        .await
        .expect("t3 client session");
    assert_eq!(
        client
            .list_t3_client_sessions(100)
            .await
            .expect("list")
            .len(),
        1
    );
    assert!(
        client
            .list_t3_client_sessions(600)
            .await
            .expect("expired")
            .is_empty()
    );

    // Approvals & answers
    let opened = client
        .upsert_open_approval(&ApprovalRow {
            approval_id: "apr-1".into(),
            machine: "david-mb".into(),
            thread_id: "t-1".into(),
            request_id: "r-1".into(),
            payload: r#"{"id":"apr-1"}"#.into(),
            state: "open".into(),
            created_at: 100,
            updated_at: 100,
        })
        .await
        .expect("upsert approval");
    assert!(opened);
    assert_eq!(
        client.list_open_approvals(None).await.expect("list").len(),
        1
    );
    client
        .request_approval_answer("req-a1", "apr-1", "bXNn", "sig", "david-pixel", 101)
        .await
        .expect("req answer");
    assert_eq!(
        client
            .claimable_approval_answers(0)
            .await
            .expect("claimable")
            .len(),
        1
    );
    client
        .claim_approval_answer("req-a1", "worker-0", 102, 0)
        .await
        .expect("claim");
    client
        .finish_approval_answer("req-a1", "worker-0", 103, "")
        .await
        .expect("finish");
    client
        .close_approval("apr-1", "answered", 103)
        .await
        .expect("close");
    assert!(
        client
            .list_open_approvals(None)
            .await
            .expect("list")
            .is_empty()
    );

    // Questions & answers
    let q_opened = client
        .upsert_open_question(&QuestionRow {
            question_id: "q-1".into(),
            thread_id: "t-1".into(),
            environment_id: "env-1".into(),
            host: "david-mb".into(),
            client: "david-mb".into(),
            via: String::new(),
            title: "Test".into(),
            payload: r#"{"requestId":"q-1","questions":[]}"#.into(),
            state: "open".into(),
            created_at: 100,
            updated_at: 100,
        })
        .await
        .expect("upsert q");
    assert!(q_opened);
    assert!(client.has_open_question("q-1").await.expect("has q"));
    client
        .request_question_answer("req-q1", "q-1", r#"{"c":"Blue"}"#, "david-pixel", 101)
        .await
        .expect("req q answer");
    assert_eq!(
        client
            .claimable_question_answers(0)
            .await
            .expect("claimable")
            .len(),
        1
    );
    client
        .close_question("q-1", "answered", 102)
        .await
        .expect("close q");
    assert!(!client.has_open_question("q-1").await.expect("has q"));

    // Agents, requests, messages, activity
    client
        .save_agent(&AgentRow {
            thread_id: "t-agent".into(),
            kind: "do".into(),
            title: "Do thing".into(),
            target: "david-mb".into(),
            client: "david-mb".into(),
            host: "david-mb".into(),
            state: "active".into(),
            created_at: 100,
            updated_at: 100,
            next_check: 110,
            ..Default::default()
        })
        .await
        .expect("save agent");
    assert_eq!(client.due_agents(120).await.expect("due").len(), 1);
    client
        .save_agent_activity(&AgentActivityRow {
            agent_id: "t-agent".into(),
            status: "running".into(),
            items_json: "[]".into(),
            updated_at: 105,
        })
        .await
        .expect("save activity");
    assert!(
        client
            .get_agent_activity("t-agent")
            .await
            .expect("get activity")
            .is_some()
    );

    // Machines, projects, settings, android releases
    client
        .upsert_machine(&MachineRow {
            name: "david-mb".into(),
            status_json: r#"{"reachable":true}"#.into(),
            metrics_json: r#"{"online":true}"#.into(),
            updated_at: 100,
        })
        .await
        .expect("machine");
    assert_eq!(client.list_machines().await.expect("machines").len(), 1);

    client
        .replace_projects(&[ProjectRow {
            project_id: "repo:gitea.quickme.at/david/prism".into(),
            name: "prism".into(),
            icon_json: String::new(),
            machines_json: r#"["david-mb"]"#.into(),
            repository: true,
            updated_at: 100,
        }])
        .await
        .expect("projects");
    assert_eq!(client.list_projects().await.expect("projects").len(), 1);

    client
        .set_setting("model", r#"{"model":"gpt-6"}"#, 100)
        .await
        .expect("setting");
    assert_eq!(
        client.get_setting("model").await.expect("get").as_deref(),
        Some(r#"{"model":"gpt-6"}"#)
    );

    let ok = client
        .put_android_release(&AndroidReleaseRow {
            package: "at.quickme.prism".into(),
            version_code: 10,
            version_name: "1.0-10".into(),
            sha256: "abc".into(),
            size: 123,
            uploaded_at: 100,
            uploaded_by: "david-mm".into(),
            stored_on: "prism-worker-0".into(),
        })
        .await
        .expect("put release");
    assert!(ok);
    // Same or lower version code is rejected.
    let conflict = client
        .put_android_release(&AndroidReleaseRow {
            package: "at.quickme.prism".into(),
            version_code: 10,
            version_name: "1.0-10".into(),
            sha256: "abc".into(),
            size: 123,
            uploaded_at: 101,
            uploaded_by: "david-mm".into(),
            stored_on: "prism-worker-0".into(),
        })
        .await
        .expect("put release conflict");
    assert!(!conflict);
    assert_eq!(
        client
            .get_android_release("at.quickme.prism")
            .await
            .expect("get")
            .expect("some")
            .version_code,
        10
    );
}

#[tokio::test]
async fn noop_writes_skip_rocky_transactions() {
    let dir = tempfile::tempdir().expect("tempdir");
    let client = PrismClient::open_or_create(dir.path().display().to_string(), None)
        .await
        .expect("open");

    let machine = MachineRow {
        name: "david-mb".into(),
        status_json: r#"{"name":"david-mb","reachable":true}"#.into(),
        metrics_json: String::new(),
        updated_at: 100,
    };
    let projects = vec![ProjectRow {
        project_id: "host:david-mb/p1".into(),
        name: "prism".into(),
        icon_json: String::new(),
        machines_json: r#"["david-mb"]"#.into(),
        repository: false,
        updated_at: 100,
    }];
    let activity = AgentActivityRow {
        agent_id: "t-1".into(),
        status: "running".into(),
        items_json: "[]".into(),
        updated_at: 100,
    };

    client.upsert_machine(&machine).await.expect("machine");
    client.replace_projects(&projects).await.expect("projects");
    client.set_setting("k", "v", 100).await.expect("setting");
    client
        .save_agent_activity(&activity)
        .await
        .expect("activity");

    // Start observing every table AFTER initial population.
    let mut events = client.observe(None).await.expect("observe");

    // Repeat identical writes (with newer timestamps) and an empty prune:
    // none of these should open a Rocky write transaction.
    client
        .upsert_machine(&MachineRow {
            updated_at: 200,
            ..machine.clone()
        })
        .await
        .expect("noop machine");
    client
        .replace_projects(&[ProjectRow {
            updated_at: 200,
            ..projects[0].clone()
        }])
        .await
        .expect("noop projects");
    client
        .set_setting("k", "v", 200)
        .await
        .expect("noop setting");
    client
        .save_agent_activity(&AgentActivityRow {
            updated_at: 200,
            ..activity.clone()
        })
        .await
        .expect("noop activity");
    client
        .prune_phase3(200, 60)
        .await
        .expect("empty prune");

    // A genuine write after the no-ops should be the ONLY event emitted.
    client
        .set_setting("sentinel", "1", 300)
        .await
        .expect("sentinel");
    let first = tokio::time::timeout(std::time::Duration::from_secs(2), events.next())
        .await
        .expect("event")
        .expect("ok");
    assert_eq!(
        first.tables,
        vec!["settings".to_string()],
        "no-op writes must not emit table change events before sentinel"
    );
    let extra = tokio::time::timeout(std::time::Duration::from_millis(150), events.next()).await;
    assert!(extra.is_err(), "no extra events expected after sentinel");
}

#[tokio::test]
async fn a_request_is_written_once_and_held_by_one_worker_until_stale() {
    let dir = tempfile::tempdir().expect("tempdir");
    let client = PrismClient::open_or_create(dir.path().display().to_string(), None)
        .await
        .expect("open");
    // A device retrying writes the same request ID again.
    for _ in 0..2 {
        client
            .request_agent_message("m-1", "thread-1", "go on", "david-pixel", 100)
            .await
            .expect("request");
    }
    let claimable = client.claimable_agent_messages(0).await.expect("claimable");
    assert_eq!(claimable.len(), 1, "one request, not one per write");

    assert!(
        client
            .claim_agent_message("m-1", "worker-0", 110, 0)
            .await
            .unwrap()
    );
    // Another worker (a new leader) cannot take a fresh claim...
    assert!(
        !client
            .claim_agent_message("m-1", "worker-1", 120, 120 - 300)
            .await
            .unwrap()
    );
    assert!(
        client
            .claimable_agent_messages(120 - 300)
            .await
            .unwrap()
            .is_empty()
    );
    // ...and cannot finish what it does not hold.
    client
        .finish_agent_message("m-1", "worker-1", 121, "stolen")
        .await
        .unwrap();
    assert!(
        client
            .claim_agent_message("m-1", "worker-0", 122, 0)
            .await
            .unwrap(),
        "still worker-0's and not done"
    );
    // Once the claim is stale it can.
    assert!(
        client
            .claim_agent_message("m-1", "worker-1", 500, 500 - 300)
            .await
            .unwrap()
    );
    client
        .finish_agent_message("m-1", "worker-1", 501, "")
        .await
        .unwrap();
    // Done: nobody claims it again, and a late finish of the old holder is ignored.
    assert!(
        !client
            .claim_agent_message("m-1", "worker-0", 600, 600)
            .await
            .unwrap()
    );
    client
        .finish_agent_message("m-1", "worker-0", 601, "late")
        .await
        .unwrap();
    assert!(
        client
            .claimable_agent_messages(1000)
            .await
            .unwrap()
            .is_empty()
    );
}

#[tokio::test]
async fn finished_requests_and_closed_rows_are_pruned_after_a_week() {
    let dir = tempfile::tempdir().expect("tempdir");
    let client = PrismClient::open_or_create(dir.path().display().to_string(), None)
        .await
        .expect("open");
    const WEEK: i64 = 7 * 24 * 60 * 60;
    for (id, at) in [("old", 100), ("new", 100 + WEEK)] {
        client
            .request_agent_message(id, "thread-1", "go on", "david-pixel", at)
            .await
            .unwrap();
        assert!(client.claim_agent_message(id, "w", at, 0).await.unwrap());
        client.finish_agent_message(id, "w", at, "").await.unwrap();
    }
    client
        .request_agent_message("open", "thread-1", "go on", "david-pixel", 100)
        .await
        .unwrap();
    for (id, state) in [("a-closed", "closed"), ("a-open", "open")] {
        client
            .upsert_open_approval(&ApprovalRow {
                approval_id: id.into(),
                machine: "m".into(),
                thread_id: "t".into(),
                request_id: "r".into(),
                payload: "{}".into(),
                state: "open".into(),
                created_at: 100,
                updated_at: 100,
            })
            .await
            .unwrap();
        if state == "closed" {
            client.close_approval(id, "closed", 100).await.unwrap();
        }
    }

    client.prune_phase3(200 + WEEK, WEEK).await.unwrap();

    // The old finished request is gone, so its ID can be written again.
    client
        .request_agent_message("old", "thread-1", "again", "david-pixel", 300 + WEEK)
        .await
        .unwrap();
    client
        .request_agent_message("new", "thread-1", "again", "david-pixel", 300 + WEEK)
        .await
        .unwrap();
    let ids: Vec<String> = client
        .claimable_agent_messages(0)
        .await
        .unwrap()
        .into_iter()
        .map(|m| m.request_id)
        .collect();
    assert_eq!(ids, ["open", "old"]);
    // Open approvals stay; closed ones are forgotten.
    client
        .upsert_open_approval(&ApprovalRow {
            approval_id: "a-closed".into(),
            machine: "m".into(),
            thread_id: "t".into(),
            request_id: "r".into(),
            payload: "{}".into(),
            state: "open".into(),
            created_at: 300,
            updated_at: 300,
        })
        .await
        .unwrap();
    let open: Vec<String> = client
        .list_open_approvals(None)
        .await
        .unwrap()
        .into_iter()
        .map(|a| a.approval_id)
        .collect();
    assert_eq!(open, ["a-closed", "a-open"]);
}
