//! Whether this worker's replica can do the leader's work, probed on a timer
//! and published as `Eligibility` for the lease campaign and `/ready`.
//!
//! A probe that Rocky refuses as incomplete makes the replica `Incomplete` at
//! once: it then neither leads nor takes webhooks until `RECOVER_AFTER` probes
//! in a row succeed. A busy database (Preempted, Timeout, a slow read) says
//! nothing about completeness and keeps the last verdict.

use crate::lease::Eligibility;
use rocky_prism::PrismError;
use std::future::Future;
use std::time::Duration;
use tokio::sync::watch;

pub const PROBE_EVERY: Duration = Duration::from_secs(5);
/// A read that takes longer counts as busy.
const PROBE_TIMEOUT: Duration = Duration::from_secs(10);
/// Successful probes in a row before an incomplete replica counts as ready.
const RECOVER_AFTER: u32 = 3;

/// The next verdict after one probe.
fn next(
    current: Eligibility,
    successes: &mut u32,
    probe: Option<Result<(), PrismError>>,
) -> Eligibility {
    match probe {
        Some(Ok(())) => {
            *successes += 1;
            if current != Eligibility::Incomplete || *successes >= RECOVER_AFTER {
                Eligibility::Ready
            } else {
                current
            }
        }
        Some(Err(err)) if err.is_incomplete() => {
            *successes = 0;
            Eligibility::Incomplete
        }
        // Busy or timed out: no news.
        Some(Err(_)) | None => {
            *successes = 0;
            current
        }
    }
}

/// Probes every `every` until `stop` turns true.
pub async fn watch_replica<P, F>(
    mut probe: P,
    eligible: watch::Sender<Eligibility>,
    every: Duration,
    mut stop: watch::Receiver<bool>,
) where
    P: FnMut() -> F,
    F: Future<Output = Result<(), PrismError>>,
{
    let mut successes = 0;
    let mut ticker = tokio::time::interval(every);
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    loop {
        tokio::select! {
            _ = ticker.tick() => {}
            _ = stop.wait_for(|s| *s) => break,
        }
        let result = tokio::time::timeout(PROBE_TIMEOUT.max(every), probe())
            .await
            .ok();
        let current = *eligible.borrow();
        let verdict = next(current, &mut successes, result);
        if verdict != current {
            eprintln!("prism-worker: replica {current:?} -> {verdict:?}");
            eligible.send_replace(verdict);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;
    use std::sync::{Arc, Mutex};

    fn incomplete() -> PrismError {
        PrismError::Unavailable("SQL session: alert_numbers is not available: Incomplete".into())
    }

    fn busy() -> PrismError {
        PrismError::Unavailable("SQL session: transaction was rolled back: Preempted".into())
    }

    #[test]
    fn incomplete_is_immediate_and_recovery_takes_three_reads() {
        let mut n = 0;
        let mut e = Eligibility::Unknown;
        e = next(e, &mut n, Some(Err(busy())));
        assert_eq!(e, Eligibility::Unknown, "busy says nothing");
        e = next(e, &mut n, Some(Err(incomplete())));
        assert_eq!(e, Eligibility::Incomplete);
        e = next(e, &mut n, Some(Ok(())));
        e = next(e, &mut n, Some(Ok(())));
        assert_eq!(e, Eligibility::Incomplete);
        e = next(e, &mut n, None);
        e = next(e, &mut n, Some(Ok(())));
        assert_eq!(e, Eligibility::Incomplete, "a timeout restarts the count");
        e = next(e, &mut n, Some(Ok(())));
        e = next(e, &mut n, Some(Ok(())));
        assert_eq!(e, Eligibility::Ready);
        e = next(e, &mut n, Some(Err(busy())));
        e = next(e, &mut n, None);
        assert_eq!(e, Eligibility::Ready, "a busy replica stays ready");
        e = next(Eligibility::Unknown, &mut 0, Some(Ok(())));
        assert_eq!(
            e,
            Eligibility::Ready,
            "an unknown replica is ready at its first read"
        );
    }

    #[tokio::test]
    async fn the_watcher_publishes_each_change() {
        let script: Arc<Mutex<VecDeque<Result<(), PrismError>>>> = Arc::new(Mutex::new(
            [Err(incomplete()), Ok(()), Ok(()), Ok(())].into(),
        ));
        let (tx, mut rx) = watch::channel(Eligibility::Unknown);
        let (stop, stop_rx) = watch::channel(false);
        let probe = {
            let script = Arc::clone(&script);
            move || {
                let next = script.lock().expect("script").pop_front().unwrap_or(Ok(()));
                async move { next }
            }
        };
        let task = tokio::spawn(watch_replica(probe, tx, Duration::from_millis(10), stop_rx));
        let within = Duration::from_secs(2);
        tokio::time::timeout(within, rx.wait_for(|e| *e == Eligibility::Incomplete))
            .await
            .expect("incomplete")
            .expect("alive");
        tokio::time::timeout(within, rx.wait_for(|e| *e == Eligibility::Ready))
            .await
            .expect("ready")
            .expect("alive");
        stop.send_replace(true);
        task.await.expect("stopped");
    }
}
