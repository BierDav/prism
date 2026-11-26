//! Worker startup enrollment: opening an existing replica, creating a new
//! database, or joining an existing database via an online invitation code
//! (`PRISM_WORKER_INVITATION(_FILE)`) and adding peers (`PRISM_WORKER_PEERS(_FILE)`).

use crate::auth;
use rocky_prism::PrismClient;
use rocky_sdk::Network;
use std::io::Write;

type Failure = Box<dyn std::error::Error + Send + Sync>;

const USAGE: &str = "usage: prism-worker";

/// Runs the subcommand in `args` (as from `std::env::args`), if there is one.
pub async fn command(args: &[String]) -> Option<Result<(), Failure>> {
    let args: Vec<&str> = args.iter().skip(1).map(String::as_str).collect();
    let done = match args.as_slice() {
        [] => return None,
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

/// Opens the worker's database at `data_dir`: from `PRISM_WORKER_INVITATION`
/// on the first start if configured, otherwise opening or creating a database
/// in `data_dir`.
pub async fn open(data_dir: String, network: Network) -> Result<PrismClient, Failure> {
    open_from(data_dir, network, auth::secret).await
}

async fn open_from(
    data_dir: String,
    network: Network,
    secret: impl Fn(&str) -> Option<String>,
) -> Result<PrismClient, Failure> {
    let peers: Vec<String> = secret("PRISM_WORKER_PEERS")
        .map(|list| list.split_whitespace().map(str::to_owned).collect())
        .unwrap_or_default();
    let Some(invitation) = secret("PRISM_WORKER_INVITATION") else {
        let client = PrismClient::open_or_create(data_dir, Some(network)).await?;
        if !peers.is_empty() {
            let added = client.add_peers(peers).await?;
            eprintln!(
                "prism-worker: opened as node {}, {added} peer(s)",
                client.node().await?
            );
        }
        return Ok(client);
    };
    let (client, added) =
        PrismClient::open_invited_with_peers(data_dir, invitation, Some(network), peers).await?;
    eprintln!(
        "prism-worker: joined as node {}, {added} peer(s)",
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
    async fn invite_and_join_from_the_environment() {
        let dir = tempfile::tempdir().expect("tempdir");
        let creator_dir = dir.path().join("creator").display().to_string();
        let creator = open_from(creator_dir, loopback().await, env(BTreeMap::new()))
            .await
            .expect("creator");
        let invitation = creator.invite(Some("prism-worker-1")).await.expect("invite");
        let contact = creator.contact().await.expect("contact");

        let joiner_dir = dir.path().join("joiner").display().to_string();
        let start = |data: String| {
            let vars = BTreeMap::from([
                ("PRISM_WORKER_INVITATION", invitation.clone()),
                ("PRISM_WORKER_PEERS", contact.clone()),
            ]);
            async move { open_from(data, loopback().await, env(vars)).await }
        };
        let first = start(joiner_dir.clone()).await.expect("first start");
        let node = first.node().await.expect("node");
        first.close().await.expect("close");
        let second = start(joiner_dir).await.expect("second start");
        assert_eq!(second.node().await.expect("node"), node);
        second.close().await.expect("close");
        creator.close().await.expect("close");
    }
}
