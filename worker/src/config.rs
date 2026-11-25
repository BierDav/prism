//! The worker's part of the Go server's config file (`PRISM_CONFIG`, JSON).
//! Field names and defaults are the Go server's (`config.go`),
//! so the same file and manifests carry over; keys this phase does not use
//! are ignored.

use crate::auth::is_valid_machine_name;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

pub const CLUSTER_TARGET: &str = "cluster";
const DEFAULT_CONFIG_PATH: &str = "/config/config.json";

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
pub struct PushConfig {
    #[serde(default)]
    pub url: String,
    #[serde(default)]
    pub topic: String,
}

#[derive(Clone, Debug, Default, PartialEq, Deserialize, Serialize)]
pub struct TextGenerationConfig {
    #[serde(default)]
    pub url: String,
    #[serde(default)]
    pub model: String,
}

#[derive(Clone, Debug, Default, PartialEq, Deserialize, Serialize)]
pub struct DeviceConfig {
    #[serde(default)]
    pub key: String,
}

#[derive(Clone, Debug, Default, PartialEq, Deserialize, Serialize)]
pub struct MachineConfig {
    #[serde(default)]
    pub aliases: Vec<String>,
    /// The machine's T3 environment label; defaults to its name.
    #[serde(default)]
    pub environment: String,
    /// The origin of the machine's T3, e.g. http://10.250.0.1:3773 (no /mcp).
    #[serde(default)]
    pub t3_url: String,
    /// The secret (NAME or NAME_FILE) holding Prism's session with this T3.
    #[serde(default)]
    pub t3_token: String,
    /// false marks a machine without T3, worked on over SSH from a runner.
    #[serde(default)]
    pub t3: Option<bool>,
    /// The machine whose T3 links this one.
    #[serde(default)]
    pub via: String,
    /// The former name of cluster_machine.
    #[serde(default)]
    pub hub: bool,
    #[serde(default)]
    pub workspace: String,
    #[serde(default)]
    pub description: String,
}

impl MachineConfig {
    pub fn has_t3(&self) -> bool {
        self.t3.unwrap_or(true)
    }

    pub fn link_label<'a>(&'a self, name: &'a str) -> &'a str {
        if self.environment.is_empty() {
            name
        } else {
            &self.environment
        }
    }
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
pub struct Config {
    #[serde(default)]
    pub push: PushConfig,
    #[serde(default)]
    pub cluster_machine: String,
    #[serde(default)]
    pub ssh_runners: Vec<String>,
    #[serde(default)]
    pub ssh_user: String,
    /// The cluster machine's MCP endpoint when it has no t3_url (old configs).
    #[serde(default)]
    pub t3_url: String,
    #[serde(default)]
    pub t3_workspace: String,
    #[serde(default)]
    pub alertmanager_url: String,
    #[serde(default)]
    pub machines: BTreeMap<String, MachineConfig>,
    #[serde(default)]
    pub machine_instructions: String,
    #[serde(default)]
    pub reconcile_grace_seconds: i64,
    #[serde(default)]
    pub reconcile_uptime_seconds: i64,
    #[serde(default)]
    pub netbird_domain: String,
    /// Where each machine serves T3's web app over NetBird (default 3774).
    #[serde(default)]
    pub t3_web_port: u16,
    #[serde(default)]
    pub metrics_url: String,
    #[serde(default)]
    pub metrics_user: String,
    /// Where the phone's metrics go (`POST /v1/telemetry/write`): a
    /// Prometheus remote write URL, with basic auth `telemetry_user` and the
    /// secret PRISM_TELEMETRY_PASSWORD. Empty turns the route off.
    #[serde(default)]
    pub telemetry_url: String,
    #[serde(default)]
    pub telemetry_user: String,
    /// ClickHouse's HTTP interface, where the workers write their placement
    /// decisions (`observability.placement_decisions`, `placement_log.rs`),
    /// with the telemetry credential. Empty turns the log off.
    #[serde(default)]
    pub placement_log_url: String,
    #[serde(default)]
    pub text_generation: TextGenerationConfig,
    #[serde(default)]
    pub android_publishers: Vec<String>,
    #[serde(default)]
    pub devices: BTreeMap<String, DeviceConfig>,
}

impl Config {
    /// Reads `PRISM_CONFIG` (default /config/config.json). Without the
    /// variable and without the default file the worker runs with defaults.
    pub fn load_from_env() -> Result<Self, String> {
        match std::env::var("PRISM_CONFIG").ok().filter(|p| !p.is_empty()) {
            Some(path) => Self::load(&path),
            None if std::path::Path::new(DEFAULT_CONFIG_PATH).exists() => {
                Self::load(DEFAULT_CONFIG_PATH)
            }
            None => Self::parse("{}"),
        }
    }

