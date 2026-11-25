use crate::enroll::{contact_node, decode_provisioning};
use crate::rank::{
    PLACEMENT_BURN_WINDOW_SECS, compute_burn_rate, guess_driver, live_used, model_key,
    placement_compact_target, placement_compact_window_start, placement_drain_target,
    placement_move_target, placement_waits_for_reset, rank_placement_profile, same_placement_entry,
    selection_for_entry, vienna_tz,
};
use crate::schema::prism_schema;
use crate::types::{
    PLACEMENT_ROLES, PlacementAccountLimit, PlacementActions, PlacementActiveHours,
    PlacementEntryDecision, PlacementEntryInput, PlacementEntryRow, PlacementEntryView,
    PlacementHostModel, PlacementLoad, PlacementModelReasoningView, PlacementModelView,
    PlacementMoveRow, PlacementMoveView, PlacementPolicy, PlacementProfileRow,
    PlacementProfileView, PlacementSample, PlacementSelection, PlacementSyncActions,
    PlacementSyncEntryResp, PlacementSyncModel, PlacementSyncMoveResp, PlacementSyncProfileResp,
    PlacementSyncRequest, PlacementSyncResponse, PlacementSyncThread, PlacementUsageReading,
    PlacementUsageSnapshot, PlacementView, PlacementWindow, PrismError, PutPlacementEntryInput,
    PutPlacementProfileRequest, SetPlacementActionsRequest, UsageByAccount,
};
use crate::weekly::{
    WeeklyActivation, WeeklyActivationState, WeeklyActivationStep, account_of,
    weekly_activation_step,
};
use chrono::{DateTime, SecondsFormat, Utc};
use rocky_sdk::{Configuration, Database, Network, SqlFailure, SqlValue, params};
use std::collections::{BTreeMap, BTreeSet};
use std::path::Path;
use std::sync::Arc;

/// What opening an absent or empty directory does.
enum First<'a> {
    Create,
    Join(&'a str),
}

const PLACEMENT_FRESH_REPORT_SECS: i64 = 3 * 60;
/// How often an account with unchanged usage gets a new burn-rate sample.
const PLACEMENT_SAMPLE_EVERY_SECS: i64 = 5 * 60;
const PLACEMENT_DAY_REPORT_SECS: i64 = 24 * 60 * 60;
const PLACEMENT_MOVE_COOLDOWN_SECS: i64 = 2 * 60;
/// How old a ClickHouse reading may be and still rank. The gateway reads each
/// account every few minutes; past this, the host's own reading counts.
pub const USAGE_FRESH_SECS: i64 = 20 * 60;
/// How often the apps' usage snapshot may change unless an account ran out
/// or came back: each change replicates everywhere.
const USAGE_SNAPSHOT_EVERY_SECS: i64 = 5 * 60;
/// The setting that holds the apps' [`PlacementUsageSnapshot`].
const SETTING_PLACEMENT_USAGE: &str = "placement_usage";

const ACTION_ALERT_FIX: &str = "alert-fix";
const ACTION_TEXT_GENERATION: &str = "text-generation";

const POLICY_SWITCH_AT: &str = "switch_at_percent";
const POLICY_WEEKLY_SLACK: &str = "weekly_slack_sessions";
const POLICY_ACTIVE_HOURS: &str = "active_hours";
const POLICY_WEEKLY_MOVE_SLACK: &str = "weekly_move_slack_sessions";
/// Per account, what [`PrismClient::due_weekly_activations`] keeps
/// (`crate::weekly::WeeklyActivationState`), as `weekly_activation:<account>`.
const POLICY_WEEKLY_ACTIVATION: &str = "weekly_activation:";
/// Per account, its temporary limit (`PlacementAccountLimit`), as
/// `account_limit:<account>`.
const POLICY_ACCOUNT_LIMIT: &str = "account_limit:";

/// How much synced history a replica keeps before it refuses writes and
/// incoming frames ("storage queue is full"). Rocky prunes history only
/// behind a final seal, which needs trusted workers and a coordinator
/// (`crate::seal`); until a database has both, history only grows: Rocky's
/// default of 64 MiB filled within about a day. Rocky keeps this limit in
/// memory, so every open sets it again.
const RETAINED_HISTORY_BYTES: usize = 512 * 1024 * 1024;

pub struct PrismClient {
    pub(crate) db: Arc<Database>,
    /// Serialises this process's alert writes (webhooks, reconciliation, actions).
    pub(crate) alerts_lock: tokio::sync::Mutex<()>,
    /// Serialises this process's machine event reports (`record_host_event`).
    pub(crate) events_lock: tokio::sync::Mutex<()>,
    /// Burn-rate samples per model key, kept in memory only: every replicated
    /// write grows Rocky's history, and each worker sees enough syncs to keep
    /// its own over `PLACEMENT_BURN_WINDOW_SECS`.
    samples: std::sync::Mutex<BTreeMap<String, Vec<PlacementSample>>>,
}

impl PrismClient {
    /// Opens an existing Rocky database at `directory`, or creates a new one if absent/empty.
    ///
    /// A database created here is its own root and can't sync with enrolled
    /// devices: for local runs and tests. Enrolled devices use
    /// [`PrismClient::open_provisioned`].
    pub async fn open_or_create(
        directory: String,
        network: Option<Network>,
    ) -> Result<Self, PrismError> {
        Self::open_with(&directory, network, None, First::Create).await
    }

    /// Opens the enrolled device at `directory`. The first open (an absent or
    /// empty directory) installs the device from `provisioning`, minted by
    /// [`crate::PrismRoot::provision`], with no round trip to anyone; later
    /// opens reuse the directory and ignore `provisioning`.
    ///
    /// With a network, the device syncs with its peers ([`PrismClient::add_peers`])
    /// on its own. Nothing is seeded: every device would seed its own copy of
    /// the default profiles, and they would all sync.
    pub async fn open_provisioned(
        directory: String,
        provisioning: String,
        network: Option<Network>,
    ) -> Result<Self, PrismError> {
        Self::open_provisioned_with(directory, provisioning, network, None).await
    }

    /// [`PrismClient::open_provisioned`], composing `coordination`: how a
    /// trusted device reaches the coordinator that makes its seals final
    /// (`crate::seal`). Devices that are not trusted need none.
    pub async fn open_provisioned_with(
        directory: String,
        provisioning: String,
        network: Option<Network>,
        coordination: Option<rocky_sdk::CoordinatorSlot>,
    ) -> Result<Self, PrismError> {
        let networked = network.is_some();
        let provisioning = decode_provisioning(&provisioning)?;
        let client = Self::open_with(
            &directory,
            network,
            coordination,
            First::Join(&provisioning),
        )
        .await?;
        if networked {
            let session = client.db.session();
            let enabled = session.set_automatic_sync(true).await;
            session.close().await;
            enabled.map_err(storage_err)?;
        }
        Ok(client)
    }

    async fn open_with(
        directory: &str,
        network: Option<Network>,
        coordination: Option<rocky_sdk::CoordinatorSlot>,
        first: First<'_>,
    ) -> Result<Self, PrismError> {
        let schema = prism_schema()?;
        let mut config = Configuration::new(directory, &schema).map_err(storage_err)?;
        if let Some(net) = network {
            config = config.network(net);
        }
        config = match coordination {
            Some(rocky_sdk::CoordinatorSlot::Client(client)) => config.coordination(client),
            Some(rocky_sdk::CoordinatorSlot::Connector(connector)) => {
                config.coordinator_connector(connector)
            }
            None => config,
        };
        let is_empty = !Path::new(directory).exists()
            || std::fs::read_dir(directory)
                .map(|mut d| d.next().is_none())
                .unwrap_or(true);
        let opened = match (is_empty, first) {
            (false, _) => config.open().await,
            (true, First::Create) => config.create().await,
            (true, First::Join(provisioning)) => config.join(provisioning).await,
        }
        .map_err(storage_err)?;
        let session = opened.database.session();
        let raised = session
            .set_retention_limits(rocky_sdk::RetentionLimits {
                hard_bytes: RETAINED_HISTORY_BYTES,
                ..rocky_sdk::RetentionLimits::default()
            })
            .await;
        session.close().await;
        raised.map_err(storage_err)?;
        crate::seal::spawn_compaction(&opened.database);
        Ok(Self {
            db: opened.database,
            alerts_lock: tokio::sync::Mutex::new(()),
            events_lock: tokio::sync::Mutex::new(()),
            samples: std::sync::Mutex::default(),
        })
    }

    /// This device's Rocky node key, hex.
    pub async fn node(&self) -> Result<String, PrismError> {
        Ok(self.own_node().await?.to_hex())
    }

    async fn own_node(&self) -> Result<rocky_sdk::PublicKey, PrismError> {
        let session = self.db.session();
        let info = session.info().await.map_err(storage_err);
        session.close().await;
        Ok(info?.node)
    }

    /// Adds the peers this device syncs with, as peer contacts
    /// ([`crate::peer_contact`]). A contact naming this device is skipped, so
    /// every worker can get the same list. Returns how many were added.
    pub async fn add_peers(&self, contacts: Vec<String>) -> Result<u32, PrismError> {
        let own = serde_json::to_value(self.own_node().await?).map_err(storage_err)?;
        let session = self.db.session();
        let mut added = 0;
        let mut result = Ok(());
        for contact in &contacts {
            match contact_node(contact) {
                Ok(node) if node == own => continue,
                Ok(_) => {}
                Err(err) => {
                    result = Err(err);
                    break;
                }
            }
            if let Err(err) = session.add_peer(contact.trim()).await {
                result = Err(PrismError::InvalidInput(format!(
                    "could not add a peer: {err}"
                )));
                break;
            }
            added += 1;
        }
        session.close().await;
        result.map(|()| added)
    }

    pub async fn close(self) -> Result<(), PrismError> {
        self.db.close().await.map_err(storage_err)
    }

    /// Watches the replica for committed changes to `tables` (all tables when
    /// `None`), local writes and synced ones alike.
    pub async fn observe(
        &self,
        tables: Option<Vec<String>>,
    ) -> Result<rocky_sdk::TableEvents, PrismError> {
        self.db.observe_tables(tables).await.map_err(storage_err)
    }

    pub async fn ping(&self) -> Result<(), PrismError> {
        let mut rows = self
            .db
            .query("SELECT 1 AS ok", params![])
            .await
            .map_err(storage_err)?;
        let _ = rows.next().await.map_err(storage_err)?;
        Ok(())
    }

    /// Rocky's own view of this replica: its alerts (a seal it skipped, an
    /// unreachable coordinator), what blocks reads, and how it coordinates.
    pub async fn rocky_status(&self) -> Result<rocky_sdk::DatabaseStatus, PrismError> {
        let session = self.db.session();
        let status = session.status().await;
        session.close().await;
        status.map_err(storage_err)
    }

    /// Handles `POST /v1/placement/sync` from a T3 host with the host's own
    /// usage readings ([`PrismClient::sync_placement_with_usage`] with none
    /// from ClickHouse).
    pub async fn sync_placement(
        &self,
        machine: String,
        req: PlacementSyncRequest,
        now_unix: i64,
    ) -> Result<PlacementSyncResponse, PrismError> {
        self.sync_placement_with_usage(machine, req, now_unix, &UsageByAccount::new())
            .await
    }

    /// Handles `POST /v1/placement/sync` from a T3 host. A model whose account
    /// `usage` knows (read from ClickHouse within [`USAGE_FRESH_SECS`]) ranks
    /// with those windows; any other with the host's reading. Retries while
    /// Rocky refuses the write (`PrismError::Unavailable`).
    pub async fn sync_placement_with_usage(
        &self,
        machine: String,
        req: PlacementSyncRequest,
        now_unix: i64,
        usage: &UsageByAccount,
    ) -> Result<PlacementSyncResponse, PrismError> {
        retry_unavailable(|| {
            self.sync_placement_once(machine.clone(), req.clone(), now_unix, usage)
        })
        .await
    }

