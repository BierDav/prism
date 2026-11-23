use rocky_prism::{
    ALERT_DISMISSED, ALERT_GONE, ALERT_OPEN, ALERT_RESOLVED, AlertActionOutcome,
    AlertActionRequest, AlertFilter, AlertWebhookError, PrismClient, PrismError,
};
use serde_json::json;
use std::collections::BTreeSet;

async fn client(dir: &tempfile::TempDir) -> PrismClient {
    PrismClient::open_or_create(dir.path().display().to_string(), None)
        .await
        .expect("open_or_create")
}

fn webhook(status: &str, alerts: serde_json::Value) -> Vec<u8> {
    json!({"status": status, "alerts": alerts})
        .to_string()
        .into_bytes()
}

fn node_down(status: &str, starts_at: &str) -> serde_json::Value {
    json!({
        "status": status,
        "labels": {"alertname": "NodeNotReady", "node": "oracle-1", "severity": "critical"},
        "annotations": {"summary": "oracle-1 is not ready"},
        "startsAt": starts_at,
        "endsAt": if status == "resolved" { "2026-11-21T11:00:00Z" } else { "0001-01-01T00:00:00Z" },
    })
}

const START: &str = "2026-11-21T10:00:00Z";
const NOW: i64 = 1_795_255_300; // 2026-11-21T10:01:40Z

#[tokio::test]
async fn webhook_records_episodes_idempotently_and_resolves_them() {
    let dir = tempfile::tempdir().expect("tempdir");
    let c = client(&dir).await;

    let firing = webhook("firing", json!([node_down("firing", START)]));
    c.record_alert_webhook(&firing, NOW).await.expect("fire");
    // Alertmanager retries; another worker may have stored it too.
    c.record_alert_webhook(&firing, NOW + 30)
        .await
        .expect("refire");
    let open = c.list_alerts(AlertFilter::Open).await.expect("list");
    assert_eq!(open.len(), 1);
    let alert = &open[0];
    assert_eq!(alert.state, ALERT_OPEN);
    assert_eq!(alert.name, "NodeNotReady");
    assert_eq!(alert.category, "Cluster nodes");
    assert_eq!(alert.created_at, NOW);
    assert_eq!(alert.id, 0, "unnumbered until the leader numbers it");

    assert_eq!(c.assign_alert_numbers(NOW).await.expect("number"), 1);
    assert_eq!(c.assign_alert_numbers(NOW).await.expect("number"), 0);
    let alert = c.alert_by_number(1).await.expect("by number").expect("#1");
    assert_eq!(alert.key, open[0].key);

    // The firing push is pending until recorded.
    let pushes = c.pending_alert_pushes(NOW).await.expect("pushes");
    assert_eq!(pushes.len(), 1);
    assert_eq!(pushes[0].key, format!("alert.firing:1:{NOW}"));
    assert_eq!(pushes[0].title, "#1 NodeNotReady");
    c.record_push(&pushes[0].key, NOW).await.expect("record");
    assert!(
        c.pending_alert_pushes(NOW)
            .await
            .expect("pushes")
            .is_empty()
    );

    // A late recovery of an earlier episode does not end this one.
    let mut old = node_down("resolved", "2026-11-20T10:00:00Z");
    old["endsAt"] = json!("2026-11-20T11:00:00Z");
    c.record_alert_webhook(&webhook("resolved", json!([old])), NOW + 60)
        .await
        .expect("old resolve");
    assert_eq!(
        c.list_alerts(AlertFilter::Open).await.expect("list").len(),
        1
    );

    let resolved = webhook("resolved", json!([node_down("resolved", START)]));
    c.record_alert_webhook(&resolved, NOW + 120)
        .await
        .expect("resolve");
    assert!(
        c.list_alerts(AlertFilter::Open)
            .await
            .expect("list")
            .is_empty()
    );
    let recent = c
        .list_alerts(AlertFilter::Recent { since: NOW })
        .await
        .expect("recent");
    assert_eq!(recent[0].state, ALERT_RESOLVED);
    assert_eq!(recent[0].ended_at, NOW + 120);
    let pushes = c.pending_alert_pushes(NOW + 120).await.expect("pushes");
    assert_eq!(pushes.len(), 1);
    assert_eq!(pushes[0].kind, "alert.ended");
    assert_eq!(pushes[0].state, ALERT_RESOLVED);

    // A firing retry must not resurrect the recovered episode.
    c.record_alert_webhook(&firing, NOW + 180)
        .await
        .expect("retry");
    assert!(
        c.list_alerts(AlertFilter::Open)
            .await
            .expect("list")
            .is_empty()
    );

    // A new episode gets a new row and, from the leader, a new number.
    let next = webhook(
        "firing",
        json!([node_down("firing", "2026-11-21T12:00:00Z")]),
    );
    c.record_alert_webhook(&next, NOW + 7200)
        .await
        .expect("next");
    c.assign_alert_numbers(NOW + 7200).await.expect("number");
    let open = c.list_alerts(AlertFilter::Open).await.expect("list");
    assert_eq!(open.len(), 1);
    assert_eq!(open[0].id, 2);
    assert_eq!(c.list_alerts(AlertFilter::All).await.expect("all").len(), 2);
}

