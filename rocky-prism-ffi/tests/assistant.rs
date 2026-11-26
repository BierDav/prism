//! The assistant's conversations through the phone's API
//! (`/v1/assistant/conversations`): saved on the phone's replica, synced to
//! a worker's, read, listed and deleted. The conversation is large (rows
//! near the limit, about 1 MB in all), so sync must carry it in frames
//! under Rocky's 128 KiB.

use rocky_prism::{ASSISTANT_MAX_BODY_BYTES, PrismClient, PrismRoot, peer_contact};
use rocky_prism_ffi::{ApiRequest, Replica, open_provisioned_replica};
use rocky_sdk::Network;
use serde_json::{Value, json};
use std::{
    net::SocketAddr,
    time::{Duration, Instant},
};

async fn contact(provisioning: &str, address: &str) -> String {
    let advertised = rocky_tcp::Attachment::external(address);
    let contact = peer_contact(provisioning, Network::Borrowed(advertised.transport()))
        .await
        .expect("peer contact");
    advertised.close().await;
    contact
}

async fn call(replica: &Replica, method: &str, path: &str, body: Option<Value>) -> (i32, Value) {
    let response = replica
        .call(ApiRequest {
            method: method.into(),
            path: path.into(),
            body: body.map(|b| b.to_string()),
            device: "david-pixel".into(),
            cluster_machine: String::new(),
            wait_seconds: 0,
            prism_url: String::new(),
        })
        .await;
    (
        response.status,
        serde_json::from_str(&response.body).expect("JSON body"),
    )
}

#[tokio::test(flavor = "multi_thread")]
async fn conversations_are_saved_on_the_phone_and_reach_the_workers() {
    let dir = tempfile::tempdir().expect("temp dir");
    let root = PrismRoot::create().await.expect("root");
    let w = root.provision("prism-worker-0").await.expect("w");
    let phone = root.provision("david-pixel").await.expect("phone");
    let attach_w = rocky_tcp::Options::new(SocketAddr::from(([127, 0, 0, 1], 0)))
        .websocket()
        .start()
        .await
        .expect("worker attachment");
    let route = format!("ws://{}", attach_w.local_addr().expect("address"));
    let worker = PrismClient::open_provisioned(
        dir.path().join("w").display().to_string(),
        w.provisioning.clone(),
        Some(Network::Borrowed(attach_w.transport())),
    )
    .await
    .expect("worker");
    worker
        .add_peers(vec![contact(&phone.provisioning, "127.0.0.1:9").await])
        .await
        .expect("worker peers");
    let on_phone = open_provisioned_replica(
        dir.path().join("phone").display().to_string(),
        phone.provisioning.clone(),
        vec![contact(&w.provisioning, &route).await],
    )
    .await
    .expect("phone");

    // Written on the phone at once, whether or not the worker is there.
    let body = |i: usize| format!("{i:03}{}", "x".repeat(ASSISTANT_MAX_BODY_BYTES - 3));
    let entries: Vec<Value> = (0..30)
        .map(|i| json!({"id": format!("e{i}"), "body": body(i)}))
        .collect();
    let history: Vec<String> = (0..30).map(|i| body(100 + i)).collect();
    let (status, saved) = call(
        &on_phone,
        "PUT",
        "/v1/assistant/conversations/c-1",
        Some(
            json!({"revision": 0, "saveId": "s-1", "title": "What's the weather?",
            "createdAt": 100, "updatedAt": 200, "entries": entries, "history": history}),
        ),
    )
    .await;
    assert_eq!(status, 200, "{saved}");
    assert_eq!(saved["conversation"], json!({"id": "c-1", "revision": 1}));
    // Sent again as it was (its answer lost): the same answer.
    let (status, again) = call(
        &on_phone,
        "PUT",
        "/v1/assistant/conversations/c-1",
        Some(
            json!({"revision": 0, "saveId": "s-1", "title": "What's the weather?",
            "createdAt": 100, "updatedAt": 200, "entries": entries, "history": history}),
        ),
    )
    .await;
    assert_eq!((status, &again), (200, &saved), "{again}");
    // Saved again on the revision it no longer is: refused.
    let (status, stale) = call(
        &on_phone,
        "PUT",
        "/v1/assistant/conversations/c-1",
        Some(json!({"revision": 0, "title": "other", "entries": [], "history": []})),
    )
    .await;
    assert_eq!(
        (status, &stale["code"]),
        (409, &json!("conflict")),
        "{stale}"
    );
    let (status, list) = call(&on_phone, "GET", "/v1/assistant/conversations", None).await;
    assert_eq!(
        list["conversations"][0],
        json!({"id": "c-1", "device": "david-pixel", "title": "What's the weather?",
            "createdAt": 100, "updatedAt": 200, "revision": 1})
    );
    assert_eq!(
        (status, &list["conversations"][0]["id"]),
        (200, &json!("c-1"))
    );
    let (_, read) = call(&on_phone, "GET", "/v1/assistant/conversations/c-1", None).await;
    assert_eq!(read["entries"].as_array().map(Vec::len), Some(30));
    assert_eq!(read["entries"][29]["id"], "e29");
    assert_eq!(read["history"][0], json!(body(100)));

    // The worker gets all of it by sync.
    let deadline = Instant::now() + Duration::from_secs(90);
    loop {
        if let Ok(c) = worker.assistant_conversation("c-1").await
            && c.entries.len() == 30
            && c.history.len() == 30
        {
            assert_eq!(c.entries[7].body, body(7));
            assert_eq!(c.history[29], body(129));
            break;
        }
        assert!(
            Instant::now() < deadline,
            "the conversation never reached the worker"
        );
        tokio::time::sleep(Duration::from_millis(300)).await;
    }

    // Deleted on the phone, it goes everywhere.
    let (status, _) = call(&on_phone, "DELETE", "/v1/assistant/conversations/c-1", None).await;
    assert_eq!(status, 200);
    let (status, gone) = call(&on_phone, "GET", "/v1/assistant/conversations/c-1", None).await;
    assert_eq!((status, &gone["code"]), (404, &json!("not_found")));
    let deadline = Instant::now() + Duration::from_secs(60);
    while !worker
        .list_assistant_conversations(10)
        .await
        .expect("list")
        .is_empty()
    {
        assert!(
            Instant::now() < deadline,
            "the delete never reached the worker"
        );
        tokio::time::sleep(Duration::from_millis(300)).await;
    }

    // A save after the delete (a session still running) is refused for good.
    let (status, gone) = call(
        &on_phone,
        "PUT",
        "/v1/assistant/conversations/c-1",
        Some(json!({"revision": 1, "title": "back", "entries": [], "history": []})),
    )
    .await;
    assert_eq!((status, &gone["code"]), (410, &json!("gone")), "{gone}");

    // Refusals.
    let (status, _) = call(
        &on_phone,
        "PUT",
        "/v1/assistant/conversations/c-2",
        Some(json!({"entries": [{"id": "e0", "body": "x".repeat(ASSISTANT_MAX_BODY_BYTES + 1)}]})),
    )
    .await;
    assert_eq!(status, 400);
    let (status, _) = call(
        &on_phone,
        "GET",
        "/v1/assistant/conversations?limit=0",
        None,
    )
    .await;
    assert_eq!(status, 400);

    on_phone.shutdown().await.expect("shutdown phone");
    worker.close().await.expect("close worker");
    attach_w.close().await;
}
