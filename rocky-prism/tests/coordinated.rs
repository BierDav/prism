//! Prism's history seals and compacts once the workers are trusted and the
//! database names a coordinator, on a database that ran without one first, as
//! production did. Three voters in this process, on loopback, stand in for
//! the public service at rocky.quickme.at (same rocky-voter code: the public
//! voters run alpha.13, which the pinned Rocky contains unchanged).
//!
//! Two workers are trusted and reach the coordinator; a third device (the
//! Mac, the phone) does neither and still compacts behind their seals.

use rocky_coordinator::certified::{
    CertifiedGenesis, ClientEndpoint, Genesis, PublicService, TrustBundle, Voter,
};
use rocky_coordinator::service::NetworkCoordinator;
use rocky_core::{MeshId, net::Protocol};
use rocky_prism::coordinator::CoordinatorLink;
use rocky_prism::{
    PrismClient, PrismRoot, PutPlacementProfileRequest, RootSigner, SealStatus, peer_contact,
};
use rocky_sdk::Network;
use rocky_sdk::credentials::PrivateKey;
use rocky_voter::VoterService;
use std::collections::BTreeSet;
use std::net::SocketAddr;
use std::path::Path;
use std::time::{Duration, Instant};

/// Rocky seals routinely every this many replayed transactions.
const ROUTINE_SEAL: u64 = 128;
const WAIT: Duration = Duration::from_secs(180);

fn iroh(bind: &str) -> rocky_iroh::Options {
    rocky_iroh::Options {
        relay: Some("disabled".into()),
        lookup: Some("disabled".into()),
        topic: false,
        local: false,
        bind: Some(bind.into()),
    }
}