#[tokio::test]
async fn webhook_refuses_what_go_refused() {
    let dir = tempfile::tempdir().expect("tempdir");
    let c = client(&dir).await;
    let invalid = |r: Result<(), AlertWebhookError>| match r {
        Err(AlertWebhookError::Invalid(reason)) => reason,
        other => panic!("expected invalid, got {other:?}"),
    };
    assert_eq!(
        invalid(c.record_alert_webhook(b"{", NOW).await),
        "invalid JSON"
    );
    assert_eq!(
        invalid(c.record_alert_webhook(br#"{"status":"firing"}"#, NOW).await),
        "missing alert list"
    );
    assert_eq!(
        invalid(
            c.record_alert_webhook(&webhook("firing", json!([{"status": "firing"}])), NOW)
                .await
        ),
        "invalid alert"
    );
    let mut odd = node_down("firing", START);
    odd["status"] = json!("pending");
    assert_eq!(
        invalid(
            c.record_alert_webhook(&webhook("firing", json!([odd])), NOW)
                .await
        ),
        "alert status must be firing or resolved"
    );
    // The top-level status stands in for a missing one; machine events are skipped.
    let mut inherit = node_down("firing", START);
    inherit["status"] = json!(null);
    let event = json!({"labels": {"alertname": "HostCheckFailed", "machine": "david-mb",
        "check": "disk-space", "source": "prism"}, "startsAt": START});
    c.record_alert_webhook(&webhook("firing", json!([inherit, event])), NOW)
        .await
        .expect("inherit");
    let all = c.list_alerts(AlertFilter::All).await.expect("all");
    assert_eq!(all.len(), 1);
    assert_eq!(all[0].name, "NodeNotReady");
}

#[tokio::test]
async fn reconciliation_ends_alerts_alertmanager_forgot() {
    let dir = tempfile::tempdir().expect("tempdir");
    let c = client(&dir).await;
    c.record_alert_webhook(&webhook("firing", json!([node_down("firing", START)])), NOW)
        .await
        .expect("fire");
    let none = BTreeSet::new();
    assert!(
        c.reconcile_alerts(&none, true, 180, NOW + 200)
            .await
            .expect("mark")
            .is_empty()
    );
    assert!(
        c.reconcile_alerts(&none, false, 180, NOW + 400)
            .await
            .expect("untrusted")
            .is_empty()
    );
    assert_eq!(
        c.reconcile_alerts(&none, true, 180, NOW + 400)
            .await
            .expect("end")
            .len(),
        1
    );
    let all = c.list_alerts(AlertFilter::All).await.expect("all");
    assert_eq!(all[0].state, ALERT_GONE);
    // It comes back: the same episode opens again.
    c.record_alert_webhook(
        &webhook("firing", json!([node_down("firing", START)])),
        NOW + 500,
    )
    .await
    .expect("again");
    let open = c.list_alerts(AlertFilter::Open).await.expect("open");
    assert_eq!(open.len(), 1);
    assert_eq!(open[0].created_at, NOW + 500);
}

#[tokio::test]
async fn requests_are_claimed_once_and_reclaimed_after_a_timeout() {
    let dir = tempfile::tempdir().expect("tempdir");
    let c = client(&dir).await;
    c.record_alert_webhook(&webhook("firing", json!([node_down("firing", START)])), NOW)
        .await
        .expect("fire");
    let key = c.list_alerts(AlertFilter::Open).await.expect("list")[0]
        .key
        .clone();

    let req = AlertActionRequest {
        request_id: "phone-1".into(),
        alert: key.clone(),
        kind: "dismiss".into(),
        instruction: String::new(),
        requested_by: "david-phone".into(),
    };
    let first = c
        .request_alert_action(req.clone(), NOW)
        .await
        .expect("request");
    let again = c
        .request_alert_action(req.clone(), NOW + 5)
        .await
        .expect("retry");
    assert_eq!(first, again, "the same request id is one request");
    assert!(matches!(
        c.request_alert_action(
            AlertActionRequest {
                kind: "resolve".into(),
                request_id: "phone-2".into(),
                ..req.clone()
            },
            NOW
        )
        .await,
        Err(PrismError::Conflict(_))
    ));
    assert!(matches!(
        c.request_alert_action(
            AlertActionRequest {
                kind: "reboot".into(),
                request_id: "phone-3".into(),
                ..req.clone()
            },
            NOW
        )
        .await,
        Err(PrismError::InvalidInput(_))
    ));
    let generated = c
        .request_alert_action(
            AlertActionRequest {
                request_id: String::new(),
                kind: "fix".into(),
                ..req.clone()
            },
            NOW + 1,
        )
        .await
        .expect("fix");
    assert!(!generated.request_id.is_empty());

    let timeout = 300;
    let pending = c
        .claimable_alert_actions(NOW - timeout)
        .await
        .expect("pending");
    assert_eq!(pending.len(), 2);
    // Worker A, the leader, claims and dies.
    assert!(
        c.claim_alert_action("phone-1", "worker-a", NOW + 10, NOW + 10 - timeout)
            .await
            .expect("claim a")
    );
    // Worker B, the next leader, cannot take it while the claim is fresh.
    assert!(
        !c.claim_alert_action("phone-1", "worker-b", NOW + 20, NOW + 20 - timeout)
            .await
            .expect("claim b")
    );
    assert_eq!(
        c.claimable_alert_actions(NOW + 20 - timeout)
            .await
            .expect("pending")
            .len(),
        1
    );
    // After the timeout it does.
    let later = NOW + 10 + timeout + 1;
    assert_eq!(
        c.claimable_alert_actions(later - timeout)
            .await
            .expect("pending")
            .len(),
        2
    );
    assert!(
        c.claim_alert_action("phone-1", "worker-b", later, later - timeout)
            .await
            .expect("reclaim")
    );
    // A's late finish does not count; B's does.
    c.finish_alert_action(
        "phone-1",
        "worker-a",
        later,
        AlertActionOutcome {
            error: "late".into(),
            ..Default::default()
        },
    )
    .await
    .expect("finish a");
    assert_eq!(
        c.alert_action("phone-1")
            .await
            .expect("get")
            .expect("row")
            .done_at,
        None
    );
    c.dismiss_alert(&key, later).await.expect("dismiss");
    c.finish_alert_action("phone-1", "worker-b", later, AlertActionOutcome::default())
        .await
        .expect("finish b");
    let done = c.alert_action("phone-1").await.expect("get").expect("row");
    assert_eq!(done.done_at, Some(later));
    assert_eq!(done.claimed_by.as_deref(), Some("worker-b"));
    assert_eq!(done.error, "");
    assert!(
        !c.claim_alert_action("phone-1", "worker-a", later + 999, later + 999)
            .await
            .expect("done")
    );

    let all = c.list_alerts(AlertFilter::All).await.expect("all");
    assert_eq!(all[0].state, ALERT_DISMISSED);
    assert_eq!(all[0].actions.len(), 2);
    // A dismiss on an ended alert is refused, a fix with instruction is not.
    assert!(matches!(
        c.request_alert_action(
            AlertActionRequest {
                request_id: "phone-4".into(),
                ..req.clone()
            },
            later
        )
        .await,
        Err(PrismError::Conflict(_))
    ));
    c.request_alert_action(
        AlertActionRequest {
            request_id: "phone-5".into(),
            kind: "fix".into(),
            instruction: "look at it anyway".into(),
            ..req
        },
        later,
    )
    .await
    .expect("fix with instruction");
}