    async fn sync_placement_once(
        &self,
        machine: String,
        req: PlacementSyncRequest,
        now_unix: i64,
        usage: &UsageByAccount,
    ) -> Result<PlacementSyncResponse, PrismError> {
        let now_time = DateTime::from_timestamp(now_unix, 0).unwrap_or_else(Utc::now);

        // 1. Record move acknowledgements and store this host's report + threads.
        // Acknowledgements are their own rows keyed by move id, so they count even
        // when the move row has not replicated to this worker yet.
        // The report keeps the host's catalog without its usage readings:
        // usage changes all the time, and every stored change replicates to
        // every worker, phone and Mac and stays in Rocky's history. Ranking
        // reads usage from ClickHouse (or this request), the apps from the
        // `placement_usage` setting.
        let catalog: Vec<PlacementSyncModel> = req
            .models
            .iter()
            .map(|m| PlacementSyncModel {
                session: None,
                weekly: None,
                ..m.clone()
            })
            .collect();
        let models_json = serde_json::to_string(&catalog).map_err(storage_err)?;
        let acks: Vec<&crate::types::PlacementMoveAck> = req
            .moves
            .iter()
            .filter(|a| !a.id.is_empty() && (a.state == "continued" || a.state == "failed"))
            .collect();
        // Every write here replicates to every worker, phone and Mac, and a
        // replica refuses reads and new rows while it applies synced changes
        // (`Incomplete`). So an unchanged report rewrites nothing, and only
        // refreshes `seen_at` in place once it is HOST_SEEN_REFRESH_SECS old.
        // Only running threads are stored: they are all the fleet-wide load
        // counts, and a host keeps every idle profile thread it ever ran
        // (hundreds), which would rewrite them all on every start and stop
        // and outgrow a Rocky transaction. Moves use the request's threads.
        let threads: Vec<StoredThread> = req
            .threads
            .iter()
            .filter(|t| t.running)
            .map(|t| {
                Ok(StoredThread {
                    thread_id: t.thread_id.clone(),
                    title: t.title.clone(),
                    profile: t.profile.clone(),
                    selection_json: serde_json::to_string(&t.selection).map_err(storage_err)?,
                    model: t.selection.model.clone(),
                    running: t.running,
                })
            })
            .collect::<Result<_, PrismError>>()?;
        let stored = self.stored_report(&machine).await?;
        let same_threads = stored.as_ref().filter(|s| {
            s.environment_id == req.environment_id && same_threads(&s.threads, &threads)
        });
        let changed_models = same_threads.filter(|s| s.models_json != models_json);
        let refresh = same_threads.filter(|s| now_unix - s.seen_at >= HOST_SEEN_REFRESH_SECS);
        if !acks.is_empty()
            || same_threads.is_none()
            || changed_models.is_some()
            || refresh.is_some()
        {
            let mut tx = self.db.transaction().await.map_err(storage_err)?;
            for ack in &acks {
                let aid = tx.next_id("move_acks", vec![]).await.map_err(storage_err)?;
                tx.execute(
                    "INSERT INTO move_acks (id, instruction_id, host, state, at) VALUES (?, ?, ?, ?, ?)",
                    params![aid, ack.id.clone(), machine.clone(), ack.state.clone(), now_unix],
                )
                .await
                .map_err(storage_err)?;
            }
            if same_threads.is_none() {
                self.write_report(&mut tx, &machine, &req, &models_json, &threads, now_unix)
                    .await?;
            } else if let Some(stored) = changed_models {
                // Usage and catalog changes don't invalidate the thread snapshot.
                tx.execute(
                    "UPDATE hosts SET seen_at = ?, models_json = ? WHERE id = ?",
                    params![now_unix, models_json, stored.id.clone()],
                )
                .await
                .map_err(storage_err)?;
            } else if let Some(stored) = refresh {
                tx.execute(
                    "UPDATE hosts SET seen_at = ? WHERE id = ?",
                    params![now_unix, stored.id.clone()],
                )
                .await
                .map_err(storage_err)?;
            }
            tx.commit().await.map_err(storage_err)?;
        }
        self.after_report(machine, req, now_unix, now_time, usage)
            .await
    }