/// Three voters with a certified genesis, initialized, with a leader.
async fn voters(dir: &Path) -> TrustBundle {
    let root = PrivateKey::new();
    let admin = PrivateKey::new();
    let keys: Vec<_> = (0..3).map(|_| PrivateKey::new()).collect();
    let secrets: Vec<[u8; 32]> = (0..3).map(|_| *PrivateKey::new().as_bytes()).collect();
    let (mut voters, mut endpoints, mut binds) = (Vec::new(), Vec::new(), Vec::new());
    for (index, key) in keys.iter().enumerate() {
        let id = index as u64 + 1;
        let tcp = std::net::TcpListener::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap();
        let udp = std::net::UdpSocket::bind("127.0.0.1:0")
            .unwrap()
            .local_addr()
            .unwrap();
        voters.push(Voter {
            id,
            address: tcp.to_string(),
            key: key.public_key(),
        });
        let address = rocky_iroh::iroh::EndpointAddr::new(
            rocky_iroh::iroh::SecretKey::from_bytes(&secrets[index]).public(),
        )
        .with_ip_addr(udp);
        endpoints.push(ClientEndpoint {
            voter: id,
            kind: "iroh".into(),
            address: serde_json::to_string(&address).unwrap(),
        });
        binds.push(udp.to_string());
    }
    let genesis = CertifiedGenesis::sign(
        Genesis {
            mesh: MeshId([0x70; 16]),
            configuration: [7; 16],
            initial_voters: BTreeSet::from([1, 2, 3]),
            membership_authority: admin.public_key(),
            authorities: Vec::new(),
            // The public service's limit.
            max_control_lifetime: 366 * 86_400,
            public: Some(PublicService {
                endpoints,
                admission: None,
                limits: rocky_coordinator::Limits::default(),
                tenants: 4,
                registrations_per_day: 16,
                tenant_lanes: 4,
            }),
            voters,
        },
        &root,
    )
    .unwrap();
    for (index, key) in keys.into_iter().enumerate() {
        let id = index as u64 + 1;
        let voter = VoterService::open(
            &dir.join(format!("voter-{id}")),
            genesis.clone(),
            root.public_key(),
            genesis.genesis.mesh,
            id,
            key,
            true,
        )
        .await
        .unwrap();
        let lane = iroh(&binds[index])
            .start_serving(secrets[index], vec![Protocol::Coordinate])
            .await
            .unwrap();
        tokio::spawn(voter.clone().serve_clients(lane.transport()));
        tokio::spawn(voter.serve());
    }
    let admin = NetworkCoordinator::open(
        genesis.clone(),
        root.public_key(),
        genesis.genesis.mesh,
        admin,
    )
    .unwrap();
    let deadline = Instant::now() + Duration::from_secs(30);
    while admin.initialize(1).await.is_err() {
        assert!(Instant::now() < deadline, "voters offline");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    while !admin.status(1).await.is_ok_and(|s| s.leader.is_some()) {
        assert!(Instant::now() < deadline, "no leader");
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    TrustBundle::new(&genesis).unwrap()
}

struct Device {
    client: PrismClient,
    attachment: rocky_tcp::Attachment,
    link: Option<CoordinatorLink>,
}

async fn device(
    dir: &Path,
    provisioning: &str,
    attachment: rocky_tcp::Attachment,
    bundle: Option<&TrustBundle>,
) -> Device {
    let link = match bundle {
        Some(bundle) => {
            let endpoint = iroh("127.0.0.1:0")
                .start(*PrivateKey::new().as_bytes())
                .await
                .expect("coordinator endpoint");
            Some(
                CoordinatorLink::with_transport(bundle.clone(), endpoint.transport())
                    .expect("coordinator link"),
            )
        }
        None => None,
    };
    let client = PrismClient::open_provisioned_with(
        dir.display().to_string(),
        provisioning.to_owned(),
        Some(Network::Borrowed(attachment.transport())),
        link.as_ref().map(CoordinatorLink::slot),
    )
    .await
    .expect("open from provisioning");
    Device {
        client,
        attachment,
        link,
    }
}

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

/// `count` transactions, each rewriting one of ten profiles: history grows
/// with every write, the data itself stays ten rows.
async fn write(client: &PrismClient, label: &str, count: usize) {
    for i in 0..count {
        client
            .put_placement_profile(
                format!("profile-{}", i % 10),
                PutPlacementProfileRequest {
                    label: format!("{label}-{i}"),
                    entries: Vec::new(),
                },
                0,
            )
            .await
            .expect("write");
    }
}

async fn statuses(devices: &[(&str, &Device)]) -> Vec<(String, SealStatus)> {
    let mut all = Vec::new();
    for (name, device) in devices {
        all.push((
            (*name).to_owned(),
            device.client.seal_status().await.expect("seal status"),
        ));
    }
    all
}

/// Waits until every device has compacted behind a final seal.
async fn compacted(devices: &[(&str, &Device)]) -> Vec<(String, SealStatus)> {
    let deadline = Instant::now() + WAIT;
    loop {
        let all = statuses(devices).await;
        if all
            .iter()
            .all(|(_, s)| s.finalized.is_some() && s.compacted.is_some())
        {
            return all;
        }
        assert!(Instant::now() < deadline, "no compaction: {all:#?}");
        tokio::time::sleep(Duration::from_secs(1)).await;
    }
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn trusted_workers_seal_and_every_replica_compacts_behind_the_coordinator() {
    let dir = tempfile::tempdir().expect("tempdir");
    let bundle = voters(&dir.path().join("voters")).await;

    let root = PrismRoot::create().await.expect("root");
    let w0 = root.provision("prism-worker-0").await.expect("provision");
    let w1 = root.provision("prism-worker-1").await.expect("provision");
    let mac = root.provision("david-mb").await.expect("provision");
    let ((a0, addr0), (a1, addr1), (am, addrm)) =
        (loopback().await, loopback().await, loopback().await);
    let contacts = vec![
        contact(&w0.provisioning, &addr0).await,
        contact(&w1.provisioning, &addr1).await,
        contact(&mac.provisioning, &addrm).await,
    ];
    let worker0 = device(&dir.path().join("w0"), &w0.provisioning, a0, Some(&bundle)).await;
    let worker1 = device(&dir.path().join("w1"), &w1.provisioning, a1, Some(&bundle)).await;
    let laptop = device(&dir.path().join("mac"), &mac.provisioning, am, None).await;
    for d in [&worker0, &worker1, &laptop] {
        d.client.add_peers(contacts.clone()).await.expect("peers");
    }
    let devices = [("w0", &worker0), ("w1", &worker1), ("mac", &laptop)];

    // As in production until now: issuers, not trusted, so nothing seals.
    write(&worker0.client, "before", ROUTINE_SEAL as usize + 22).await;
    tokio::time::sleep(Duration::from_secs(35)).await;
    for (name, status) in statuses(&devices).await {
        assert!(!status.trusted, "{name} trusted before the root said so");
        assert_eq!(status.finalized, None, "{name} sealed untrusted");
    }
    let before = worker0.client.seal_status().await.expect("status");
    assert!(before.operations_since_seal > ROUTINE_SEAL, "{before:?}");

    // The rollout Job's steps: each worker trusts itself, one registers the
    // domain trusting both and names the coordinator.
    let signer = RootSigner::parse(&root.encode()).expect("signer");
    let p0 = worker0.client.trust_self(&signer).await.expect("trust w0");
    let p1 = worker1.client.trust_self(&signer).await.expect("trust w1");
    assert_ne!(p0, p1);
    let state = dir.path().join("coordinator-state");
    let link0 = worker0.link.as_ref().expect("link");
    let receipt = worker0
        .client
        .register_coordination(link0, &signer, &[p0.clone(), p1.clone()], 1, &state)
        .await
        .expect("register");
    assert_eq!(receipt.revision, 1);
    assert!(receipt.expires > 300 * 86_400);
    // A retry of the same registration is answered again, not refused.
    let again = worker0
        .client
        .register_coordination(link0, &signer, &[p0.clone(), p1.clone()], 1, &state)
        .await
        .expect("retry registration");
    assert_eq!(again.tenant, receipt.tenant);
    worker0
        .client
        .declare_coordinated(&signer)
        .await
        .expect("declare");
    worker0
        .client
        .declare_coordinated(&signer)
        .await
        .expect("declaring again is a no-op");

    // Routine seals now happen and become final; every replica compacts.
    write(&worker1.client, "after", ROUTINE_SEAL as usize + 10).await;
    let after = compacted(&devices).await;
    for (name, status) in &after {
        if *name == "mac" {
            assert!(!status.trusted, "the Mac is not trusted");
        } else {
            assert!(status.trusted, "{name} is not trusted: {status:?}");
            assert_eq!(status.coordination, "service");
        }
        assert_eq!(status.seal_skipped, None, "{name}: {status:?}");
        assert!(
            status.operations_since_seal < before.operations_since_seal,
            "{name} did not seal: {status:?}"
        );
    }
    eprintln!("after coordination: {after:#?}");

    // History stops growing: every replica compacts behind each new final
    // seal. What a compacted replica retains is the checkpoint of its data
    // (ten rows, rewritten) plus the operations since the last seal, so two
    // more seal rounds leave it where it was. Uncompacted, each operation
    // adds what it added before the first seal.
    let per_operation = before.retained_bytes / before.operations_since_seal;
    let floor = after
        .iter()
        .map(|(_, s)| s.retained_bytes)
        .max()
        .expect("statuses");
    let first_compacted = after[0].1.compacted.clone();
    let rounds = 2;
    for round in 0..rounds {
        write(
            &worker0.client,
            &format!("round{round}"),
            ROUTINE_SEAL as usize + 10,
        )
        .await;
    }
    let uncompacted = floor + per_operation * rounds as u64 * (ROUTINE_SEAL + 10);
    let deadline = Instant::now() + WAIT;
    let compacted = loop {
        let all = statuses(&devices).await;
        if all.iter().all(|(_, s)| {
            s.compacted != first_compacted
                && s.compacted == s.finalized
                && s.retained_bytes < floor + floor / 4
        }) {
            break all;
        }
        assert!(
            Instant::now() < deadline,
            "history kept growing past {floor} bytes: {all:#?}"
        );
        tokio::time::sleep(Duration::from_secs(1)).await;
    };
    eprintln!(
        "retained bytes: {} unsealed ({per_operation} per operation), {floor} \
         compacted, {:?} after {rounds} more seals (about {uncompacted} without compaction)",
        before.retained_bytes,
        compacted
            .iter()
            .map(|(_, s)| s.retained_bytes)
            .collect::<Vec<_>>()
    );

    // A renewal (the yearly Job) is the next revision.
    let renewed = worker0
        .client
        .register_coordination(link0, &signer, &[p0, p1], 2, &state)
        .await
        .expect("renew");
    assert_eq!((renewed.revision, renewed.tenant), (2, receipt.tenant));

    // A restarted worker opens with the same coordinator: no mismatch.
    let Device {
        client,
        attachment,
        link,
    } = worker1;
    client.close().await.expect("close w1");
    let link = link.expect("link");
    let reopened = PrismClient::open_provisioned_with(
        dir.path().join("w1").display().to_string(),
        w1.provisioning.clone(),
        Some(Network::Borrowed(attachment.transport())),
        Some(link.slot()),
    )
    .await
    .expect("reopen coordinated");
    reopened.add_peers(contacts).await.expect("peers");
    write(&reopened, "reopened", 5).await;
    assert!(reopened.seal_status().await.expect("status").trusted);
    reopened.close().await.expect("close");
    link.close().await;
    attachment.close().await;

    for d in [worker0, laptop] {
        d.client.close().await.expect("close");
        if let Some(link) = d.link {
            link.close().await;
        }
        d.attachment.close().await;
    }
}
