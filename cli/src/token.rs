//! Secrets and the tokens `prism` derives from Prism's event key.

use hmac::{Hmac, Mac};
use sha2::Sha256;

type HmacSha256 = Hmac<Sha256>;

/// `^[a-z0-9][a-z0-9-]{0,62}$`, the machine (and device) name rule.
pub fn valid_machine_name(name: &str) -> bool {
    let b = name.as_bytes();
    !b.is_empty()
        && b.len() <= 63
        && (b[0].is_ascii_lowercase() || b[0].is_ascii_digit())
        && b.iter()
            .all(|&c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'-')
}

/// Go's `Secret`: NAME, read from the file NAME_FILE names when that is set
/// (trimmed; unreadable is empty).
pub fn secret(name: &str, env: &dyn Fn(&str) -> Option<String>) -> String {
    if let Some(path) = env(&format!("{name}_FILE")).filter(|p| !p.is_empty()) {
        return std::fs::read_to_string(path)
            .map(|raw| raw.trim().to_string())
            .unwrap_or_default();
    }
    env(name).unwrap_or_default()
}

fn mac(key: &[u8]) -> Result<HmacSha256, String> {
    if key.len() < 32 {
        return Err("the event key must be at least 32 bytes".into());
    }
    HmacSha256::new_from_slice(key).map_err(|err| format!("invalid HMAC key: {err}"))
}

/// A machine's event API token: `<machine>.<hex HMAC-SHA256>`.
pub fn machine_token(key: &[u8], machine: &str) -> Result<String, String> {
    let mut mac = mac(key)?;
    if !valid_machine_name(machine) {
        return Err(format!("invalid machine name {}", go_quote(machine)));
    }
    mac.update(b"prism-machine:");
    mac.update(machine.as_bytes());
    Ok(format!(
        "{machine}.{}",
        hex::encode(mac.finalize().into_bytes())
    ))
}

/// The worker's admin API token (`worker/src/auth.rs`, `verify_admin_token`).
pub fn admin_token(key: &[u8]) -> Result<String, String> {
    let mut mac = mac(key)?;
    mac.update(b"prism-admin-v1");
    Ok(format!(
        "admin:{}",
        hex::encode(mac.finalize().into_bytes())
    ))
}

/// Go's `%q` for the names `prism` quotes.
pub fn go_quote(s: &str) -> String {
    serde_json::Value::String(s.to_string()).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    const KEY: &str = "prism-test-event-key-0123456789abcdef";

    fn env(vars: &[(&str, String)]) -> impl Fn(&str) -> Option<String> + use<> {
        let vars: BTreeMap<String, String> = vars
            .iter()
            .map(|(k, v)| ((*k).to_string(), v.clone()))
            .collect();
        move |key| vars.get(key).cloned()
    }

    #[test]
    fn tokens_match_the_go_server_and_the_worker() {
        // `PRISM_EVENT_KEY=… prism token david-mb` from the Go server.
        assert_eq!(
            machine_token(KEY.as_bytes(), "david-mb").as_deref(),
            Ok("david-mb.09541051eb3665a385687eb787f165713a2adf8c88e9e75ff6e795e8b8faa4fc")
        );
        // The same vector is in worker/src/auth.rs.
        assert_eq!(
            admin_token(KEY.as_bytes()).as_deref(),
            Ok("admin:b93de1ad716b01ee054e7ac7b16b539ef2639a97b86321c60295a8ce8ec373b9")
        );
        assert_eq!(
            machine_token(b"short", "david-mb"),
            Err("the event key must be at least 32 bytes".into())
        );
        assert_eq!(
            machine_token(KEY.as_bytes(), "David"),
            Err("invalid machine name \"David\"".into())
        );
    }

    #[test]
    fn secrets_come_from_the_file_then_the_variable() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("key");
        std::fs::write(&path, format!("{KEY}\n")).unwrap();
        let file = path.display().to_string();
        let e = env(&[
            ("PRISM_EVENT_KEY", "from-env".into()),
            ("PRISM_EVENT_KEY_FILE", file.clone()),
        ]);
        assert_eq!(secret("PRISM_EVENT_KEY", &e), KEY);
        let e = env(&[("PRISM_EVENT_KEY", "from-env".into())]);
        assert_eq!(secret("PRISM_EVENT_KEY", &e), "from-env");
        let e = env(&[("PRISM_EVENT_KEY_FILE", "/nonexistent/key".into())]);
        assert_eq!(secret("PRISM_EVENT_KEY", &e), "");
    }
}