    /// Replaces this host's `hosts` row and its `threads` rows.
    async fn write_report(
        &self,
        tx: &mut rocky_sdk::Transaction,
        machine: &str,
        req: &PlacementSyncRequest,
        models_json: &str,
        threads: &[StoredThread],
        now_unix: i64,
    ) -> Result<(), PrismError> {
        let machine = machine.to_string();
        let models_json = models_json.to_string();
        tx.execute("DELETE FROM hosts WHERE name = ?", params![machine.clone()])
            .await
            .map_err(storage_err)?;
        let hid = tx.next_id("hosts", vec![]).await.map_err(storage_err)?;
        let report = hid.to_string();
        tx.execute(
            "INSERT INTO hosts (id, name, seen_at, environment_id, models_json) VALUES (?, ?, ?, ?, ?)",
            params![
                hid,
                machine.clone(),
                now_unix,
                req.environment_id.clone(),
                models_json,
            ],
        )
        .await
        .map_err(storage_err)?;
        tx.execute(
            "DELETE FROM threads WHERE host = ?",
            params![machine.clone()],
        )
        .await
        .map_err(storage_err)?;
        for thread in threads {
            let tid = tx.next_id("threads", vec![]).await.map_err(storage_err)?;
            tx.execute(
                "INSERT INTO threads (id, host, thread_id, title, profile, selection_json, model, running, updated_at, report) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                params![
                    tid,
                    machine.clone(),
                    thread.thread_id.clone(),
                    thread.title.clone(),
                    thread.profile.clone(),
                    thread.selection_json.clone(),
                    thread.model.clone(),
                    thread.running,
                    now_unix,
                    report.clone(),
                ],
            )
            .await
            .map_err(storage_err)?;
        }
        Ok(())
    }

    /// The rest of a placement sync, after this host's report is stored.
    async fn after_report(
        &self,
        machine: String,
        req: PlacementSyncRequest,
        now_unix: i64,
        now_time: DateTime<Utc>,
        usage: &UsageByAccount,
    ) -> Result<PlacementSyncResponse, PrismError> {
        // 2. Load fresh reports (< 3m) for fleet-wide running counts and optionId fallbacks.
        let fresh_since = now_unix - PLACEMENT_FRESH_REPORT_SECS;
        let fresh_hosts = self.hosts_since(fresh_since).await?;
        let fresh_threads = self.running_threads_for_hosts(&fresh_hosts).await?;

        let mut running_by_account: BTreeMap<String, i32> = BTreeMap::new();
        for (model, count) in fresh_threads {
            running_by_account.insert(model, count);
        }

        let mut option_ids: BTreeMap<String, String> = BTreeMap::new();
        for host in &fresh_hosts {
            for m in &host.models {
                if let Some(r) = &m.reasoning
                    && !r.option_id.is_empty()
                {
                    let k = model_key(&m.instance_id, &m.model);
                    option_ids.entry(k).or_insert_with(|| r.option_id.clone());
                }
            }
        }

        // Prefer this host's own optionIds.
        let mut host_models: BTreeMap<String, PlacementHostModel> = BTreeMap::new();
        for m in &req.models {
            let k = model_key(&m.instance_id, &m.model);
            if let Some(r) = &m.reasoning
                && !r.option_id.is_empty()
            {
                option_ids.insert(k.clone(), r.option_id.clone());
            }
            let (session, weekly) = usage_readings(m, usage, now_unix);
            host_models.insert(
                k,
                PlacementHostModel {
                    blocked: m.blocked.clone(),
                    session,
                    weekly,
                },
            );
        }

        // 3. Record session samples for burn rate, in memory. Every host
        // reports every account each 30 seconds; a new sample is kept only
        // when the usage moved or the last one is PLACEMENT_SAMPLE_EVERY_SECS
        // old, which is plenty for a burn rate over PLACEMENT_BURN_WINDOW_SECS.
        let mut sessions: Vec<(String, PlacementWindow)> = Vec::new();
        for m in &req.models {
            let k = model_key(&m.instance_id, &m.model);
            if let Some(live) = host_models
                .get(&k)
                .and_then(|h| live_used(h.session.as_ref(), now_time))
            {
                sessions.push((m.model.clone(), live));
            }
        }
        let samples_by_account = self.record_samples(&sessions, &running_by_account, now_unix);

        // 4. Compute burn rates per account key.
        let mut loads: BTreeMap<String, PlacementLoad> = BTreeMap::new();
        for (acct, count) in &running_by_account {
            loads.entry(acct.clone()).or_default().running = *count;
        }
        for (acct, samples) in &samples_by_account {
            if let Some(burn) = compute_burn_rate(samples, now_unix) {
                loads
                    .entry(acct.clone())
                    .or_default()
                    .burn_per_thread_per_hour = Some(burn);
            }
        }
        // Best effort: the apps' copy of these readings. A busy replica only
        // delays it to a later sync.
        let _ = self
            .store_usage_snapshot(&host_models, &loads, now_unix)
            .await;

        // 5. Rank all profiles for this host.
        let profiles = self.placement_profiles().await?;
        let policy = self.placement_policy().await?;
        let text_gen = self.text_generation_profile().await?;

        let mut decisions_by_id = BTreeMap::new();
        let mut inputs_by_id: BTreeMap<String, Vec<PlacementEntryInput>> = BTreeMap::new();
        let mut resp_profiles = Vec::with_capacity(profiles.len());
        for p in &profiles {
            let mut inputs = Vec::with_capacity(p.entries.len());
            let mut resp_entries = Vec::with_capacity(p.entries.len());
            for e in &p.entries {
                let sel = selection_for_entry(e, &option_ids);
                inputs.push(PlacementEntryInput {
                    selection: sel.clone(),
                    enabled: e.enabled,
                    session_share_of_weekly: e.session_share_of_weekly,
                });
                resp_entries.push(PlacementSyncEntryResp {
                    selection: sel,
                    enabled: e.enabled,
                    session_share_of_weekly: e.session_share_of_weekly,
                });
            }
            let dec = rank_placement_profile(
                &p.id,
                &p.label,
                &inputs,
                &policy,
                &host_models,
                &loads,
                now_time,
                vienna_tz(),
            );
            resp_profiles.push(PlacementSyncProfileResp {
                id: p.id.clone(),
                label: p.label.clone(),
                entries: resp_entries,
                next: dec.next.clone(),
                ranked: dec.ranked.clone(),
            });
            decisions_by_id.insert(p.id.clone(), dec);
            inputs_by_id.insert(p.id.clone(), inputs);
        }
        // Ranks the profiles again once this sync moved running threads, so
        // that a host evacuating one account doesn't pile every thread onto the
        // same entry: each moved thread counts where it goes.
        let rerank = |loads: &BTreeMap<String, PlacementLoad>| {
            profiles
                .iter()
                .filter_map(|p| {
                    let inputs = inputs_by_id.get(&p.id)?;
                    let dec = rank_placement_profile(
                        &p.id,
                        &p.label,
                        inputs,
                        &policy,
                        &host_models,
                        loads,
                        now_time,
                        vienna_tz(),
                    );
                    Some((p.id.clone(), dec))
                })
                .collect::<BTreeMap<_, _>>()
        };

        // 6. Compute moves for this host's reported threads.
        let cooldown_since = now_unix - PLACEMENT_MOVE_COOLDOWN_SECS;
        let existing_moves = self.machine_placement_moves(machine.clone(), 0).await?;

        // Moves come sorted by (created_at, id). Several workers may have asked for
        // the same thread at once: the earliest move wins, and any other move created
        // within the cooldown after it lost the race and is dropped, whatever the
        // winner's state. Winners that are still open are asked and closed together.
        let mut asked_by_thread: BTreeMap<String, PlacementMoveRow> = BTreeMap::new();
        let mut open_by_thread: BTreeMap<String, Vec<String>> = BTreeMap::new();
        let mut recent_move_by_thread: BTreeSet<String> = BTreeSet::new();
        let mut winner_at: BTreeMap<String, i64> = BTreeMap::new();
        let mut lost_races: Vec<String> = Vec::new();
        for m in &existing_moves {
            if m.at >= cooldown_since {
                recent_move_by_thread.insert(m.thread_id.clone());
            }
            match winner_at.get(&m.thread_id) {
                Some(&at) if m.at < at + PLACEMENT_MOVE_COOLDOWN_SECS => {
                    if m.state == "asked" {
                        lost_races.push(m.id.clone());
                    }
                    continue;
                }
                _ => {
                    winner_at.insert(m.thread_id.clone(), m.at);
                }
            }
            if m.state == "asked" {
                open_by_thread
                    .entry(m.thread_id.clone())
                    .or_default()
                    .push(m.id.clone());
                asked_by_thread
                    .entry(m.thread_id.clone())
                    .or_insert_with(|| m.clone());
            }
        }

        let reported_threads: BTreeMap<&str, &PlacementSyncThread> = req
            .threads
            .iter()
            .map(|t| (t.thread_id.as_str(), t))
            .collect();

        let mut moves_to_drop: Vec<String> = lost_races;
        for (thread_id, open) in &open_by_thread {
            if !reported_threads.contains_key(thread_id.as_str()) {
                moves_to_drop.extend(open.iter().cloned());
            }
        }

        let mut moves_to_continue: Vec<String> = Vec::new();
        let mut moves_to_insert: Vec<PlacementMoveRow> = Vec::new();
        let mut resp_moves: Vec<PlacementSyncMoveResp> = Vec::new();
        let mut drained_this_sync = std::collections::BTreeSet::new();

        for t in &req.threads {
            if let Some(asked) = asked_by_thread.get(&t.thread_id) {
                if same_placement_entry(&t.selection, &asked.to) {
                    if let Some(open) = open_by_thread.get(&t.thread_id) {
                        moves_to_continue.extend(open.iter().cloned());
                    }
                    continue;
                }
                resp_moves.push(PlacementSyncMoveResp {
                    id: asked.id.clone(),
                    thread_id: asked.thread_id.clone(),
                    from: asked.from.clone(),
                    to: asked.to.clone(),
                    reason: asked.reason.clone(),
                });
                continue;
            }
            if recent_move_by_thread.contains(&t.thread_id) {
                continue;
            }
            let Some(dec) = decisions_by_id.get(&t.profile) else {
                continue;
            };
            // One running thread per drained entry per sync: the next report
            // counts it there, so the entry turns full before it overfills.
            if placement_waits_for_reset(dec, &t.selection, &policy, now_time) {
                continue;
            }
            let mut drain = || {
                let drain = placement_drain_target(dec, &t.selection, &policy)?;
                drained_this_sync
                    .insert(model_key(&drain.to.instance_id, &drain.to.model))
                    .then_some(drain)
            };
            // Compacting in place happens once per session window: the thread then
            // runs on until the limit and moves with the short context.
            let compact = || {
                if !t.running {
                    return None;
                }
                let target = placement_compact_target(dec, &t.selection)?;
                let since = placement_compact_window_start(dec, &t.selection, now_time);
                let compacted = existing_moves.iter().any(|m| {
                    m.thread_id == t.thread_id
                        && m.at >= since
                        && same_placement_entry(&m.from, &m.to)
                        && same_placement_entry(&m.from, &t.selection)
                });
                (!compacted).then_some(target)
            };
            let Some(target) = placement_move_target(dec, &t.selection)
                .or_else(|| if t.running { drain() } else { None })
                .or_else(compact)
            else {
                continue;
            };
            if t.running && t.selection.model != target.to.model {
                if let Some(from) = loads.get_mut(&t.selection.model) {
                    from.running = (from.running - 1).max(0);
                }
                loads.entry(target.to.model.clone()).or_default().running += 1;
                decisions_by_id = rerank(&loads);
            }
            let move_id = format!("{machine}:{}:{}:{now_unix}", t.thread_id, target.to.model);
            let move_row = PlacementMoveRow {
                id: move_id.clone(),
                machine: machine.clone(),
                thread_id: t.thread_id.clone(),
                from: t.selection.clone(),
                to: target.to.clone(),
                reason: target.reason.clone(),
                at: now_unix,
                state: "asked".into(),
            };
            resp_moves.push(PlacementSyncMoveResp {
                id: move_id,
                thread_id: t.thread_id.clone(),
                from: t.selection.clone(),
                to: target.to,
                reason: target.reason,
            });
            moves_to_insert.push(move_row);
        }

        if !moves_to_drop.is_empty() || !moves_to_continue.is_empty() || !moves_to_insert.is_empty()
        {
            let mut tx = self.db.transaction().await.map_err(storage_err)?;
            let closed = moves_to_drop
                .into_iter()
                .map(|id| (id, "dropped"))
                .chain(moves_to_continue.into_iter().map(|id| (id, "continued")));
            for (id, state) in closed {
                let aid = tx.next_id("move_acks", vec![]).await.map_err(storage_err)?;
                tx.execute(
                    "INSERT INTO move_acks (id, instruction_id, host, state, at) VALUES (?, ?, ?, ?, ?)",
                    params![aid, id, machine.clone(), state, now_unix],
                )
                .await
                .map_err(storage_err)?;
            }
            for m in moves_to_insert {
                let from_json = serde_json::to_string(&m.from).map_err(storage_err)?;
                let to_json = serde_json::to_string(&m.to).map_err(storage_err)?;
                tx.execute(
                    "DELETE FROM instructions WHERE instruction_id = ?",
                    params![m.id.clone()],
                )
                .await
                .map_err(storage_err)?;
                let iid = tx
                    .next_id("instructions", vec![])
                    .await
                    .map_err(storage_err)?;
                tx.execute(
                    "INSERT INTO instructions (id, instruction_id, host, thread_id, kind, from_selection_json, selection_json, reason, state, created_at, done_at) VALUES (?, ?, ?, ?, 'move', ?, ?, ?, ?, ?, NULL)",
                    params![
                        iid,
                        m.id,
                        m.machine,
                        m.thread_id,
                        from_json,
                        to_json,
                        m.reason,
                        m.state,
                        m.at,
                    ],
                )
                .await
                .map_err(storage_err)?;
            }
            tx.commit().await.map_err(storage_err)?;
        }

        Ok(PlacementSyncResponse {
            v: 1,
            checked_at: now_time.to_rfc3339_opts(SecondsFormat::Secs, true),
            policy,
            profiles: resp_profiles,
            actions: PlacementSyncActions {
                text_generation: text_gen,
            },
            moves: resp_moves,
        })
    }

    /// Builds the phone/Mac `GET /v1/placement` view from the local Rocky replica.
    pub async fn placement_view(
        &self,
        cluster_machine: String,
        now_unix: i64,
    ) -> Result<PlacementView, PrismError> {
        let now_time = DateTime::from_timestamp(now_unix, 0).unwrap_or_else(Utc::now);
        let day_hosts = self
            .hosts_since(now_unix - PLACEMENT_DAY_REPORT_SECS)
            .await?;
        let fresh_since = now_unix - PLACEMENT_FRESH_REPORT_SECS;

        let fresh_hosts: Vec<HostReport> = day_hosts
            .iter()
            .filter(|h| h.seen_at >= fresh_since)
            .cloned()
            .collect();
        let running_by_account = self.running_threads_for_hosts(&fresh_hosts).await?;

        struct ModelAgg {
            view: PlacementModelView,
            cluster_hidden: Option<bool>,
            machines: BTreeSet<String>,
        }
        let mut models_map: BTreeMap<String, ModelAgg> = BTreeMap::new();
        let mut cluster_models: Option<BTreeMap<String, PlacementHostModel>> = None;
        // Host reports carry no usage any more (reports stored before did);
        // the workers keep the readings they rank with in the snapshot.
        let snapshot = self.usage_snapshot().await?.unwrap_or_default();

        for host in &day_hosts {
            if host.name == cluster_machine && host.seen_at >= fresh_since {
                let mut cm = BTreeMap::new();
                for m in &host.models {
                    let reading = snapshot
                        .models
                        .get(&format!("{}/{}", m.instance_id, m.model));
                    cm.insert(
                        model_key(&m.instance_id, &m.model),
                        PlacementHostModel {
                            blocked: m.blocked.clone(),
                            session: reading
                                .and_then(|r| r.session.clone())
                                .or_else(|| m.session.clone()),
                            weekly: reading
                                .and_then(|r| r.weekly.clone())
                                .or_else(|| m.weekly.clone()),
                        },
                    );
                }
                cluster_models = Some(cm);
            }
            for m in &host.models {
                let k = model_key(&m.instance_id, &m.model);
                let agg = models_map.entry(k).or_insert_with(|| ModelAgg {
                    view: PlacementModelView {
                        instance_id: m.instance_id.clone(),
                        model: m.model.clone(),
                        label: m.label.clone(),
                        driver: String::new(),
                        provider: String::new(),
                        hidden: true,
                        reasoning: None,
                        machines: Vec::new(),
                    },
                    machines: BTreeSet::new(),
                    cluster_hidden: None,
                });
                let cluster = host.name == cluster_machine;
                if cluster {
                    agg.cluster_hidden = Some(m.hidden);
                }
                agg.view.hidden &= m.hidden;
                if (cluster || agg.view.label.is_empty()) && !m.label.is_empty() {
                    agg.view.label = m.label.clone();
                }
                if (cluster || agg.view.driver.is_empty()) && !m.driver.is_empty() {
                    agg.view.driver = m.driver.clone();
                }
                if (cluster || agg.view.provider.is_empty()) && !m.provider.is_empty() {
                    agg.view.provider = m.provider.clone();
                }
                if (cluster || agg.view.reasoning.is_none())
                    && let Some(r) = &m.reasoning
                {
                    agg.view.reasoning = Some(PlacementModelReasoningView {
                        levels: r.levels.clone(),
                        default: r.default.clone(),
                    });
                }
                agg.machines.insert(host.name.clone());
            }
        }

        let mut models = Vec::with_capacity(models_map.len());
        for agg in models_map.values_mut() {
            if agg.view.driver.is_empty() {
                agg.view.driver = guess_driver(&agg.view.instance_id);
            }
            if agg.view.provider.is_empty() {
                agg.view.provider = agg.view.instance_id.clone();
            }
            agg.view.hidden = agg.cluster_hidden.unwrap_or(agg.view.hidden);
            agg.view.machines = agg.machines.iter().cloned().collect();
            models.push(agg.view.clone());
        }
        models.sort_by(|a, b| {
            a.instance_id
                .cmp(&b.instance_id)
                .then_with(|| a.model.cmp(&b.model))
        });

        let mut loads: BTreeMap<String, PlacementLoad> = BTreeMap::new();
        for (acct, count) in &running_by_account {
            loads.entry(acct.clone()).or_default().running = *count;
        }
        for (acct, burn) in &snapshot.burn {
            loads
                .entry(acct.clone())
                .or_default()
                .burn_per_thread_per_hour = Some(*burn);
        }

        let profiles = self.placement_profiles().await?;
        let policy = self.placement_policy().await?;
        let alert_fix = self.alert_fix_profile().await?;
        let text_gen = self.text_generation_profile().await?;

        let mut profile_views = Vec::with_capacity(profiles.len());
        for p in &profiles {
            let decision_by_key: Option<BTreeMap<String, PlacementEntryDecision>> =
                cluster_models.as_ref().map(|cm| {
                    let inputs: Vec<PlacementEntryInput> = p
                        .entries
                        .iter()
                        .map(|e| PlacementEntryInput {
                            selection: PlacementSelection {
                                instance_id: e.instance_id.clone(),
                                model: e.model.clone(),
                                options: Vec::new(),
                            },
                            enabled: e.enabled,
                            session_share_of_weekly: e.session_share_of_weekly,
                        })
                        .collect();
                    let dec = rank_placement_profile(
                        &p.id,
                        &p.label,
                        &inputs,
                        &policy,
                        cm,
                        &loads,
                        now_time,
                        vienna_tz(),
                    );
                    dec.ranked
                        .into_iter()
                        .map(|r| (model_key(&r.selection.instance_id, &r.selection.model), r))
                        .collect()
                });

            let mut rows = p.entries.clone();
            let held: BTreeSet<String> = p
                .entries
                .iter()
                .map(|e| model_key(&e.instance_id, &e.model))
                .collect();
            for m in &models {
                if !held.contains(&model_key(&m.instance_id, &m.model)) {
                    rows.push(PlacementEntryRow {
                        profile_id: p.id.clone(),
                        position: rows.len() as i32,
                        instance_id: m.instance_id.clone(),
                        model: m.model.clone(),
                        reasoning: String::new(),
                        enabled: false,
                        session_share_of_weekly: None,
                    });
                }
            }

            let mut entries = Vec::with_capacity(rows.len());
            for e in rows {
                let k = model_key(&e.instance_id, &e.model);
                let mut ev = PlacementEntryView {
                    instance_id: e.instance_id.clone(),
                    model: e.model.clone(),
                    label: String::new(),
                    driver: guess_driver(&e.instance_id),
                    provider: e.instance_id.clone(),
                    reasoning: e.reasoning,
                    enabled: e.enabled,
                    session_share_of_weekly: e.session_share_of_weekly,
                    state: String::new(),
                    reason: String::new(),
                    running_threads: None,
                };
                if let Some(agg) = models_map.get(&k) {
                    ev.label = agg.view.label.clone();
                    ev.driver = agg.view.driver.clone();
                    ev.provider = agg.view.provider.clone();
                }
                if let Some(dec_map) = &decision_by_key
                    && let Some(d) = dec_map.get(&k)
                {
                    ev.state = d.state.clone();
                    ev.reason = d.reason.clone();
                    ev.running_threads = Some(d.running_threads);
                }
                entries.push(ev);
            }
            profile_views.push(PlacementProfileView {
                id: p.id.clone(),
                label: p.label.clone(),
                configured_entry_count: p.entries.len(),
                entries,
            });
        }

        let move_rows = self.recent_placement_moves(20).await?;
        let moves = move_rows
            .into_iter()
            .map(|m| PlacementMoveView {
                machine: m.machine,
                thread_id: m.thread_id,
                from: m.from.model,
                to: m.to.model,
                reason: m.reason,
                at: m.at,
                state: m.state,
            })
            .collect();

        Ok(PlacementView {
            profiles: profile_views,
            models,
            alert_fix_profile: alert_fix.clone(),
            actions: PlacementActions {
                alert_fix,
                text_generation: text_gen,
            },
            roles: PLACEMENT_ROLES.iter().map(|s| (*s).to_string()).collect(),
            policy,
            moves,
        })
    }

    /// Saves every profile that ends in padding again, which drops it (see
    /// `put_placement_profile`). Returns the ids it trimmed. Editors that
    /// saved the padded list stored it before saves dropped it.
    pub async fn trim_profile_padding(&self, now_unix: i64) -> Result<Vec<String>, PrismError> {
        let mut trimmed = Vec::new();
        for profile in self.placement_profiles().await? {
            if !profile.entries.last().is_some_and(is_padding) {
                continue;
            }
            let entries = profile
                .entries
                .iter()
                .map(|e| PutPlacementEntryInput {
                    instance_id: e.instance_id.clone(),
                    model: e.model.clone(),
                    reasoning: e.reasoning.clone(),
                    enabled: e.enabled,
                    session_share_of_weekly: e.session_share_of_weekly,
                })
                .collect();
            let label = profile.label.clone();
            self.put_placement_profile(
                profile.id.clone(),
                PutPlacementProfileRequest { label, entries },
                now_unix,
            )
            .await?;
            trimmed.push(profile.id);
        }
        Ok(trimmed)
    }

    pub async fn put_placement_profile(
        &self,
        id: String,
        req: PutPlacementProfileRequest,
        now_unix: i64,
    ) -> Result<(), PrismError> {
        if !is_valid_profile_id(&id) {
            return Err(PrismError::InvalidInput(
                "Profile id must match ^[a-z0-9][a-z0-9-]{0,39}$.".into(),
            ));
        }
        let label = req.label.trim().to_string();
        if label.is_empty() {
            return Err(PrismError::InvalidInput(
                "Profile label is required.".into(),
            ));
        }

        let existing = self.placement_profiles().await?;
        let previous = existing.iter().find(|p| p.id == id);

        let day_hosts = self
            .hosts_since(now_unix - PLACEMENT_DAY_REPORT_SECS)
            .await?;
        let mut reasoning_levels: BTreeMap<String, Vec<String>> = BTreeMap::new();
        let mut known_models: BTreeSet<String> = BTreeSet::new();
        for host in &day_hosts {
            for m in &host.models {
                let k = model_key(&m.instance_id, &m.model);
                known_models.insert(k.clone());
                if let Some(r) = &m.reasoning
                    && !r.levels.is_empty()
                {
                    reasoning_levels.insert(k, r.levels.clone());
                }
            }
        }

        let mut entries = Vec::with_capacity(req.entries.len());
        for (i, e) in req.entries.iter().enumerate() {
            let inst = e.instance_id.trim().to_string();
            let model = e.model.trim().to_string();
            if inst.is_empty() || model.is_empty() {
                return Err(PrismError::InvalidInput(format!(
                    "Entry {} must name both instanceId and model.",
                    i + 1
                )));
            }
            if let Some(share) = e.session_share_of_weekly
                && !(0.1..=100.0).contains(&share)
            {
                return Err(PrismError::InvalidInput(
                    "sessionShareOfWeekly must be between 0.1 and 100.".into(),
                ));
            }
            let reasoning = e.reasoning.trim().to_string();
            if !reasoning.is_empty() {
                let k = model_key(&inst, &model);
                if known_models.contains(&k) {
                    let allowed = reasoning_levels
                        .get(&k)
                        .is_some_and(|levels| levels.iter().any(|l| l == &reasoning));
                    // A provider can change its advertised levels, including for
                    // hidden models that an editor preserves without showing.
                    let retained = previous.is_some_and(|profile| {
                        profile.entries.iter().any(|entry| {
                            entry.instance_id == inst
                                && entry.model == model
                                && entry.reasoning == reasoning
                        })
                    });
                    if !allowed && !retained {
                        return Err(PrismError::InvalidInput(format!(
                            "Unsupported reasoning level {reasoning:?} for {model}."
                        )));
                    }
                }
            }
            entries.push(PlacementEntryRow {
                profile_id: id.clone(),
                position: i as i32,
                instance_id: inst,
                model,
                reasoning,
                enabled: e.enabled,
                session_share_of_weekly: e.session_share_of_weekly,
            });
        }

        // The view lists every model a host reports, appending the ones a
        // profile doesn't name as disabled rows. An editor that saves the
        // list it shows sends them back: a disabled entry at the end with
        // nothing set says no more than its absence, so it isn't stored.
        while entries.last().is_some_and(is_padding) {
            entries.pop();
        }
        if previous.is_some_and(|p| p.label == label && p.entries == entries) {
            return Ok(());
        }
        let next_pos = existing
            .iter()
            .map(|p| p.position)
            .max()
            .map_or(0, |m| m + 1);

        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        let mut previous_by_position = BTreeMap::new();
        if let Some(profile) = previous {
            if profile.label != label {
                tx.execute(
                    "UPDATE profiles SET label = ? WHERE slug = ?",
                    params![label, id.clone()],
                )
                .await
                .map_err(storage_err)?;
            }
            for row in &profile.entries {
                // Multiple replicas can have inserted different entries at
                // the same position. Treat such a position as changed.
                previous_by_position
                    .entry(row.position)
                    .and_modify(|entry| *entry = None)
                    .or_insert(Some(row));
            }
            for (&position, &old) in &previous_by_position {
                if old.is_some() && old == entries.get(position as usize) {
                    continue;
                }
                tx.execute(
                    "DELETE FROM profile_entries WHERE profile = ? AND position = ?",
                    params![id.clone(), position],
                )
                .await
                .map_err(storage_err)?;
            }
        } else {
            let pid = tx.next_id("profiles", vec![]).await.map_err(storage_err)?;
            tx.execute(
                "INSERT INTO profiles (id, slug, label, position) VALUES (?, ?, ?, ?)",
                params![pid, id.clone(), label, next_pos],
            )
            .await
            .map_err(storage_err)?;
        }
        for (i, e) in entries.iter().enumerate() {
            if previous_by_position.get(&e.position) == Some(&Some(e)) {
                continue;
            }
            let eid = tx
                .next_id("profile_entries", vec![])
                .await
                .map_err(storage_err)?;
            tx.execute(
                "INSERT INTO profile_entries (id, profile, position, instance_id, model, reasoning, enabled, session_share_of_weekly) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                params![
                    eid,
                    id.clone(),
                    i as i32,
                    e.instance_id.clone(),
                    e.model.clone(),
                    e.reasoning.clone(),
                    e.enabled,
                    opt_f64_to_sql(e.session_share_of_weekly)?,
                ],
            )
            .await
            .map_err(storage_err)?;
        }
        tx.commit().await.map_err(storage_err)?;
        Ok(())
    }

    pub async fn delete_placement_profile(&self, id: String) -> Result<(), PrismError> {
        if !is_valid_profile_id(&id) {
            return Err(PrismError::NotFound);
        }
        if id == self.alert_fix_profile().await? {
            return Err(PrismError::Conflict(
                "Cannot delete the profile used for alert fixes.".into(),
            ));
        }
        if id == self.text_generation_profile().await? {
            return Err(PrismError::Conflict(
                "Cannot delete the profile used for text generation.".into(),
            ));
        }
        let policy = self.placement_policy().await?;
        for (role, profile) in &policy.role_profiles {
            if profile == &id {
                return Err(PrismError::Conflict(format!(
                    "Cannot delete the profile the {role} role uses."
                )));
            }
        }
        let existing = self.placement_profiles().await?;
        if !existing.iter().any(|p| p.id == id) {
            return Err(PrismError::NotFound);
        }
        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        tx.execute(
            "DELETE FROM profile_entries WHERE profile = ?",
            params![id.clone()],
        )
        .await
        .map_err(storage_err)?;
        tx.execute("DELETE FROM profiles WHERE slug = ?", params![id])
            .await
            .map_err(storage_err)?;
        tx.commit().await.map_err(storage_err)?;
        Ok(())
    }

    pub async fn order_placement_profiles(&self, ids: Vec<String>) -> Result<(), PrismError> {
        let profiles = self.placement_profiles().await?;
        let existing: BTreeSet<String> = profiles.into_iter().map(|p| p.id).collect();
        let mut seen: BTreeSet<String> = BTreeSet::new();
        for id in &ids {
            if !existing.contains(id) {
                return Err(PrismError::InvalidInput(format!("Unknown profile {id:?}.")));
            }
            if !seen.insert(id.clone()) {
                return Err(PrismError::InvalidInput(format!(
                    "Duplicate profile {id:?}."
                )));
            }
        }
        if seen.len() != existing.len() {
            return Err(PrismError::InvalidInput(
                "Order must list every profile.".into(),
            ));
        }
        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        for (i, id) in ids.into_iter().enumerate() {
            tx.execute(
                "UPDATE profiles SET position = ? WHERE slug = ?",
                params![i as i32, id],
            )
            .await
            .map_err(storage_err)?;
        }
        tx.commit().await.map_err(storage_err)?;
        Ok(())
    }

    pub async fn set_alert_fix_profile(&self, profile_id: String) -> Result<(), PrismError> {
        let profile_id = profile_id.trim().to_string();
        let profiles = self.placement_profiles().await?;
        if !profiles.iter().any(|p| p.id == profile_id) {
            return Err(PrismError::InvalidInput(format!(
                "Unknown profile {profile_id:?}."
            )));
        }
        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        tx.execute(
            "DELETE FROM actions WHERE action = ?",
            params![ACTION_ALERT_FIX],
        )
        .await
        .map_err(storage_err)?;
        let aid = tx.next_id("actions", vec![]).await.map_err(storage_err)?;
        tx.execute(
            "INSERT INTO actions (id, action, profile) VALUES (?, ?, ?)",
            params![aid, ACTION_ALERT_FIX, profile_id],
        )
        .await
        .map_err(storage_err)?;
        tx.commit().await.map_err(storage_err)?;
        Ok(())
    }

    pub async fn set_placement_actions(
        &self,
        req: SetPlacementActionsRequest,
    ) -> Result<(), PrismError> {
        let profiles = self.placement_profiles().await?;
        let exists: BTreeSet<String> = profiles.into_iter().map(|p| p.id).collect();
        let check_known = |what: &str, id: &str| -> Result<(), PrismError> {
            if !exists.contains(id) {
                return Err(PrismError::InvalidInput(format!(
                    "Unknown profile {id:?} for {what}."
                )));
            }
            Ok(())
        };

        if let Some(af) = &req.alert_fix {
            check_known("alert fixes", af.trim())?;
        }
        if let Some(tg) = &req.text_generation {
            let trimmed = tg.trim();
            if !trimmed.is_empty() {
                check_known("text generation", trimmed)?;
            }
        }
        let mut roles_to_set: Option<BTreeMap<String, String>> = None;
        if let Some(role_map) = &req.role_profiles {
            let mut roles = BTreeMap::new();
            for (role, id_opt) in role_map {
                if !PLACEMENT_ROLES.contains(&role.as_str()) {
                    return Err(PrismError::InvalidInput(format!("Unknown role {role:?}.")));
                }
                if let Some(id) = id_opt {
                    let trimmed = id.trim();
                    if !trimmed.is_empty() {
                        check_known(&format!("the {role} role"), trimmed)?;
                        roles.insert(role.clone(), trimmed.to_string());
                    }
                }
            }
            roles_to_set = Some(roles);
        }

        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        if let Some(af) = req.alert_fix {
            tx.execute(
                "DELETE FROM actions WHERE action = ?",
                params![ACTION_ALERT_FIX],
            )
            .await
            .map_err(storage_err)?;
            let aid = tx.next_id("actions", vec![]).await.map_err(storage_err)?;
            tx.execute(
                "INSERT INTO actions (id, action, profile) VALUES (?, ?, ?)",
                params![aid, ACTION_ALERT_FIX, af.trim()],
            )
            .await
            .map_err(storage_err)?;
        }
        if let Some(tg) = req.text_generation {
            tx.execute(
                "DELETE FROM actions WHERE action = ?",
                params![ACTION_TEXT_GENERATION],
            )
            .await
            .map_err(storage_err)?;
            let trimmed = tg.trim();
            if !trimmed.is_empty() {
                let aid = tx.next_id("actions", vec![]).await.map_err(storage_err)?;
                tx.execute(
                    "INSERT INTO actions (id, action, profile) VALUES (?, ?, ?)",
                    params![aid, ACTION_TEXT_GENERATION, trimmed],
                )
                .await
                .map_err(storage_err)?;
            }
        }
        if let Some(roles) = roles_to_set {
            for role in PLACEMENT_ROLES {
                tx.execute("DELETE FROM actions WHERE action = ?", params![*role])
                    .await
                    .map_err(storage_err)?;
            }
            for (role, profile) in roles {
                let aid = tx.next_id("actions", vec![]).await.map_err(storage_err)?;
                tx.execute(
                    "INSERT INTO actions (id, action, profile) VALUES (?, ?, ?)",
                    params![aid, role, profile],
                )
                .await
                .map_err(storage_err)?;
            }
        }
        tx.commit().await.map_err(storage_err)?;
        Ok(())
    }

    pub async fn set_placement_policy(&self, policy: PlacementPolicy) -> Result<(), PrismError> {
        if !(50.0..=100.0).contains(&policy.switch_at_percent) {
            return Err(PrismError::InvalidInput(
                "switchAtPercent must be between 50 and 100.".into(),
            ));
        }
        if !(0.0..=20.0).contains(&policy.weekly_slack_sessions) {
            return Err(PrismError::InvalidInput(
                "weeklySlackSessions must be between 0 and 20.".into(),
            ));
        }
        if let Some(m) = policy.weekly_move_slack_sessions
            && !(0.0..=20.0).contains(&m)
        {
            return Err(PrismError::InvalidInput(
                "weeklyMoveSlackSessions must be between 0 and 20.".into(),
            ));
        }
        if let Some(h) = &policy.active_hours
            && (h.start < 0 || h.start > 23 || h.end < 1 || h.end > 24 || h.start == h.end)
        {
            return Err(PrismError::InvalidInput(
                "activeHours start must be 0..23 and end 1..24, and they must differ.".into(),
            ));
        }
        for (role, profile) in &policy.role_profiles {
            if !is_valid_profile_id(profile) {
                return Err(PrismError::InvalidInput(format!(
                    "Role {role:?} names an invalid profile id."
                )));
            }
        }

        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        for key in [
            POLICY_SWITCH_AT,
            POLICY_WEEKLY_SLACK,
            POLICY_WEEKLY_MOVE_SLACK,
            POLICY_ACTIVE_HOURS,
        ] {
            tx.execute("DELETE FROM policy WHERE key = ?", params![key])
                .await
                .map_err(storage_err)?;
        }
        let pid1 = tx.next_id("policy", vec![]).await.map_err(storage_err)?;
        tx.execute(
            "INSERT INTO policy (id, key, value) VALUES (?, ?, ?)",
            params![pid1, POLICY_SWITCH_AT, policy.switch_at_percent.to_string()],
        )
        .await
        .map_err(storage_err)?;
        let pid2 = tx.next_id("policy", vec![]).await.map_err(storage_err)?;
        tx.execute(
            "INSERT INTO policy (id, key, value) VALUES (?, ?, ?)",
            params![
                pid2,
                POLICY_WEEKLY_SLACK,
                policy.weekly_slack_sessions.to_string()
            ],
        )
        .await
        .map_err(storage_err)?;
        if let Some(slack) = policy.weekly_move_slack_sessions {
            let pid = tx.next_id("policy", vec![]).await.map_err(storage_err)?;
            tx.execute(
                "INSERT INTO policy (id, key, value) VALUES (?, ?, ?)",
                params![pid, POLICY_WEEKLY_MOVE_SLACK, slack.to_string()],
            )
            .await
            .map_err(storage_err)?;
        }
        if let Some(ah) = &policy.active_hours {
            let ah_json = serde_json::to_string(ah).map_err(storage_err)?;
            let pid3 = tx.next_id("policy", vec![]).await.map_err(storage_err)?;
            tx.execute(
                "INSERT INTO policy (id, key, value) VALUES (?, ?, ?)",
                params![pid3, POLICY_ACTIVE_HOURS, ah_json],
            )
            .await
            .map_err(storage_err)?;
        }
        for role in PLACEMENT_ROLES {
            tx.execute("DELETE FROM actions WHERE action = ?", params![*role])
                .await
                .map_err(storage_err)?;
        }
        for (role, profile) in policy.role_profiles {
            let aid = tx.next_id("actions", vec![]).await.map_err(storage_err)?;
            tx.execute(
                "INSERT INTO actions (id, action, profile) VALUES (?, ?, ?)",
                params![aid, role, profile],
            )
            .await
            .map_err(storage_err)?;
        }
        tx.commit().await.map_err(storage_err)?;
        Ok(())
    }

    /// Sets `account`'s temporary limit, or removes it with `None`. Each
    /// percent is how much of the window placement lets the account use,
    /// 1 to 100; `until` (RFC 3339, empty for no end) must lie ahead.
    pub async fn set_account_limit(
        &self,
        account: &str,
        limit: Option<PlacementAccountLimit>,
        now_unix: i64,
    ) -> Result<(), PrismError> {
        if account.is_empty() || account.contains('/') {
            return Err(PrismError::InvalidInput(
                "Name a gateway account, like claude-alex.".into(),
            ));
        }
        if let Some(limit) = &limit {
            if limit.session_percent.is_none() && limit.weekly_percent.is_none() {
                return Err(PrismError::InvalidInput(
                    "Give sessionPercent, weeklyPercent or both.".into(),
                ));
            }
            for p in [limit.session_percent, limit.weekly_percent]
                .into_iter()
                .flatten()
            {
                if !(1.0..=100.0).contains(&p) {
                    return Err(PrismError::InvalidInput(
                        "Limits are percents from 1 to 100.".into(),
                    ));
                }
            }
            if !limit.until.is_empty()
                && !crate::rank::parse_placement_time(&limit.until)
                    .is_some_and(|t| t.timestamp() > now_unix)
            {
                return Err(PrismError::InvalidInput(
                    "until must be a time ahead, like 2026-11-28T08:00:00Z.".into(),
                ));
            }
        }
        let key = format!("{POLICY_ACCOUNT_LIMIT}{account}");
        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        tx.execute("DELETE FROM policy WHERE key = ?", params![key.clone()])
            .await
            .map_err(storage_err)?;
        if let Some(limit) = limit {
            let value = serde_json::to_string(&limit).map_err(storage_err)?;
            let id = tx.next_id("policy", vec![]).await.map_err(storage_err)?;
            tx.execute(
                "INSERT INTO policy (id, key, value) VALUES (?, ?, ?)",
                params![id, key, value],
            )
            .await
            .map_err(storage_err)?;
        }
        tx.commit().await.map_err(storage_err)?;
        Ok(())
    }

    /// The profile accounts whose weekly window ended and has not started
    /// again, for the leader to send each a very small prompt (`crate::weekly`).
    /// An account counts when an enabled profile entry names it and `usage`
    /// (ClickHouse) has a weekly reading for it within [`USAGE_FRESH_SECS`]:
    /// pooled names (`agy-pro`) are no single account. Stores each running
    /// window's reset the first time it shows, and counts a prompt as sent
    /// when it returns the account, so a lost or failed prompt is tried again
    /// only after `WEEKLY_ACTIVATION_RETRY_SECS`.
    pub async fn due_weekly_activations(
        &self,
        now_unix: i64,
        usage: &UsageByAccount,
    ) -> Result<Vec<WeeklyActivation>, PrismError> {
        let now_time = DateTime::from_timestamp(now_unix, 0).unwrap_or_else(Utc::now);
        // Each account with the models its enabled entries name, in profile order.
        let mut models_by_account: BTreeMap<String, Vec<String>> = BTreeMap::new();
        for p in self.placement_profiles().await? {
            for e in p.entries.iter().filter(|e| e.enabled) {
                let models = models_by_account
                    .entry(account_of(&e.model).to_string())
                    .or_default();
                if !models.contains(&e.model) {
                    models.push(e.model.clone());
                }
            }
        }
        if models_by_account.is_empty() {
            return Ok(Vec::new());
        }

        let mut states: BTreeMap<String, WeeklyActivationState> = BTreeMap::new();
        let mut rows = self
            .db
            .query("SELECT key, value FROM policy", params![])
            .await
            .map_err(storage_err)?;
        while let Some(row) = rows.next().await.map_err(storage_err)? {
            let key: String = row.get("key").map_err(storage_err)?;
            let Some(account) = key.strip_prefix(POLICY_WEEKLY_ACTIVATION) else {
                continue;
            };
            let value: String = row.get("value").map_err(storage_err)?;
            let Ok(state) = serde_json::from_str::<WeeklyActivationState>(&value) else {
                continue;
            };
            // Two leaders in a row may each have written one; the later prompt counts.
            if states
                .get(account)
                .is_none_or(|kept| kept.pinged_at < state.pinged_at)
            {
                states.insert(account.to_string(), state);
            }
        }
        drop(rows);

        let mut writes: Vec<(String, WeeklyActivationState)> = Vec::new();
        let mut due = Vec::new();
        for (account, models) in &models_by_account {
            let account_readings: Vec<PlacementWindow> = usage
                .get(account)
                .filter(|u| now_unix - u.observed_at <= USAGE_FRESH_SECS)
                .and_then(|u| u.weekly.clone())
                .into_iter()
                .collect();
            let state = states.get(account).cloned().unwrap_or_default();
            match weekly_activation_step(&account_readings, &state, now_time) {
                WeeklyActivationStep::Wait => {}
                WeeklyActivationStep::Remember(resets_at) => writes.push((
                    account.clone(),
                    WeeklyActivationState {
                        resets_at,
                        pinged_at: state.pinged_at,
                        tries: 0,
                    },
                )),
                WeeklyActivationStep::Activate => {
                    writes.push((
                        account.clone(),
                        WeeklyActivationState {
                            pinged_at: now_unix,
                            tries: state.tries + 1,
                            ..state
                        },
                    ));
                    due.push(WeeklyActivation {
                        account: account.clone(),
                        model: models[0].clone(),
                    });
                }
            }
        }
        if !writes.is_empty() {
            let mut tx = self.db.transaction().await.map_err(storage_err)?;
            for (account, state) in writes {
                let key = format!("{POLICY_WEEKLY_ACTIVATION}{account}");
                let value = serde_json::to_string(&state).map_err(storage_err)?;
                tx.execute("DELETE FROM policy WHERE key = ?", params![key.clone()])
                    .await
                    .map_err(storage_err)?;
                let id = tx.next_id("policy", vec![]).await.map_err(storage_err)?;
                tx.execute(
                    "INSERT INTO policy (id, key, value) VALUES (?, ?, ?)",
                    params![id, key, value],
                )
                .await
                .map_err(storage_err)?;
            }
            tx.commit().await.map_err(storage_err)?;
        }
        Ok(due)
    }

    /// Returns the moves for `machine` that are still asked or were created at or
    /// after `since`, sorted by (created_at, instruction_id).
    pub async fn machine_placement_moves(
        &self,
        machine: String,
        since: i64,
    ) -> Result<Vec<PlacementMoveRow>, PrismError> {
        let mut moves = self
            .placement_moves(
                "SELECT id, instruction_id, host, thread_id, from_selection_json, selection_json, reason, created_at, state \
                 FROM instructions WHERE host = ? AND kind = 'move'",
                params![machine],
            )
            .await?;
        moves.retain(|m| m.state == "asked" || m.at >= since);
        Ok(moves)
    }

    async fn recent_placement_moves(
        &self,
        limit: usize,
    ) -> Result<Vec<PlacementMoveRow>, PrismError> {
        let mut moves = self
            .placement_moves(
                "SELECT id, instruction_id, host, thread_id, from_selection_json, selection_json, reason, created_at, state \
                 FROM instructions WHERE kind = 'move'",
                params![],
            )
            .await?;
        moves.reverse();
        moves.truncate(limit);
        Ok(moves)
    }

    /// Reads move rows, keeps one row per instruction id (the smallest Rocky id),
    /// resolves each state from its acknowledgements and sorts by (created_at, id).
    async fn placement_moves(
        &self,
        sql: &str,
        params: Vec<SqlValue>,
    ) -> Result<Vec<PlacementMoveRow>, PrismError> {
        let mut rows = self.db.query(sql, params).await.map_err(storage_err)?;
        let mut by_id: BTreeMap<String, (Vec<u8>, PlacementMoveRow)> = BTreeMap::new();
        while let Some(row) = rows.next().await.map_err(storage_err)? {
            let row_id = id_bytes(&row.get::<SqlValue>("id").map_err(storage_err)?);
            let from_raw: String = row.get("from_selection_json").map_err(storage_err)?;
            let to_raw: String = row.get("selection_json").map_err(storage_err)?;
            let from: PlacementSelection = serde_json::from_str(&from_raw).map_err(storage_err)?;
            let to: PlacementSelection = serde_json::from_str(&to_raw).map_err(storage_err)?;
            let m = PlacementMoveRow {
                id: row.get("instruction_id").map_err(storage_err)?,
                machine: row.get("host").map_err(storage_err)?,
                thread_id: row.get("thread_id").map_err(storage_err)?,
                from,
                to,
                reason: row.get("reason").map_err(storage_err)?,
                at: row.get("created_at").map_err(storage_err)?,
                state: row.get("state").map_err(storage_err)?,
            };
            match by_id.get(&m.id) {
                Some((kept, _)) if *kept <= row_id => {}
                _ => {
                    by_id.insert(m.id.clone(), (row_id, m));
                }
            }
        }
        drop(rows);

        let acks = self.move_ack_states().await?;
        let mut out: Vec<PlacementMoveRow> = by_id
            .into_values()
            .map(|(_, mut m)| {
                if let Some(state) = acks.get(&(m.machine.clone(), m.id.clone())) {
                    m.state = state.clone();
                }
                m
            })
            .collect();
        out.sort_by(|a, b| a.at.cmp(&b.at).then_with(|| a.id.cmp(&b.id)));
        Ok(out)
    }

    /// Returns the acknowledged state per (host, move id): the latest by time,
    /// ties broken by the larger Rocky id.
    async fn move_ack_states(&self) -> Result<BTreeMap<(String, String), String>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT id, instruction_id, host, state, at FROM move_acks",
                params![],
            )
            .await
            .map_err(storage_err)?;
        // (host, move id) -> ((at, row id), state)
        type Latest = BTreeMap<(String, String), ((i64, Vec<u8>), String)>;
        let mut latest: Latest = BTreeMap::new();
        while let Some(row) = rows.next().await.map_err(storage_err)? {
            let order = (
                row.get::<i64>("at").map_err(storage_err)?,
                id_bytes(&row.get::<SqlValue>("id").map_err(storage_err)?),
            );
            let key = (
                row.get::<String>("host").map_err(storage_err)?,
                row.get::<String>("instruction_id").map_err(storage_err)?,
            );
            let state: String = row.get("state").map_err(storage_err)?;
            match latest.get(&key) {
                Some((kept, _)) if *kept >= order => {}
                _ => {
                    latest.insert(key, (order, state));
                }
            }
        }
        Ok(latest
            .into_iter()
            .map(|(k, (_, state))| (k, state))
            .collect())
    }

    async fn placement_profiles(&self) -> Result<Vec<PlacementProfileRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT slug, label, position FROM profiles ORDER BY position ASC, slug ASC",
                params![],
            )
            .await
            .map_err(storage_err)?;
        let mut profiles = Vec::new();
        let mut by_id: BTreeMap<String, usize> = BTreeMap::new();
        while let Some(row) = rows.next().await.map_err(storage_err)? {
            let id: String = row.get("slug").map_err(storage_err)?;
            let label: String = row.get("label").map_err(storage_err)?;
            let position: i32 = row.get("position").map_err(storage_err)?;
            by_id.insert(id.clone(), profiles.len());
            profiles.push(PlacementProfileRow {
                id,
                label,
                position,
                entries: Vec::new(),
            });
        }
        drop(rows);

        let mut erows = self
            .db
            .query(
                "SELECT profile, position, instance_id, model, reasoning, enabled, session_share_of_weekly \
                 FROM profile_entries ORDER BY profile ASC, position ASC",
                params![],
            )
            .await
            .map_err(storage_err)?;
        while let Some(row) = erows.next().await.map_err(storage_err)? {
            let profile_id: String = row.get("profile").map_err(storage_err)?;
            let share_val: SqlValue = row.get("session_share_of_weekly").map_err(storage_err)?;
            let entry = PlacementEntryRow {
                profile_id: profile_id.clone(),
                position: row.get("position").map_err(storage_err)?,
                instance_id: row.get("instance_id").map_err(storage_err)?,
                model: row.get("model").map_err(storage_err)?,
                reasoning: row.get("reasoning").map_err(storage_err)?,
                enabled: row.get("enabled").map_err(storage_err)?,
                session_share_of_weekly: sql_to_opt_f64(&share_val),
            };
            if let Some(&idx) = by_id.get(&profile_id) {
                profiles[idx].entries.push(entry);
            }
        }
        Ok(profiles)
    }

    async fn placement_policy(&self) -> Result<PlacementPolicy, PrismError> {
        let mut policy = PlacementPolicy::default();
        let mut rows = self
            .db
            .query("SELECT key, value FROM policy", params![])
            .await
            .map_err(storage_err)?;
        while let Some(row) = rows.next().await.map_err(storage_err)? {
            let key: String = row.get("key").map_err(storage_err)?;
            let value: String = row.get("value").map_err(storage_err)?;
            match key.as_str() {
                POLICY_SWITCH_AT => {
                    if let Ok(v) = value.parse::<f64>() {
                        policy.switch_at_percent = v;
                    }
                }
                POLICY_WEEKLY_SLACK => {
                    if let Ok(v) = value.parse::<f64>() {
                        policy.weekly_slack_sessions = v;
                    }
                }
                POLICY_WEEKLY_MOVE_SLACK => {
                    if let Ok(v) = value.parse::<f64>() {
                        policy.weekly_move_slack_sessions = Some(v);
                    }
                }
                POLICY_ACTIVE_HOURS => {
                    if let Ok(ah) = serde_json::from_str::<PlacementActiveHours>(&value) {
                        policy.active_hours = Some(ah);
                    }
                }
                _ => {
                    if let Some(account) = key.strip_prefix(POLICY_ACCOUNT_LIMIT)
                        && let Ok(limit) = serde_json::from_str::<PlacementAccountLimit>(&value)
                    {
                        policy.account_limits.insert(account.to_string(), limit);
                    }
                }
            }
        }
        drop(rows);

        let mut role_profiles = BTreeMap::new();
        let mut arows = self
            .db
            .query("SELECT action, profile FROM actions", params![])
            .await
            .map_err(storage_err)?;
        while let Some(row) = arows.next().await.map_err(storage_err)? {
            let action: String = row.get("action").map_err(storage_err)?;
            let profile: String = row.get("profile").map_err(storage_err)?;
            if PLACEMENT_ROLES.contains(&action.as_str()) && !profile.is_empty() {
                role_profiles.insert(action, profile);
            }
        }
        policy.role_profiles = role_profiles;
        Ok(policy)
    }

    /// The placement profile alert fixes launch in, or `""` when the owner
    /// picked none (T3 then uses its own default).
    pub async fn alert_fix_profile(&self) -> Result<String, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT profile FROM actions WHERE action = ?",
                params![ACTION_ALERT_FIX],
            )
            .await
            .map_err(storage_err)?;
        if let Some(row) = rows.next().await.map_err(storage_err)? {
            return row.get("profile").map_err(storage_err);
        }
        Ok(String::new())
    }

    async fn text_generation_profile(&self) -> Result<String, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT profile FROM actions WHERE action = ?",
                params![ACTION_TEXT_GENERATION],
            )
            .await
            .map_err(storage_err)?;
        if let Some(row) = rows.next().await.map_err(storage_err)? {
            return row.get("profile").map_err(storage_err);
        }
        Ok(String::new())
    }

    /// This host's latest stored report, if any: the `hosts` row with the
    /// largest (`seen_at`, id) and the `threads` rows that came with it.
    async fn stored_report(&self, machine: &str) -> Result<Option<StoredReport>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT id, seen_at, environment_id, models_json FROM hosts WHERE name = ?",
                params![machine.to_string()],
            )
            .await
            .map_err(storage_err)?;
        let mut latest: Option<((i64, Vec<u8>), StoredReport)> = None;
        while let Some(row) = rows.next().await.map_err(storage_err)? {
            let id: SqlValue = row.get("id").map_err(storage_err)?;
            let seen_at: i64 = row.get("seen_at").map_err(storage_err)?;
            let order = (seen_at, id_bytes(&id));
            if latest.as_ref().is_some_and(|(kept, _)| *kept >= order) {
                continue;
            }
            latest = Some((
                order,
                StoredReport {
                    id,
                    seen_at,
                    environment_id: row.get("environment_id").map_err(storage_err)?,
                    models_json: row.get("models_json").map_err(storage_err)?,
                    threads: Vec::new(),
                },
            ));
        }
        drop(rows);
        let Some(((_, id), mut report)) = latest else {
            return Ok(None);
        };
        let mut rows = self
            .db
            .query(
                "SELECT thread_id, title, profile, selection_json, model, running FROM threads WHERE host = ? AND report = ?",
                params![machine.to_string(), hex_string(&id)],
            )
            .await
            .map_err(storage_err)?;
        while let Some(row) = rows.next().await.map_err(storage_err)? {
            let running: i64 = row.get("running").map_err(storage_err)?;
            report.threads.push(StoredThread {
                thread_id: row.get("thread_id").map_err(storage_err)?,
                title: row.get("title").map_err(storage_err)?,
                profile: row.get("profile").map_err(storage_err)?,
                selection_json: row.get("selection_json").map_err(storage_err)?,
                model: row.get("model").map_err(storage_err)?,
                running: running != 0,
            });
        }
        Ok(Some(report))
    }

    /// Returns each host's latest report (by `seen_at`, ties broken by the larger
    /// Rocky id) when it was seen at or after `since`, sorted by host name. Two
    /// workers may each have stored a report for the same host.
    async fn hosts_since(&self, since: i64) -> Result<Vec<HostReport>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT id, name, seen_at, models_json FROM hosts",
                params![],
            )
            .await
            .map_err(storage_err)?;
        let mut latest: BTreeMap<String, ((i64, Vec<u8>), String)> = BTreeMap::new();
        while let Some(row) = rows.next().await.map_err(storage_err)? {
            let name: String = row.get("name").map_err(storage_err)?;
            let seen_at: i64 = row.get("seen_at").map_err(storage_err)?;
            let id = id_bytes(&row.get::<SqlValue>("id").map_err(storage_err)?);
            let raw: String = row.get("models_json").map_err(storage_err)?;
            let order = (seen_at, id);
            match latest.get(&name) {
                Some((kept, _)) if *kept >= order => {}
                _ => {
                    latest.insert(name, (order, raw));
                }
            }
        }
        let mut out = Vec::new();
        for (name, ((seen_at, id), raw)) in latest {
            if seen_at < since {
                continue;
            }
            if let Ok(models) = serde_json::from_str::<Vec<PlacementSyncModel>>(&raw) {
                out.push(HostReport {
                    name,
                    seen_at,
                    report: hex_string(&id),
                    models,
                });
            }
        }
        Ok(out)
    }

    /// Counts running threads per account, taking each host's threads only from
    /// the report `hosts` resolved to.
    async fn running_threads_for_hosts(
        &self,
        hosts: &[HostReport],
    ) -> Result<BTreeMap<String, i32>, PrismError> {
        let reports: BTreeMap<&str, &str> = hosts
            .iter()
            .map(|h| (h.name.as_str(), h.report.as_str()))
            .collect();
        let mut out: BTreeMap<String, i32> = BTreeMap::new();
        if reports.is_empty() {
            return Ok(out);
        }
        let mut rows = self
            .db
            .query(
                "SELECT host, model, report FROM threads WHERE running != 0",
                params![],
            )
            .await
            .map_err(storage_err)?;
        while let Some(row) = rows.next().await.map_err(storage_err)? {
            let host: String = row.get("host").map_err(storage_err)?;
            let model: String = row.get("model").map_err(storage_err)?;
            let report: String = row.get("report").map_err(storage_err)?;
            if reports.get(host.as_str()) == Some(&report.as_str()) && !model.is_empty() {
                *out.entry(model).or_insert(0) += 1;
            }
        }
        Ok(out)
    }

    /// Adds this sync's session readings to the in-memory samples and returns
    /// every account's samples within `PLACEMENT_BURN_WINDOW_SECS`.
    fn record_samples(
        &self,
        sessions: &[(String, PlacementWindow)],
        running_by_account: &BTreeMap<String, i32>,
        now_unix: i64,
    ) -> BTreeMap<String, Vec<PlacementSample>> {
        let mut samples = self
            .samples
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        let mut recorded: BTreeSet<&str> = BTreeSet::new();
        for (model, live) in sessions {
            if !recorded.insert(model) {
                continue;
            }
            let running = *running_by_account.get(model).unwrap_or(&0);
            let kept = samples.entry(model.clone()).or_default();
            if let Some(last) = kept.last()
                && (last.at >= now_unix
                    || now_unix - last.at < PLACEMENT_SAMPLE_EVERY_SECS
                        && (last.used - live.used_percent).abs() < 0.05
                        && last.running == running)
            {
                continue;
            }
            kept.push(PlacementSample {
                account: model.clone(),
                at: now_unix,
                used: live.used_percent,
                running,
            });
        }
        let since = now_unix - PLACEMENT_BURN_WINDOW_SECS;
        samples.retain(|_, kept| {
            kept.retain(|s| s.at >= since);
            !kept.is_empty()
        });
        samples.clone()
    }

    /// The readings and burn rates the workers ranked with last, for the apps.
    pub async fn usage_snapshot(&self) -> Result<Option<PlacementUsageSnapshot>, PrismError> {
        Ok(self
            .get_setting(SETTING_PLACEMENT_USAGE)
            .await?
            .and_then(|raw| serde_json::from_str(&raw).ok()))
    }

    /// Merges this sync's readings (whole percents) and this worker's burn
    /// rates into the apps' snapshot. Writes only when something changed, and
    /// then at most every `USAGE_SNAPSHOT_EVERY_SECS` unless a window ran out
    /// or came back, which changes what the apps should show at once.
    async fn store_usage_snapshot(
        &self,
        host_models: &BTreeMap<String, PlacementHostModel>,
        loads: &BTreeMap<String, PlacementLoad>,
        now_unix: i64,
    ) -> Result<(), PrismError> {
        let old = self.usage_snapshot().await?.unwrap_or_default();
        let mut next = old.clone();
        for (k, h) in host_models {
            if h.session.is_some() || h.weekly.is_some() {
                next.models.insert(
                    k.replace('\0', "/"),
                    PlacementUsageReading {
                        session: h.session.as_ref().map(whole_percent),
                        weekly: h.weekly.as_ref().map(whole_percent),
                    },
                );
            }
        }
        next.burn = loads
            .iter()
            .filter_map(|(acct, load)| {
                let burn = load.burn_per_thread_per_hour?;
                Some((acct.clone(), (burn * 10.0).round() / 10.0))
            })
            .collect();
        if next == old {
            return Ok(());
        }
        let full = |r: Option<&PlacementUsageReading>| {
            r.map(|r| {
                [&r.session, &r.weekly].map(|w| w.as_ref().is_some_and(|w| w.used_percent >= 100.0))
            })
            .unwrap_or_default()
        };
        let ran_out_or_back = next
            .models
            .iter()
            .any(|(k, r)| full(Some(r)) != full(old.models.get(k)));
        if !ran_out_or_back && now_unix - old.at < USAGE_SNAPSHOT_EVERY_SECS {
            return Ok(());
        }
        next.at = now_unix;
        let raw = serde_json::to_string(&next).map_err(storage_err)?;
        self.set_setting(SETTING_PLACEMENT_USAGE, &raw, now_unix)
            .await
    }
}

