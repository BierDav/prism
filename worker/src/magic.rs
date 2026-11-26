//! Assistant magic picks: `"auto"` target (least-loaded strongest tier) and
//! `"auto"` project (OpenAI-compatible chat completion), ported from
//! the Go server's `magic.go`.

use crate::config::{CLUSTER_TARGET, Config};
use crate::metrics::{HostMetrics, fetch_host_metrics};
use crate::t3::Secrets;
use rocky_prism::{PrismClient, ProjectRow};
use serde::Deserialize;
use serde_json::json;
use std::collections::{BTreeMap, BTreeSet};
use std::time::Duration;

pub const AUTO_CHOICE: &str = "auto";

#[derive(Clone, Debug)]
pub struct HostLoad {
    pub name: String,
    pub cores: i64,
    pub mem_gib: f64,
    pub cpu: f64,
    pub mem: f64,
}

impl HostLoad {
    fn tier(&self) -> i32 {
        let p = (self.cores as f64) * self.mem_gib;
        if p < 2.0 { 0 } else { p.log2() as i32 }
    }

    fn light(&self) -> bool {
        self.cpu < 0.6 && self.mem < 0.85
    }

    fn load(&self) -> f64 {
        self.cpu + self.mem / 2.0
    }
}

/// Picks the strongest tier with a lightly used machine and, in it, the least
/// used one; when every machine is busy, the least used.
pub fn pick_by_load(hosts: &[HostLoad]) -> Option<String> {
    let mut best: Option<&HostLoad> = None;
    for h in hosts {
        let Some(b) = best else {
            best = Some(h);
            continue;
        };
        let better = if h.light() != b.light() {
            h.light()
        } else if h.light() && h.tier() != b.tier() {
            h.tier() > b.tier()
        } else {
            h.load() < b.load()
        };
        if better {
            best = Some(h);
        }
    }
    best.map(|h| h.name.clone())
}

/// Picks the best machine using live ClickHouse host metrics and the
/// replicated `machines` table, or `"cluster"` if metrics are unavailable.
pub async fn pick_machine(
    client: &PrismClient,
    cfg: &Config,
    secrets: &Secrets,
    now: i64,
) -> String {
    let Ok(machines) = client.list_machines().await else {
        return CLUSTER_TARGET.into();
    };
    let live_metrics: BTreeMap<String, HostMetrics> = if cfg.metrics_url.is_empty() {
        BTreeMap::new()
    } else {
        match fetch_host_metrics(cfg, secrets, now).await {
            Ok(view) => view
                .hosts
                .into_iter()
                .map(|h| (h.name.clone(), h))
                .collect(),
            Err(_) => BTreeMap::new(),
        }
    };
    let mut hosts = Vec::new();
    for m in machines {
        let Ok(status) = serde_json::from_str::<serde_json::Value>(&m.status_json) else {
            continue;
        };
        let has_t3 = status.get("t3").and_then(|v| v.as_bool()).unwrap_or(false);
        let reachable = status
            .get("reachable")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
        if !has_t3 || !reachable {
            continue;
        }
        let Some(hm) = live_metrics.get(&m.name).cloned().or_else(|| {
            (!m.metrics_json.is_empty())
                .then(|| serde_json::from_str::<HostMetrics>(&m.metrics_json).ok())
                .flatten()
        }) else {
            continue;
        };
        if !hm.online {
            continue;
        }
        let (Some(cpu), Some(mem)) = (hm.cpu, hm.memory) else {
            continue;
        };
        if mem.total <= 0 {
            continue;
        }
        hosts.push(HostLoad {
            name: m.name,
            cores: hm.cores,
            mem_gib: (mem.total as f64) / ((1_i64 << 30) as f64),
            cpu,
            mem: (mem.used as f64) / (mem.total as f64),
        });
    }
    match pick_by_load(&hosts) {
        Some(name) if name != cfg.cluster_machine => name,
        _ => CLUSTER_TARGET.into(),
    }
}

#[derive(Deserialize)]
struct ChatResponse {
    #[serde(default)]
    choices: Vec<ChatChoice>,
}

#[derive(Deserialize)]
struct ChatChoice {
    #[serde(default)]
    message: ChatMessage,
}

#[derive(Default, Deserialize)]
struct ChatMessage {
    #[serde(default)]
    content: String,
}

