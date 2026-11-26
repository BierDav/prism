//! The leader's once-only work: numbering new alert episodes, posting their
//! ntfy wake-ups, claiming and running the owner's requests, and reconciling
//! with Alertmanager. It runs only while this worker leads and stops as soon
//! as leadership ends.

use crate::config::Config;
use crate::lease::{Eligibility, LeaseClient, LeaseConfig, campaign};
use crate::push::Pusher;
use crate::t3::{Secrets, T3Hub};
use chrono::Utc;
use rocky_prism::{
    ACTION_FIX, AlertActionOutcome, AlertActionView, Notification, PrismClient, PrismError,
    check_action, label_fingerprint, parse_time,
};
use serde::Deserialize;
use std::collections::{BTreeMap, BTreeSet};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::watch;

/// A request claimed this long ago and never finished is claimed again: a
/// T3 launch gives up after 150 seconds.
pub const CLAIM_TIMEOUT_SECS: i64 = 5 * 60;
/// How long finished requests and closed approvals and questions stay.
const PHASE3_KEEP_SECS: i64 = 7 * 24 * 60 * 60;
const TICK: Duration = Duration::from_secs(2);
const RECONCILE_EVERY: Duration = Duration::from_secs(60);
const SCAN_EVERY: Duration = Duration::from_secs(30);

/// How long a worker leads before it trims the profiles' stored padding, so
/// its replica has caught up with the profiles first.
const TRIM_AFTER: Duration = Duration::from_secs(120);

pub type Clock = Arc<dyn Fn() -> i64 + Send + Sync>;

pub struct LeaderWork {
    pub client: Arc<PrismClient>,
    pub cfg: Arc<Config>,
    /// This worker's name, which its claims carry.
    pub worker: String,
    pub pusher: Pusher,
    pub t3: T3Hub,
    pub secrets: Secrets,
    pub http: reqwest::Client,
    pub now: Clock,
}

impl LeaderWork {
    pub fn new(
        client: Arc<PrismClient>,
        cfg: Arc<Config>,
        worker: String,
        secrets: Secrets,
    ) -> Self {
        Self {
            pusher: Pusher::new(&cfg.push.url, &cfg.push.topic, Arc::clone(&secrets)),
            t3: T3Hub::new(Arc::clone(&cfg), Arc::clone(&secrets)),
            secrets,
            http: reqwest::Client::builder()
                .timeout(Duration::from_secs(30))
                .build()
                .expect("reqwest client"),
            client,
            cfg,
            worker,
            now: Arc::new(|| Utc::now().timestamp()),
        }
    }

    /// One pass of the leader's work. Alerts and pushes come first, so an
    /// unavailable T3 never delays them.
    pub async fn tick(&self) -> Result<(), String> {
        let now = (self.now)();
        if let Ok(sessions) = self.client.list_t3_sessions().await {
            self.t3.load_paired_sessions(&sessions);
        }
        // Numbering failing must not hold up pushes, requests and approvals.
        if let Err(err) = self.client.assign_alert_numbers(now).await {
            eprintln!("prism-worker: numbering alerts failed; retrying: {err}");
        }
        self.publish_device_config(now).await;
        self.deliver_alert_pushes()
            .await
            .map_err(|err| format!("push delivery: {err}"))?;
        self.run_requests()
            .await
            .map_err(|err| format!("requests: {err}"))?;
        crate::approvals::run_approval_answers(
            &self.client,
            &self.cfg,
            &self.pusher,
            &self.worker,
            (self.now)(),
            CLAIM_TIMEOUT_SECS,
        )
        .await?;
        crate::questions::run_question_answers(
            &self.client,
            &self.t3,
            &self.pusher,
            &self.worker,
            (self.now)(),
            CLAIM_TIMEOUT_SECS,
        )
        .await?;
        crate::agents::run_agent_requests(
            &self.client,
            &self.cfg,
            &self.t3,
            &self.pusher,
            &self.secrets,
            &self.worker,
            (self.now)(),
            CLAIM_TIMEOUT_SECS,
        )
        .await?;
        crate::agents::run_agent_messages(
            &self.client,
            &self.t3,
            &self.worker,
            (self.now)(),
            CLAIM_TIMEOUT_SECS,
        )
        .await?;
        self.deliver_release_pushes()
            .await
            .map_err(|err| format!("release push: {err}"))?;
        self.deliver_settings_pushes()
            .await
            .map_err(|err| format!("settings push: {err}"))?;
        // Alerts that fired or ended wake the phone in the same pass.
        self.deliver_alert_pushes()
            .await
            .map_err(|err| format!("push delivery: {err}"))
    }

    /// Keeps the config the devices run their own requests with
    /// (`crate::direct`) in the replica, written once per change.
    async fn publish_device_config(&self, now: i64) {
        let wanted = crate::direct::device_config(&self.cfg);
        match self.client.get_setting(crate::direct::CONFIG_SETTING).await {
            Ok(Some(current)) if current == wanted => {}
            Ok(_) => {
                if let Err(err) = self
                    .client
                    .set_setting(crate::direct::CONFIG_SETTING, &wanted, now)
                    .await
                {
                    eprintln!("prism-worker: publishing the devices' T3 config failed: {err}");
                }
            }
            Err(err) => eprintln!("prism-worker: reading the devices' T3 config failed: {err}"),
        }
    }