#[derive(Clone)]
struct HostReport {
    name: String,
    seen_at: i64,
    /// The Rocky id (hex) of the `hosts` row this report was read from.
    report: String,
    models: Vec<PlacementSyncModel>,
}

/// How often an unchanged host report refreshes its `seen_at`. Reports count
/// as fresh for PLACEMENT_FRESH_REPORT_SECS (3 minutes).
const HOST_SEEN_REFRESH_SECS: i64 = 60;

/// One `threads` row as a placement sync stores it.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
struct StoredThread {
    thread_id: String,
    title: String,
    profile: String,
    selection_json: String,
    model: String,
    running: bool,
}

/// A host's stored report: its latest `hosts` row and that report's threads.
struct StoredReport {
    id: SqlValue,
    seen_at: i64,
    environment_id: String,
    models_json: String,
    threads: Vec<StoredThread>,
}

/// A model's session and weekly windows: its account's from ClickHouse when
/// `usage` read them within [`USAGE_FRESH_SECS`], else the host's.
fn usage_readings(
    m: &PlacementSyncModel,
    usage: &UsageByAccount,
    now_unix: i64,
) -> (Option<PlacementWindow>, Option<PlacementWindow>) {
    match usage
        .get(account_of(&m.model))
        .filter(|u| now_unix - u.observed_at <= USAGE_FRESH_SECS)
    {
        Some(u) => (
            u.session.clone().or_else(|| m.session.clone()),
            u.weekly.clone().or_else(|| m.weekly.clone()),
        ),
        None => (m.session.clone(), m.weekly.clone()),
    }
}

