//! The apps' Prism API answered from a phone's replica (`Replica::call`):
//! reads of what the leader wrote, and actions the leader runs and answers
//! through sync. A task on the worker's replica stands in for the leader.

use rocky_prism::{
    AGENT_STOP, AgentRow, AlertActionOutcome, ApprovalRow, MachineRow, PrismClient, QuestionRow,
};
use rocky_prism_ffi::{ApiRequest, Replica, open_provisioned_replica};
use rocky_sdk::Network;
use serde_json::{Value, json};
use std::{
    net::SocketAddr,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

const WORKER: &str = "prism-worker-0";

fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("clock")
        .as_secs() as i64
}

async fn call(replica: &Replica, method: &str, path: &str, body: Option<Value>) -> (i32, Value) {
    call_waiting(replica, method, path, body, 30).await
}

async fn call_waiting(
    replica: &Replica,
    method: &str,
    path: &str,
    body: Option<Value>,
    wait_seconds: u32,
) -> (i32, Value) {
    let response = replica
        .call(ApiRequest {
            method: method.into(),
            path: path.into(),
            body: body.map(|b| b.to_string()),
            device: "david-pixel".into(),
            cluster_machine: "david-mm".into(),
            wait_seconds,
            prism_url: String::new(),
        })
        .await;
    let body = serde_json::from_str(&response.body).expect("JSON body");
    (response.status, body)
}

