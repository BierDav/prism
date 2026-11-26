//! Against the deployed workers (ignored by default): two devices, each
//! reaching only the workers over wss, see each other's rows.
//! `PRISM_LIVE_A_FILE` and `PRISM_LIVE_B_FILE` hold the provisionings of
//! `live-test-a` and `live-test-b` (both in the workers' peers, as
//! `prism-worker peer -`), `PRISM_LIVE_PEERS_FILE` the workers' public
//! contacts (`prism-worker peer wss://.../rocky/<pod>`).
//! `cargo test -p rocky-prism --test live -- --ignored`

use rocky_prism::{PrismClient, PutPlacementProfileRequest};
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

#[tokio::test(flavor = "multi_thread")]
#[ignore = "needs the deployed workers"]
async fn two_devices_sync_through_the_deployed_workers() {
    let read = |name: &str| {
        let path = std::env::var(name).unwrap_or_else(|_| panic!("set {name}"));
        std::fs::read_to_string(path)
            .expect("read")
            .trim()
            .to_owned()
    };
    let peers: Vec<String> = read("PRISM_LIVE_PEERS_FILE")
        .split_whitespace()
        .map(str::to_owned)
        .collect();
    let dir = tempfile::tempdir().expect("tempdir");
    let (a, b) = (read("PRISM_LIVE_A_FILE"), read("PRISM_LIVE_B_FILE"));
    let (attach_a, _) = loopback().await;
    let (attach_b, _) = loopback().await;
    let client_a = open(&dir.path().join("a"), &a, &attach_a).await;
    let client_b = open(&dir.path().join("b"), &b, &attach_b).await;
    client_a.add_peers(peers.clone()).await.expect("peers a");
    client_b.add_peers(peers).await.expect("peers b");

    let id = format!("live-{}", std::process::id());
    put_profile(&client_a, &id).await;
    let deadline = Instant::now() + SYNC_WAIT;
    while !profile_ids(&client_b).await.contains(&id) {
        assert!(Instant::now() < deadline, "{id} did not reach b");
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    client_a.close().await.expect("close a");
    client_b.close().await.expect("close b");
    attach_a.close().await;
    attach_b.close().await;
}