    /// Pushes app.release once per published Android version (Go's
    /// `PublishAndroid` notify). Any worker takes uploads; only the leader pushes.
    async fn deliver_release_pushes(&self) -> Result<(), String> {
        let releases = self
            .client
            .list_android_releases()
            .await
            .map_err(|e| e.to_string())?;
        let pending: Vec<Notification> = releases
            .into_iter()
            .map(|rel| Notification {
                kind: "app.release".into(),
                key: format!("app.release:{}:{}", rel.package, rel.version_code),
                time: rel.uploaded_at,
                title: format!("Prism {}", rel.version_name),
                body: "A new version of Prism for Android is published.".into(),
                version_code: rel.version_code,
                package: rel.package,
                ..Notification::default()
            })
            .collect();
        self.pusher
            .deliver(&self.client, &pending, (self.now)())
            .await
            .map(|_| ())
            .map_err(|e| e.to_string())
    }

    /// Pushes app.settings once per change of a device's settings (Go's
    /// `PublishAndroidSettings` notify). Any worker takes them; only the leader pushes.
    async fn deliver_settings_pushes(&self) -> Result<(), String> {
        let now = (self.now)();
        let rows = self
            .client
            .list_settings()
            .await
            .map_err(|e| e.to_string())?;
        let pending = crate::android_settings::settings_pushes(&rows, now);
        if pending.is_empty() {
            return Ok(());
        }
        self.pusher
            .deliver(&self.client, &pending, now)
            .await
            .map(|_| ())
            .map_err(|e| e.to_string())
    }

    /// Periodic Phase 3 scans (agents, questions, approvals, machines, expiry).
    pub async fn tick_scans(&self) {
        let now = (self.now)();
        if let Err(err) =
            crate::agents::watch_agents(&self.client, &self.t3, &self.pusher, now).await
        {
            eprintln!("prism-worker: agent watcher failed: {err}");
        }
        if let Err(err) =
            crate::questions::scan_questions(&self.client, &self.t3, &self.pusher, now).await
        {
            eprintln!("prism-worker: question scanner failed: {err}");
        }
        if let Err(err) = crate::approvals::scan_approvals(&self.client, &self.pusher, now).await {
            eprintln!("prism-worker: approval scanner failed: {err}");
        }
        if let Err(err) = crate::agents::refresh_machines_and_projects(
            &self.client,
            &self.cfg,
            &self.t3,
            &self.secrets,
            now,
        )
        .await
        {
            eprintln!("prism-worker: machine/project refresh failed: {err}");
        }
        if let Err(err) = crate::agents::expiry_reminders(
            &self.client,
            &self.cfg,
            &self.pusher,
            &self.secrets,
            now,
        )
        .await
        {
            eprintln!("prism-worker: expiry reminders failed: {err}");
        }
        if let Err(err) = self.client.prune_phase3(now, PHASE3_KEEP_SECS).await {
            eprintln!("prism-worker: pruning finished requests failed: {err}");
        }
    }

    async fn deliver_alert_pushes(&self) -> Result<(), String> {
        let now = (self.now)();
        let pending = self
            .client
            .pending_alert_pushes(now)
            .await
            .map_err(|e| e.to_string())?;
        self.pusher
            .deliver(&self.client, &pending, now)
            .await
            .map_err(|e| e.to_string())?;
        Ok(())
    }

    /// Claims every request no worker finished and runs it.
    pub async fn run_requests(&self) -> Result<(), PrismError> {
        let now = (self.now)();
        for req in self
            .client
            .claimable_alert_actions(now - CLAIM_TIMEOUT_SECS)
            .await?
        {
            // A request can replicate before its alert does: wait for it.
            if self.client.alert_by_key(&req.alert).await?.is_none() {
                continue;
            }
            let now = (self.now)();
            if !self
                .client
                .claim_alert_action(&req.request_id, &self.worker, now, now - CLAIM_TIMEOUT_SECS)
                .await?
            {
                continue;
            }
            let outcome = self.run(&req).await;
            self.client
                .finish_alert_action(&req.request_id, &self.worker, (self.now)(), outcome)
                .await?;
        }
        Ok(())
    }

    async fn run(&self, req: &AlertActionView) -> AlertActionOutcome {
        run_alert_action(&self.client, &self.t3, &self.pusher, &self.now, req).await
    }

    /// Ends the alerts Alertmanager no longer has (Go's `Reconcile`).
    pub async fn reconcile(&self) -> Result<(), String> {
        let base = self.cfg.alertmanager_url.trim_end_matches('/');
        if base.is_empty() {
            return Ok(());
        }
        #[derive(Deserialize)]
        struct Status {
            #[serde(default)]
            uptime: String,
        }
        #[derive(Deserialize)]
        struct Current {
            #[serde(default)]
            labels: BTreeMap<String, String>,
        }
        let status: Status = self
            .get_json(&format!("{base}/api/v2/status"))
            .await
            .map_err(|err| format!("alertmanager status: {err}"))?;
        let current: Vec<Current> = self
            .get_json(&format!("{base}/api/v2/alerts"))
            .await
            .map_err(|err| format!("alertmanager alerts: {err}"))?;
        let present: BTreeSet<String> = current
            .iter()
            .map(|a| label_fingerprint(&a.labels))
            .collect();
        let now = (self.now)();
        let trusted = parse_time(&status.uptime)
            .is_some_and(|up| now - up >= self.cfg.reconcile_uptime_seconds);
        let ended = self
            .client
            .reconcile_alerts(&present, trusted, self.cfg.reconcile_grace_seconds, now)
            .await
            .map_err(|e| e.to_string())?;
        for key in ended {
            eprintln!("prism-worker: alert {key} no longer in Alertmanager; ended it");
        }
        Ok(())
    }

