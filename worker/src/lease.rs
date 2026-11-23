//! Leader election through a Kubernetes `coordination.k8s.io/v1` Lease, the
//! pattern of the Go elector (`leader.go`) without Postgres.
//!
//! The leader renews every `renew`; another worker takes over once the
//! lease is `ttl` old. The leader stops leading when it has not renewed for
//! `ttl - margin` by its own monotonic clock, so it has stopped before anyone
//! else can take over. Outside a cluster (no service-account token) the
//! worker always leads.
//!
//! Only a worker whose replica can serve the leader's work campaigns
//! (`Eligibility`): an incomplete replica (one still importing, or a node
//! whose author log forked) gives the lease up and never takes it, and one
//! whose state isn't known yet takes it only after it has been left for
//! `ttl` beyond its expiry, so a worker known to be ready wins it first.

use chrono::{DateTime, SecondsFormat, Utc};
use serde_json::{Value, json};
use std::time::Duration;
use tokio::sync::watch;
use tokio::time::Instant;

const SA_DIR: &str = "/var/run/secrets/kubernetes.io/serviceaccount";

/// Whether this worker's replica can do the leader's work.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Eligibility {
    /// It read what the leader needs.
    Ready,
    /// Not known yet (just started, or every probe so far was busy).
    Unknown,
    /// Rocky refused the leader's tables as incomplete.
    Incomplete,
}

#[derive(Clone, Debug)]
pub struct LeaseConfig {
    /// e.g. https://kubernetes.default.svc:443
    pub api: String,
    pub namespace: String,
    pub name: String,
    pub identity: String,
    /// Read on every request: projected tokens rotate.
    pub token_path: String,
    /// The cluster CA; None for plain HTTP (tests).
    pub ca_path: Option<String>,
    pub ttl: Duration,
    pub renew: Duration,
    pub margin: Duration,
}

impl LeaseConfig {
    /// The in-cluster lease, or None outside a cluster. `PRISM_LEADER_LEASE`
    /// names it (default prism-worker-leader) and `PRISM_LEADER_NAMESPACE`
    /// its namespace (default: the pod's own).
    pub fn from_env(identity: &str) -> Option<Self> {
        let host = std::env::var("KUBERNETES_SERVICE_HOST").ok()?;
        let token_path = format!("{SA_DIR}/token");
        if !std::path::Path::new(&token_path).exists() {
            return None;
        }
        let port = std::env::var("KUBERNETES_SERVICE_PORT").unwrap_or_else(|_| "443".into());
        let host = if host.contains(':') {
            format!("[{host}]")
        } else {
            host
        };
        let namespace = std::env::var("PRISM_LEADER_NAMESPACE")
            .ok()
            .filter(|n| !n.is_empty())
            .or_else(|| {
                std::fs::read_to_string(format!("{SA_DIR}/namespace"))
                    .ok()
                    .map(|n| n.trim().to_string())
            })
            .unwrap_or_else(|| "default".into());
        Some(Self {
            api: format!("https://{host}:{port}"),
            namespace,
            name: std::env::var("PRISM_LEADER_LEASE")
                .ok()
                .filter(|n| !n.is_empty())
                .unwrap_or_else(|| "prism-worker-leader".into()),
            identity: identity.to_string(),
            token_path,
            ca_path: Some(format!("{SA_DIR}/ca.crt")),
            ttl: Duration::from_secs(15),
            renew: Duration::from_secs(3),
            margin: Duration::from_secs(5),
        })
    }
}

pub struct LeaseClient {
    cfg: LeaseConfig,
    http: reqwest::Client,
}

fn micro_time(t: DateTime<Utc>) -> String {
    t.to_rfc3339_opts(SecondsFormat::Micros, true)
}

impl LeaseClient {
    pub fn new(cfg: LeaseConfig) -> Result<Self, String> {
        let mut builder = reqwest::Client::builder();
        if let Some(ca) = &cfg.ca_path {
            let pem = std::fs::read(ca).map_err(|err| format!("{ca}: {err}"))?;
            let cert = reqwest::Certificate::from_pem(&pem).map_err(|err| err.to_string())?;
            builder = builder.add_root_certificate(cert);
        }
        Ok(Self {
            http: builder.build().map_err(|err| err.to_string())?,
            cfg,
        })
    }

