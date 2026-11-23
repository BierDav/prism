//! What the apps do: open a provisioned replica that dials a worker, and see
//! another device's rows arrive through it.

use rocky_prism::{PrismClient, PrismRoot, peer_contact};
use rocky_prism_ffi::{Replica, open_provisioned_replica};
use rocky_sdk::Network;
use std::{
    net::SocketAddr,
    sync::Arc,
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
    let root = PrismRoot::create().await.expect("root");
    let w = root.provision("prism-worker-0").await.expect("w");
    let phone = root.provision("david-pixel").await.expect("phone");
    let mac = root.provision("david-mb").await.expect("mac");

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
        .add_peers(vec![
            contact(&phone.provisioning, "127.0.0.1:9").await,
            contact(&mac.provisioning, "127.0.0.1:9").await,
        ])
        .await
        .expect("worker peers");

    let peers = vec![contact(&w.provisioning, &route).await];
    let open = |name: &str, provisioning: String| {
        let path = dir.path().join(name).display().to_string();
        let peers = peers.clone();
        async move {
            open_provisioned_replica(path, provisioning, peers)
                .await
                .expect("open provisioned")
        }
    };
    let on_phone = open("phone", phone.provisioning.clone()).await;
    let on_mac = open("mac", mac.provisioning.clone()).await;

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