    async fn get_json<T: serde::de::DeserializeOwned>(&self, url: &str) -> Result<T, String> {
        let resp = self.http.get(url).send().await.map_err(|e| e.to_string())?;
        if !resp.status().is_success() {
            return Err(format!("HTTP {}", resp.status().as_u16()));
        }
        resp.json().await.map_err(|e| e.to_string())
    }

    /// Works while `leading` is true; stops at once when it turns false.
    pub async fn run_while_leading(self, mut leading: watch::Receiver<bool>) {
        loop {
            if leading.wait_for(|l| *l).await.is_err() {
                return;
            }
            tokio::select! {
                _ = self.lead() => {}
                _ = leading.wait_for(|l| !*l) => {}
            }
        }
    }

    async fn lead(&self) {
        // The scans call every T3 and may wait on an offline one for
        // minutes; they run beside the requests and pushes, never before them
        // (Go runs each on its own timer).
        // Prompting accounts waits on the AI gateway, so it runs on its own
        // timer too.
        tokio::join!(
            self.lead_requests(),
            self.lead_scans(),
            self.lead_weekly_activations()
        );
    }

    async fn lead_weekly_activations(&self) {
        let Some(source) = crate::usage::UsageSource::from_config(&self.cfg, &self.secrets) else {
            return;
        };
        let mut ticker = tokio::time::interval(crate::activate::ACTIVATE_EVERY);
        ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            ticker.tick().await;
            let usage = source.current().await;
            if let Err(err) = crate::activate::activate_weekly_windows(
                &self.client,
                &self.cfg,
                &self.secrets,
                &usage,
                (self.now)(),
            )
            .await
            {
                eprintln!("prism-worker: starting weekly windows failed; retrying: {err}");
            }
        }
    }

    async fn lead_requests(&self) {
        let mut ticker = tokio::time::interval(TICK);
        ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        let mut last_reconcile: Option<tokio::time::Instant> = None;
        let leading_since = tokio::time::Instant::now();
        let mut trimmed = false;
        loop {
            ticker.tick().await;
            if !trimmed && leading_since.elapsed() >= TRIM_AFTER {
                let now = (self.now)();
                match rocky_prism::retry_unavailable(|| self.client.trim_profile_padding(now)).await
                {
                    Ok(ids) => {
                        trimmed = true;
                        if !ids.is_empty() {
                            eprintln!("prism-worker: trimmed padding from profiles {ids:?}");
                        }
                    }
                    Err(err) => {
                        eprintln!("prism-worker: trimming profiles failed; retrying: {err}")
                    }
                }
            }
            if let Err(err) = self.tick().await {
                eprintln!("prism-worker: leader work failed; retrying: {err}");
            }
            if last_reconcile.is_none_or(|t| t.elapsed() >= RECONCILE_EVERY) {
                last_reconcile = Some(tokio::time::Instant::now());
                if let Err(err) = self.reconcile().await {
                    eprintln!("prism-worker: alert reconciliation failed; retrying: {err}");
                }
            }
        }
    }

    async fn lead_scans(&self) {
        let mut ticker = tokio::time::interval(SCAN_EVERY);
        ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            ticker.tick().await;
            self.tick_scans().await;
        }
    }
}

/// Runs one claimed alert action (fix): the leader's, and
/// a device's own when the workers are unreachable (`crate::direct`).
pub async fn run_alert_action(
    client: &PrismClient,
    t3: &T3Hub,
    pusher: &Pusher,
    clock: &Clock,
    req: &AlertActionView,
) -> AlertActionOutcome {
    let failed = |error: String| AlertActionOutcome {
        error,
        ..AlertActionOutcome::default()
    };
    let row = match client.alert_by_key(&req.alert).await {
        Ok(Some(row)) => row,
        Ok(None) => return failed("The alert is gone.".into()),
        Err(err) => return failed(storage_failure(err)),
    };
    if let Err(err) = check_action(&row, &req.kind, &req.instruction) {
        return failed(err.to_string());
    }
    match req.kind.as_str() {
        ACTION_FIX => {
            let profile = match client.alert_fix_profile().await {
                Ok(p) => p,
                Err(err) => return failed(storage_failure(err)),
            };
            match t3
                .fix_alert(
                    &row,
                    &req.instruction,
                    &req.requested_by,
                    &req.request_id,
                    &profile,
                )
                .await
            {
                Ok((launch, title, target)) => {
                    let now = clock();
                    let _ = client
                        .save_agent(&rocky_prism::AgentRow {
                            thread_id: launch.thread_id.clone(),
                            kind: if req.instruction.trim().is_empty() {
                                "investigate".into()
                            } else {
                                "instruct".into()
                            },
                            title: title.clone(),
                            alert_id: row.number,
                            target: target.name.clone(),
                            client: target.client.clone(),
                            environment_id: target.environment_id.clone(),
                            host: target.host.clone(),
                            link: launch.link.clone(),
                            request_text: req.instruction.clone(),
                            state: "active".into(),
                            run_status: "running".into(),
                            question_pending: false,
                            last_message: String::new(),
                            requested_by: req.requested_by.clone(),
                            created_at: now,
                            updated_at: now,
                            next_check: now + 10,
                            reported_run: String::new(),
                            terminal_run: String::new(),
                            terminal_seen: 0,
                        })
                        .await;
                    let started = Notification {
                        kind: "agent.started".into(),
                        key: format!("agent.started:{}", launch.thread_id),
                        time: clock(),
                        title: format!("Agent started: {title}"),
                        alert_id: row.number,
                        agent_id: launch.thread_id.clone(),
                        thread_id: launch.thread_id.clone(),
                        environment_id: target.environment_id.clone(),
                        host: target.host.clone(),
                        ..Notification::default()
                    };
                    if let Err(err) = pusher.deliver(client, &[started], clock()).await {
                        eprintln!("prism-worker: could not post the agent's push: {err}");
                    }
                    AlertActionOutcome {
                        thread_id: launch.thread_id,
                        link: launch.link,
                        error: String::new(),
                    }
                }
                Err(err) => {
                    eprintln!(
                        "prism-worker: fix launch for {} failed: {err}",
                        req.request_id
                    );
                    failed(err.for_owner())
                }
            }
        }
        other => failed(rocky_prism::unknown_action(other)),
    }
}

