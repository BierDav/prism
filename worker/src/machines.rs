//! How the worker reaches each machine (`GET /v1/machines`, what `prism
//! machines` prints), ported from Go's `MachineList` (`routes.go`).
//!
//! A machine is reached directly (the worker has a T3 session with it),
//! through another machine's T3 link, or over SSH from a runner. The worker
//! asks every session's T3 for its links, in parallel.

use crate::auth::is_valid_machine_name;
use crate::config::{CLUSTER_TARGET, Config};
use crate::t3::{EnvironmentLink, Secrets, T3Hub};
use rocky_prism::{AlertRow, PrismClient, T3SessionRow};
use serde::Serialize;
use std::collections::{BTreeMap, BTreeSet};
use std::sync::Arc;

#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct ClusterView {
    pub name: String,
    #[serde(rename = "runsOn")]
    pub runs_on: String,
    pub workspace: String,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct MachineView {
    pub name: String,
    pub aliases: Vec<String>,
    pub description: String,
    pub t3: bool,
    /// direct, link, ssh, none or reports.
    pub route: String,
    pub via: String,
    pub reachable: bool,
    pub state: String,
    pub detail: String,
    #[serde(rename = "sshRunner")]
    pub ssh_runner: bool,
    #[serde(rename = "openAlerts")]
    pub open_alerts: Vec<i64>,
    #[serde(rename = "t3WebOrigin")]
    pub t3_web_origin: String,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize)]
pub struct MachinesView {
    pub cluster: ClusterView,
    pub machines: Vec<MachineView>,
}

/// Each probed machine's links; `None` when its T3 did not answer.
pub type Probes = BTreeMap<String, Option<Vec<EnvironmentLink>>>;

fn is_cluster_client(cfg: &Config, machine: &str) -> bool {
    machine.is_empty() || machine == cfg.cluster_machine
}

/// The machines the worker has a session with: the cluster machine first,
/// then the others by name (Go's `sessionClients`).
pub fn session_clients(cfg: &Config, sessions: &[T3SessionRow]) -> Vec<String> {
    let mut names: BTreeSet<String> = cfg
        .machines
        .iter()
        .filter(|(name, m)| !m.t3_token.is_empty() && !is_cluster_client(cfg, name))
        .map(|(name, _)| name.clone())
        .collect();
    names.extend(
        sessions
            .iter()
            .filter(|s| !is_cluster_client(cfg, &s.machine))
            .map(|s| s.machine.clone()),
    );
    let mut out = Vec::new();
    if !cfg.cluster_machine.is_empty() {
        out.push(cfg.cluster_machine.clone());
    }
    out.extend(names);
    out
}

fn days_until(expires: i64, now: i64) -> i64 {
    // Go: int(duration.Hours() / 24), which truncates toward zero.
    (expires - now) / 86_400
}

fn session_detail(expires_at: i64, now: i64) -> String {
    if expires_at == 0 {
        return String::new();
    }
    match days_until(expires_at, now) {
        d if d < 0 => "session expired".into(),
        d if d < 7 => format!("session expires in {d} days"),
        _ => String::new(),
    }
}

fn link_detail(link: &EnvironmentLink, now: i64) -> String {
    let Some(expires) = chrono::DateTime::parse_from_rfc3339(&link.expires_at)
        .ok()
        .filter(|t| chrono::Datelike::year(t) >= 2000)
    else {
        return String::new();
    };
    match days_until(expires.timestamp(), now) {
        d if d < 0 => "link expired".into(),
        d if d < 7 => format!("link expires in {d} days"),
        _ => String::new(),
    }
}

fn or_unknown(status: &str) -> String {
    if status.is_empty() {
        "unknown".into()
    } else {
        status.to_string()
    }
}

/// The open, numbered host events by number, with their machine label.
pub fn open_host_events(alerts: &[AlertRow]) -> Vec<(i64, String)> {
    let mut open: Vec<(i64, String)> = alerts
        .iter()
        .filter(|a| a.is_open() && a.number != 0)
        .filter_map(|a| {
            let alert = a.alert();
            alert
                .is_host_event()
                .then(|| (a.number, alert.label("machine").to_string()))
        })
        .collect();
    open.sort();
    open
}