    fn url(&self, named: bool) -> String {
        let base = format!(
            "{}/apis/coordination.k8s.io/v1/namespaces/{}/leases",
            self.cfg.api.trim_end_matches('/'),
            self.cfg.namespace
        );
        if named {
            format!("{base}/{}", self.cfg.name)
        } else {
            base
        }
    }

    fn token(&self) -> Result<String, String> {
        std::fs::read_to_string(&self.cfg.token_path)
            .map(|t| t.trim().to_string())
            .map_err(|err| format!("service account token: {err}"))
    }

    async fn send(&self, req: reqwest::RequestBuilder) -> Result<(u16, Value), String> {
        let resp = req
            .bearer_auth(self.token()?)
            .timeout(self.cfg.renew)
            .send()
            .await
            .map_err(|err| err.to_string())?;
        let status = resp.status().as_u16();
        let body = resp.json::<Value>().await.unwrap_or(Value::Null);
        Ok((status, body))
    }

    /// Takes or renews the lease. Ok(true) when this worker holds it now.
    pub async fn acquire(&self) -> Result<bool, String> {
        self.acquire_with_patience(Duration::ZERO).await
    }

    /// Like `acquire`, but someone else's lease, or a released one, counts
    /// as held until `patience` past its expiry.
    pub async fn acquire_with_patience(&self, patience: Duration) -> Result<bool, String> {
        let now = Utc::now();
        let ttl_secs = self.cfg.ttl.as_secs().max(1) as i64;
        let (status, mut lease) = self.send(self.http.get(self.url(true))).await?;
        if status == 404 {
            let body = json!({
                "apiVersion": "coordination.k8s.io/v1",
                "kind": "Lease",
                "metadata": {"name": self.cfg.name, "namespace": self.cfg.namespace},
                "spec": {
                    "holderIdentity": self.cfg.identity,
                    "leaseDurationSeconds": ttl_secs,
                    "acquireTime": micro_time(now),
                    "renewTime": micro_time(now),
                    "leaseTransitions": 0,
                },
            });
            let (status, _) = self
                .send(self.http.post(self.url(false)).json(&body))
                .await?;
            return match status {
                200..=299 => Ok(true),
                409 => Ok(false), // Another worker created it first.
                s => Err(format!("creating the lease: HTTP {s}")),
            };
        }
        if !(200..300).contains(&status) {
            return Err(format!("reading the lease: HTTP {status}"));
        }
        let spec = lease.get("spec").cloned().unwrap_or(Value::Null);
        let holder = spec
            .get("holderIdentity")
            .and_then(Value::as_str)
            .unwrap_or("");
        let duration = spec
            .get("leaseDurationSeconds")
            .and_then(Value::as_i64)
            .unwrap_or(ttl_secs);
        let renewed = spec
            .get("renewTime")
            .and_then(Value::as_str)
            .and_then(|t| DateTime::parse_from_rfc3339(t).ok())
            .map(|t| t.with_timezone(&Utc));
        let ours = holder == self.cfg.identity;
        let patience = chrono::Duration::from_std(patience).unwrap_or(chrono::Duration::MAX);
        let live = |extra: chrono::Duration| {
            renewed.is_some_and(|t| t + chrono::Duration::seconds(duration) + extra > now)
        };
        if !ours && !holder.is_empty() && live(chrono::Duration::zero()) {
            return Ok(false);
        }
        if !ours && !patience.is_zero() && live(patience) {
            return Ok(false);
        }
        let transitions = spec
            .get("leaseTransitions")
            .and_then(Value::as_i64)
            .unwrap_or(0);
        let mut new_spec = spec.as_object().cloned().unwrap_or_default();
        new_spec.insert("holderIdentity".into(), self.cfg.identity.clone().into());
        new_spec.insert("leaseDurationSeconds".into(), ttl_secs.into());
        new_spec.insert("renewTime".into(), micro_time(now).into());
        if !ours {
            new_spec.insert("acquireTime".into(), micro_time(now).into());
            new_spec.insert("leaseTransitions".into(), (transitions + 1).into());
        }
        lease["spec"] = Value::Object(new_spec);
        // metadata.resourceVersion stays: a concurrent update answers 409.
        let (status, _) = self
            .send(self.http.put(self.url(true)).json(&lease))
            .await?;
        match status {
            200..=299 => Ok(true),
            409 => Ok(false),
            s => Err(format!("updating the lease: HTTP {s}")),
        }
    }