fn storage_failure(err: PrismError) -> String {
    eprintln!("prism-worker: request failed: {err}");
    "The worker could not run this request. Try again.".into()
}

/// The running leader: the campaign (when in a cluster) and the work.
pub struct Leader {
    stop: watch::Sender<bool>,
    tasks: Vec<tokio::task::JoinHandle<()>>,
    /// Whether this worker's replica can do the leader's work (`/ready`).
    pub eligible: watch::Receiver<Eligibility>,
}

impl Leader {
    /// Starts campaigning for the Kubernetes Lease (or leads at once outside
    /// a cluster) and runs the leader's work while leading. In a cluster only
    /// while the replica can serve it (`crate::health`).
    pub fn start(work: LeaderWork, lease: Option<LeaseConfig>) -> Result<Self, String> {
        Self::start_probing(work, lease, crate::health::PROBE_EVERY)
    }

    pub fn start_probing(
        work: LeaderWork,
        lease: Option<LeaseConfig>,
        probe_every: Duration,
    ) -> Result<Self, String> {
        let (stop, stop_rx) = watch::channel(false);
        let mut tasks = Vec::new();
        let (eligible_tx, eligible) = watch::channel(Eligibility::Unknown);
        let probed = Arc::clone(&work.client);
        tasks.push(tokio::spawn(crate::health::watch_replica(
            move || {
                let client = Arc::clone(&probed);
                async move { client.leader_ready().await }
            },
            eligible_tx,
            probe_every,
            stop_rx.clone(),
        )));
        let leading_rx = match lease {
            Some(cfg) => {
                let client = LeaseClient::new(cfg)?;
                let (leading_tx, leading_rx) = watch::channel(false);
                tasks.push(tokio::spawn(campaign(
                    client,
                    leading_tx,
                    eligible.clone(),
                    stop_rx.clone(),
                )));
                leading_rx
            }
            None => {
                eprintln!("prism-worker: not in a cluster; leading without a lease");
                let (leading_tx, leading_rx) = watch::channel(true);
                let mut stop_rx = stop_rx.clone();
                tasks.push(tokio::spawn(async move {
                    let _ = stop_rx.wait_for(|s| *s).await;
                    leading_tx.send_replace(false);
                }));
                leading_rx
            }
        };
        tasks.push(tokio::spawn(work.run_while_leading(leading_rx)));
        Ok(Self {
            stop,
            tasks,
            eligible,
        })
    }

