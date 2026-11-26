//! Phones and Macs only dial: the workers can't reach them. Rows still flow
//! from one such device to another through a worker.

use rocky_prism::{PrismClient, PrismError, PutPlacementProfileRequest};
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

async fn profile_ids(client: &PrismClient) -> Option<Vec<String>> {
    let view = match client.placement_view("cluster".into(), 0).await {
        Ok(view) => view,
        Err(PrismError::Unavailable(_)) => return None,
        Err(error) => panic!("placement view: {error:?}"),
    };
    let mut ids: Vec<String> = view.profiles.into_iter().map(|p| p.id).collect();
    ids.sort();
    Some(ids)
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
        if ids.as_deref().is_some_and(|ids| ids == expected) {
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
        .advertise("tcp://127.0.0.1:9")
        .start()
        .await
        .expect("rocky-tcp on loopback")
}

#[tokio::test(flavor = "multi_thread")]
async fn rows_flow_between_dial_only_devices_through_a_worker() {
    let dir = tempfile::tempdir().expect("tempdir");
    let attach_w = loopback().await;
    let attach_a = unreachable().await;
    let attach_b = unreachable().await;
    let worker = PrismClient::open_or_create(
        dir.path().join("w").display().to_string(),
        Some(Network::Borrowed(attach_w.transport())),
    )
    .await
    .expect("open worker");
    let inv_a = worker.invite(Some("phone")).await.expect("invite a");
    let inv_b = worker.invite(Some("mac")).await.expect("invite b");
    let client_a = open(&dir.path().join("a"), &inv_a, &attach_a).await;
    let client_b = open(&dir.path().join("b"), &inv_b, &attach_b).await;

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
