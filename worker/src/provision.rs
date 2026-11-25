//! Enrollment from fleet: `prism-worker init`, `provision` and `peer`, and
//! opening the worker's own database from its provisioning.
//!
//! - `prism-worker init [FILE]` creates the Prism database once and writes its
//!   root (`prism-root-v1:...`) to FILE (never overwriting) or stdout.
//! - `prism-worker provision DEVICE` reads the root from `PRISM_ROOT_FILE` (or
//!   `PRISM_ROOT`) and prints DEVICE's provisioning (`prism-device-v1:...`).
//! - `prism-worker peer ADDRESS` reads a device's provisioning on stdin and
//!   prints its peer contact at ADDRESS (`wss://prism.quickme.at/rocky/<pod>`
//!   or an in-cluster `host:port`). `peer -` is for a device nobody can dial
//!   (a phone or a Mac): workers sync with it only over the links it opens,
//!   but they must know it as a peer to sync at all.
//!
//! - `prism-worker coordinate [--revision N] WORKER_URL...` is the rollout
//!   Job that makes the workers seal, and `prism-worker seal WORKER_URL...`
//!   prints where each stands (`coordination.rs`).
//!
//! At startup the worker opens from `PRISM_WORKER_PROVISIONING(_FILE)` and adds
//! the contacts in `PRISM_WORKER_PEERS(_FILE)`. Without a provisioning it
//! creates a database of its own, as before.

use crate::auth;
use rocky_prism::{PrismClient, PrismRoot, peer_contact};
use rocky_sdk::Network;
use std::io::{Read, Write};

type Failure = Box<dyn std::error::Error + Send + Sync>;

const USAGE: &str = "usage: prism-worker [init [FILE] | provision DEVICE | peer ADDRESS|- \
                     | coordinate [--revision N] WORKER_URL... | seal WORKER_URL...]";

/// Runs the subcommand in `args` (as from `std::env::args`), if there is one.
pub async fn command(args: &[String]) -> Option<Result<(), Failure>> {
    let args: Vec<&str> = args.iter().skip(1).map(String::as_str).collect();
    let done = match args.as_slice() {
        [] => return None,
        ["init"] => init(None).await,
        ["init", file] => init(Some(file)).await,
        ["provision", device] => provision(device, |key| std::env::var(key).ok())
            .await
            .and_then(print),
        ["peer", address] => peer(address, std::io::stdin()).await.and_then(print),
        #[cfg(feature = "coordinator")]
        ["coordinate", rest @ ..] => {
            crate::coordination::command(rest, |key| std::env::var(key).ok()).await
        }
        #[cfg(feature = "coordinator")]
        ["seal", urls @ ..] => {
            crate::coordination::seal_command(urls, |key| std::env::var(key).ok()).await
        }
        ["help" | "-h" | "--help"] => print(USAGE.into()),
        _ => Err(USAGE.into()),
    };
    Some(done)
}

fn print(text: String) -> Result<(), Failure> {
    let mut stdout = std::io::stdout().lock();
    writeln!(stdout, "{text}")?;
    stdout.flush()?;
    Ok(())
}

async fn init(file: Option<&str>) -> Result<(), Failure> {
    let root = PrismRoot::create().await?.encode();
    let Some(file) = file else {
        return print(root);
    };
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut options, 0o600);
    let mut out = options
        .open(file)
        .map_err(|err| format!("{file}: {err} (init never overwrites a root)"))?;
    writeln!(out, "{root}")?;
    out.sync_all()?;
    eprintln!("prism-worker: wrote the Prism root to {file}");
    Ok(())
}

async fn provision(device: &str, env: impl Fn(&str) -> Option<String>) -> Result<String, Failure> {
    let root = auth::secret_from("PRISM_ROOT", env)
        .ok_or("set PRISM_ROOT_FILE (or PRISM_ROOT) to the root from `prism-worker init`")?;
    let minted = PrismRoot::parse(&root)?.provision(device).await?;
    eprintln!(
        "prism-worker: provisioned {} (node {})",
        minted.device, minted.node
    );
    Ok(minted.provisioning)
}

/// The route of a device nobody dials: refused at once wherever it's tried.
const DIAL_ONLY: &str = "127.0.0.1:9";

