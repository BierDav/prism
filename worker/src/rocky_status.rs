//! Logs Rocky's own status for this worker's replica: its alerts (a due seal
//! it skipped, an unreachable coordinator, a stalled finality pass), what
//! blocks its reads, how it coordinates, and the size of its files.
//!
//! Rocky reports these only through `status()`, so without this a replica
//! that never sealed went unnoticed while its history grew to a gigabyte and
//! every transaction started timing out. A line is logged when the status
//! changes, and at least every [`SUMMARY_EVERY`] with the file size.

use rocky_prism::PrismClient;
use rocky_sdk::DatabaseStatus;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

pub const CHECK_EVERY: Duration = Duration::from_secs(60);
const SUMMARY_EVERY: Duration = Duration::from_secs(60 * 60);
/// A status read waits behind the replica's other work; give up after this.
const READ_TIMEOUT: Duration = Duration::from_secs(30);

/// The status in one line: alerts and blockers.
pub fn summary(status: &DatabaseStatus) -> String {
    format!(
        "alerts {:?}, blockers {:?}",
        status.alerts(),
        status.blockers(),
    )
}

/// The bytes of the replica's SQLite files (`rocky.sqlite` and its WAL).
pub fn replica_bytes(data_dir: &Path) -> u64 {
    ["rocky.sqlite", "rocky.sqlite-wal"]
        .iter()
        .filter_map(|name| std::fs::metadata(data_dir.join(name)).ok())
        .map(|m| m.len())
        .sum()
}

/// Checks every [`CHECK_EVERY`] until the process ends.
pub async fn watch(client: Arc<PrismClient>, data_dir: PathBuf) {
    let mut ticker = tokio::time::interval(CHECK_EVERY);
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut last: Option<String> = None;
    let mut summarized: Option<tokio::time::Instant> = None;
    loop {
        ticker.tick().await;
        let line = match tokio::time::timeout(READ_TIMEOUT, client.rocky_status()).await {
            Ok(Ok(status)) => summary(&status),
            Ok(Err(err)) => format!("unreadable ({err})"),
            Err(_) => "unreadable (timed out)".into(),
        };
        let due = summarized.is_none_or(|at| at.elapsed() >= SUMMARY_EVERY);
        if last.as_deref() != Some(line.as_str()) || due {
            let mib = replica_bytes(&data_dir) / (1024 * 1024);
            eprintln!("prism-worker: rocky status: {line}; replica {mib} MiB");
            summarized = Some(tokio::time::Instant::now());
            last = Some(line);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn a_fresh_replica_reports_its_coordination() {
        let dir = tempfile::tempdir().expect("tempdir");
        let client = PrismClient::open_or_create(dir.path().display().to_string(), None)
            .await
            .expect("open");
        let status = client.rocky_status().await.expect("status");
        let line = summary(&status);
        assert!(line.starts_with("alerts ["), "{line}");
        assert!(line.contains("blockers ["), "{line}");
        assert!(replica_bytes(dir.path()) > 0);
        client.close().await.expect("close");
    }
}