    pub fn load(path: &str) -> Result<Self, String> {
        let raw = std::fs::read_to_string(path).map_err(|err| format!("{path}: {err}"))?;
        Self::parse(&raw).map_err(|err| format!("{path}: {err}"))
    }

    pub fn parse(raw: &str) -> Result<Self, String> {
        let mut c: Config = serde_json::from_str(raw).map_err(|err| err.to_string())?;
        c.apply_defaults();
        c.validate()?;
        Ok(c)
    }

    fn apply_defaults(&mut self) {
        if self.push.url.is_empty() {
            self.push.url = "https://ntfy.quickme.at".into();
        }
        if self.push.topic.is_empty() {
            self.push.topic = "prism-events".into();
        }
        if self.cluster_machine.is_empty()
            && let Some((name, _)) = self.machines.iter().find(|(_, m)| m.hub)
        {
            self.cluster_machine = name.clone();
        }
        if self.reconcile_grace_seconds == 0 {
            self.reconcile_grace_seconds = 180;
        }
        if self.reconcile_uptime_seconds == 0 {
            // Directly reported CronJob alerts reappear within one schedule
            // (20 minutes) after an Alertmanager restart empties its memory.
            self.reconcile_uptime_seconds = 30 * 60;
        }
        if self.netbird_domain.is_empty() {
            self.netbird_domain = "netbird.cloud".into();
        }
        if self.t3_web_port == 0 {
            self.t3_web_port = 3774;
        }
        if self.telemetry_user.is_empty() {
            self.telemetry_user = "prism_phone".into();
        }
    }

    fn validate(&self) -> Result<(), String> {
        let mut names: BTreeMap<&str, &str> = BTreeMap::from([(CLUSTER_TARGET, CLUSTER_TARGET)]);
        for (name, m) in &self.machines {
            if !m.via.is_empty() && (!is_valid_machine_name(&m.via) || &m.via == name) {
                return Err(format!("machine {name}: via must name another machine"));
            }
            if !m.has_t3() && (!m.t3_url.is_empty() || !m.t3_token.is_empty() || !m.via.is_empty())
            {
                return Err(format!(
                    "machine {name} has no T3, so it takes no t3_url, t3_token or via"
                ));
            }
            if !m.t3_url.is_empty() && !valid_origin(&m.t3_url) {
                return Err(format!(
                    "machine {name}: t3_url must be an http(s) origin without a path"
                ));
            }
            if !m.t3_token.is_empty() && m.t3_url.is_empty() {
                return Err(format!("machine {name}: t3_token needs t3_url"));
            }
            for n in std::iter::once(name).chain(m.aliases.iter()) {
                if !is_valid_machine_name(n) {
                    return Err(format!("invalid machine name or alias {n:?}"));
                }
                if let Some(other) = names.insert(n, name) {
                    return Err(format!("machine name {n:?} is used by {other} too"));
                }
            }
        }
        if !self.cluster_machine.is_empty()
            && !self
                .machines
                .get(&self.cluster_machine)
                .is_some_and(MachineConfig::has_t3)
        {
            return Err("cluster_machine must name a configured machine with T3".into());
        }
        for runner in &self.ssh_runners {
            if !self.machines.get(runner).is_some_and(MachineConfig::has_t3) {
                return Err(format!(
                    "ssh_runners must name configured machines with T3; {runner:?} does not"
                ));
            }
        }
        Ok(())
    }

