//! Phones and Macs only dial: the workers can't reach them. Rows still flow
//! from one such device to another through a worker, as long as the worker
//! knows them as peers (`prism-worker peer -`); Rocky syncs only with known
//! peers.

use rocky_prism::{PrismClient, PrismRoot, PutPlacementProfileRequest, peer_contact};
use rocky_sdk::Network;
use std::{
    net::SocketAddr,
    path::Path,
    time::{Duration, Instant},
};

const SYNC_WAIT: Duration = Duration::from_secs(60);

async fn loopback() -> (rocky_tcp::Attachment, String) {
    let attachment = rocky_tcp::Options::new(SocketAddr::from(([127, 0, 0, 1], 0)))
        .start()
        .await
        .expect("rocky-tcp on loopback");
    let address = attachment.local_addr().expect("bound address").to_string();
    (attachment, address)
}

async fn contact(provisioning: &str, address: &str) -> String {
    let advertised = rocky_tcp::Attachment::external(address);
    let contact = peer_contact(provisioning, Network::Borrowed(advertised.transport()))
        .await
        .expect("peer contact");
    advertised.close().await;
    contact
}

async fn open(dir: &Path, provisioning: &str, attachment: &rocky_tcp::Attachment) -> PrismClient {
    PrismClient::open_provisioned(
        dir.display().to_string(),
        provisioning.to_owned(),
        Some(Network::Borrowed(attachment.transport())),
    )
    .await
    .expect("open from provisioning")
}

async fn profile_ids(client: &PrismClient) -> Vec<String> {
    let view = client
        .placement_view("cluster".into(), 0)
        .await
        .expect("placement view");
    let mut ids: Vec<String> = view.profiles.into_iter().map(|p| p.id).collect();
    ids.sort();
    ids
}

async fn put_profile(client: &PrismClient, id: &str) {
    client
        .put_placement_profile(
            id.into(),
            PutPlacementProfileRequest {
                label: id.into(),
                entries: Vec::new(),
            },
            0,
        )
        .await
        .expect("put profile");
}

async fn wait_for(client: &PrismClient, expected: &[&str]) {
    let deadline = Instant::now() + SYNC_WAIT;
    loop {
        let ids = profile_ids(client).await;
        if ids == expected {
            return;
        }
        assert!(
            Instant::now() < deadline,
            "rows did not sync: have {ids:?}, want {expected:?}"
        );
        tokio::time::sleep(Duration::from_millis(200)).await;
    }
}

async fn unreachable() -> rocky_tcp::Attachment {
    rocky_tcp::Options::new(SocketAddr::from(([127, 0, 0, 1], 0)))
        .advertise("127.0.0.1:9")
        .start()
        .await
        .expect("rocky-tcp on loopback")
}

#[tokio::test(flavor = "multi_thread")]
async fn rows_flow_between_dial_only_devices_through_a_worker() {
    let dir = tempfile::tempdir().expect("tempdir");
    let root = PrismRoot::create().await.expect("root");
    let w = root.provision("prism-worker-0").await.expect("w");
    let a = root.provision("phone").await.expect("a");
    let b = root.provision("mac").await.expect("b");
    let (attach_w, addr_w) = loopback().await;
    let attach_a = unreachable().await;
    let attach_b = unreachable().await;
    let worker = open(&dir.path().join("w"), &w.provisioning, &attach_w).await;
    let client_a = open(&dir.path().join("a"), &a.provisioning, &attach_a).await;
    let client_b = open(&dir.path().join("b"), &b.provisioning, &attach_b).await;
    let peers = vec![contact(&w.provisioning, &addr_w).await];
    client_a.add_peers(peers.clone()).await.expect("peers a");
    client_b.add_peers(peers).await.expect("peers b");
    let devices = vec![
        contact(&a.provisioning, "127.0.0.1:9").await,
        contact(&b.provisioning, "127.0.0.1:9").await,
    ];
    worker.add_peers(devices).await.expect("peers w");

    put_profile(&client_a, "from-a").await;
    wait_for(&worker, &["from-a"]).await;
    wait_for(&client_b, &["from-a"]).await;
    put_profile(&client_b, "from-b").await;
    wait_for(&client_a, &["from-a", "from-b"]).await;

    for client in [worker, client_a, client_b] {
        client.close().await.expect("close");
    }
    for attachment in [attach_w, attach_a, attach_b] {
        attachment.close().await;
    }
}
