//! Host metrics from ClickHouse's PromQL API (`observability.metrics`),
//! ported from the Go server's `metrics.go`.

use crate::config::Config;
use crate::t3::Secrets;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::BTreeMap;
use std::time::Duration;

const METRICS_ONLINE_WINDOW_SECS: i64 = 3 * 60;
const METRICS_HISTORY_SECS: i64 = 2 * 60 * 60;
const METRICS_STEP_SECS: i64 = 2 * 60;

const PROM_CPU: &str = r#"1 - sum by (instance) (rate(node_cpu_seconds_total{mode="idle"}[5m])) / sum by (instance) (rate(node_cpu_seconds_total[5m]))"#;
const PROM_CORES: &str = r#"count by (instance) (node_cpu_seconds_total{mode="idle"})"#;
const PROM_MEMORY: &str = r#"1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes or (node_memory_internal_bytes - node_memory_purgeable_bytes + node_memory_wired_bytes + node_memory_compressed_bytes) / node_memory_total_bytes"#;
const PROM_LAST_SEEN: &str = r#"max by (instance) (last_over_time(node_time_seconds[7d]))"#;
const PROM_GAUGES: &str = r#"{__name__=~"node_load(1|5|15)|node_boot_time_seconds|node_memory_(MemTotal|MemAvailable|SwapTotal|SwapFree|total|internal|purgeable|wired|compressed|swap_total|swap_used)_bytes|node_power_supply_(current_capacity|charging)|node_filesystem_(size|avail)_bytes"}"#;
const PROM_UNAME: &str = "node_uname_info";
const NET_DEVICES: &str = r#"device!~"lo|lo0|wg.*|nb-.*|wt[0-9]*|tun.*|utun.*|tailscale.*|veth.*|docker.*|br-.*|cilium.*|lxc.*|flannel.*""#;

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct Usage {
    pub used: i64,
    pub total: i64,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct Filesystem {
    pub mount: String,
    pub used: i64,
    pub total: i64,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct NetworkRate {
    pub receive: f64,
    pub transmit: f64,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct Battery {
    pub level: f64,
    pub charging: bool,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct HostHistory {
    pub start: i64,
    pub step: i64,
    pub cpu: Vec<Option<f64>>,
    pub memory: Vec<Option<f64>>,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostMetrics {
    pub name: String,
    pub online: bool,
    pub last_seen: i64,
    pub os: String,
    pub arch: String,
    pub boot_time: i64,
    pub cpu: Option<f64>,
    pub cores: i64,
    pub load: Vec<f64>,
    pub memory: Option<Usage>,
    pub swap: Option<Usage>,
    pub disk: Option<Filesystem>,
    pub filesystems: Vec<Filesystem>,
    pub network: Option<NetworkRate>,
    pub battery: Option<Battery>,
    pub history: HostHistory,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct HostMetricsView {
    pub time: i64,
    pub hosts: Vec<HostMetrics>,
}

#[derive(Deserialize)]
struct PromResponse {
    status: String,
    #[serde(default)]
    error: String,
    #[serde(default)]
    data: PromData,
}

#[derive(Default, Deserialize)]
struct PromData {
    #[serde(default)]
    result: Vec<PromSeries>,
}

#[derive(Deserialize)]
struct PromSeries {
    #[serde(default)]
    metric: BTreeMap<String, String>,
    #[serde(default)]
    value: Vec<Value>,
    #[serde(default)]
    values: Vec<Vec<Value>>,
}

fn prom_point(p: &[Value]) -> Option<(i64, f64)> {
    if p.len() != 2 {
        return None;
    }
    let ts = p[0].as_f64()?;
    let val: f64 = p[1].as_str()?.parse().ok()?;
    if val.is_nan() || val.is_infinite() {
        return None;
    }
    Some((ts.round() as i64, val))
}

async fn prom_query(
    http: &reqwest::Client,
    cfg: &Config,
    secrets: &Secrets,
    api: &str,
    params: Vec<(&str, String)>,
) -> Result<Vec<PromSeries>, String> {
    let base = cfg.metrics_url.trim_end_matches('/');
    let mut url = url::Url::parse(&format!("{base}/api/v1/{api}")).map_err(|e| e.to_string())?;
    {
        let mut pairs = url.query_pairs_mut();
        for (k, v) in &params {
            pairs.append_pair(k, v);
        }
    }
    let mut req = http.get(url);
    if let Some(password) = secrets("PRISM_METRICS_PASSWORD")
        && !password.is_empty()
    {
        req = req.basic_auth(&cfg.metrics_user, Some(password));
    }
    let resp = req
        .send()
        .await
        .map_err(|e| format!("metrics {api}: {e}"))?;
    if !resp.status().is_success() {
        return Err(format!("metrics {api}: HTTP {}", resp.status()));
    }
    let out: PromResponse = resp
        .json()
        .await
        .map_err(|e| format!("metrics {api}: {e}"))?;
    if out.status != "success" {
        return Err(format!("metrics {api}: {}", out.error));
    }
    Ok(out.data.result)
}

pub async fn fetch_host_metrics(
    cfg: &Config,
    secrets: &Secrets,
    now: i64,
) -> Result<HostMetricsView, String> {
    if cfg.metrics_url.is_empty() {
        return Err("Host metrics are not configured.".into());
    }
    let http = reqwest::Client::builder()
        .timeout(Duration::from_secs(20))
        .build()
        .map_err(|e| e.to_string())?;

    let at = now.to_string();
    let step = METRICS_STEP_SECS;
    let end = (now / step) * step;
    let start = end - METRICS_HISTORY_SECS;
    let points = ((end - start) / step + 1) as usize;

    let prom_rx =
        format!("sum by (instance) (rate(node_network_receive_bytes_total{{{NET_DEVICES}}}[5m]))");
    let prom_tx =
        format!("sum by (instance) (rate(node_network_transmit_bytes_total{{{NET_DEVICES}}}[5m]))");

    let (last_seen, cpu, cores, gauges, uname, rx, tx, cpu_hist, mem_hist) = tokio::try_join!(
        prom_query(
            &http,
            cfg,
            secrets,
            "query",
            vec![("query", PROM_LAST_SEEN.into()), ("time", at.clone())]
        ),
        prom_query(
            &http,
            cfg,
            secrets,
            "query",
            vec![("query", PROM_CPU.into()), ("time", at.clone())]
        ),
        prom_query(
            &http,
            cfg,
            secrets,
            "query",
            vec![("query", PROM_CORES.into()), ("time", at.clone())]
        ),
        prom_query(
            &http,
            cfg,
            secrets,
            "query",
            vec![("query", PROM_GAUGES.into()), ("time", at.clone())]
        ),
        prom_query(
            &http,
            cfg,
            secrets,
            "query",
            vec![("query", PROM_UNAME.into()), ("time", at.clone())]
        ),
        prom_query(
            &http,
            cfg,
            secrets,
            "query",
            vec![("query", prom_rx), ("time", at.clone())]
        ),
        prom_query(
            &http,
            cfg,
            secrets,
            "query",
            vec![("query", prom_tx), ("time", at.clone())]
        ),
        prom_query(
            &http,
            cfg,
            secrets,
            "query_range",
            vec![
                ("query", PROM_CPU.into()),
                ("start", start.to_string()),
                ("end", end.to_string()),
                ("step", step.to_string()),
            ]
        ),
        prom_query(
            &http,
            cfg,
            secrets,
            "query_range",
            vec![
                ("query", PROM_MEMORY.into()),
                ("start", start.to_string()),
                ("end", end.to_string()),
                ("step", step.to_string()),
            ]
        ),
    )?;

    let mut hosts: BTreeMap<String, HostMetrics> = BTreeMap::new();
    fn ensure_host<'a>(
        hosts: &'a mut BTreeMap<String, HostMetrics>,
        name: &str,
        start: i64,
        step: i64,
        points: usize,
    ) -> &'a mut HostMetrics {
        hosts
            .entry(name.to_string())
            .or_insert_with(|| HostMetrics {
                name: name.to_string(),
                history: HostHistory {
                    start,
                    step,
                    cpu: vec![None; points],
                    memory: vec![None; points],
                },
                ..Default::default()
            })
    }

    for s in last_seen {
        if let (Some(inst), Some((_, val))) = (s.metric.get("instance"), prom_point(&s.value)) {
            let h = ensure_host(&mut hosts, inst, start, step, points);
            h.last_seen = val.round() as i64;
            h.online = now - h.last_seen <= METRICS_ONLINE_WINDOW_SECS;
        }
    }

    for s in uname {
        if let Some(inst) = s.metric.get("instance") {
            let h = ensure_host(&mut hosts, inst, start, step, points);
            let sys = s.metric.get("sysname").map(String::as_str).unwrap_or("");
            let rel = s.metric.get("release").map(String::as_str).unwrap_or("");
            h.os = format!("{sys} {rel}").trim().to_string();
            h.arch = s.metric.get("machine").cloned().unwrap_or_default();
        }
    }

    for s in cpu {
        if let (Some(inst), Some((_, val))) = (s.metric.get("instance"), prom_point(&s.value)) {
            let h = ensure_host(&mut hosts, inst, start, step, points);
            if h.online {
                h.cpu = Some(val.clamp(0.0, 1.0));
            }
        }
    }

    for s in cores {
        if let (Some(inst), Some((_, val))) = (s.metric.get("instance"), prom_point(&s.value)) {
            let h = ensure_host(&mut hosts, inst, start, step, points);
            h.cores = val.round() as i64;
        }
    }

    // Gauges: load, boot time, memory, swap, battery, filesystems
    let mut gauge_vals: BTreeMap<String, BTreeMap<String, f64>> = BTreeMap::new();
    let mut fs_size: BTreeMap<(String, String), i64> = BTreeMap::new();
    let mut fs_avail: BTreeMap<(String, String), i64> = BTreeMap::new();

    for s in gauges {
        let Some(inst) = s.metric.get("instance") else {
            continue;
        };
        let Some(name) = s.metric.get("__name__") else {
            continue;
        };
        let Some((_, val)) = prom_point(&s.value) else {
            continue;
        };
        if name == "node_filesystem_size_bytes" || name == "node_filesystem_avail_bytes" {
            if let Some(mount) = s.metric.get("mountpoint") {
                if name == "node_filesystem_size_bytes" {
                    fs_size.insert((inst.clone(), mount.clone()), val.round() as i64);
                } else {
                    fs_avail.insert((inst.clone(), mount.clone()), val.round() as i64);
                }
            }
            continue;
        }
        gauge_vals
            .entry(inst.clone())
            .or_default()
            .insert(name.clone(), val);
    }

    for (inst, g) in &gauge_vals {
        let h = ensure_host(&mut hosts, inst, start, step, points);
        if let Some(&bt) = g.get("node_boot_time_seconds") {
            h.boot_time = bt.round() as i64;
        }
        if !h.online {
            continue;
        }
        if let (Some(&l1), Some(&l5), Some(&l15)) = (
            g.get("node_load1"),
            g.get("node_load5"),
            g.get("node_load15"),
        ) {
            h.load = vec![l1, l5, l15];
        }
        if let (Some(&total), Some(&avail)) = (
            g.get("node_memory_MemTotal_bytes"),
            g.get("node_memory_MemAvailable_bytes"),
        ) && total > 0.0
        {
            h.memory = Some(Usage {
                used: (total - avail).max(0.0).round() as i64,
                total: total.round() as i64,
            });
        } else if let Some(&total) = g.get("node_memory_total_bytes")
            && total > 0.0
        {
            let internal = g.get("node_memory_internal_bytes").copied().unwrap_or(0.0);
            let purgeable = g.get("node_memory_purgeable_bytes").copied().unwrap_or(0.0);
            let wired = g.get("node_memory_wired_bytes").copied().unwrap_or(0.0);
            let compressed = g
                .get("node_memory_compressed_bytes")
                .copied()
                .unwrap_or(0.0);
            h.memory = Some(Usage {
                used: (internal - purgeable + wired + compressed).max(0.0).round() as i64,
                total: total.round() as i64,
            });
        }
        if let (Some(&total), Some(&free)) = (
            g.get("node_memory_SwapTotal_bytes"),
            g.get("node_memory_SwapFree_bytes"),
        ) && total > 0.0
        {
            h.swap = Some(Usage {
                used: (total - free).max(0.0).round() as i64,
                total: total.round() as i64,
            });
        } else if let (Some(&total), Some(&used)) = (
            g.get("node_memory_swap_total_bytes"),
            g.get("node_memory_swap_used_bytes"),
        ) && total > 0.0
        {
            h.swap = Some(Usage {
                used: used.max(0.0).round() as i64,
                total: total.round() as i64,
            });
        }
        if let Some(&cap) = g.get("node_power_supply_current_capacity") {
            let charging = g.get("node_power_supply_charging").copied().unwrap_or(0.0) > 0.5;
            h.battery = Some(Battery {
                level: (cap / 100.0).clamp(0.0, 1.0),
                charging,
            });
        }
    }

    // Filesystems (>= 1 GiB)
    const ONE_GIB: i64 = 1 << 30;
    for ((inst, mount), total) in &fs_size {
        if *total < ONE_GIB {
            continue;
        }
        let avail = fs_avail
            .get(&(inst.clone(), mount.clone()))
            .copied()
            .unwrap_or(0);
        let h = ensure_host(&mut hosts, inst, start, step, points);
        if !h.online {
            continue;
        }
        let fs = Filesystem {
            mount: mount.clone(),
            used: (*total - avail).max(0),
            total: *total,
        };
        if mount == "/" || mount == "/System/Volumes/Data" {
            h.disk = Some(fs.clone());
        }
        h.filesystems.push(fs);
    }

    for s in rx {
        if let (Some(inst), Some((_, val))) = (s.metric.get("instance"), prom_point(&s.value)) {
            let h = ensure_host(&mut hosts, inst, start, step, points);
            if h.online {
                h.network.get_or_insert_with(Default::default).receive = val.max(0.0);
            }
        }
    }
    for s in tx {
        if let (Some(inst), Some((_, val))) = (s.metric.get("instance"), prom_point(&s.value)) {
            let h = ensure_host(&mut hosts, inst, start, step, points);
            if h.online {
                h.network.get_or_insert_with(Default::default).transmit = val.max(0.0);
            }
        }
    }

    for s in cpu_hist {
        if let Some(inst) = s.metric.get("instance") {
            let h = ensure_host(&mut hosts, inst, start, step, points);
            for p in &s.values {
                if let Some((ts, val)) = prom_point(p)
                    && ts >= start
                    && ts <= end
                {
                    let idx = ((ts - start) / step) as usize;
                    if idx < h.history.cpu.len() {
                        h.history.cpu[idx] = Some(val.clamp(0.0, 1.0));
                    }
                }
            }
        }
    }
    for s in mem_hist {
        if let Some(inst) = s.metric.get("instance") {
            let h = ensure_host(&mut hosts, inst, start, step, points);
            for p in &s.values {
                if let Some((ts, val)) = prom_point(p)
                    && ts >= start
                    && ts <= end
                {
                    let idx = ((ts - start) / step) as usize;
                    if idx < h.history.memory.len() {
                        h.history.memory[idx] = Some(val.clamp(0.0, 1.0));
                    }
                }
            }
        }
    }

    Ok(HostMetricsView {
        time: now,
        hosts: hosts.into_values().collect(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{Router, extract::Query, routing::get};
    use serde_json::json;
    use std::sync::Arc;
    use tokio::net::TcpListener;

    #[tokio::test]
    async fn fetches_and_shapes_host_metrics() {
        let app = Router::new()
            .route(
                "/api/v1/query",
                get(|Query(q): Query<BTreeMap<String, String>>| async move {
                    let expr = q.get("query").map(String::as_str).unwrap_or("");
                    let result = if expr.contains("node_time_seconds") {
                        json!([{"metric": {"instance": "david-mb"}, "value": [1000, "995"]}])
                    } else if expr.contains("node_cpu_seconds_total") && expr.contains("count") {
                        json!([{"metric": {"instance": "david-mb"}, "value": [1000, "16"]}])
                    } else if expr.contains("node_cpu_seconds_total") {
                        json!([{"metric": {"instance": "david-mb"}, "value": [1000, "0.25"]}])
                    } else if expr.contains("node_uname_info") {
                        json!([{"metric": {"instance": "david-mb", "sysname": "Darwin", "release": "25.0", "machine": "arm64"}, "value": [1000, "1"]}])
                    } else {
                        json!([])
                    };
                    axum::Json(json!({"status": "success", "data": {"result": result}}))
                }),
            )
            .route(
                "/api/v1/query_range",
                get(|| async {
                    axum::Json(json!({"status": "success", "data": {"result": []}}))
                }),
            );
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let addr = listener.local_addr().expect("addr");
        tokio::spawn(async move {
            let _ = axum::serve(listener, app).await;
        });

        let mut cfg = Config::parse("{}").expect("cfg");
        cfg.metrics_url = format!("http://{addr}");
        let secrets: Secrets = Arc::new(|_| None);
        let view = fetch_host_metrics(&cfg, &secrets, 1000)
            .await
            .expect("metrics");
        assert_eq!(view.hosts.len(), 1);
        assert_eq!(view.hosts[0].name, "david-mb");
        assert!(view.hosts[0].online);
        assert_eq!(view.hosts[0].cores, 16);
        assert_eq!(view.hosts[0].cpu, Some(0.25));
        assert_eq!(view.hosts[0].os, "Darwin 25.0");
    }
}