/// A window rounded to whole percents, so the snapshot changes only when the
/// apps would show a different number.
fn whole_percent(w: &PlacementWindow) -> PlacementWindow {
    PlacementWindow {
        used_percent: w.used_percent.round(),
        resets_at: w.resets_at.clone(),
    }
}

fn same_threads(a: &[StoredThread], b: &[StoredThread]) -> bool {
    let mut a = a.to_vec();
    let mut b = b.to_vec();
    a.sort();
    b.sort();
    a == b
}

pub(crate) fn storage_err<E: std::fmt::Display + 'static>(err: E) -> PrismError {
    let any: &dyn std::any::Any = &err;
    let sql = any.downcast_ref::<SqlFailure>().or_else(|| {
        any.downcast_ref::<rocky_sdk::Failure>()
            .and_then(|failure| failure.downcast_ref::<SqlFailure>())
    });
    if sql.is_some_and(|failure| failure.retryable) {
        PrismError::Unavailable(err.to_string())
    } else {
        PrismError::Storage(err.to_string())
    }
}

/// How long `retry_unavailable` keeps trying: Rocky refuses new rows while it
/// imports synced rows, which on a busy worker lasts a few seconds at a time.
const RETRY_UNAVAILABLE_FOR: std::time::Duration = std::time::Duration::from_secs(8);