/// Asks the configured OpenAI-compatible text-generation model which project a
/// request is about. Returns `Ok(project_id)` or `Ok("")` for scratch (`none`).
pub async fn pick_project(
    cfg: &Config,
    secrets: &Secrets,
    catalog: &[ProjectRow],
    request: &str,
) -> Result<String, String> {
    let tg = &cfg.text_generation;
    if tg.url.is_empty() || tg.model.is_empty() {
        return Err(
            "Picking the project needs text generation on the Prism server. Choose a project."
                .into(),
        );
    }
    let mut list = String::new();
    for p in catalog {
        if p.project_id.is_empty() {
            continue;
        }
        let machines: Vec<String> = serde_json::from_str(&p.machines_json).unwrap_or_default();
        list.push_str(&format!(
            "{}\t{}\t{}\n",
            p.project_id,
            p.name,
            machines.join(", ")
        ));
    }

    let system = "You route a task to the software project it is about. Reply with exactly one project ID (the first column of the list) as written, and nothing else: no name, no machines. Reply none when the task is about no project in the list.";
    let user = format!("Projects (ID, name, machines):\n{list}\nTask:\n{request}");

    let http = reqwest::Client::builder()
        .timeout(Duration::from_secs(45))
        .build()
        .map_err(|e| e.to_string())?;
    let mut req = http
        .post(format!("{}/chat/completions", tg.url.trim_end_matches('/')))
        .json(&json!({
            "model": tg.model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
        }));
    if let Some(key) = secrets("PRISM_TEXT_GENERATION_KEY")
        && !key.is_empty()
    {
        req = req.bearer_auth(key);
    }
    let resp = req.send().await.map_err(|_| {
        "The model that picks the project did not answer. Choose a project.".to_string()
    })?;
    if !resp.status().is_success() {
        return Err("The model that picks the project did not answer. Choose a project.".into());
    }
    let out: ChatResponse = resp.json().await.map_err(|_| {
        "The model that picks the project did not answer. Choose a project.".to_string()
    })?;
    let answer = out
        .choices
        .first()
        .map(|c| c.message.content.as_str())
        .unwrap_or("");

    let words: Vec<&str> = answer
        .split([' ', '\t', '\r', '\n', '`', '"', '\'', ',', ';'])
        .filter(|w| !w.is_empty())
        .collect();
    if let Some(first) = words.first()
        && first.trim_matches('.').eq_ignore_ascii_case("none")
    {
        return Ok(String::new());
    }
    let ids: BTreeSet<&str> = catalog
        .iter()
        .filter(|p| !p.project_id.is_empty())
        .map(|p| p.project_id.as_str())
        .collect();
    for w in &words {
        let trimmed = w.trim_end_matches('.');
        if ids.contains(trimmed) {
            return Ok(trimmed.to_string());
        }
    }
    let name = answer
        .trim()
        .trim_matches(|c: char| matches!(c, '`' | '"' | '\'' | ' ' | '.'));
    for p in catalog {
        if !p.project_id.is_empty() && p.name.eq_ignore_ascii_case(name) {
            return Ok(p.project_id.clone());
        }
    }
    Err("The request did not tell which project it is about. Choose a project.".into())
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{Router, routing::post};
    use std::sync::Arc;
    use tokio::net::TcpListener;

    #[test]
    fn picks_strongest_lightly_used_tier() {
        let hosts = vec![
            HostLoad {
                name: "small".into(),
                cores: 4,
                mem_gib: 8.0,
                cpu: 0.05,
                mem: 0.10,
            },
            HostLoad {
                name: "big-busy".into(),
                cores: 16,
                mem_gib: 64.0,
                cpu: 0.90,
                mem: 0.50,
            },
            HostLoad {
                name: "big-light".into(),
                cores: 16,
                mem_gib: 64.0,
                cpu: 0.20,
                mem: 0.30,
            },
        ];
        assert_eq!(pick_by_load(&hosts).as_deref(), Some("big-light"));
    }

    #[tokio::test]
    async fn picks_project_from_fake_llm() {
        let app = Router::new().route(
            "/chat/completions",
            post(|| async {
                axum::Json(json!({
                    "choices": [{"message": {"content": "repo:gitea.quickme.at/david/prism"}}]
                }))
            }),
        );
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let addr = listener.local_addr().expect("addr");
        tokio::spawn(async move {
            let _ = axum::serve(listener, app).await;
        });

        let mut cfg = Config::parse("{}").expect("cfg");
        cfg.text_generation.url = format!("http://{addr}");
        cfg.text_generation.model = "test-model".into();
        let secrets: Secrets = Arc::new(|_| Some("key".into()));
        let catalog = vec![ProjectRow {
            project_id: "repo:gitea.quickme.at/david/prism".into(),
            name: "prism".into(),
            icon_json: String::new(),
            machines_json: r#"["david-mb"]"#.into(),
            repository: true,
            updated_at: 100,
        }];
        let picked = pick_project(&cfg, &secrets, &catalog, "fix prism worker")
            .await
            .expect("pick");
        assert_eq!(picked, "repo:gitea.quickme.at/david/prism");
    }
}