/// Builds the view from the probes (Go's `MachineList` after its calls).
pub fn machine_list(
    cfg: &Config,
    sessions: &[T3SessionRow],
    probes: &Probes,
    open_events: &[(i64, String)],
    now: i64,
) -> MachinesView {
    let clients = session_clients(cfg, sessions);
    let links = |machine: &str| probes.get(machine).and_then(Option::as_ref);
    let mut rows: BTreeMap<String, MachineView> = BTreeMap::new();
    let row = |rows: &mut BTreeMap<String, MachineView>, name: &str| {
        if !rows.contains_key(name) {
            let m = cfg
                .machine_by_name(name)
                .map(|(_, m)| m)
                .unwrap_or_default();
            rows.insert(
                name.to_string(),
                MachineView {
                    name: name.to_string(),
                    aliases: m.aliases,
                    description: m.description,
                    ..MachineView::default()
                },
            );
        }
    };
    // The first candidate with a link to the label.
    let linked_by = |label: &str, candidates: &[String]| {
        candidates.iter().find_map(|c| {
            links(c)?
                .iter()
                .find(|l| l.label.eq_ignore_ascii_case(label))
                .map(|l| (c.clone(), l.clone()))
        })
    };
    let candidates_for = |via: &str| {
        if via.is_empty() {
            clients.clone()
        } else {
            vec![via.to_string()]
        }
    };
    for name in &clients {
        row(&mut rows, name);
        let r = rows.get_mut(name).expect("row");
        r.t3 = true;
        r.route = "direct".into();
        (r.state, r.reachable) = if links(name).is_some() {
            ("reachable".into(), true)
        } else {
            ("not reachable".into(), false)
        };
        if let Some(s) = sessions.iter().find(|s| &s.machine == name)
            && !is_cluster_client(cfg, name)
        {
            r.detail = session_detail(s.expires_at, now);
        }
    }
    let mut runner = String::new();
    for name in cfg.ssh_runners() {
        if rows.get(&name).is_some_and(|r| r.reachable) {
            runner = name;
            break;
        }
        if let Some(m) = cfg.machines.get(&name)
            && !rows.contains_key(&name)
            && linked_by(m.link_label(&name), &candidates_for(&m.via))
                .is_some_and(|(_, l)| l.status == "reachable")
        {
            runner = name;
            break;
        }
    }
    for (name, m) in &cfg.machines {
        if rows.contains_key(name) {
            continue;
        }
        row(&mut rows, name);
        let found = if m.has_t3() {
            linked_by(m.link_label(name), &candidates_for(&m.via))
        } else {
            None
        };
        let r = rows.get_mut(name).expect("row");
        r.t3 = m.has_t3();
        if !m.has_t3() && !runner.is_empty() {
            r.route = "ssh".into();
            r.via = runner.clone();
            r.state = format!("over SSH from {runner}");
            r.reachable = true;
        } else if !m.has_t3() {
            r.route = "none".into();
            r.state = "no SSH runner answers".into();
        } else if let Some((via, link)) = found {
            r.route = "link".into();
            r.via = via;
            r.state = or_unknown(&link.status);
            r.detail = link_detail(&link, now);
            r.reachable = link.status == "reachable";
        } else {
            r.route = "none".into();
            r.state = "not paired".into();
        }
    }
    // Machines only a link names.
    for c in &clients {
        for link in links(c).into_iter().flatten() {
            let name = link.label.to_lowercase();
            if cfg.machine_by_name(&name).is_some()
                || rows.contains_key(&name)
                || !is_valid_machine_name(&name)
            {
                continue;
            }
            row(&mut rows, &name);
            let r = rows.get_mut(&name).expect("row");
            r.t3 = true;
            r.route = "link".into();
            r.via = c.clone();
            r.state = or_unknown(&link.status);
            r.detail = link_detail(link, now);
            r.reachable = link.status == "reachable";
        }
    }
    for (number, label) in open_events {
        let name = cfg
            .machine_by_name(label)
            .map(|(k, _)| k)
            .unwrap_or_else(|| label.to_string());
        row(&mut rows, &name);
        let r = rows.get_mut(&name).expect("row");
        if r.route.is_empty() {
            r.route = "reports".into();
        }
        r.open_alerts.push(*number);
    }
    let runners = cfg.ssh_runners();
    let machines = rows
        .into_values()
        .map(|mut r| {
            r.ssh_runner = runners.contains(&r.name);
            if r.t3 && !r.name.is_empty() {
                r.t3_web_origin = cfg.t3_web_origin(&r.name);
            }
            r
        })
        .collect();
    MachinesView {
        cluster: ClusterView {
            name: CLUSTER_TARGET.into(),
            runs_on: cfg.cluster_machine.clone(),
            workspace: cfg.t3_workspace.clone(),
        },
        machines,
    }
}