    /// Gives the lease up at once, so another worker need not wait for it to expire.
    pub async fn release(&self) -> Result<(), String> {
        let (status, mut lease) = self.send(self.http.get(self.url(true))).await?;
        if !(200..300).contains(&status) {
            return Ok(());
        }
        if lease
            .pointer("/spec/holderIdentity")
            .and_then(Value::as_str)
            != Some(self.cfg.identity.as_str())
        {
            return Ok(());
        }
        lease["spec"]["holderIdentity"] = Value::Null;
        lease["spec"]["leaseDurationSeconds"] = 1.into();
        lease["spec"]["renewTime"] = micro_time(Utc::now()).into();
        let (status, _) = self
            .send(self.http.put(self.url(true)).json(&lease))
            .await?;
        if (200..300).contains(&status) || status == 409 {
            Ok(())
        } else {
            Err(format!("releasing the lease: HTTP {status}"))
        }
    }
}

/// Campaigns until `stop` turns true, publishing whether this worker leads,
/// while `eligible` allows it.
pub async fn campaign(
    client: LeaseClient,
    leading: watch::Sender<bool>,
    mut eligible: watch::Receiver<Eligibility>,
    mut stop: watch::Receiver<bool>,
) {
    let cfg = client.cfg.clone();
    let mut valid_until: Option<Instant> = None;
    let mut ticker = tokio::time::interval(cfg.renew);
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let step_down = |leading: &watch::Sender<bool>, reason: &str| {
        if *leading.borrow() {
            eprintln!("prism-worker: stopped leading ({reason})");
            leading.send_replace(false);
        }
    };
    let mut watching = true;
    loop {
        let expiry = match valid_until {
            Some(until) if *leading.borrow() => Some(until),
            _ => None,
        };
        tokio::select! {
            _ = ticker.tick() => {}
            _ = stop.wait_for(|s| *s) => break,
            changed = eligible.changed(), if watching => {
                // Its sender gone, eligibility stays as it last was.
                watching = changed.is_ok();
            }
            // Stop at once when the lease may no longer be ours.
            _ = async { tokio::time::sleep_until(expiry.unwrap_or_else(Instant::now)).await }, if expiry.is_some() => {
                step_down(&leading, "the lease could not be renewed in time");
                continue;
            }
        }
        let eligibility = *eligible.borrow_and_update();
        if eligibility == Eligibility::Incomplete {
            if *leading.borrow() {
                step_down(&leading, "this replica is incomplete");
                valid_until = None;
                if let Err(err) = client.release().await {
                    eprintln!("prism-worker: could not release the leader lease: {err}");
                }
            }
            continue;
        }
        // Not known to be ready: keep a lease it holds, take a free one only
        // once a ready worker had time to.
        let patience = if eligibility == Eligibility::Unknown && !*leading.borrow() {
            cfg.ttl
        } else {
            Duration::ZERO
        };
        let started = Instant::now();
        // A renewal that would end after the lease could be valid cannot keep it.
        let deadline = match valid_until {
            Some(until) if *leading.borrow() => {
                until.saturating_duration_since(started).min(cfg.renew)
            }
            _ => cfg.renew,
        };
        match tokio::time::timeout(deadline, client.acquire_with_patience(patience)).await {
            Ok(Ok(true)) => {
                valid_until = Some(started + cfg.ttl.saturating_sub(cfg.margin));
                if !*leading.borrow() {
                    eprintln!("prism-worker: leading as {}", cfg.identity);
                    leading.send_replace(true);
                }
            }
            Ok(Ok(false)) => step_down(&leading, "another worker holds the lease"),
            Ok(Err(err)) => eprintln!("prism-worker: could not renew the leader lease: {err}"),
            Err(_) => eprintln!("prism-worker: renewing the leader lease timed out"),
        }
        if *leading.borrow() && valid_until.is_none_or(|until| Instant::now() >= until) {
            step_down(&leading, "the lease could not be renewed in time");
        }
    }
    if *leading.borrow() {
        leading.send_replace(false);
        if let Err(err) = client.release().await {
            eprintln!("prism-worker: could not release the leader lease: {err}");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::{FakeLeases, serve};

    fn config(api: &str, token: &std::path::Path, identity: &str) -> LeaseConfig {
        LeaseConfig {
            api: api.into(),
            namespace: "monitoring".into(),
            name: "prism-worker-leader".into(),
            identity: identity.into(),
            token_path: token.display().to_string(),
            ca_path: None,
            ttl: Duration::from_secs(2),
            renew: Duration::from_millis(150),
            margin: Duration::from_millis(500),
        }
    }

    struct Candidate {
        leading: watch::Receiver<bool>,
        stop: watch::Sender<bool>,
        eligible: watch::Sender<Eligibility>,
        task: tokio::task::JoinHandle<()>,
    }

    fn start(cfg: LeaseConfig) -> Candidate {
        start_as(cfg, Eligibility::Ready)
    }

    fn start_as(cfg: LeaseConfig, eligibility: Eligibility) -> Candidate {
        let (leading_tx, leading) = watch::channel(false);
        let (stop, stop_rx) = watch::channel(false);
        let (eligible, eligible_rx) = watch::channel(eligibility);
        let client = LeaseClient::new(cfg).expect("client");
        let task = tokio::spawn(campaign(client, leading_tx, eligible_rx, stop_rx));
        Candidate {
            leading,
            stop,
            eligible,
            task,
        }
    }

    async fn rig() -> (FakeLeases, String, tempfile::TempDir, std::path::PathBuf) {
        let fake = FakeLeases::default();
        let api = serve(fake.router()).await;
        let dir = tempfile::tempdir().expect("tempdir");
        let token = dir.path().join("token");
        std::fs::write(&token, "sa-token").expect("token");
        (fake, api, dir, token)
    }

    #[tokio::test]
    async fn an_incomplete_worker_never_takes_the_lease() {
        let (fake, api, _dir, token) = rig().await;
        let a = start_as(
            config(&api, &token, "prism-worker-1"),
            Eligibility::Incomplete,
        );
        tokio::time::sleep(Duration::from_millis(800)).await;
        assert!(!*a.leading.borrow());
        assert_eq!(fake.holder(), None, "it never created or took the lease");
        a.stop.send_replace(true);
        a.task.await.expect("stopped");
    }

    #[tokio::test]
    async fn a_leader_whose_replica_turns_incomplete_hands_over_at_once() {
        let (fake, api, _dir, token) = rig().await;
        let mut a = start(config(&api, &token, "prism-worker-1"));
        becomes(&mut a.leading, true, Duration::from_secs(2)).await;
        let mut b = start(config(&api, &token, "prism-worker-2"));
        a.eligible.send_replace(Eligibility::Incomplete);
        becomes(&mut a.leading, false, Duration::from_millis(500)).await;
        // Released, not left to expire (2 s): B takes over at its next tick.
        becomes(&mut b.leading, true, Duration::from_millis(1000)).await;
        assert_eq!(fake.holder().as_deref(), Some("prism-worker-2"));
        // Ready again, A doesn't take a live lease back.
        a.eligible.send_replace(Eligibility::Ready);
        tokio::time::sleep(Duration::from_millis(500)).await;
        assert!(!*a.leading.borrow());
        for c in [a, b] {
            c.stop.send_replace(true);
            c.task.await.expect("stopped");
        }
    }

    #[tokio::test]
    async fn an_unknown_worker_stands_back_for_a_ready_one() {
        let (fake, api, _dir, token) = rig().await;
        // A ready worker leads, then releases: an unknown one waits while
        // a ready one takes the free lease.
        let mut first = start(config(&api, &token, "prism-worker-0"));
        becomes(&mut first.leading, true, Duration::from_secs(2)).await;
        let unknown = start_as(config(&api, &token, "prism-worker-1"), Eligibility::Unknown);
        first.stop.send_replace(true);
        first.task.await.expect("stopped");
        let mut ready = start(config(&api, &token, "prism-worker-2"));
        becomes(&mut ready.leading, true, Duration::from_secs(1)).await;
        assert!(!*unknown.leading.borrow());
        // Alone, the unknown worker takes the lease once it has been left
        // for ttl (2 s) past its expiry.
        ready.stop.send_replace(true);
        ready.task.await.expect("stopped");
        let left = tokio::time::Instant::now();
        let mut unknown = unknown;
        becomes(&mut unknown.leading, true, Duration::from_secs(5)).await;
        assert!(
            left.elapsed() >= Duration::from_millis(1800),
            "took it after {:?}",
            left.elapsed()
        );
        assert_eq!(fake.holder().as_deref(), Some("prism-worker-1"));
        unknown.stop.send_replace(true);
        unknown.task.await.expect("stopped");
    }

    async fn becomes(rx: &mut watch::Receiver<bool>, want: bool, within: Duration) {
        tokio::time::timeout(within, rx.wait_for(|l| *l == want))
            .await
            .unwrap_or_else(|_| panic!("leading did not become {want} within {within:?}"))
            .expect("campaign alive");
    }

    #[tokio::test]
    async fn one_worker_leads_and_hands_over_on_release() {
        let fake = FakeLeases::default();
        let api = serve(fake.router()).await;
        let dir = tempfile::tempdir().expect("tempdir");
        let token = dir.path().join("token");
        std::fs::write(&token, "sa-token\n").expect("token");

        let mut a = start(config(&api, &token, "prism-worker-0"));
        becomes(&mut a.leading, true, Duration::from_secs(2)).await;
        let mut b = start(config(&api, &token, "prism-worker-1"));
        tokio::time::sleep(Duration::from_millis(500)).await;
        assert!(*a.leading.borrow());
        assert!(!*b.leading.borrow(), "only one worker leads");
        assert_eq!(fake.holder().as_deref(), Some("prism-worker-0"));

        // A shuts down and releases: B takes over well before the lease expires.
        a.stop.send_replace(true);
        a.task.await.expect("a stopped");
        assert!(!*a.leading.borrow());
        becomes(&mut b.leading, true, Duration::from_millis(1000)).await;
        assert_eq!(fake.holder().as_deref(), Some("prism-worker-1"));
        let transitions =
            fake.lease.lock().expect("lease").as_ref().expect("lease")["spec"]["leaseTransitions"]
                .as_i64();
        assert_eq!(transitions, Some(1));
        b.stop.send_replace(true);
        b.task.await.expect("b stopped");
    }

    #[tokio::test]
    async fn losing_the_lease_stops_leading_promptly() {
        let fake = FakeLeases::default();
        let api = serve(fake.router()).await;
        let dir = tempfile::tempdir().expect("tempdir");
        let token = dir.path().join("token");
        std::fs::write(&token, "sa-token").expect("token");

        let mut a = start(config(&api, &token, "prism-worker-0"));
        becomes(&mut a.leading, true, Duration::from_secs(2)).await;
        // Another holder took it: A notices at its next renewal.
        fake.steal("prism-worker-9");
        becomes(&mut a.leading, false, Duration::from_millis(500)).await;
        a.stop.send_replace(true);
        a.task.await.expect("stopped");
        assert_eq!(
            fake.holder().as_deref(),
            Some("prism-worker-9"),
            "a non-holder releases nothing"
        );

        // The API goes away: A stops by its own clock, before the lease expires.
        *fake.lease.lock().expect("lease") = None;
        let mut a = start(config(&api, &token, "prism-worker-0"));
        becomes(&mut a.leading, true, Duration::from_secs(2)).await;
        let lost = tokio::time::Instant::now();
        *fake.broken.lock().expect("broken") = true;
        becomes(&mut a.leading, false, Duration::from_secs(2)).await;
        assert!(
            lost.elapsed() < Duration::from_millis(1600),
            "stopped after {:?}, the lease lasts 2s",
            lost.elapsed()
        );
        a.stop.send_replace(true);
        a.task.await.expect("stopped");
    }
}