    /// Stops leading (releasing the lease) and waits for the tasks.
    pub async fn shutdown(self) {
        self.stop.send_replace(true);
        for task in self.tasks {
            let _ = task.await;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::lease::LeaseConfig;
    use crate::t3::launch_key;
    use crate::testutil::{FakeLeases, FakeNtfy, FakeT3, serve};
    use rocky_prism::{AlertActionRequest, AlertFilter, sha256_hex};
    use serde_json::{Value, json};
    use std::sync::atomic::{AtomicI64, Ordering};

    const NOW: i64 = 1_795_255_300;

    struct Rig {
        client: Arc<PrismClient>,
        ntfy: FakeNtfy,
        t3: FakeT3,
        cfg: Arc<Config>,
        clock: Arc<AtomicI64>,
        _dir: tempfile::TempDir,
    }

    async fn rig() -> Rig {
        let dir = tempfile::tempdir().expect("tempdir");
        let client = Arc::new(
            PrismClient::open_or_create(dir.path().display().to_string(), None)
                .await
                .expect("open"),
        );
        let ntfy = FakeNtfy::default();
        // Nothing is built in: the owner's own alert-fix profile.
        client
            .put_placement_profile(
                "fixes".into(),
                rocky_prism::PutPlacementProfileRequest {
                    label: "Fixes".into(),
                    entries: Vec::new(),
                },
                0,
            )
            .await
            .expect("profile");
        client
            .set_placement_actions(rocky_prism::SetPlacementActionsRequest {
                alert_fix: Some("fixes".into()),
                text_generation: None,
                role_profiles: None,
            })
            .await
            .expect("alert-fix profile");
        let t3 = FakeT3::default();
        t3.profiles.lock().expect("profiles").push("fixes".into());
        let ntfy_url = serve(ntfy.router()).await;
        let t3_url = serve(t3.router()).await;
        let cfg = Arc::new(
            Config::parse(
                &json!({
                    "push": {"url": ntfy_url, "topic": "prism-test"},
                    "cluster_machine": "david-server",
                    "machines": {"david-server": {"t3_url": t3_url}},
                    "t3_workspace": "/srv/selfhosted-platform",
                })
                .to_string(),
            )
            .expect("config"),
        );
        Rig {
            client,
            ntfy,
            t3,
            cfg,
            clock: Arc::new(AtomicI64::new(NOW)),
            _dir: dir,
        }
    }

    impl Rig {
        fn work(&self, worker: &str) -> LeaderWork {
            let secrets: Secrets = Arc::new(|name: &str| match name {
                "PRISM_PUSH_TOKEN" => Some("push-token".into()),
                "T3_ACCESS_TOKEN" => Some("t3-token".into()),
                _ => None,
            });
            let mut work = LeaderWork::new(
                Arc::clone(&self.client),
                Arc::clone(&self.cfg),
                worker.into(),
                secrets,
            );
            let clock = Arc::clone(&self.clock);
            work.now = Arc::new(move || clock.load(Ordering::SeqCst));
            work
        }

        async fn fire(&self, alertname: &str) {
            self.webhook("firing", alertname).await;
        }

        /// The alert's rule recovered.
        async fn recover(&self, alertname: &str) {
            self.webhook("resolved", alertname).await;
        }

        async fn webhook(&self, status: &str, alertname: &str) {
            let body = json!({"status": status, "alerts": [{
                "labels": {"alertname": alertname, "severity": "critical"},
                "annotations": {"summary": format!("{alertname} fired")},
                "startsAt": "2026-11-21T10:00:00Z",
                "endsAt": if status == "resolved" { "2026-11-21T11:00:00Z" } else { "0001-01-01T00:00:00Z" },
            }]});
            self.client
                .record_alert_webhook(
                    body.to_string().as_bytes(),
                    self.clock.load(Ordering::SeqCst),
                )
                .await
                .expect("webhook");
        }

        fn posts(&self) -> Vec<(String, Value)> {
            self.ntfy.posts.lock().expect("posts").clone()
        }

        async fn request(&self, id: &str, kind: &str, instruction: &str) {
            let key = self
                .client
                .list_alerts(AlertFilter::All)
                .await
                .expect("list")[0]
                .key
                .clone();
            self.client
                .request_alert_action(
                    AlertActionRequest {
                        request_id: id.into(),
                        alert: key,
                        kind: kind.into(),
                        instruction: instruction.into(),
                        requested_by: "david-phone".into(),
                    },
                    self.clock.load(Ordering::SeqCst),
                )
                .await
                .expect("request");
        }
    }

    #[tokio::test]
    async fn leader_numbers_wakes_the_phone_and_runs_requests() {
        let rig = rig().await;
        let work = rig.work("prism-worker-0");
        rig.fire("NodeNotReady").await;

        work.tick().await.expect("tick");
        let posts = rig.posts();
        assert_eq!(posts.len(), 1);
        let (auth, body) = &posts[0];
        assert_eq!(auth, "Bearer push-token");
        assert_eq!(body["topic"], "prism-test");
        assert_eq!(body["title"], "#1 NodeNotReady");
        assert_eq!(body["priority"], 5);
        assert_eq!(body["tags"], json!(["prism", "alert-firing"]));
        let message: Value =
            serde_json::from_str(body["message"].as_str().expect("message")).expect("json");
        assert_eq!(message["v"], 1);
        assert_eq!(message["alertId"], 1);
        assert_eq!(message["body"], "NodeNotReady fired");
        work.tick().await.expect("tick");
        assert_eq!(rig.posts().len(), 1, "a push is posted once");

        // fix without instruction: an investigation in the alert-fix profile.
        rig.request("r1", "fix", "").await;
        work.tick().await.expect("tick");
        let launches = rig.t3.launches.lock().expect("launches").clone();
        assert_eq!(launches.len(), 1);
        let launch = &launches[0];
        let expected_id = format!(
            "prism:{}",
            sha256_hex(&launch_key("investigate", "david-phone", "r1"))
        );
        assert_eq!(launch["clientRequestId"], expected_id);
        assert_eq!(launch["placementProfile"], "fixes");
        assert_eq!(launch["projectId"], "project-platform");
        assert_eq!(
            launch["workspaceStrategy"],
            json!({"type": "worktree", "baseRef": "origin/HEAD", "startFromOrigin": true})
        );
        let prompt = launch["message"].as_str().expect("prompt");
        assert!(prompt.starts_with("/prism\n"));
        assert!(prompt.contains(
            "Task: investigate\nTarget: the cluster (selfhosted-platform on david-server)."
        ));
        assert!(prompt.contains("The alert is #1, a cluster alert."));
        let done = rig
            .client
            .alert_action("r1")
            .await
            .expect("get")
            .expect("r1");
        assert_eq!(done.thread_id, "thread-1");
        assert_eq!(done.link, "t3-thread://v2/env/thread-1");
        assert_eq!(done.claimed_by.as_deref(), Some("prism-worker-0"));
        assert!(done.done_at.is_some());
        let posts = rig.posts();
        assert_eq!(posts.len(), 2);
        assert_eq!(
            posts[1].1["title"],
            "Agent started: Investigate: #1 NodeNotReady"
        );
        assert_eq!(posts[1].1["priority"], 1);

        // A leader launched r2 and died before recording it. The next leader
        // re-claims it after the timeout, and T3 returns the same thread.
        rig.request("r2", "fix", "restart the node").await;
        let row = rig
            .client
            .alert_by_number(1)
            .await
            .expect("get")
            .expect("#1");
        let now = rig.clock.load(Ordering::SeqCst);
        assert!(
            rig.client
                .claim_alert_action("r2", "prism-worker-0", now, now - CLAIM_TIMEOUT_SECS)
                .await
                .expect("claim")
        );
        let (first, _, _) = work
            .t3
            .fix_alert(&row, "restart the node", "david-phone", "r2", "fixes")
            .await
            .expect("launch");
        let next = rig.work("prism-worker-1");
        next.tick().await.expect("tick");
        assert_eq!(
            rig.client
                .alert_action("r2")
                .await
                .expect("get")
                .expect("r2")
                .done_at,
            None,
            "claim still fresh"
        );
        rig.clock
            .fetch_add(CLAIM_TIMEOUT_SECS + 1, Ordering::SeqCst);
        next.tick().await.expect("tick");
        let done = rig
            .client
            .alert_action("r2")
            .await
            .expect("get")
            .expect("r2");
        assert_eq!(done.claimed_by.as_deref(), Some("prism-worker-1"));
        assert_eq!(
            done.thread_id, first.thread_id,
            "the retried launch is the same thread"
        );
        assert_eq!(rig.t3.launches.lock().expect("launches").len(), 3);
        assert_eq!(rig.t3.distinct_threads(), 2);

        // Only its rule recovering ends the episode; that wakes the phone
        // quietly.
        rig.recover("NodeNotReady").await;
        next.tick().await.expect("tick");
        assert_eq!(
            rig.client
                .list_alerts(AlertFilter::All)
                .await
                .expect("list")[0]
                .state,
            "resolved"
        );
        let last = rig.posts().pop().expect("post");
        assert_eq!(last.1["title"], "#1 NodeNotReady resolved");
        assert_eq!(last.1["tags"], json!(["prism", "alert-ended"]));

        // A fix on a profile T3 does not offer fails with the owner's text.
        rig.t3.profiles.lock().expect("profiles").clear();
        rig.request("r4", "fix", "anyway").await;
        next.tick().await.expect("tick");
        let failed = rig
            .client
            .alert_action("r4")
            .await
            .expect("get")
            .expect("r4");
        assert_eq!(
            failed.error,
            "The profile fixes is not available on david-server. Choose another profile."
        );
    }

    #[tokio::test]
    async fn the_leader_pushes_each_android_release_once() {
        let rig = rig().await;
        let work = rig.work("prism-worker-0");
        rig.client
            .put_android_release(&rocky_prism::AndroidReleaseRow {
                package: "at.quickme.prism".into(),
                version_code: 42,
                version_name: "1.4.2".into(),
                sha256: "0".repeat(64),
                size: 10,
                uploaded_at: NOW - 5,
                uploaded_by: "builder".into(),
                stored_on: "prism-worker-1".into(),
            })
            .await
            .expect("release");
        work.tick().await.expect("tick");
        work.tick().await.expect("tick");
        let posts = rig.posts();
        assert_eq!(posts.len(), 1, "{posts:?}");
        let (_, body) = &posts[0];
        assert_eq!(body["title"], "Prism 1.4.2");
        assert_eq!(body["tags"], json!(["prism", "app-release"]));
        let message: Value =
            serde_json::from_str(body["message"].as_str().expect("message")).expect("json");
        assert_eq!(message["type"], "app.release");
        assert_eq!(message["versionCode"], 42);
        assert_eq!(message["package"], "at.quickme.prism");
    }

    #[tokio::test]
    async fn work_runs_only_while_this_worker_holds_the_lease() {
        let rig = rig().await;
        let leases = FakeLeases::default();
        let api = serve(leases.router()).await;
        let dir = tempfile::tempdir().expect("tempdir");
        let token = dir.path().join("token");
        std::fs::write(&token, "sa-token").expect("token");
        let lease = LeaseConfig {
            api,
            namespace: "monitoring".into(),
            name: "prism-worker-leader".into(),
            identity: "prism-worker-0".into(),
            token_path: token.display().to_string(),
            ca_path: None,
            ttl: Duration::from_secs(2),
            renew: Duration::from_millis(150),
            margin: Duration::from_millis(500),
        };
        // Real time here: the leader loop stamps pushes with the clock.
        rig.clock.store(Utc::now().timestamp(), Ordering::SeqCst);
        rig.fire("EtcdNoLeader").await;
        let leader = Leader::start(rig.work("prism-worker-0"), Some(lease)).expect("start");
        let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
        while rig.posts().is_empty() {
            assert!(
                tokio::time::Instant::now() < deadline,
                "the leader never posted"
            );
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        assert_eq!(leases.holder().as_deref(), Some("prism-worker-0"));

        leases.steal("prism-worker-1");
        tokio::time::sleep(Duration::from_millis(400)).await;
        rig.fire("DNSRedundancyLost").await;
        tokio::time::sleep(TICK + Duration::from_secs(1)).await;
        assert_eq!(
            rig.posts().len(),
            1,
            "a worker that lost the lease posts nothing"
        );
        assert_eq!(
            rig.client
                .list_alerts(AlertFilter::Open)
                .await
                .expect("list")
                .iter()
                .filter(|a| a.id > 0)
                .count(),
            1
        );
        leader.shutdown().await;
    }

    #[tokio::test]
    async fn outside_a_cluster_the_worker_always_leads() {
        let rig = rig().await;
        rig.clock.store(Utc::now().timestamp(), Ordering::SeqCst);
        rig.fire("NodeNotReady").await;
        let leader = Leader::start(rig.work("local"), None).expect("start");
        let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
        while rig.posts().is_empty() {
            assert!(
                tokio::time::Instant::now() < deadline,
                "the leader never posted"
            );
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        leader.shutdown().await;
    }

    #[tokio::test]
    async fn an_unreachable_t3_does_not_hold_up_alert_pushes() {
        let rig = rig().await;
        rig.clock.store(Utc::now().timestamp(), Ordering::SeqCst);
        rig.t3.stalled.store(true, Ordering::SeqCst);
        let leader = Leader::start(rig.work("local"), None).expect("start");
        // Let the first scan reach the stalled T3, then an alert fires.
        tokio::time::sleep(Duration::from_millis(500)).await;
        rig.fire("NodeNotReady").await;
        let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
        while rig.posts().is_empty() {
            assert!(
                tokio::time::Instant::now() < deadline,
                "the alert waited for the T3 scans"
            );
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
        leader.shutdown().await;
    }

    #[tokio::test]
    async fn reconciliation_asks_alertmanager() {
        use axum::{Json, Router, routing::get};
        let mut rig = rig().await;
        let am = serve(
            Router::new()
                .route(
                    "/api/v2/status",
                    get(|| async { Json(json!({"uptime": "2026-11-01T00:00:00Z"})) }),
                )
                .route("/api/v2/alerts", get(|| async { Json(json!([])) })),
        )
        .await;
        let mut cfg = (*rig.cfg).clone();
        cfg.alertmanager_url = am;
        rig.cfg = Arc::new(cfg);
        let work = rig.work("prism-worker-0");
        rig.fire("NodeNotReady").await;
        work.reconcile().await.expect("mark missing");
        rig.clock.fetch_add(600, Ordering::SeqCst);
        work.reconcile().await.expect("end");
        assert_eq!(
            rig.client
                .list_alerts(AlertFilter::All)
                .await
                .expect("list")[0]
                .state,
            "gone"
        );
    }
    /// The workers are gone: the phone runs its own requests in T3 with its
    /// own session, the config the leader published and the leader's code,
    /// and no worker runs them again once it is back.
    #[tokio::test]
    async fn a_device_runs_its_own_requests_while_the_workers_are_unreachable() {
        use crate::direct::{CONFIG_SETTING, DeviceSession, Direct, KIND_MCP, session_key};
        let rig = rig().await;
        let work = rig.work("prism-worker-0");
        rig.fire("NodeNotReady").await;
        work.tick().await.expect("tick");
        let posts = rig.posts().len();

        // The leader published the config without the workers' T3 URL.
        let t3_url = rig.cfg.machines["david-server"].t3_url.clone();
        let published = rig
            .client
            .get_setting(CONFIG_SETTING)
            .await
            .expect("get")
            .expect("published");
        assert!(!published.contains(&t3_url));
        assert!(published.contains("/srv/selfhosted-platform"));

        let clock = Arc::clone(&rig.clock);
        let now: Clock = Arc::new(move || clock.load(Ordering::SeqCst));
        // Without a session of its own the phone can't run anything.
        let refused = Direct::open(Arc::clone(&rig.client), "david-phone", Arc::clone(&now)).await;
        assert!(
            refused
                .err()
                .expect("no sessions")
                .contains("no T3 sessions")
        );

        // david-server registered the phone's MCP session.
        let session = DeviceSession {
            origin: t3_url.clone(),
            token: "t3-token".into(),
            environment_id: "env".into(),
            expires_at: NOW + 3600,
        };
        rig.client
            .set_setting(
                &session_key("david-phone", "david-server", KIND_MCP),
                &serde_json::to_string(&session).expect("json"),
                NOW,
            )
            .await
            .expect("session");
        let direct = Direct::open(Arc::clone(&rig.client), "david-phone", Arc::clone(&now))
            .await
            .expect("open");
        assert_eq!(direct.machines(), ["david-server"]);

        // A fix: the leader's launch, in full access, claimed by the phone.
        rig.request("r9", "fix", "").await;
        assert!(direct.alert_action("r9").await.expect("fix"));
        let launches = rig.t3.launches.lock().expect("launches").clone();
        assert_eq!(launches.len(), 1);
        assert_eq!(launches[0]["runtimeMode"], "full-access");
        assert_eq!(launches[0]["placementProfile"], "fixes");
        assert_eq!(launches[0]["projectId"], "project-platform");
        assert_eq!(
            launches[0]["clientRequestId"],
            format!(
                "prism:{}",
                sha256_hex(&launch_key("investigate", "david-phone", "r9"))
            )
        );
        let done = rig
            .client
            .alert_action("r9")
            .await
            .expect("get")
            .expect("r9");
        assert_eq!(done.claimed_by.as_deref(), Some("device:david-phone"));
        assert!(done.done_at.is_some());
        assert!(done.error.is_empty(), "{}", done.error);
        let agent = rig
            .client
            .get_agent(&done.thread_id)
            .await
            .expect("get")
            .expect("agent");
        assert_eq!(agent.host, "david-server");
        assert_eq!(agent.requested_by, "david-phone");
        assert!(
            !direct.alert_action("r9").await.expect("again"),
            "done once"
        );

        // An agent of its own, and stopping it.
        rig.client
            .request_agent("a1", "do", "", "clean up", "", "", "david-phone", NOW)
            .await
            .expect("request");
        assert!(direct.agent_request("a1").await.expect("agent"));
        let started = rig
            .client
            .request_outcome(rocky_prism::RequestTable::AgentRequests, "a1")
            .await
            .expect("get")
            .expect("a1");
        assert!(started.error.is_empty(), "{}", started.error);
        assert_eq!(rig.t3.launches.lock().expect("launches").len(), 2);
        rig.client
            .request_agent_stop("s1", &started.thread_id, "david-phone", NOW)
            .await
            .expect("stop");
        assert!(direct.agent_message("s1").await.expect("stop"));
        assert_eq!(rig.t3.interrupts.lock().expect("interrupts").len(), 1);

        // The device posted nothing; once the workers are back, their leader
        // runs none of it again, not even after the claim timeout.
        assert_eq!(rig.posts().len(), posts);
        rig.clock
            .fetch_add(CLAIM_TIMEOUT_SECS + 60, Ordering::SeqCst);
        work.tick().await.expect("tick");
        assert_eq!(rig.t3.launches.lock().expect("launches").len(), 2);
        assert_eq!(rig.t3.interrupts.lock().expect("interrupts").len(), 1);
        // A request the phone claimed but never finished stays its own.
        rig.request("r10", "fix", "").await;
        let at = rig.clock.load(Ordering::SeqCst);
        assert!(
            rig.client
                .claim_alert_action("r10", "device:david-phone", at, at - CLAIM_TIMEOUT_SECS)
                .await
                .expect("claim")
        );
        rig.clock
            .fetch_add(CLAIM_TIMEOUT_SECS + 60, Ordering::SeqCst);
        work.tick().await.expect("tick");
        assert_eq!(rig.t3.launches.lock().expect("launches").len(), 2);
    }

    /// The overlay's default is project "auto", which the leader picks with
    /// its text generation. A device has none (the published config leaves
    /// it out), so it must not claim such a request: claimed as
    /// `device:<name>` it ended in an error at best, and at worst stayed the
    /// device's own and never ran, since no worker takes a device's claim.
    #[tokio::test]
    async fn a_device_leaves_a_request_for_an_auto_project_to_the_workers() {
        use crate::direct::{DeviceSession, Direct, KIND_MCP, session_key};
        let rig = rig().await;
        let work = rig.work("prism-worker-0");
        work.tick().await.expect("tick");
        let t3_url = rig.cfg.machines["david-server"].t3_url.clone();
        let session = DeviceSession {
            origin: t3_url,
            token: "t3-token".into(),
            environment_id: "env".into(),
            expires_at: NOW + 3600,
        };
        rig.client
            .set_setting(
                &session_key("david-phone", "david-server", KIND_MCP),
                &serde_json::to_string(&session).expect("json"),
                NOW,
            )
            .await
            .expect("session");
        let clock = Arc::clone(&rig.clock);
        let now: Clock = Arc::new(move || clock.load(Ordering::SeqCst));
        let direct = Direct::open(Arc::clone(&rig.client), "david-phone", now)
            .await
            .expect("open");

        rig.client
            .request_agent(
                "a1",
                "do",
                "auto",
                "what is the uptime of david-server",
                "",
                "auto",
                "david-phone",
                NOW,
            )
            .await
            .expect("request");
        let why = direct
            .agent_request("a1")
            .await
            .expect_err("left to the workers");
        assert!(why.contains("waits for them"), "{why}");
        let waiting = rig
            .client
            .request_outcome(rocky_prism::RequestTable::AgentRequests, "a1")
            .await
            .expect("get")
            .expect("a1");
        assert_eq!(waiting.claimed_by, None, "the device did not claim it");
        assert_eq!(waiting.done_at, None);

        // The leader takes it on its next pass.
        work.tick().await.expect("tick");
        let ran = rig
            .client
            .request_outcome(rocky_prism::RequestTable::AgentRequests, "a1")
            .await
            .expect("get")
            .expect("a1");
        assert_eq!(ran.claimed_by.as_deref(), Some("prism-worker-0"));
        assert!(ran.done_at.is_some());

        // A request naming its project still runs on the device.
        rig.client
            .request_agent("a2", "do", "", "clean up", "", "", "david-phone", NOW)
            .await
            .expect("request");
        assert!(direct.agent_request("a2").await.expect("agent"));
    }
}
