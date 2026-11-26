//! A creator invites devices; they join over rocky-tcp on loopback, sync both
//! ways, and reopen their directory.

use rocky_prism::{PrismClient, PutPlacementProfileRequest};
use rocky_sdk::Network;
use std::{
    net::SocketAddr,
    path::Path,
    time::{Duration, Instant},
};

const SYNC_WAIT: Duration = Duration::from_secs(60);

async fn loopback() -> rocky_tcp::Attachment {
    rocky_tcp::Options::new(SocketAddr::from(([127, 0, 0, 1], 0)))
        .start()
        .await
        .expect("rocky-tcp on loopback")
}

async fn open(dir: &Path, invitation: &str, attachment: &rocky_tcp::Attachment) -> PrismClient {
    PrismClient::open_invited(
        dir.display().to_string(),
        invitation.to_owned(),
        Some(Network::Borrowed(attachment.transport())),
    )
    .await
    .expect("open from invitation")
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

#[tokio::test(flavor = "multi_thread")]
async fn invited_devices_sync_both_ways_and_reopen_their_directory() {
    let dir = tempfile::tempdir().expect("tempdir");
    let attach_a = loopback().await;
    let attach_b = loopback().await;

    let client_a = PrismClient::open_or_create(
        dir.path().join("a").display().to_string(),
        Some(Network::Borrowed(attach_a.transport())),
    )
    .await
    .expect("open a");
    let inv_b = client_a.invite(Some("david-pixel")).await.expect("invite b");
    let client_b = open(&dir.path().join("b"), &inv_b, &attach_b).await;
    assert_ne!(
        client_a.node().await.expect("node a"),
        client_b.node().await.expect("node b")
    );

    put_profile(&client_a, "from-a").await;
    wait_for(&client_b, &["from-a"]).await;

    put_profile(&client_b, "from-b").await;
    wait_for(&client_a, &["from-a", "from-b"]).await;

    let node_b = client_b.node().await.expect("node b");
    client_b.close().await.expect("close b");

    let client_b = open(&dir.path().join("b"), &inv_b, &attach_b).await;
    assert_eq!(client_b.node().await.expect("reopen node b"), node_b);
    wait_for(&client_b, &["from-a", "from-b"]).await;

    client_a.close().await.expect("close a");
    client_b.close().await.expect("close b");
    attach_a.close().await;
    attach_b.close().await;
}