    /// The cluster machine's MCP endpoint and the secret holding Prism's
    /// session with it.
    pub fn cluster_endpoint(&self) -> (String, String) {
        if let Some(m) = self.machines.get(&self.cluster_machine)
            && !m.t3_url.is_empty()
        {
            let secret = if m.t3_token.is_empty() {
                "T3_ACCESS_TOKEN".to_string()
            } else {
                m.t3_token.clone()
            };
            return (format!("{}/mcp", m.t3_url.trim_end_matches('/')), secret);
        }
        (self.t3_url.clone(), "T3_ACCESS_TOKEN".into())
    }

    /// The machines that work over SSH on machines without T3, by preference.
    pub fn ssh_runners(&self) -> Vec<String> {
        if !self.ssh_runners.is_empty() {
            self.ssh_runners.clone()
        } else if !self.cluster_machine.is_empty() {
            vec![self.cluster_machine.clone()]
        } else {
            Vec::new()
        }
    }

    /// The origin of a machine's T3 web app over NetBird.
    pub fn t3_web_origin(&self, machine: &str) -> String {
        let port = if self.t3_web_port == 0 {
            3774
        } else {
            self.t3_web_port
        };
        format!("https://{machine}.{}:{port}", self.netbird_domain)
    }

    /// A configured machine by name or alias.
    pub fn machine_by_name(&self, name: &str) -> Option<(String, MachineConfig)> {
        let name = name.to_lowercase();
        self.machines
            .iter()
            .find(|(key, m)| **key == name || m.aliases.contains(&name))
            .map(|(k, m)| (k.clone(), m.clone()))
    }
}

fn valid_origin(raw: &str) -> bool {
    let Some((scheme, rest)) = raw.split_once("://") else {
        return false;
    };
    let host = rest.strip_suffix('/').unwrap_or(rest);
    (scheme == "http" || scheme == "https")
        && !host.is_empty()
        && !host.contains(['/', '?', '#', '@'])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_and_the_cluster_endpoint() {
        let c = Config::parse(
            r#"{"machines": {"david-server": {"hub": true, "t3_url": "http://10.0.0.1:3773/", "workspace": "/srv/selfhosted-platform"}},
                "t3_workspace": "/srv/selfhosted-platform", "unknown_key": 1}"#,
        )
        .expect("config");
        assert_eq!(c.push.url, "https://ntfy.quickme.at");
        assert_eq!(c.push.topic, "prism-events");
        assert_eq!(c.cluster_machine, "david-server");
        assert_eq!(c.reconcile_grace_seconds, 180);
        assert_eq!(
            c.cluster_endpoint(),
            ("http://10.0.0.1:3773/mcp".into(), "T3_ACCESS_TOKEN".into())
        );
        assert_eq!(c.ssh_runners(), vec!["david-server".to_string()]);
    }

    #[test]
    fn refuses_what_go_refuses() {
        for bad in [
            r#"{"machines": {"a": {"t3": false, "t3_url": "http://x"}}}"#,
            r#"{"machines": {"a": {"t3_url": "http://x/mcp"}}}"#,
            r#"{"machines": {"a": {"t3_token": "T"}}}"#,
            r#"{"machines": {"a": {"aliases": ["cluster"]}}}"#,
            r#"{"cluster_machine": "nope"}"#,
            r#"{"machines": {"a": {"via": "a"}}}"#,
        ] {
            assert!(Config::parse(bad).is_err(), "{bad}");
        }
    }
}
