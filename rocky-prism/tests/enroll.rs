//! A root provisions devices; they open from their provisioning and sync
//! with each other over rocky-tcp on loopback.

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

#[tokio::test(flavor = "multi_thread")]
async fn provisioned_devices_sync_both_ways_and_reopen_their_directory() {
    let dir = tempfile::tempdir().expect("tempdir");

    // fleet: create the root once, store its text, provision from the stored copy.
    let root = PrismRoot::parse(&PrismRoot::create().await.expect("root").encode())
        .expect("root round trip");
    assert!(!root.root_key().expect("root key").is_empty());
    let a = root.provision("prism-worker-0").await.expect("provision a");
    let b = root.provision("david-pixel").await.expect("provision b");
    assert_ne!(a.node, b.node);
    assert!(root.provision("Not A Label").await.is_err());

    let (attach_a, addr_a) = loopback().await;
    let (attach_b, addr_b) = loopback().await;
    let contacts = vec![
        contact(&a.provisioning, &addr_a).await,
        contact(&b.provisioning, &addr_b).await,
    ];

    let dir_a = dir.path().join("a");
    let client_a = open(&dir_a, &a.provisioning, &attach_a).await;
    let client_b = open(&dir.path().join("b"), &b.provisioning, &attach_b).await;
    assert_eq!(client_a.node().await.expect("node a"), a.node);
    assert_eq!(client_b.node().await.expect("node b"), b.node);
    // Both get the same list; each skips itself.
    assert_eq!(
        client_a.add_peers(contacts.clone()).await.expect("peers"),
        1
    );
    assert_eq!(
        client_b.add_peers(contacts.clone()).await.expect("peers"),
        1
    );
    // A provisioned device seeds nothing.
    assert!(profile_ids(&client_a).await.is_empty());

    put_profile(&client_a, "from-a").await;
    put_profile(&client_b, "from-b").await;
    wait_for(&client_a, &["from-a", "from-b"]).await;
    wait_for(&client_b, &["from-a", "from-b"]).await;

    // A second start reuses the data directory; the provisioning is not reinstalled.
    client_a.close().await.expect("close a");
    let reopened = open(&dir_a, &a.provisioning, &attach_a).await;
    assert_eq!(reopened.node().await.expect("node"), a.node);
    assert_eq!(profile_ids(&reopened).await, ["from-a", "from-b"]);
    assert_eq!(reopened.add_peers(contacts).await.expect("peers"), 1);
    put_profile(&reopened, "again-a").await;
    wait_for(&client_b, &["again-a", "from-a", "from-b"]).await;

    reopened.close().await.expect("close a");
    client_b.close().await.expect("close b");
    attach_a.close().await;
    attach_b.close().await;
}

#[tokio::test(flavor = "multi_thread")]
async fn a_provisioning_of_a_new_database_replaces_the_old_replica() {
    let dir = tempfile::tempdir().expect("tempdir");
    let replica = dir.path().join("rocky");
    let (attachment, _) = loopback().await;

    let old = PrismRoot::create().await.expect("old root");
    let old_device = old.provision("david-pixel").await.expect("provision old");
    let client = open(&replica, &old_device.provisioning, &attachment).await;
    put_profile(&client, "old").await;
    client.close().await.expect("close");

    // The same provisioning again keeps the replica.
    let client = open(&replica, &old_device.provisioning, &attachment).await;
    assert_eq!(profile_ids(&client).await, ["old"]);
    client.close().await.expect("close");

    // A provisioning from a new root: the old replica goes, the device
    // enrolls in the new database.
    let new = PrismRoot::create().await.expect("new root");
    let new_device = new.provision("david-pixel").await.expect("provision new");
    let client = open(&replica, &new_device.provisioning, &attachment).await;
    assert_eq!(client.node().await.expect("node"), new_device.node);
    assert!(profile_ids(&client).await.is_empty());
    client.close().await.expect("close");

    // And it stays: the next open reuses the new replica.
    let client = open(&replica, &new_device.provisioning, &attachment).await;
    assert_eq!(client.node().await.expect("node"), new_device.node);
    client.close().await.expect("close");
    attachment.close().await;
}