/// Runs `write` again while it fails with `PrismError::Unavailable`, backing
/// off from 100 ms to 2 s, for up to `RETRY_UNAVAILABLE_FOR`. Each attempt must
/// be a whole transaction.
pub async fn retry_unavailable<T, F, Fut>(mut write: F) -> Result<T, PrismError>
where
    F: FnMut() -> Fut,
    Fut: std::future::Future<Output = Result<T, PrismError>>,
{
    let started = std::time::Instant::now();
    let mut delay = std::time::Duration::from_millis(100);
    loop {
        match write().await {
            Err(PrismError::Unavailable(_))
                if started.elapsed() + delay < RETRY_UNAVAILABLE_FOR =>
            {
                tokio::time::sleep(delay).await;
                delay = (delay * 2).min(std::time::Duration::from_secs(2));
            }
            other => return other,
        }
    }
}

fn f64_to_sql(v: f64) -> Result<SqlValue, PrismError> {
    SqlValue::real(v).ok_or_else(|| PrismError::InvalidInput("non-finite float".into()))
}

fn opt_f64_to_sql(v: Option<f64>) -> Result<SqlValue, PrismError> {
    match v {
        Some(f) => f64_to_sql(f),
        None => Ok(SqlValue::Null),
    }
}

/// Returns the bytes of a Rocky row id, for deterministic tie-breaks.
pub(crate) fn id_bytes(v: &SqlValue) -> Vec<u8> {
    match v {
        SqlValue::Blob(b) => b.clone(),
        SqlValue::Text(t) => t.as_bytes().to_vec(),
        _ => Vec::new(),
    }
}

