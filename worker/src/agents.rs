//! Prism's own agents: start an agent (`agent_requests`), send follow-ups
//! (`agent_messages`), watch active/idle agents (`agents` + `agent_activity` +
//! `agent.finished` push), credential-expiry reminders, and machine/project
//! catalog refresh.

use crate::config::{CLUSTER_TARGET, Config};
use crate::magic::{AUTO_CHOICE, pick_machine, pick_project};
use crate::metrics::fetch_host_metrics;
use crate::push::Pusher;
use crate::t3::{Secrets, T3Hub};
use rocky_prism::{
    AGENT_STOP, AgentActivityRow, AgentRow, MachineRow, Notification, PrismClient, ProjectRow,
    sha256_hex, truncate,
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::BTreeMap;

const ACTIVE_POLL_SECS: i64 = 10;
const IDLE_POLL_SECS: i64 = 5 * 60;
const SETTLE_DELAY_SECS: i64 = 15;
const FORGET_IDLE_SECS: i64 = 3 * 24 * 60 * 60;
const MAX_LAST_MESSAGE: usize = 4000;
const ACTIVITY_ITEMS: usize = 40;
const ACTIVITY_TEXT: usize = 4000;
const ACTIVITY_STEP_TEXT: usize = 300;
/// The `settings` key the apps store the default launch model under.
const MODEL_SETTING: &str = "model";
const LAUNCH_PROMPT_PREFIX: &str = "Prism, the owner's alert and operations app, started you.";

fn is_terminal_status(status: &str) -> bool {
    matches!(
        status,
        "idle" | "completed" | "interrupted" | "failed" | "cancelled" | "rolled_back"
    )
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActivityItem {
    pub id: String,
    pub position: i64,
    pub run_id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub status: String,
    pub title: String,
    pub text: String,
    pub truncated: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ThreadItem {
    #[serde(default)]
    position: i64,
    #[serde(default)]
    item_id: String,
    #[serde(default)]
    run_id: String,
    #[serde(default, rename = "type")]
    kind: String,
    #[serde(default)]
    status: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    text: String,
    #[serde(default)]
    text_truncated: bool,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ThreadRead {
    #[serde(default)]
    thread: ThreadMeta,
    #[serde(default)]
    items: Vec<ThreadItem>,
    #[serde(default)]
    has_more: bool,
    earlier_position: Option<i64>,
}

#[derive(Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ThreadMeta {
    #[serde(default)]
    status: String,
    #[serde(default)]
    latest_run_id: String,
}

/// The tasks the owner can start directly (Go's `agentTasks`).
const AGENT_TASKS: [&str; 5] = ["alert", "fix", "deploy", "retire", "do"];
/// Tasks that work in the GitOps repository, never on a machine.
const CLUSTER_ONLY_TASKS: [&str; 3] = ["alert", "deploy", "retire"];
const MAX_REQUEST_CHARS: usize = 10_000;

/// Go's `StartAgent` checks: a known task and a request of at most 10,000
/// characters.
fn check_agent_request(req: &rocky_prism::AgentRequestRow) -> Result<(), String> {
    if !AGENT_TASKS.contains(&req.task.as_str()) {
        return Err(format!(
            "Unknown task {:?}: use fix, do, alert, deploy or retire.",
            req.task
        ));
    }
    let request = req.request_text.trim();
    if request.is_empty() {
        return Err("Say what the agent should do.".into());
    }
    if request.chars().count() > MAX_REQUEST_CHARS {
        return Err("Please keep the request under 10,000 characters.".into());
    }
    Ok(())
}

/// Go's agent title: "Fix: <request>", with the machine for a machine's agent.
fn agent_title(task: &str, request: &str, target: &str) -> String {
    let prefix = match task {
        "alert" => "Add alert",
        "retire" => "Retire",
        "deploy" => "Deploy",
        "do" => "Do",
        _ => "Fix",
    };
    let mut title = format!("{prefix}: {request}");
    if target != CLUSTER_TARGET {
        title += &format!(" ({target})");
    }
    truncate(&title, 200)
}

/// Claims and launches pending `agent_requests` rows.
#[allow(clippy::too_many_arguments)]
pub async fn run_agent_requests(
    client: &PrismClient,
    cfg: &Config,
    t3: &T3Hub,
    pusher: &Pusher,
    secrets: &Secrets,
    worker: &str,
    now: i64,
    claim_timeout: i64,
) -> Result<(), String> {
    let items = client
        .claimable_agent_requests(now - claim_timeout)
        .await
        .map_err(|e| e.to_string())?;
    for req in items {
        if !client
            .claim_agent_request(&req.request_id, worker, now, now - claim_timeout)
            .await
            .map_err(|e| e.to_string())?
        {
            continue;
        }
        process_agent_request(client, cfg, t3, pusher, secrets, worker, now, &req).await?;
    }
    Ok(())
}

/// Launches one claimed `agent_requests` row and finishes it: the leader's,
/// and a device's own when the workers are unreachable (`crate::direct`).
#[allow(clippy::too_many_arguments)]
pub async fn process_agent_request(
    client: &PrismClient,
    cfg: &Config,
    t3: &T3Hub,
    pusher: &Pusher,
    secrets: &Secrets,
    worker: &str,
    now: i64,
    req: &rocky_prism::AgentRequestRow,
) -> Result<(), String> {
    if let Err(err) = check_agent_request(req) {
        client
            .finish_agent_request(&req.request_id, worker, now, "", "", &err)
            .await
            .map_err(|e| e.to_string())?;
        return Ok(());
    }
    let cluster_only = CLUSTER_ONLY_TASKS.contains(&req.task.as_str());
    let mut target_name = req.target.trim().trim_start_matches('@').to_lowercase();
    if target_name.is_empty() {
        target_name = CLUSTER_TARGET.to_string();
    }
    if target_name == AUTO_CHOICE {
        target_name = if cluster_only {
            CLUSTER_TARGET.to_string()
        } else {
            pick_machine(client, cfg).await
        };
    }
    if cluster_only && target_name != CLUSTER_TARGET {
        let err = format!(
            "{} works on the cluster only. Use fix or do for a machine.",
            req.task
        );
        client
            .finish_agent_request(&req.request_id, worker, now, "", "", &err)
            .await
            .map_err(|e| e.to_string())?;
        return Ok(());
    }
    let mut project_id = req.project_id.clone();
    if project_id == AUTO_CHOICE {
        let catalog = client.list_projects().await.unwrap_or_default();
        match pick_project(cfg, secrets, &catalog, &req.request_text).await {
            Ok(picked) => project_id = picked,
            Err(err) => {
                client
                    .finish_agent_request(&req.request_id, worker, now, "", "", &err)
                    .await
                    .map_err(|e| e.to_string())?;
                return Ok(());
            }
        }
    }
    let title = agent_title(&req.task, req.request_text.trim(), &target_name);
    let prompt = format!(
        "{LAUNCH_PROMPT_PREFIX}\nTask: {}\n\n{}",
        req.task, req.request_text
    );
    // A launch that names no model starts with the one the owner picked
    // in the apps (`PUT /v1/settings/model`, the `model` setting).
    let model_json = if req.model_json.is_empty() {
        client
            .get_setting(MODEL_SETTING)
            .await
            .ok()
            .flatten()
            .unwrap_or_default()
    } else {
        req.model_json.clone()
    };
    let model: Option<Value> = serde_json::from_str::<Value>(&model_json)
        .ok()
        .filter(|m| {
            m.get("model")
                .and_then(Value::as_str)
                .is_some_and(|s| !s.is_empty())
        })
        .map(|mut m| {
            // T3's model selection takes the instance and the model only.
            if let Some(o) = m.as_object_mut() {
                o.retain(|k, _| k == "instanceId" || k == "model");
            }
            m
        });
    let client_req_id = format!(
        "prism:agent:{}",
        sha256_hex(&format!("{}:{}", req.requested_by, req.request_id))
    );
    match t3
        .launch_agent_thread(
            &target_name,
            &title,
            &prompt,
            &client_req_id,
            model,
            &project_id,
        )
        .await
    {
        Ok((launch, target)) => {
            let agent = AgentRow {
                thread_id: launch.thread_id.clone(),
                kind: req.task.clone(),
                title: title.clone(),
                alert_id: 0,
                target: target.name.clone(),
                client: target.client.clone(),
                environment_id: target.environment_id.clone(),
                host: target.host.clone(),
                link: launch.link.clone(),
                request_text: req.request_text.clone(),
                state: "active".into(),
                run_status: "running".into(),
                question_pending: false,
                last_message: String::new(),
                requested_by: req.requested_by.clone(),
                created_at: now,
                updated_at: now,
                next_check: now + ACTIVE_POLL_SECS,
                reported_run: String::new(),
                terminal_run: String::new(),
                terminal_seen: 0,
            };
            let _ = client.save_agent(&agent).await;
            let n = Notification {
                kind: "agent.started".into(),
                key: format!("agent.started:{}", launch.thread_id),
                time: now,
                title: format!("Agent started: {title}"),
                agent_id: launch.thread_id.clone(),
                host: target.host.clone(),
                environment_id: target.environment_id.clone(),
                thread_id: launch.thread_id.clone(),
                ..Notification::default()
            };
            let _ = pusher.deliver(client, &[n], now).await;
            client
                .finish_agent_request(
                    &req.request_id,
                    worker,
                    now,
                    &launch.thread_id,
                    &launch.link,
                    "",
                )
                .await
                .map_err(|e| e.to_string())?;
        }
        Err(err) => {
            client
                .finish_agent_request(&req.request_id, worker, now, "", "", &err.for_owner())
                .await
                .map_err(|e| e.to_string())?;
        }
    }
    Ok(())
}

/// Claims and sends pending `agent_messages` follow-ups.
pub async fn run_agent_messages(
    client: &PrismClient,
    t3: &T3Hub,
    worker: &str,
    now: i64,
    claim_timeout: i64,
) -> Result<(), String> {
    let items = client
        .claimable_agent_messages(now - claim_timeout)
        .await
        .map_err(|e| e.to_string())?;
    for req in items {
        if !client
            .claim_agent_message(&req.request_id, worker, now, now - claim_timeout)
            .await
            .map_err(|e| e.to_string())?
        {
            continue;
        }
        process_agent_message(client, t3, worker, now, &req).await?;
    }
    Ok(())
}

/// Sends (or stops) one claimed `agent_messages` row and finishes it.
pub async fn process_agent_message(
    client: &PrismClient,
    t3: &T3Hub,
    worker: &str,
    now: i64,
    req: &rocky_prism::AgentMessageRow,
) -> Result<(), String> {
    let err_msg = match send_one_message(client, t3, req, now).await {
        Ok(()) => String::new(),
        Err(e) => e,
    };
    client
        .finish_agent_message(&req.request_id, worker, now, &err_msg)
        .await
        .map_err(|e| e.to_string())
}

async fn send_one_message(
    client: &PrismClient,
    t3: &T3Hub,
    req: &rocky_prism::AgentMessageRow,
    now: i64,
) -> Result<(), String> {
    if req.kind == AGENT_STOP {
        return stop_agent(client, t3, req).await;
    }
    let text = req.text.trim();
    if text.is_empty() {
        return Err("Say what the agent should do.".into());
    }
    let Some(mut agent) = client
        .get_agent(&req.agent_id)
        .await
        .map_err(|e| e.to_string())?
    else {
        return Err("Agent not found.".into());
    };
    let client_req_id = format!(
        "prism:message:{}",
        sha256_hex(&format!("{}:{}", req.requested_by, req.request_id))
    );
    t3.call(
        &agent.client,
        &agent.environment_id,
        "t3_thread_send",
        json!({
            "threadId": agent.thread_id,
            "message": text,
            "mode": "auto",
            "clientRequestId": client_req_id,
        }),
    )
    .await
    .map_err(|_| "T3 could not send this to the agent. Try again later.".to_string())?;
    agent.state = "active".into();
    agent.updated_at = now;
    agent.next_check = now;
    agent.terminal_run.clear();
    agent.terminal_seen = 0;
    client.save_agent(&agent).await.map_err(|e| e.to_string())?;
    Ok(())
}

/// Stops an agent's run through the T3 that launched it (Go's `StopAgent`).
/// An agent that is not running is left alone; the watcher records the
/// outcome when T3 reports it.
async fn stop_agent(
    client: &PrismClient,
    t3: &T3Hub,
    req: &rocky_prism::AgentMessageRow,
) -> Result<(), String> {
    let Some(agent) = client
        .get_agent(&req.agent_id)
        .await
        .map_err(|e| e.to_string())?
    else {
        return Err("Agent not found.".into());
    };
    if agent.state != "active" {
        return Ok(());
    }
    let client_req_id = format!(
        "prism:stop:{}",
        sha256_hex(&format!("{}:{}", req.requested_by, req.request_id))
    );
    t3.call(
        &agent.client,
        &agent.environment_id,
        "t3_thread_interrupt",
        json!({
            "threadId": agent.thread_id,
            "stop": true,
            "clientRequestId": client_req_id,
        }),
    )
    .await
    .map_err(|_| "T3 could not stop this agent. Try again later.".to_string())?;
    Ok(())
}

/// Watches due agents, refreshes `agent_activity`, and pushes `agent.finished`.
pub async fn watch_agents(
    client: &PrismClient,
    t3: &T3Hub,
    pusher: &Pusher,
    now: i64,
) -> Result<(), String> {
    let agents = client.due_agents(now).await.map_err(|e| e.to_string())?;
    for mut agent in agents {
        if let Err(err) = check_agent(client, t3, pusher, &mut agent, now).await {
            eprintln!(
                "prism-worker: agent watcher failed for {}: {err}",
                agent.thread_id
            );
            agent.next_check = now + ACTIVE_POLL_SECS;
            let _ = client.save_agent(&agent).await;
        }
    }
    Ok(())
}

async fn check_agent(
    client: &PrismClient,
    t3: &T3Hub,
    pusher: &Pusher,
    agent: &mut AgentRow,
    now: i64,
) -> Result<(), String> {
    let raw = t3
        .call(
            &agent.client,
            &agent.environment_id,
            "t3_thread_read",
            json!({
                "threadId": agent.thread_id,
                "view": "activity",
                "limit": 60,
                "beforePosition": 1_i64 << 30,
                "maxCharsPerItem": ACTIVITY_TEXT,
            }),
        )
        .await
        .map_err(|e| e.to_string())?;
    let mut read: ThreadRead = serde_json::from_value(raw).map_err(|e| e.to_string())?;
    read.items.sort_by_key(|i| i.position);

    // Update replicated agent_activity table.
    let thread_start = read.earlier_position.is_none() && !read.has_more;
    let mut first_user = true;
    let mut activity = Vec::new();
    for it in &read.items {
        if it.kind == "reasoning" {
            continue;
        }
        let mut text = it.text.clone();
        let mut truncated = it.text_truncated;
        if it.kind == "user_message" {
            let launch =
                (thread_start && first_user) || text.trim_start().starts_with(LAUNCH_PROMPT_PREFIX);
            first_user = false;
            if launch {
                if agent.request_text.is_empty() {
                    continue;
                }
                text = agent.request_text.clone();
                truncated = false;
            }
        }
        let limit = if it.kind == "assistant_message" || it.kind == "user_message" {
            ACTIVITY_TEXT
        } else {
            ACTIVITY_STEP_TEXT
        };
        let cut = truncate(&text, limit);
        if cut != text {
            text = cut;
            truncated = true;
        }
        activity.push(ActivityItem {
            id: it.item_id.clone(),
            position: it.position,
            run_id: it.run_id.clone(),
            kind: it.kind.clone(),
            status: it.status.clone(),
            title: it.title.clone(),
            text,
            truncated,
        });
    }
    if activity.len() > ACTIVITY_ITEMS {
        activity = activity.split_off(activity.len() - ACTIVITY_ITEMS);
    }
    let _ = client
        .save_agent_activity(&AgentActivityRow {
            agent_id: agent.thread_id.clone(),
            status: read.thread.status.clone(),
            items_json: serde_json::to_string(&activity).unwrap_or_else(|_| "[]".into()),
            updated_at: now,
        })
        .await;

    let status = read.thread.status.as_str();
    let latest = read.thread.latest_run_id.as_str();
    if !status.is_empty() && status != agent.run_status {
        agent.run_status = status.to_string();
        agent.updated_at = now;
    }
    if !is_terminal_status(status) {
        agent.state = "active".into();
        agent.terminal_run.clear();
        agent.terminal_seen = 0;
        agent.next_check = now + ACTIVE_POLL_SECS;
        return client.save_agent(agent).await.map_err(|e| e.to_string());
    }
    if latest.is_empty() || latest == agent.reported_run {
        if agent.state == "active" && now - agent.updated_at < 120 {
            agent.next_check = now + ACTIVE_POLL_SECS;
            return client.save_agent(agent).await.map_err(|e| e.to_string());
        }
        if agent.state == "active" {
            agent.state = "idle".into();
            agent.updated_at = now;
        } else if now - agent.updated_at > FORGET_IDLE_SECS {
            agent.state = "done".into();
        }
        agent.next_check = now + IDLE_POLL_SECS;
        return client.save_agent(agent).await.map_err(|e| e.to_string());
    }
    if agent.terminal_run != latest {
        agent.terminal_run = latest.to_string();
        agent.terminal_seen = now;
        agent.next_check = now + SETTLE_DELAY_SECS;
        return client.save_agent(agent).await.map_err(|e| e.to_string());
    }
    if now - agent.terminal_seen < SETTLE_DELAY_SECS {
        agent.next_check = agent.terminal_seen + SETTLE_DELAY_SECS;
        return client.save_agent(agent).await.map_err(|e| e.to_string());
    }

    let final_text = read
        .items
        .iter()
        .rev()
        .find(|i| i.kind == "assistant_message" && i.run_id == latest)
        .map(|i| i.text.trim().to_string())
        .unwrap_or_default();
    agent.last_message = truncate(&final_text, MAX_LAST_MESSAGE);
    agent.reported_run = latest.to_string();
    agent.state = "idle".into();
    agent.updated_at = now;
    agent.next_check = now + IDLE_POLL_SECS;
    client.save_agent(agent).await.map_err(|e| e.to_string())?;

    let display_title = if agent.title.is_empty() {
        &agent.kind
    } else {
        &agent.title
    };
    let failed = matches!(
        status,
        "failed" | "interrupted" | "cancelled" | "rolled_back"
    );
    let title = if failed {
        format!("Agent {}: {display_title}", status.replace('_', " "))
    } else {
        format!("Agent finished: {display_title}")
    };
    let body = if agent.last_message.is_empty() {
        "The agent ended without a final message.".to_string()
    } else {
        agent.last_message.clone()
    };
    let n = Notification {
        kind: "agent.finished".into(),
        key: format!("agent.finished:{}:{latest}", agent.thread_id),
        time: now,
        title,
        body,
        agent_id: agent.thread_id.clone(),
        alert_id: agent.alert_id,
        state: if failed {
            "failed".into()
        } else {
            "finished".into()
        },
        host: agent.host.clone(),
        environment_id: agent.environment_id.clone(),
        thread_id: agent.thread_id.clone(),
        ..Notification::default()
    };
    let _ = pusher.deliver(client, &[n], now).await;
    Ok(())
}

/// Sends daily `notice` pushes when the cluster T3 token (< 7 days) or any
/// paired T3 session (< 5 days) expires soon.
pub async fn expiry_reminders(
    client: &PrismClient,
    pusher: &Pusher,
    secrets: &Secrets,
    now: i64,
) -> Result<(), String> {
    let day = now / 86_400;
    if let Some(raw) = secrets("T3_TOKEN_EXPIRES_AT")
        && let Ok(expiry) = raw.trim().parse::<i64>()
        && expiry > 0
        && expiry - now < 7 * 86_400
    {
        let days = ((expiry - now) / 86_400).max(0);
        let n = Notification {
            kind: "notice".into(),
            key: format!("notice:t3-expiry:{expiry}:{day}"),
            time: now,
            title: "Prism's T3 credential expires soon".into(),
            body: format!(
                "It expires in {days} days. Run python3 scripts/setup-alerting-t3.py in selfhosted-platform and push the sealed secret to main; Prism picks it up without a restart."
            ),
            ..Notification::default()
        };
        let _ = pusher.deliver(client, &[n], now).await;
    }
    let sessions = client.list_t3_sessions().await.map_err(|e| e.to_string())?;
    for s in sessions {
        if s.expires_at == 0 || s.expires_at - now > 5 * 86_400 {
            continue;
        }
        let date = chrono::DateTime::<chrono::Utc>::from_timestamp(s.expires_at, 0)
            .map(|dt| dt.format("%Y-%m-%d").to_string())
            .unwrap_or_default();
        let n = Notification {
            kind: "notice".into(),
            key: format!("notice:session-expiry:{}:{}:{day}", s.machine, s.paired_at),
            time: now,
            title: format!("T3 session with {} expires", s.machine),
            body: format!(
                "Prism's T3 session with {} expires on {date}. Create a fresh pairing link on {} and run `prism pair {}` in a prism-worker pod to renew it. Until then agents cannot start there.",
                s.machine, s.machine, s.machine
            ),
            ..Notification::default()
        };
        let _ = pusher.deliver(client, &[n], now).await;
    }
    Ok(())
}

/// Refreshes the replicated `machines` table (config + ClickHouse metrics) and
/// `projects` catalog from reachable T3 hosts.
pub async fn refresh_machines_and_projects(
    client: &PrismClient,
    cfg: &Config,
    t3: &T3Hub,
    secrets: &Secrets,
    now: i64,
) -> Result<(), String> {
    let metrics_by_host: BTreeMap<String, String> =
        match fetch_host_metrics(cfg, secrets, now).await {
            Ok(view) => view
                .hosts
                .into_iter()
                .filter_map(|h| {
                    let name = h.name.clone();
                    serde_json::to_string(&h).ok().map(|j| (name, j))
                })
                .collect(),
            Err(_) => BTreeMap::new(),
        };

    let mut all_names: Vec<String> = cfg.machines.keys().cloned().collect();
    for k in metrics_by_host.keys() {
        if !cfg.machines.contains_key(k) {
            all_names.push(k.clone());
        }
    }
    for name in all_names {
        let m = cfg.machines.get(&name);
        let has_t3 = m.map(|x| x.has_t3()).unwrap_or(false);
        let status = json!({
            "name": name,
            "aliases": m.map(|x| x.aliases.clone()).unwrap_or_default(),
            "description": m.map(|x| x.description.clone()).unwrap_or_default(),
            "t3": has_t3,
            "reachable": has_t3,
            "t3WebOrigin": if has_t3 { cfg.t3_web_origin(&name) } else { String::new() },
        });
        let metrics_json = metrics_by_host.get(&name).cloned().unwrap_or_default();
        let _ = client
            .upsert_machine(&MachineRow {
                name,
                status_json: status.to_string(),
                metrics_json,
                updated_at: now,
            })
            .await;
    }

    // The apps' placement view and models rank the cluster machine's models.
    if !cfg.cluster_machine.is_empty()
        && client
            .get_setting("cluster_machine")
            .await
            .ok()
            .flatten()
            .as_deref()
            != Some(cfg.cluster_machine.as_str())
    {
        let _ = client
            .set_setting("cluster_machine", &cfg.cluster_machine, now)
            .await;
    }

    // Refresh project catalog from direct T3 machines.
    let mut projects_by_id: BTreeMap<String, ProjectRow> = BTreeMap::new();
    for machine in t3.direct_machines().await {
        if let Ok(val) = t3.call(&machine, "", "t3_project_list", json!({})).await
            && let Some(arr) = val.get("projects").and_then(Value::as_array)
        {
            for p in arr {
                let id = p.get("id").and_then(Value::as_str).unwrap_or("");
                let name = p
                    .get("title")
                    .or_else(|| p.get("name"))
                    .and_then(Value::as_str)
                    .unwrap_or("");
                if id.is_empty() || name.is_empty() {
                    continue;
                }
                let pid = format!("host:{machine}/{id}");
                projects_by_id.insert(
                    pid.clone(),
                    ProjectRow {
                        project_id: pid,
                        name: name.to_string(),
                        icon_json: p
                            .get("projectIcon")
                            .map(|v| v.to_string())
                            .unwrap_or_default(),
                        machines_json: json!([machine]).to_string(),
                        repository: false,
                        updated_at: now,
                    },
                );
            }
        }
    }
    if !projects_by_id.is_empty() {
        let rows: Vec<ProjectRow> = projects_by_id.into_values().collect();
        let _ = client.replace_projects(&rows).await;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn req(task: &str, text: &str) -> rocky_prism::AgentRequestRow {
        rocky_prism::AgentRequestRow {
            task: task.into(),
            request_text: text.into(),
            ..Default::default()
        }
    }

    #[tokio::test]
    async fn a_stop_request_interrupts_a_running_agent_through_its_t3() {
        use crate::testutil::{FakeT3, serve};
        let dir = tempfile::tempdir().unwrap();
        let client = PrismClient::open_or_create(dir.path().display().to_string(), None)
            .await
            .unwrap();
        let t3 = FakeT3::default();
        let url = serve(t3.router()).await;
        let cfg = std::sync::Arc::new(
            Config::parse(
                &json!({"cluster_machine": "david-server",
                        "machines": {"david-server": {"t3_url": url}}})
                .to_string(),
            )
            .unwrap(),
        );
        let secrets: Secrets = std::sync::Arc::new(|name: &str| {
            (name == "T3_ACCESS_TOKEN").then(|| "t3-token".to_string())
        });
        let hub = T3Hub::new(cfg, secrets);
        let now = 1_800_000_000;
        for (thread, state) in [("t-run", "active"), ("t-idle", "idle")] {
            client
                .save_agent(&AgentRow {
                    thread_id: thread.into(),
                    client: "david-server".into(),
                    state: state.into(),
                    ..AgentRow::default()
                })
                .await
                .unwrap();
        }
        for (request, thread) in [("s1", "t-run"), ("s2", "t-idle"), ("s3", "t-gone")] {
            client
                .request_agent_stop(request, thread, "david-pixel", now)
                .await
                .unwrap();
        }
        run_agent_messages(&client, &hub, "w0", now, 300)
            .await
            .unwrap();
        let interrupts = t3.interrupts.lock().unwrap().clone();
        assert_eq!(interrupts.len(), 1, "{interrupts:?}");
        assert_eq!(interrupts[0]["threadId"], "t-run");
        assert_eq!(interrupts[0]["stop"], true);
        let outcome = |id: &'static str| {
            let client = &client;
            async move {
                client
                    .request_outcome(rocky_prism::RequestTable::AgentMessages, id)
                    .await
                    .unwrap()
                    .unwrap()
            }
        };
        assert_eq!(outcome("s1").await.error, "");
        assert_eq!(outcome("s2").await.error, "");
        assert_eq!(outcome("s3").await.error, "Agent not found.");
        assert!(outcome("s1").await.done_at.is_some());

        // A placement profile picked as the model launches in that profile.
        hub.launch_agent_thread(
            CLUSTER_TARGET,
            "Do: x",
            "x",
            "k1",
            Some(json!({"instanceId": "placement", "model": "coding"})),
            "",
        )
        .await
        .unwrap();
        let launch = t3.launches.lock().unwrap()[0].clone();
        assert_eq!(launch["placementProfile"], "coding");
        assert!(launch.get("modelSelection").is_none(), "{launch}");
    }

    #[test]
    fn agent_requests_are_checked_like_start_agent() {
        assert!(check_agent_request(&req("fix", "the disk")).is_ok());
        assert!(check_agent_request(&req("rm", "x")).is_err());
        assert!(check_agent_request(&req("do", "   ")).is_err());
        assert!(check_agent_request(&req("do", &"x".repeat(10_001))).is_err());
        assert!(check_agent_request(&req("do", &"x".repeat(10_000))).is_ok());
        assert_eq!(
            agent_title("alert", "disk full", CLUSTER_TARGET),
            "Add alert: disk full"
        );
        assert_eq!(
            agent_title("fix", "brew", "david-mb"),
            "Fix: brew (david-mb)"
        );
    }
}