async fn peer(address: &str, mut input: impl Read) -> Result<String, Failure> {
    let address = if address == "-" { DIAL_ONLY } else { address };
    let mut provisioning = String::new();
    input.read_to_string(&mut provisioning)?;
    let provisioning = provisioning.trim();
    if provisioning.is_empty() {
        return Err("pipe the device's provisioning into `prism-worker peer`".into());
    }
    let advertised = rocky_tcp::Attachment::external(address);
    let contact = peer_contact(provisioning, Network::Borrowed(advertised.transport())).await;
    advertised.close().await;
    Ok(contact?)
}

/// Opens the worker's database at `data_dir`: from its provisioning on the
/// first start, reusing the directory afterwards. `coordination` is how its
/// seals reach the coordinator, when it has one (`coordination.rs`).
pub async fn open(
    data_dir: String,
    network: Network,
    coordination: Option<rocky_sdk::CoordinatorSlot>,
) -> Result<PrismClient, Failure> {
    open_from(data_dir, network, coordination, auth::secret).await
}

async fn open_from(
    data_dir: String,
    network: Network,
    coordination: Option<rocky_sdk::CoordinatorSlot>,
    secret: impl Fn(&str) -> Option<String>,
) -> Result<PrismClient, Failure> {
    let peers: Vec<String> = secret("PRISM_WORKER_PEERS")
        .map(|list| list.split_whitespace().map(str::to_owned).collect())
        .unwrap_or_default();
    let Some(provisioning) = secret("PRISM_WORKER_PROVISIONING") else {
        if !peers.is_empty() {
            return Err("PRISM_WORKER_PEERS needs PRISM_WORKER_PROVISIONING: \
                        a database the worker created itself syncs with no one"
                .into());
        }
        return Ok(PrismClient::open_or_create(data_dir, Some(network)).await?);
    };
    let (client, added) = PrismClient::open_provisioned_with_peers(
        data_dir,
        provisioning,
        Some(network),
        coordination,
        peers,
    )
    .await?;
    eprintln!(
        "prism-worker: enrolled as node {}, {added} peer(s)",
        client.node().await?
    );
    Ok(client)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    fn env(vars: BTreeMap<&'static str, String>) -> impl Fn(&str) -> Option<String> {
        move |key| vars.get(key).cloned()
    }

    async fn loopback() -> Network {
        let attachment = rocky_tcp::Options::new(([127, 0, 0, 1], 0).into())
            .start()
            .await
            .expect("rocky-tcp on loopback");
        Network::Borrowed(attachment.transport())
    }

    #[tokio::test]
    async fn init_provision_peer_and_open_from_the_environment() {
        let dir = tempfile::tempdir().expect("tempdir");
        let root_file = dir.path().join("prism-root");
        let root_path = root_file.display().to_string();
        init(Some(&root_path)).await.expect("init");
        assert!(
            init(Some(&root_path)).await.is_err(),
            "init overwrote a root"
        );
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(&root_file).expect("root").permissions();
            assert_eq!(mode.mode() & 0o777, 0o600);
        }

        let root_env = BTreeMap::from([("PRISM_ROOT_FILE", root_path.clone())]);
        let provisioning = provision("prism-worker-0", env(root_env.clone()))
            .await
            .expect("provision");
        assert!(provisioning.starts_with("prism-device-v1:"));
        assert!(provision("Bad_Name", env(root_env)).await.is_err());
        assert!(
            provision("prism-worker-0", env(BTreeMap::new()))
                .await
                .is_err()
        );

        let contact = peer(
            "wss://prism.example/rocky/prism-worker-0",
            provisioning.as_bytes(),
        )
        .await
        .expect("peer");
        assert!(contact.starts_with("rocky://peer/"));
        assert!(peer("10.0.0.1:7411", &b""[..]).await.is_err());

        let data = dir.path().join("data").display().to_string();
        let start = |data: String| {
            let vars = BTreeMap::from([
                ("PRISM_WORKER_PROVISIONING", provisioning.clone()),
                ("PRISM_WORKER_PEERS", contact.clone()),
            ]);
            async move { open_from(data, loopback().await, None, env(vars)).await }
        };
        let first = start(data.clone()).await.expect("first start");
        let node = first.node().await.expect("node");
        first.close().await.expect("close");
        let second = start(data).await.expect("second start");
        assert_eq!(second.node().await.expect("node"), node);
        second.close().await.expect("close");

        // Peers without a provisioning are a mistake, not a silent no-op.
        let vars = BTreeMap::from([("PRISM_WORKER_PEERS", contact.clone())]);
        let lone = dir.path().join("lone").display().to_string();
        assert!(
            open_from(lone, loopback().await, None, env(vars))
                .await
                .is_err()
        );
    }
}