/// Formats row id bytes as lowercase hex, as `RowIdentity` displays itself.
pub(crate) fn hex_string(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn sql_to_opt_f64(v: &SqlValue) -> Option<f64> {
    match v {
        SqlValue::Real(r) => Some(r.get()),
        SqlValue::Integer(i) => Some(*i as f64),
        _ => None,
    }
}

/// A disabled entry with no reasoning and no session share: what the view
/// adds for a model a profile doesn't name.
fn is_padding(entry: &PlacementEntryRow) -> bool {
    !entry.enabled && entry.reasoning.is_empty() && entry.session_share_of_weekly.is_none()
}

pub fn is_valid_profile_id(id: &str) -> bool {
    let bytes = id.as_bytes();
    if bytes.is_empty() || bytes.len() > 40 {
        return false;
    }
    let first = bytes[0];
    if !(first.is_ascii_lowercase() || first.is_ascii_digit()) {
        return false;
    }
    bytes
        .iter()
        .all(|&b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::{AccountUsage, PlacementMoveAck, PlacementWindow};

    // 2026-11-21T18:00:00Z
    const NOW: i64 = 1_795_284_000;
    const DAVID: &str = "claude-david/claude-opus-5-5";

    fn sel(model: &str) -> PlacementSelection {
        PlacementSelection {
            instance_id: "claudeAgent".into(),
            model: model.into(),
            options: Vec::new(),
        }
    }

    fn request(used: f64, now: i64, threads: Vec<PlacementSyncThread>) -> PlacementSyncRequest {
        let resets_at = DateTime::from_timestamp(now + 2 * 3600, 0)
            .map(|t| t.to_rfc3339_opts(SecondsFormat::Secs, true))
            .unwrap_or_default();
        PlacementSyncRequest {
            v: 1,
            environment_id: "env".into(),
            models: vec![PlacementSyncModel {
                instance_id: "claudeAgent".into(),
                model: DAVID.into(),
                label: String::new(),
                driver: String::new(),
                hidden: false,
                provider: String::new(),
                reasoning: None,
                blocked: None,
                session: Some(PlacementWindow {
                    used_percent: used,
                    resets_at,
                }),
                weekly: None,
            }],
            threads,
            moves: Vec::new(),
        }
    }

    fn thread(id: &str, profile: &str, model: &str) -> PlacementSyncThread {
        PlacementSyncThread {
            thread_id: id.into(),
            title: String::new(),
            profile: profile.into(),
            selection: sel(model),
            running: true,
        }
    }

    async fn host_rows(client: &PrismClient) -> Vec<(String, i64)> {
        let mut rows = client
            .db
            .query("SELECT id, seen_at FROM hosts", params![])
            .await
            .expect("hosts");
        let mut out = Vec::new();
        while let Some(row) = rows.next().await.expect("row") {
            let id: SqlValue = row.get("id").expect("id");
            out.push((
                hex_string(&id_bytes(&id)),
                row.get("seen_at").expect("seen_at"),
            ));
        }
        out
    }

    #[tokio::test]
    async fn an_unchanged_report_rewrites_nothing() {
        let (_dir, client) = open().await;
        let req = request(50.0, NOW, vec![thread("thr-1", "default", DAVID)]);
        client
            .sync_placement("host-a".into(), req.clone(), NOW)
            .await
            .expect("first sync");
        let first = host_rows(&client).await;
        assert_eq!(first.len(), 1);

        // Within a minute: nothing is written.
        client
            .sync_placement("host-a".into(), req.clone(), NOW + 30)
            .await
            .expect("same report");
        assert_eq!(host_rows(&client).await, first);

        // After a minute: seen_at moves, the row stays.
        client
            .sync_placement("host-a".into(), req.clone(), NOW + 60)
            .await
            .expect("refresh");
        assert_eq!(
            host_rows(&client).await,
            vec![(first[0].0.clone(), NOW + 60)]
        );
        // The threads still belong to the report, so they count as running.
        let fresh = client.hosts_since(NOW).await.expect("hosts");
        let running = client
            .running_threads_for_hosts(&fresh)
            .await
            .expect("running");
        assert_eq!(running.get(DAVID), Some(&1));

        // Catalog and usage changes update the report without rewriting its threads.
        let mut catalog = req.clone();
        catalog.models[0].label = "New model label".into();
        client
            .sync_placement("host-a".into(), catalog, NOW + 65)
            .await
            .expect("catalog change");
        assert_eq!(
            host_rows(&client).await,
            vec![(first[0].0.clone(), NOW + 65)]
        );
        let fresh = client.hosts_since(NOW).await.expect("new catalog");
        assert_eq!(fresh[0].models[0].label, "New model label");
        assert_eq!(
            client
                .running_threads_for_hosts(&fresh)
                .await
                .unwrap()
                .get(DAVID),
            Some(&1)
        );

        // A changed thread rewrites the report.
        let mut changed = req.clone();
        changed.threads[0].running = false;
        client
            .sync_placement("host-a".into(), changed, NOW + 70)
            .await
            .expect("changed");
        let after = host_rows(&client).await;
        assert_eq!(after.len(), 1);
        assert_ne!(after[0].0, first[0].0);
        assert_eq!(after[0].1, NOW + 70);
    }

    async fn open() -> (tempfile::TempDir, PrismClient) {
        let dir = tempfile::tempdir().expect("tempdir");
        let client = PrismClient::open_or_create(dir.path().display().to_string(), None)
            .await
            .expect("open_or_create");
        (dir, client)
    }

    /// Writes what a second worker stores for a report of `host` it received.
    async fn insert_report(client: &PrismClient, host: &str, seen_at: i64, threads: &[&str]) {
        let models_json =
            serde_json::to_string(&request(50.0, seen_at, Vec::new()).models).expect("json");
        let sel_json = serde_json::to_string(&sel(DAVID)).expect("json");
        let mut tx = client.db.transaction().await.expect("tx");
        let hid = tx.next_id("hosts", vec![]).await.expect("id");
        let report = hid.to_string();
        tx.execute(
            "INSERT INTO hosts (id, name, seen_at, environment_id, models_json) VALUES (?, ?, ?, '', ?)",
            params![hid, host, seen_at, models_json],
        )
        .await
        .expect("insert host");
        for t in threads {
            let tid = tx.next_id("threads", vec![]).await.expect("id");
            tx.execute(
                "INSERT INTO threads (id, host, thread_id, title, profile, selection_json, model, running, updated_at, report) VALUES (?, ?, ?, '', 'coding', ?, ?, 1, ?, ?)",
                params![tid, host, *t, sel_json.clone(), DAVID, seen_at, report.clone()],
            )
            .await
            .expect("insert thread");
        }
        tx.commit().await.expect("commit");
    }

    /// Adds a burn-rate sample for claude-david with one thread running.
    fn insert_sample(client: &PrismClient, at: i64, used: f64) {
        let mut samples = client.samples.lock().expect("samples");
        let kept = samples.entry(DAVID.into()).or_default();
        kept.push(PlacementSample {
            account: DAVID.into(),
            at,
            used,
            running: 1,
        });
        kept.sort_by_key(|s| s.at);
    }

    async fn insert_move(client: &PrismClient, id: &str, thread_id: &str, created_at: i64) {
        let from = serde_json::to_string(&sel(DAVID)).expect("json");
        let to = serde_json::to_string(&sel("claude-1/claude-opus-5-5")).expect("json");
        let mut tx = client.db.transaction().await.expect("tx");
        let iid = tx.next_id("instructions", vec![]).await.expect("id");
        tx.execute(
            "INSERT INTO instructions (id, instruction_id, host, thread_id, kind, from_selection_json, selection_json, reason, state, created_at, done_at) VALUES (?, ?, 'host-a', ?, 'move', ?, ?, 'full', 'asked', ?, NULL)",
            params![iid, id, thread_id, from, to, created_at],
        )
        .await
        .expect("insert move");
        tx.commit().await.expect("commit");
    }

    /// Saves the profile `id` with `models` (Claude accounts), all enabled.
    async fn put_profile(client: &PrismClient, id: &str, models: &[&str]) {
        let entries = models
            .iter()
            .map(|model| PutPlacementEntryInput {
                instance_id: "claudeAgent".into(),
                model: (*model).into(),
                reasoning: String::new(),
                enabled: true,
                session_share_of_weekly: None,
            })
            .collect();
        client
            .put_placement_profile(
                id.into(),
                PutPlacementProfileRequest {
                    label: id.into(),
                    entries,
                },
                NOW,
            )
            .await
            .expect("put profile");
    }

    /// Returns claude-david's (running threads, predicted session %) as host-b sees it.
    async fn david_load(duplicates: bool) -> (i32, Option<f64>) {
        let (_dir, client) = open().await;
        put_profile(&client, "coding", &[DAVID]).await;
        let threads = || vec![thread("thr-1", "coding", DAVID)];
        client
            .sync_placement(
                "host-a".into(),
                request(40.0, NOW - 1200, threads()),
                NOW - 1200,
            )
            .await
            .expect("sync t0");
        client
            .sync_placement(
                "host-a".into(),
                request(50.0, NOW - 60, threads()),
                NOW - 60,
            )
            .await
            .expect("sync t1");
        if duplicates {
            // A second worker got host-a's previous report a second before and
            // stored it with its own Rocky ids.
            insert_report(&client, "host-a", NOW - 61, &["thr-1"]).await;
        }
        let resp = client
            .sync_placement("host-b".into(), request(55.0, NOW, Vec::new()), NOW)
            .await
            .expect("sync host-b");
        let david = resp.profiles[0]
            .ranked
            .iter()
            .find(|r| r.selection.model == DAVID)
            .expect("claude-david ranked");
        let out = (david.running_threads, david.predicted_session_percent);
        client.close().await.expect("close");
        out
    }

    #[tokio::test]
    async fn duplicate_reports_from_two_workers_count_once() {
        let single = david_load(false).await;
        assert_eq!(single.0, 1);
        assert!(single.1.is_some(), "burn rate from the samples");
        assert_eq!(david_load(true).await, single);
    }

    #[tokio::test]
    async fn a_host_with_hundreds_of_idle_threads_syncs() {
        // david-mb reported 178 profile threads, one of them running; storing
        // them all outgrew a Rocky transaction and every sync failed.
        let (_dir, client) = open().await;
        put_profile(&client, "default", &[DAVID]).await;
        let mut threads: Vec<PlacementSyncThread> = (0..300)
            .map(|i| PlacementSyncThread {
                title: format!("An idle thread with a title of some length, number {i}"),
                running: false,
                ..thread(&format!("thr-idle-{i:03}"), "default", DAVID)
            })
            .collect();
        threads.push(thread("thr-running", "default", DAVID));
        client
            .sync_placement("host-a".into(), request(50.0, NOW, threads), NOW)
            .await
            .expect("sync");
        let fresh = client.hosts_since(NOW).await.expect("hosts");
        let running = client
            .running_threads_for_hosts(&fresh)
            .await
            .expect("running");
        assert_eq!(running.get(DAVID), Some(&1));
        let stored = client.stored_report("host-a").await.expect("stored");
        assert_eq!(stored.expect("report").threads.len(), 1);
    }

    #[tokio::test]
    async fn an_evacuation_spreads_running_threads_by_load() {
        // claude-1 is spent for hours; five threads running on it move. Each
        // moved thread counts on claude-david, so once claude-david would
        // fill up the rest go to claude-alex instead of all to the first.
        const ONE: &str = "claude-1/claude-opus-5-5";
        const ALEX: &str = "claude-alex/claude-opus-5-5";
        let (_dir, client) = open().await;
        put_profile(&client, "default", &[ONE, DAVID, ALEX]).await;
        // claude-david burns 6% per thread per hour.
        insert_sample(&client, NOW - 1200, 40.0);
        insert_sample(&client, NOW - 60, 42.0);
        let window = |used: f64, hours: i64| PlacementWindow {
            used_percent: used,
            resets_at: DateTime::from_timestamp(NOW + hours * 3600, 0)
                .map(|t| t.to_rfc3339_opts(SecondsFormat::Secs, true))
                .unwrap_or_default(),
        };
        let mut req = request(42.0, NOW, Vec::new());
        let david = req.models[0].clone();
        req.models = vec![
            PlacementSyncModel {
                model: ONE.into(),
                session: Some(window(100.0, 3)),
                ..david.clone()
            },
            PlacementSyncModel {
                session: Some(window(42.0, 2)),
                ..david.clone()
            },
            PlacementSyncModel {
                model: ALEX.into(),
                session: Some(window(10.0, 4)),
                ..david
            },
        ];
        req.threads = (0..5)
            .map(|i| thread(&format!("thr-{i}"), "default", ONE))
            .collect();
        let resp = client
            .sync_placement("host-a".into(), req, NOW)
            .await
            .expect("sync");
        assert_eq!(resp.moves.len(), 5, "{:?}", resp.moves);
        let to_david = resp.moves.iter().filter(|m| m.to.model == DAVID).count();
        let to_alex = resp.moves.iter().filter(|m| m.to.model == ALEX).count();
        assert!(
            to_david >= 1 && to_alex >= 1,
            "david {to_david}, alex {to_alex}"
        );
        assert_eq!(to_david + to_alex, 5);
    }

    #[tokio::test]
    async fn usage_comes_from_clickhouse_and_is_never_stored_with_the_report() {
        let (_dir, client) = open().await;
        put_profile(&client, "coding", &[DAVID]).await;
        let reading = |used: f64, observed_at: i64| {
            UsageByAccount::from([(
                "claude-david".to_string(),
                AccountUsage {
                    session: Some(PlacementWindow {
                        used_percent: used,
                        resets_at: DateTime::from_timestamp(NOW + 2 * 3600, 0)
                            .map(|t| t.to_rfc3339_opts(SecondsFormat::Secs, true))
                            .unwrap_or_default(),
                    }),
                    weekly: None,
                    observed_at,
                },
            )])
        };
        let session_of = |resp: &PlacementSyncResponse| {
            resp.profiles[0].ranked[0]
                .session
                .as_ref()
                .map(|w| w.used_percent)
        };
        // The host says 10%, ClickHouse 98%: ClickHouse ranks.
        let resp = client
            .sync_placement_with_usage(
                "host-a".into(),
                request(10.0, NOW, vec![thread("thr-1", "coding", DAVID)]),
                NOW,
                &reading(98.0, NOW - 60),
            )
            .await
            .expect("sync");
        assert_eq!(session_of(&resp), Some(98.0));
        assert_ne!(resp.profiles[0].ranked[0].state, "ok");
        let stored = client.stored_report("host-a").await.expect("stored");
        assert!(
            !stored.expect("report").models_json.contains("usedPercent"),
            "the stored report keeps no usage"
        );

        // The apps rank with the snapshot.
        let snapshot = client
            .usage_snapshot()
            .await
            .expect("read")
            .expect("written");
        assert_eq!(snapshot.at, NOW);
        let key = format!("claudeAgent/{DAVID}");
        assert_eq!(
            snapshot.models[&key]
                .session
                .as_ref()
                .map(|w| w.used_percent),
            Some(98.0)
        );
        let view = client
            .placement_view("host-a".into(), NOW)
            .await
            .expect("view");
        assert_eq!(
            view.profiles[0].entries[0].state,
            resp.profiles[0].ranked[0].state
        );

        // A minute later at 99%: not worth replicating yet.
        client
            .sync_placement_with_usage(
                "host-a".into(),
                request(10.0, NOW + 60, vec![thread("thr-1", "coding", DAVID)]),
                NOW + 60,
                &reading(99.0, NOW + 30),
            )
            .await
            .expect("sync");
        assert_eq!(client.usage_snapshot().await.unwrap().unwrap().at, NOW);
        // Out at 100%: the apps learn at once.
        client
            .sync_placement_with_usage(
                "host-a".into(),
                request(10.0, NOW + 120, vec![thread("thr-1", "coding", DAVID)]),
                NOW + 120,
                &reading(100.0, NOW + 90),
            )
            .await
            .expect("sync");
        assert_eq!(
            client.usage_snapshot().await.unwrap().unwrap().at,
            NOW + 120
        );

        // A stale ClickHouse reading gives way to the host's.
        let resp = client
            .sync_placement_with_usage(
                "host-a".into(),
                request(10.0, NOW + 180, vec![thread("thr-1", "coding", DAVID)]),
                NOW + 180,
                &reading(100.0, NOW + 180 - USAGE_FRESH_SECS - 1),
            )
            .await
            .expect("sync");
        assert_eq!(session_of(&resp), Some(10.0));
        client.close().await.expect("close");
    }

    #[tokio::test]
    async fn the_move_slack_is_stored() {
        let (_dir, client) = open().await;
        let policy = PlacementPolicy {
            weekly_move_slack_sessions: Some(2.5),
            ..PlacementPolicy::default()
        };
        client
            .set_placement_policy(policy.clone())
            .await
            .expect("set");
        assert_eq!(client.placement_policy().await.expect("get"), policy);
        // Left out, it falls back to the default again.
        client
            .set_placement_policy(PlacementPolicy::default())
            .await
            .expect("clear");
        assert_eq!(
            client
                .placement_policy()
                .await
                .expect("get")
                .weekly_move_slack_sessions,
            None
        );
    }

    #[tokio::test]
    async fn a_move_that_lost_the_race_is_not_asked_after_the_winner_is_acknowledged() {
        let (_dir, client) = open().await;
        insert_move(&client, "early", "thr-1", NOW - 20).await;
        insert_move(&client, "late", "thr-1", NOW - 10).await;
        let mut req = request(50.0, NOW, vec![thread("thr-1", "none", DAVID)]);
        req.moves = vec![PlacementMoveAck {
            id: "early".into(),
            state: "continued".into(),
            detail: String::new(),
        }];
        let resp = client
            .sync_placement("host-a".into(), req, NOW)
            .await
            .expect("sync ack");
        assert!(resp.moves.is_empty(), "{:?}", resp.moves);
        let resp = client
            .sync_placement(
                "host-a".into(),
                request(50.0, NOW + 10, vec![thread("thr-1", "none", DAVID)]),
                NOW + 10,
            )
            .await
            .expect("sync after ack");
        assert!(resp.moves.is_empty(), "{:?}", resp.moves);
        client.close().await.expect("close");
    }

    #[tokio::test]
    async fn a_lost_race_stays_lost_after_the_winner_ages_out() {
        let (_dir, client) = open().await;
        let mut req = request(50.0, NOW - 100, vec![thread("thr-1", "none", DAVID)]);
        req.moves = vec![PlacementMoveAck {
            id: "early".into(),
            state: "continued".into(),
            detail: String::new(),
        }];
        client
            .sync_placement("host-a".into(), req, NOW - 100)
            .await
            .expect("sync ack");
        insert_move(&client, "early", "thr-1", NOW - 300).await;
        insert_move(&client, "late", "thr-1", NOW - 290).await;
        let resp = client
            .sync_placement(
                "host-a".into(),
                request(50.0, NOW, vec![thread("thr-1", "none", DAVID)]),
                NOW,
            )
            .await
            .expect("sync after the winner aged out");
        assert!(resp.moves.is_empty(), "{:?}", resp.moves);
        client.close().await.expect("close");
    }

    #[tokio::test]
    async fn ack_before_move_replicates_and_duplicate_moves_resolve_deterministically() {
        let (_dir, client) = open().await;
        // Threads in an unknown profile get no new moves, so only replicated ones show.
        let mut req = request(50.0, NOW, vec![thread("thr-1", "none", DAVID)]);
        req.moves = vec![PlacementMoveAck {
            id: "m1".into(),
            state: "failed".into(),
            detail: String::new(),
        }];
        let resp = client
            .sync_placement("host-a".into(), req, NOW)
            .await
            .expect("sync ack");
        assert!(resp.moves.is_empty());

        // The move row replicates only after its acknowledgement was stored.
        insert_move(&client, "m1", "thr-1", NOW - 30).await;
        // Two workers asked to move thr-2; one of the moves replicated twice.
        insert_move(&client, "b-late", "thr-2", NOW - 10).await;
        insert_move(&client, "b-early", "thr-2", NOW - 20).await;
        insert_move(&client, "b-early", "thr-2", NOW - 20).await;

        let threads = vec![
            thread("thr-1", "none", DAVID),
            thread("thr-2", "none", DAVID),
        ];
        let resp = client
            .sync_placement("host-a".into(), request(50.0, NOW + 10, threads), NOW + 10)
            .await
            .expect("sync after replication");
        let ids: Vec<&str> = resp.moves.iter().map(|m| m.id.as_str()).collect();
        assert_eq!(ids, ["b-early"]);

        // The move that lost the race was dropped at once; the winner continues
        // once thr-2 runs on the target.
        let threads = vec![
            thread("thr-1", "none", DAVID),
            thread("thr-2", "none", "claude-1/claude-opus-5-5"),
        ];
        client
            .sync_placement("host-a".into(), request(50.0, NOW + 20, threads), NOW + 20)
            .await
            .expect("sync continued");
        let moves = client
            .machine_placement_moves("host-a".into(), 0)
            .await
            .expect("moves");
        let states: Vec<(&str, &str)> = moves
            .iter()
            .map(|m| (m.id.as_str(), m.state.as_str()))
            .collect();
        assert_eq!(
            states,
            [
                ("m1", "failed"),
                ("b-early", "continued"),
                ("b-late", "dropped")
            ]
        );
        client.close().await.expect("close");
    }
}

#[cfg(test)]
mod retry_tests {
    use super::*;

    fn failure(retryable: bool) -> SqlFailure {
        SqlFailure {
            code: 1,
            message: "SQL session: hosts is not available: Incomplete".into(),
            retryable,
        }
    }

    #[test]
    fn storage_err_keeps_rockys_retryable_flag() {
        assert!(matches!(
            storage_err(failure(true)),
            PrismError::Unavailable(_)
        ));
        assert!(matches!(
            storage_err(failure(false)),
            PrismError::Storage(_)
        ));
        let boxed: rocky_sdk::Failure = Box::new(failure(true));
        assert!(matches!(storage_err(boxed), PrismError::Unavailable(_)));
        assert!(matches!(storage_err("plain"), PrismError::Storage(_)));
    }

    #[test]
    fn incomplete_is_told_apart_from_a_busy_database() {
        assert!(storage_err(failure(true)).is_incomplete());
        assert!(storage_err(failure(false)).is_incomplete());
        let busy = SqlFailure {
            code: 4,
            message: "SQL session: transaction was rolled back: Preempted".into(),
            retryable: true,
        };
        assert!(!storage_err(busy).is_incomplete());
        assert!(!PrismError::NotFound.is_incomplete());
    }

    #[tokio::test]
    async fn a_fresh_replica_is_leader_ready() {
        let dir = tempfile::tempdir().expect("tempdir");
        let client = PrismClient::open_or_create(dir.path().display().to_string(), None)
            .await
            .expect("open");
        client.leader_ready().await.expect("ready");
        client.close().await.expect("close");
    }

    #[tokio::test]
    async fn retry_unavailable_retries_until_the_write_succeeds() {
        let attempts = std::sync::atomic::AtomicU32::new(0);
        let result = retry_unavailable(|| async {
            if attempts.fetch_add(1, std::sync::atomic::Ordering::SeqCst) < 2 {
                Err(PrismError::Unavailable("Incomplete".into()))
            } else {
                Ok(7)
            }
        })
        .await;
        assert_eq!(result.unwrap(), 7);
        assert_eq!(attempts.load(std::sync::atomic::Ordering::SeqCst), 3);

        let result: Result<(), _> =
            retry_unavailable(|| async { Err(PrismError::Storage("broken".into())) }).await;
        assert!(matches!(result, Err(PrismError::Storage(_))));
    }
}
