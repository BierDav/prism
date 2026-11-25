use prism_worker::http::{self, AppState, router};
use prism_worker::rocky_route::{RockyRoute, valid_node};
use prism_worker::{auth, config, leader, lease, provision, t3};
use std::{net::SocketAddr, path::PathBuf, sync::Arc};
use tokio::net::TcpListener;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    if let Some(done) = provision::command(&std::env::args().collect::<Vec<_>>()).await {
        return done;
    }
    let rocky_bind: SocketAddr = std::env::var("PRISM_WORKER_BIND")
        .unwrap_or_else(|_| "0.0.0.0:7411".into())
        .parse()?;
    let http_bind: SocketAddr = std::env::var("PRISM_WORKER_HTTP_BIND")
        .unwrap_or_else(|_| "0.0.0.0:8080".into())
        .parse()?;
    let data_dir = std::env::var("PRISM_WORKER_DATA").unwrap_or_else(|_| "./local/prism".into());
    let event_key = auth::secret("PRISM_EVENT_KEY").map(String::into_bytes);

    // This worker's node name: the StatefulSet pod name in the cluster.
    let node = std::env::var("PRISM_WORKER_NODE")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_else(|_| "local".into());
    if !valid_node(&node) {
        return Err(format!("node name {node:?} is not a DNS label").into());
    }
    // Phones and Macs reach `<PRISM_WORKER_PUBLIC_URL>/<node>` (e.g.
    // wss://prism.quickme.at/rocky/prism-worker-0); other workers are
    // `<node>.<PRISM_WORKER_PEER_DOMAIN>` on the plain rocky-tcp port.
    let advertise = std::env::var("PRISM_WORKER_ADVERTISE").ok().or_else(|| {
        std::env::var("PRISM_WORKER_PUBLIC_URL")
            .ok()
            .map(|base| format!("{}/{node}", base.trim_end_matches('/')))
    });
    // Absolute (trailing dot), so the resolver's search list can't redirect it.
    let peer_domain = std::env::var("PRISM_WORKER_PEER_DOMAIN")
        .ok()
        .map(|domain| domain.trim_end_matches('.').to_owned());
    let rocky_port = rocky_bind.port();
    let http_port = http_bind.port();
    let peer_http: http::PeerHttp = {
        let peer_domain = peer_domain.clone();
        Arc::new(move |other: &str| {
            peer_domain
                .as_ref()
                .map(|domain| format!("http://{other}.{domain}.:{http_port}"))
        })
    };

    let mut options = rocky_tcp::Options::new(rocky_bind);
    if let Some(advertise) = advertise {
        options = options.advertise(advertise);
    }
    let attachment = options.start().await?;
    let transport = attachment.transport();
    let network = rocky_sdk::Network::Borrowed(Arc::clone(&transport));

    // The coordinator that makes this worker's seals final, when configured.
    #[cfg(feature = "coordinator")]
    // Prism keeps serving without it: seals wait, everything else works.
    let coordinator =
        match prism_worker::coordination::link_from_env(|key| std::env::var(key).ok()).await {
            Ok(link) => link.map(Arc::new),
            Err(err) => {
                eprintln!("prism-worker: no coordinator link, so this worker doesn't seal: {err}");
                None
            }
        };
    #[cfg(feature = "coordinator")]
    let coordination = coordinator.as_ref().map(|link| link.slot());
    #[cfg(not(feature = "coordinator"))]
    let coordination = None;
    let client = Arc::new(provision::open(data_dir.clone(), network, coordination).await?);
    let listener = TcpListener::bind(http_bind).await?;

    println!(
        "prism-worker: Rocky over TCP at {}, HTTP at {}, data at {}",
        transport.address().await?,
        listener.local_addr()?,
        data_dir,
    );

    let route = RockyRoute {
        node,
        attachment: attachment.clone(),
        forward: Arc::new(move |other| {
            peer_domain
                .as_ref()
                .map(|domain| format!("{other}.{domain}.:{rocky_port}"))
        }),
    };
    // Phase 2 & 3: alerts, approvals, questions, agents, machines. The leader
    // (a Kubernetes Lease, or always outside a cluster) runs once-only work.
    tokio::spawn(prism_worker::rocky_status::watch(
        Arc::clone(&client),
        PathBuf::from(&data_dir),
    ));
    let cfg = Arc::new(config::Config::load_from_env()?);
    let leader = start_leader(&client, Arc::clone(&cfg), &route.node)?;
    let state = AppState::new(Arc::clone(&client), event_key);
    #[cfg(feature = "coordinator")]
    let state = state.with_coordinator(coordinator.clone());
    let app = router(
        state
            .with_node(route.node.clone())
            .with_peer_http(peer_http)
            .with_rocky(route)
            .with_config(cfg)
            .with_data_dir(data_dir.into())
            .with_alerts_token(auth::secret("PRISM_ALERTS_TOKEN"))
            .with_eligibility(leader.eligible.clone()),
    );
    axum::serve(listener, app)
        .with_graceful_shutdown(async {
            let _ = tokio::signal::ctrl_c().await;
        })
        .await?;

    leader.shutdown().await;
    if let Ok(client) = Arc::try_unwrap(client) {
        let _ = client.close().await;
    }
    attachment.close().await;
    #[cfg(feature = "coordinator")]
    if let Some(link) = coordinator {
        link.close().await;
    }
    Ok(())
}

fn start_leader(
    client: &Arc<rocky_prism::PrismClient>,
    cfg: Arc<config::Config>,
    node: &str,
) -> Result<leader::Leader, Box<dyn std::error::Error + Send + Sync>> {
    let secrets: t3::Secrets = Arc::new(auth::secret);
    let work = leader::LeaderWork::new(Arc::clone(client), cfg, node.to_string(), secrets);
    Ok(leader::Leader::start(
        work,
        lease::LeaseConfig::from_env(node),
    )?)
}
