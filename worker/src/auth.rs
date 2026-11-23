use hmac::{Hmac, Mac};
use sha2::Sha256;

type HmacSha256 = Hmac<Sha256>;

pub fn is_valid_machine_name(name: &str) -> bool {
    let bytes = name.as_bytes();
    if bytes.is_empty() || bytes.len() > 63 {
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

/// Reads the secret `name` as the Go server's `Secret` does: from the file named
/// by `<name>_FILE` when that is set (trimmed), otherwise from `name` itself.
/// An empty or unreadable secret is `None`.
pub fn secret(name: &str) -> Option<String> {
    secret_from(name, |key| std::env::var(key).ok())
}

pub(crate) fn secret_from(name: &str, env: impl Fn(&str) -> Option<String>) -> Option<String> {
    let value = match env(&format!("{name}_FILE")).filter(|path| !path.is_empty()) {
        Some(path) => std::fs::read_to_string(path).ok()?.trim().to_string(),
        None => env(name)?,
    };
    Some(value).filter(|v| !v.is_empty())
}

/// Derives the event API token of a machine from Prism's event key.
#[cfg(test)]
pub fn machine_token(key: &[u8], machine: &str) -> Result<String, String> {
    if key.len() < 32 {
        return Err("the event key must be at least 32 bytes".into());
    }
    if !is_valid_machine_name(machine) {
        return Err(format!("invalid machine name {machine:?}"));
    }
    let mut mac =
        HmacSha256::new_from_slice(key).map_err(|err| format!("invalid HMAC key: {err}"))?;
    // The prefix is from before the rename; fleet's tokens depend on it.
    mac.update(b"klaxon-machine:");
    mac.update(machine.as_bytes());
    let sig = hex::encode(mac.finalize().into_bytes());
    Ok(format!("{machine}.{sig}"))
}

/// Verifies a machine bearer token and returns the machine name it belongs to.
pub fn verify_machine_token(key: &[u8], token: &str) -> Option<String> {
    let (machine, sig_hex) = token.split_once('.')?;
    if !is_valid_machine_name(machine) || key.len() < 32 {
        return None;
    }
    let sig_bytes = hex::decode(sig_hex).ok()?;
    let mut mac = HmacSha256::new_from_slice(key).ok()?;
    mac.update(b"klaxon-machine:");
    mac.update(machine.as_bytes());
    mac.verify_slice(&sig_bytes).ok()?;
    Some(machine.to_string())
}

/// The admin token's prefix. It has no dot, so it is never a machine token.
const ADMIN_PREFIX: &str = "admin:";

/// Derives the admin API token from Prism's event key: `prism pair`,
/// `machines` and `device-*` mint it where the key is (the worker pod) and
/// the worker checks it. The CLI derives it the same way (`cli/src/token.rs`).
#[cfg(test)]
pub fn admin_token(key: &[u8]) -> Option<String> {
    let mut mac = admin_mac(key)?;
    mac.update(b"prism-admin-v1");
    Some(format!(
        "{ADMIN_PREFIX}{}",
        hex::encode(mac.finalize().into_bytes())
    ))
}

fn admin_mac(key: &[u8]) -> Option<HmacSha256> {
    if key.len() < 32 {
        return None;
    }
    HmacSha256::new_from_slice(key).ok()
}

/// Verifies the admin API token, in constant time.
pub fn verify_admin_token(key: &[u8], token: &str) -> bool {
    let Some(sig) = token
        .strip_prefix(ADMIN_PREFIX)
        .and_then(|hex_sig| hex::decode(hex_sig).ok())
    else {
        return false;
    };
    let Some(mut mac) = admin_mac(key) else {
        return false;
    };
    mac.update(b"prism-admin-v1");
    mac.verify_slice(&sig).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    const KEY: &str = "prism-test-event-key-0123456789abcdef";
    /// `PRISM_EVENT_KEY_FILE=<file holding KEY and a newline> prism token david-mb`
    /// from the Go server.
    const GO_TOKEN: &str =
        "david-mb.d6ed43973d8463ff02792f533d7462036fd62c2e8978a1eb5f6d1201ffff49f1";

    fn env(vars: &[(&str, String)]) -> impl Fn(&str) -> Option<String> + use<> {
        let vars: BTreeMap<String, String> = vars
            .iter()
            .map(|(k, v)| ((*k).to_string(), v.clone()))
            .collect();
        move |key| vars.get(key).cloned()
    }

    #[test]
    fn key_file_wins_over_env_and_verifies_a_go_minted_token() {
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("PRISM_EVENT_KEY");
        std::fs::write(&path, format!("{KEY}\n")).expect("write key");
        let vars = env(&[
            (
                "PRISM_EVENT_KEY",
                "another-key-that-must-not-be-used-xx".into(),
            ),
            ("PRISM_EVENT_KEY_FILE", path.display().to_string()),
        ]);
        let key = secret_from("PRISM_EVENT_KEY", vars).expect("key");
        assert_eq!(key, KEY);
        assert_eq!(
            verify_machine_token(key.as_bytes(), GO_TOKEN).as_deref(),
            Some("david-mb")
        );
        assert_eq!(
            machine_token(key.as_bytes(), "david-mb").as_deref(),
            Ok(GO_TOKEN)
        );
    }

    /// The same vector is in `cli/src/token.rs`: both sides must agree.
    const ADMIN_TOKEN: &str =
        "admin:b93de1ad716b01ee054e7ac7b16b539ef2639a97b86321c60295a8ce8ec373b9";

    #[test]
    fn admin_tokens_verify_and_differ_from_machine_tokens() {
        let token = admin_token(KEY.as_bytes()).expect("token");
        assert_eq!(token, ADMIN_TOKEN);
        assert!(verify_admin_token(KEY.as_bytes(), &token));
        assert!(!verify_admin_token(
            b"another-key-that-must-not-be-used-xx",
            &token
        ));
        assert!(!verify_admin_token(KEY.as_bytes(), GO_TOKEN));
        assert!(!verify_admin_token(KEY.as_bytes(), "admin:00"));
        assert_eq!(verify_machine_token(KEY.as_bytes(), &token), None);
        assert_eq!(admin_token(b"short"), None);
    }

    #[test]
    fn key_falls_back_to_env_and_empty_is_none() {
        let vars = env(&[("PRISM_EVENT_KEY", KEY.into())]);
        assert_eq!(secret_from("PRISM_EVENT_KEY", vars).as_deref(), Some(KEY));
        let vars = env(&[("PRISM_EVENT_KEY_FILE", "/nonexistent/prism-key".into())]);
        assert_eq!(secret_from("PRISM_EVENT_KEY", vars), None);
        assert_eq!(secret_from("PRISM_EVENT_KEY", env(&[])), None);
    }
}
