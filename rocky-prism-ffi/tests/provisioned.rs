//! What the apps do: open a provisioned replica that dials a worker, and see
//! another device's rows arrive through it.

use rocky_prism::PrismClient;
use rocky_prism_ffi::{Replica, open_provisioned_replica};
use rocky_sdk::Network;
use std::{
    net::SocketAddr,
    sync::Arc,
    time::{Duration, Instant},
};

async fn profile_ids(replica: &Replica) -> Vec<String> {
    let view: serde_json::Value =
        serde_json::from_str(&replica.placement(String::new()).await.expect("view")).expect("JSON");
    let mut ids: Vec<String> = view["profiles"]
        .as_array()
        .expect("profiles")
        .iter()
        .filter_map(|p| p["id"].as_str().map(str::to_owned))
        .collect();
    ids.sort();
    ids
}

async fn wait_for(replica: &Arc<Replica>, id: &str) {
    let deadline = Instant::now() + Duration::from_secs(60);
    while !profile_ids(replica).await.iter().any(|have| have == id) {
        assert!(Instant::now() < deadline, "{id} did not arrive");
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn provisioned_replicas_sync_through_a_worker() {
    let dir = tempfile::tempdir().expect("temp dir");

    let attach_w = rocky_tcp::Options::new(SocketAddr::from(([127, 0, 0, 1], 0)))
        .websocket()
        .start()
        .await
        .expect("worker attachment");
    let worker = PrismClient::open_or_create(
        dir.path().join("w").display().to_string(),
        Some(Network::Borrowed(attach_w.transport())),
    )
    .await
    .expect("worker");
    let phone_inv = worker.invite(Some("david-pixel")).await.expect("invite phone");
    let mac_inv = worker.invite(Some("david-mb")).await.expect("invite mac");

    let open = |name: &str, invitation: String| {
        let path = dir.path().join(name).display().to_string();
        async move {
            open_provisioned_replica(path, invitation, Vec::new())
                .await
                .expect("open provisioned")
        }
    };
    let on_phone = open("phone", phone_inv).await;
    let on_mac = open("mac", mac_inv).await;

    on_phone
        .put_placement_profile(
            "from-phone".into(),
            r#"{"label":"Phone","entries":[]}"#.into(),
        )
        .await
        .expect("put");
    wait_for(&on_mac, "from-phone").await;

    on_phone.shutdown().await.expect("shutdown phone");
    on_mac.shutdown().await.expect("shutdown mac");
    worker.close().await.expect("close worker");
    attach_w.close().await;
}
