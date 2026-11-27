use prism_worker::http::{self, AppState, router};
use prism_worker::rocky_route::{RockyRoute, valid_node};
use prism_worker::{auth, config, leader, lease, provision};
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

    let client = Arc::new(provision::open(data_dir.clone(), network).await?);
    let listener = TcpListener::bind(http_bind).await?;

    println!(
        "prism-worker: Rocky over TCP at {}, HTTP at {}, data at {}",
        transport.address().await?.as_deref().unwrap_or("none"),
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
    let state = AppState::new(Arc::clone(&client), event_key);
    let state = state
        .with_node(route.node.clone())
        .with_peer_http(peer_http)
        .with_rocky(route)
        .with_config(cfg)
        .with_data_dir(data_dir.into())
        .with_alerts_token(auth::secret("PRISM_ALERTS_TOKEN"));
    let leader = start_leader(&state)?;
    let usage_task = state.usage.as_ref().map(|source| {
        let source = Arc::clone(source);
        tokio::spawn(async move { source.observe().await })
    });
    let app = router(state.with_eligibility(leader.eligible.clone()));
    axum::serve(listener, app)
        .with_graceful_shutdown(async {
            let _ = tokio::signal::ctrl_c().await;
        })
        .await?;

    if let Some(task) = usage_task {
        task.abort();
    }
    leader.shutdown().await;
    if let Ok(client) = Arc::try_unwrap(client) {
        let _ = client.close().await;
    }
    attachment.close().await;
    Ok(())
}

fn start_leader(
    state: &AppState,
) -> Result<leader::Leader, Box<dyn std::error::Error + Send + Sync>> {
    let mut work = leader::LeaderWork::new(
        Arc::clone(&state.client),
        Arc::clone(&state.cfg),
        state.node.clone(),
        Arc::clone(&state.secrets),
    );
    work.usage = state.usage.clone();
    Ok(leader::Leader::start(
        work,
        lease::LeaseConfig::from_env(&state.node),
    )?)
}