/// Lists the machines, asking every session's T3 (and each `via`) for its
/// links in parallel.
pub async fn list_machines(
    client: &PrismClient,
    cfg: &Arc<Config>,
    secrets: &Secrets,
    now: i64,
) -> Result<MachinesView, String> {
    let sessions = client.list_t3_sessions().await.map_err(|e| e.to_string())?;
    let alerts = client.alert_rows().await.map_err(|e| e.to_string())?;
    let hub = T3Hub::new(Arc::clone(cfg), Arc::clone(secrets));
    hub.load_paired_sessions(
        &sessions
            .iter()
            .filter(|s| !is_cluster_client(cfg, &s.machine))
            .cloned()
            .collect::<Vec<_>>(),
    );
    let mut targets: BTreeSet<String> = session_clients(cfg, &sessions).into_iter().collect();
    targets.extend(
        cfg.machines
            .values()
            .filter(|m| !m.via.is_empty())
            .map(|m| m.via.clone()),
    );
    let probed = futures_util::future::join_all(targets.iter().map(|machine| {
        let hub = &hub;
        async move { (machine.clone(), hub.environment_links(machine).await.ok()) }
    }))
    .await;
    Ok(machine_list(
        cfg,
        &sessions,
        &probed.into_iter().collect(),
        &open_host_events(&alerts),
        now,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const NOW: i64 = 1_800_000_000;

    fn link(label: &str, status: &str, expires_at: &str) -> EnvironmentLink {
        serde_json::from_value(
            json!({"environmentId": format!("env-{label}"), "label": label,
            "status": status, "expiresAt": expires_at}),
        )
        .expect("link")
    }

    #[test]
    fn every_route_go_lists() {
        let cfg = Config::parse(
            r#"{"cluster_machine": "david-server", "t3_workspace": "/srv/selfhosted-platform",
                "machines": {
                  "david-server": {"t3_url": "http://10.0.0.1:3773"},
                  "david-mb": {"aliases": ["mb"], "description": "the laptop"},
                  "david-mm": {"environment": "Office"},
                  "ccs-oracle-1": {"t3": false},
                  "offline-box": {}
                }}"#,
        )
        .expect("config");
        let sessions = vec![T3SessionRow {
            machine: "david-mb".into(),
            origin: "http://100.64.0.2:3773".into(),
            token: "t".into(),
            expires_at: NOW + 3 * 86_400 + 60,
            ..T3SessionRow::default()
        }];
        let soon = chrono::DateTime::from_timestamp(NOW + 2 * 86_400 + 60, 0)
            .unwrap()
            .to_rfc3339();
        let probes: Probes = [
            (
                "david-server".to_string(),
                Some(vec![
                    link("office", "reachable", &soon),
                    link("Stray-Box", "offline", ""),
                ]),
            ),
            ("david-mb".to_string(), None),
        ]
        .into_iter()
        .collect();
        let alerts = vec![(2, "mb".to_string()), (4, "reporter".to_string())];
        let view = machine_list(&cfg, &sessions, &probes, &alerts, NOW);
        assert_eq!(
            view.cluster,
            ClusterView {
                name: "cluster".into(),
                runs_on: "david-server".into(),
                workspace: "/srv/selfhosted-platform".into()
            }
        );
        let lines: Vec<String> = view
            .machines
            .iter()
            .map(|m| {
                format!(
                    "{} {} via={} {} [{}] t3={} reach={} runner={} open={:?} web={}",
                    m.name,
                    m.route,
                    m.via,
                    m.state,
                    m.detail,
                    m.t3,
                    m.reachable,
                    m.ssh_runner,
                    m.open_alerts,
                    m.t3_web_origin
                )
            })
            .collect();
        assert_eq!(
            lines,
            vec![
                "ccs-oracle-1 ssh via=david-server over SSH from david-server [] t3=false reach=true runner=false open=[] web=",
                "david-mb direct via= not reachable [session expires in 3 days] t3=true reach=false runner=false open=[2] web=https://david-mb.netbird.cloud:3774",
                "david-mm link via=david-server reachable [link expires in 2 days] t3=true reach=true runner=false open=[] web=https://david-mm.netbird.cloud:3774",
                "david-server direct via= reachable [] t3=true reach=true runner=true open=[] web=https://david-server.netbird.cloud:3774",
                // As in Go: a link label that is only an `environment` is its own row too.
                "office link via=david-server reachable [link expires in 2 days] t3=true reach=true runner=false open=[] web=https://office.netbird.cloud:3774",
                "offline-box none via= not paired [] t3=true reach=false runner=false open=[] web=https://offline-box.netbird.cloud:3774",
                "reporter reports via=  [] t3=false reach=false runner=false open=[4] web=",
                "stray-box link via=david-server offline [] t3=true reach=false runner=false open=[] web=https://stray-box.netbird.cloud:3774",
            ]
        );
        assert_eq!(view.machines[1].aliases, vec!["mb".to_string()]);
        assert_eq!(view.machines[1].description, "the laptop");
        let wire = serde_json::to_value(&view.machines[0]).expect("json");
        for key in [
            "name",
            "aliases",
            "description",
            "t3",
            "route",
            "via",
            "reachable",
            "state",
            "detail",
            "sshRunner",
            "openAlerts",
            "t3WebOrigin",
        ] {
            assert!(wire.get(key).is_some(), "{key} in {wire}");
        }
    }

    #[test]
    fn without_a_runner_machines_without_t3_have_no_route() {
        let cfg =
            Config::parse(r#"{"machines": {"ccs-oracle-1": {"t3": false}}}"#).expect("config");
        let view = machine_list(&cfg, &[], &Probes::new(), &[], NOW);
        assert_eq!(view.machines[0].route, "none");
        assert_eq!(view.machines[0].state, "no SSH runner answers");
        assert_eq!(session_detail(NOW - 1, NOW), "session expires in 0 days");
        assert_eq!(session_detail(NOW + 7 * 86_400, NOW), "");
        assert_eq!(session_detail(NOW - 86_400 - 1, NOW), "session expired");
    }
}