/// Calls `GET path` until `done` accepts its body.
async fn eventually(replica: &Replica, path: &str, done: impl Fn(&Value) -> bool) -> Value {
    let deadline = Instant::now() + Duration::from_secs(60);
    loop {
        let (status, body) = call(replica, "GET", path, None).await;
        if status == 200 && done(&body) {
            return body;
        }
        assert!(Instant::now() < deadline, "{path} never matched: {body}");
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
}

fn agent(thread: &str, alert_id: i64, state: &str) -> AgentRow {
    AgentRow {
        thread_id: thread.into(),
        kind: "do".into(),
        title: format!("Do: {thread}"),
        alert_id,
        target: "cluster".into(),
        client: "david-mm".into(),
        host: "david-mm".into(),
        link: format!("https://app.t3.codes/threads/env-1/{thread}"),
        state: state.into(),
        run_status: "running".into(),
        requested_by: "david-pixel".into(),
        created_at: now(),
        updated_at: now(),
        ..AgentRow::default()
    }
}

/// What the stand-in leader saw: (table, request id, detail).
type Seen = Arc<Mutex<Vec<(String, String, String)>>>;

/// Runs every request the phone writes, as the leader would.
async fn lead(worker: Arc<PrismClient>, running: Arc<AtomicBool>, seen: Seen) {
    let note = |table: &str, id: &str, detail: String| {
        seen.lock()
            .expect("seen")
            .push((table.into(), id.into(), detail));
    };
    while running.load(Ordering::SeqCst) {
        let t = now();
        for a in worker
            .claimable_alert_actions(t - 300)
            .await
            .expect("actions")
        {
            if !worker
                .claim_alert_action(&a.request_id, WORKER, t, t - 300)
                .await
                .expect("claim")
            {
                continue;
            }
            note("alert_actions", &a.request_id, a.kind.clone());
            let outcome = AlertActionOutcome {
                error: "#1 has already ended.".into(),
                ..AlertActionOutcome::default()
            };
            worker
                .finish_alert_action(&a.request_id, WORKER, t, outcome)
                .await
                .expect("finish");
        }
        for r in worker
            .claimable_agent_requests(t - 300)
            .await
            .expect("starts")
        {
            if !worker
                .claim_agent_request(&r.request_id, WORKER, t, t - 300)
                .await
                .expect("claim")
            {
                continue;
            }
            note("agent_requests", &r.request_id, r.model_json.clone());
            worker
                .save_agent(&agent("t-new", 0, "active"))
                .await
                .expect("save");
            worker
                .finish_agent_request(
                    &r.request_id,
                    WORKER,
                    t,
                    "t-new",
                    "https://app.t3.codes/threads/env-1/t-new",
                    "",
                )
                .await
                .expect("finish");
        }
        for m in worker
            .claimable_agent_messages(t - 300)
            .await
            .expect("messages")
        {
            if !worker
                .claim_agent_message(&m.request_id, WORKER, t, t - 300)
                .await
                .expect("claim")
            {
                continue;
            }
            note(
                "agent_messages",
                &m.request_id,
                format!("{}:{}", m.kind, m.text),
            );
            let error = if m.kind == AGENT_STOP {
                ""
            } else {
                "T3 could not send this to the agent. Try again later."
            };
            worker
                .finish_agent_message(&m.request_id, WORKER, t, error)
                .await
                .expect("finish");
        }
        for q in worker
            .claimable_question_answers(t - 300)
            .await
            .expect("answers")
        {
            if !worker
                .claim_question_answer(&q.request_id, WORKER, t, t - 300)
                .await
                .expect("claim")
            {
                continue;
            }
            note("question_answers", &q.request_id, q.answers_json.clone());
            worker
                .finish_question_answer(&q.request_id, WORKER, t, "")
                .await
                .expect("finish");
        }
        for a in worker
            .claimable_approval_answers(t - 300)
            .await
            .expect("approvals")
        {
            if !worker
                .claim_approval_answer(&a.request_id, WORKER, t, t - 300)
                .await
                .expect("claim")
            {
                continue;
            }
            note(
                "approval_answers",
                &a.request_id,
                format!("{}:{}:{}", a.requested_by, a.signature, a.message),
            );
            worker
                .finish_approval_answer(&a.request_id, WORKER, t, "")
                .await
                .expect("finish");
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn the_phone_reads_and_acts_through_its_replica() {
    let dir = tempfile::tempdir().expect("temp dir");

    let attach_w = rocky_tcp::Options::new(SocketAddr::from(([127, 0, 0, 1], 0)))
        .websocket()
        .start()
        .await
        .expect("worker attachment");
    let worker = Arc::new(
        PrismClient::open_or_create(
            dir.path().join("w").display().to_string(),
            Some(Network::Borrowed(attach_w.transport())),
        )
        .await
        .expect("worker"),
    );
    let phone_inv = worker.invite(Some("david-pixel")).await.expect("invite phone");

    // What the leader keeps in the replica.
    let firing = json!({"status": "firing", "alerts": [{
        "labels": {"alertname": "DiskFull", "severity": "critical", "machine": "david-mb"},
        "annotations": {"summary": "The disk is full"},
        "startsAt": "2026-11-21T10:00:00Z",
    }]});
    worker
        .record_alert_webhook(firing.to_string().as_bytes(), now())
        .await
        .expect("webhook");
    worker.assign_alert_numbers(now()).await.expect("numbers");
    worker
        .save_agent(&agent("t-1", 1, "active"))
        .await
        .expect("agent");
    worker
        .upsert_machine(&MachineRow {
            name: "david-mb".into(),
            status_json: json!({"name": "david-mb", "t3": true, "reachable": true}).to_string(),
            metrics_json: json!({"name": "david-mb", "online": true, "cores": 8}).to_string(),
            updated_at: now(),
        })
        .await
        .expect("machine");
    let pending = json!({"requestId": "q-1", "questions": [{"id": "color", "header": "Color",
        "question": "Which color?", "options": [{"label": "Red", "description": "warm"}]}]});
    worker
        .upsert_open_question(&QuestionRow {
            question_id: "q-1".into(),
            thread_id: "t-1".into(),
            payload: pending.to_string(),
            state: "open".into(),
            created_at: now(),
            updated_at: now(),
            ..QuestionRow::default()
        })
        .await
        .expect("question");
    let approval = json!({"id": "a-1", "title": "Run rm", "source": "claude", "requestedAt": now(),
        "expiresAt": 0, "summary": "", "details": "", "thread": null,
        "options": [{"decision": "accept", "label": "Allow"}], "decision": null});
    worker
        .upsert_open_approval(&ApprovalRow {
            approval_id: "a-1".into(),
            machine: "david-mb".into(),
            payload: approval.to_string(),
            state: "open".into(),
            created_at: now(),
            updated_at: now(),
            ..ApprovalRow::default()
        })
        .await
        .expect("approval");

    let published = json!({"device": "david-pixel", "settings": {"relay_token": "r", "t3_url": null},
        "updatedAt": now(), "updatedBy": "david-mm"});
    worker
        .set_setting(
            "android.settings.david-pixel",
            &published.to_string(),
            now(),
        )
        .await
        .expect("phone settings");
    worker
        .set_setting("android.settings.david-tv", "{}", now())
        .await
        .expect("tv settings");

    let on_phone = open_provisioned_replica(
        dir.path().join("phone").display().to_string(),
        phone_inv,
        Vec::new(),
    )
    .await
    .expect("open provisioned");

    // Reads, in the server's shapes.
    // The alert and its agent are separate rows: wait for both.
    let alerts = eventually(&on_phone, "/v1/alerts?state=open", |b| {
        b["alerts"][0]["agents"][0]["state"] == "running"
    })
    .await;
    let alert = &alerts["alerts"][0];
    assert_eq!(alert["id"], 1);
    assert_eq!(alert["host"], "david-mb");
    assert_eq!(alert["agents"][0]["id"], "t-1");
    assert_eq!(alert["agents"][0]["state"], "running");
    assert_eq!(alert["agents"][0]["t3"]["environmentId"], "env-1");
    let agents = eventually(&on_phone, "/v1/agents?state=active", |b| {
        b["agents"].as_array().is_some_and(|a| !a.is_empty())
    })
    .await;
    assert_eq!(agents["agents"][0]["id"], "t-1");
    let machines = eventually(&on_phone, "/v1/machines", |b| {
        b["machines"].as_array().is_some_and(|a| !a.is_empty())
    })
    .await;
    assert_eq!(machines["machines"][0]["openAlerts"], json!([1]));
    // Host metrics are fetched from the Prism server (`prism_url`), not Rocky.
    let (missing_url_status, _) = call(&on_phone, "GET", "/v1/machines/metrics", None).await;
    assert_eq!(missing_url_status, 503);
    let metrics_listener = tokio::net::TcpListener::bind("127.0.0.1:0")
        .await
        .expect("bind metrics mock");
    let metrics_addr = metrics_listener.local_addr().expect("metrics addr");
    let metrics_body =
        json!({"time": now(), "hosts": [{"name": "david-mb", "online": true, "cores": 8}]})
            .to_string();
    let metrics_server = tokio::spawn(async move {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        if let Ok((mut stream, _)) = metrics_listener.accept().await {
            let mut buf = [0u8; 1024];
            let _ = stream.read(&mut buf).await;
            let resp = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                metrics_body.len(),
                metrics_body
            );
            let _ = stream.write_all(resp.as_bytes()).await;
        }
    });
    let metrics_resp = on_phone
        .call(ApiRequest {
            method: "GET".into(),
            path: "/v1/machines/metrics".into(),
            body: None,
            device: "david-pixel".into(),
            cluster_machine: "david-mm".into(),
            wait_seconds: 5,
            prism_url: format!("http://{metrics_addr}"),
        })
        .await;
    let _ = metrics_server.await;
    assert_eq!(metrics_resp.status, 200);
    let metrics: Value = serde_json::from_str(&metrics_resp.body).expect("metrics json");
    assert_eq!(metrics["hosts"][0]["cores"], 8);
    let questions = eventually(&on_phone, "/v1/questions", |b| {
        b["questions"].as_array().is_some_and(|a| !a.is_empty())
    })
    .await;
    assert_eq!(questions["questions"][0]["agentId"], "t-1");
    assert_eq!(questions["questions"][0]["host"], "david-mm");
    assert_eq!(
        questions["questions"][0]["questions"][0]["options"][0]["description"],
        "warm"
    );
    let approvals = eventually(&on_phone, "/v1/approvals", |b| {
        b["approvals"].as_array().is_some_and(|a| !a.is_empty())
    })
    .await;
    assert_eq!(approvals["approvals"][0], approval);

    // This phone's settings only.
    let settings = eventually(&on_phone, "/v1/android/settings", |b| {
        b["device"] == "david-pixel"
    })
    .await;
    assert_eq!(settings, published);

    // Without a leader, an action stays queued and answers 202.
    let (status, body) = call_waiting(
        &on_phone,
        "POST",
        "/v1/agents",
        Some(
            json!({"task": "do", "request": "update brew", "requestId": "start-1",
                    "model": {"instanceId": "placement", "model": "coding"}}),
        ),
        0,
    )
    .await;
    assert_eq!(status, 202, "{body}");
    assert_eq!(body["pending"], true);

    let running = Arc::new(AtomicBool::new(true));
    let seen: Seen = Arc::default();
    let leader = tokio::spawn(lead(
        Arc::clone(&worker),
        Arc::clone(&running),
        Arc::clone(&seen),
    ));

    // The same request ID again: the queued start, now answered.
    let (status, body) = call(
        &on_phone,
        "POST",
        "/v1/agents",
        Some(json!({"task": "do", "request": "update brew", "requestId": "start-1"})),
    )
    .await;
    assert_eq!(status, 201, "{body}");
    assert_eq!(body["agent"]["id"], "t-new");

    let (status, body) = call(
        &on_phone,
        "POST",
        "/v1/agents/t-1/stop",
        Some(json!({"requestId": "stop-1"})),
    )
    .await;
    assert_eq!((status, &body), (200, &json!({"ok": true})));

    let (status, body) = call(
        &on_phone,
        "POST",
        "/v1/agents/t-1/messages",
        Some(json!({"text": "and the casks", "requestId": "msg-1"})),
    )
    .await;
    // The leader ran it and T3 failed: final, so not "unavailable".
    assert_eq!(status, 503, "{body}");
    assert_eq!(body["code"], "failed");

    let (status, body) = call(
        &on_phone,
        "POST",
        "/v1/questions/q-1/answer",
        Some(json!({"answers": {"color": ["Red"]}, "requestId": "ans-1"})),
    )
    .await;
    assert_eq!(status, 200, "{body}");

    let (status, body) = call(
        &on_phone,
        "POST",
        "/v1/approvals/a-1/answer",
        Some(json!({"message": "bWVzc2FnZQ==", "signature": "SSHSIG"})),
    )
    .await;
    assert_eq!(status, 200, "{body}");

    let (status, body) = call(
        &on_phone,
        "POST",
        "/v1/alerts/1/fix",
        Some(json!({"requestId": "fix-1"})),
    )
    .await;
    assert_eq!(
        (status, body["code"].as_str()),
        (409, Some("conflict")),
        "{body}"
    );

    // Alerts end only when their condition does: there is no dismiss or
    // resolve, and the alert stays open.
    for action in ["dismiss", "resolve"] {
        let (status, body) = call(
            &on_phone,
            "POST",
            &format!("/v1/alerts/1/{action}"),
            Some(json!({"requestId": format!("{action}-1")})),
        )
        .await;
        assert_eq!(
            (status, body["code"].as_str()),
            (404, Some("not_found")),
            "{body}"
        );
    }
    let (_, open) = call(&on_phone, "GET", "/v1/alerts?state=open", None).await;
    assert_eq!(open["alerts"].as_array().map(Vec::len), Some(1), "{open}");

    running.store(false, Ordering::SeqCst);
    leader.await.expect("leader");
    let seen = seen.lock().expect("seen").clone();
    let detail = |table: &str, id: &str| {
        seen.iter()
            .find(|(t, i, _)| t == table && i == id)
            .map(|(_, _, d)| d.clone())
            .unwrap_or_else(|| panic!("the leader never saw {table} {id}: {seen:?}"))
    };
    assert_eq!(
        detail("agent_requests", "start-1"),
        r#"{"instanceId":"placement","model":"coding"}"#
    );
    assert_eq!(detail("agent_messages", "stop-1"), "stop:");
    assert_eq!(detail("agent_messages", "msg-1"), "message:and the casks");
    assert_eq!(detail("question_answers", "ans-1"), r#"{"color":["Red"]}"#);
    let approval_answer = seen
        .iter()
        .find(|(t, _, _)| t == "approval_answers")
        .expect("approval answer");
    assert_eq!(approval_answer.2, "david-pixel:SSHSIG:bWVzc2FnZQ==");
    assert_eq!(
        seen.iter()
            .filter(|(t, _, _)| t == "agent_requests")
            .count(),
        1
    );

    on_phone.shutdown().await.expect("shutdown phone");
    Arc::try_unwrap(worker)
        .ok()
        .expect("worker unshared")
        .close()
        .await
        .expect("close worker");
    attach_w.close().await;
}

#[tokio::test(flavor = "multi_thread")]
async fn bad_requests_get_the_servers_errors() {
    let dir = tempfile::tempdir().expect("temp dir");
    let replica = rocky_prism_ffi::open_replica(dir.path().display().to_string(), false)
        .await
        .expect("open");
    let cases = [
        ("GET", "/v1/alerts/999", None, 404, "not_found"),
        ("GET", "/v1/alerts?state=soon", None, 400, "invalid"),
        ("GET", "/v1/nope", None, 404, "not_found"),
        ("GET", "/v1/android/settings", None, 404, "not_found"),
        (
            "POST",
            "/v1/agents",
            Some(json!({"task": "paint", "request": "x"})),
            400,
            "invalid",
        ),
        (
            "POST",
            "/v1/agents",
            Some(json!({"task": "do", "request": " "})),
            400,
            "invalid",
        ),
        (
            "POST",
            "/v1/agents/t-9/messages",
            Some(json!({"text": "hi"})),
            404,
            "not_found",
        ),
        (
            "POST",
            "/v1/questions/q-9/answer",
            Some(json!({"answers": {}})),
            400,
            "invalid",
        ),
        (
            "POST",
            "/v1/approvals/a-9/answer",
            Some(json!({"message": "eA=="})),
            404,
            "not_found",
        ),
        (
            "PUT",
            "/v1/settings/model",
            Some(json!({"instanceId": "x", "model": "y"})),
            400,
            "invalid",
        ),
    ];
    for (method, path, body, status, code) in cases {
        let (got, answer) = call(&replica, method, path, body).await;
        assert_eq!(
            (got, answer["code"].as_str()),
            (status, Some(code)),
            "{method} {path}: {answer}"
        );
    }
    let response = replica
        .call(ApiRequest {
            method: "POST".into(),
            path: "/v1/agents".into(),
            body: Some("{not json".into()),
            device: "david-pixel".into(),
            cluster_machine: String::new(),
            wait_seconds: 0,
            prism_url: String::new(),
        })
        .await;
    assert_eq!(response.status, 400);
    let (status, body) = call(&replica, "GET", "/v1/health", None).await;
    assert_eq!((status, body), (200, json!({"ok": true})));
    let (status, body) = call(&replica, "GET", "/v1/placement", None).await;
    assert_eq!(status, 200);
    assert!(body["profiles"].is_array());
    let (status, body) = call(&replica, "GET", "/v1/settings/model", None).await;
    assert_eq!((status, &body["reasoning"]), (200, &Value::Null), "{body}");
    replica.shutdown().await.expect("shutdown");
}
