//! Enrollment helpers: device names, peer contact inspection, and local
//! persistence of the device's Rocky person key (`IdentityKey`) and optional
//! root key (`RootKey`).
//!
//! Rocky v0.1.0-beta.4 never stores a person key on disk: `create` and `join`
//! return it once, and subsequent `open` calls pass it via
//! `Configuration::identity`. Prism persists it inside the database directory
//! (`prism-identity.key`, mode 0600) so restarts retain admin/invite authority.

use crate::types::PrismError;
use data_encoding::BASE64URL_NOPAD;
use rocky_sdk::{IdentityKey, RootKey};
use std::io::Write;
use std::path::Path;
use std::sync::Arc;

const CONTACT_PREFIX: &str = "rocky://peer/";
const IDENTITY_KEY_FILE: &str = "prism-identity.key";
const ROOT_KEY_FILE: &str = "prism-root.key";

/// A device name: a DNS label, as the workers' pod names and fleet's hosts are.
pub fn is_valid_device_name(name: &str) -> bool {
    (1..=63).contains(&name.len())
        && !name.starts_with('-')
        && !name.ends_with('-')
        && name
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
}

/// The node a peer contact names, in Rocky's JSON form.
pub(crate) fn contact_node(contact: &str) -> Result<serde_json::Value, PrismError> {
    let encoded = contact
        .trim()
        .strip_prefix(CONTACT_PREFIX)
        .ok_or_else(|| invalid("not a peer contact (rocky://peer/...)"))?;
    let bytes = BASE64URL_NOPAD
        .decode(encoded.as_bytes())
        .map_err(|err| invalid(format!("invalid peer contact: {err}")))?;
    let record: serde_json::Value = serde_json::from_slice(&bytes)
        .map_err(|err| invalid(format!("invalid peer contact: {err}")))?;
    match record.get("node") {
        Some(node) if !node.is_null() => Ok(node.clone()),
        _ => Err(invalid("invalid peer contact: no node")),
    }
}

/// Persists `key`'s 24-word phrase inside `directory` so future opens can
/// supply it to `Configuration::identity`.
pub(crate) fn save_identity(directory: &Path, key: &IdentityKey) -> Result<(), PrismError> {
    write_secret_file(&directory.join(IDENTITY_KEY_FILE), &key.phrase())
}

/// Loads the persisted person key from `directory`, if present.
pub(crate) fn load_identity(directory: &Path) -> Result<Option<Arc<IdentityKey>>, PrismError> {
    let path = directory.join(IDENTITY_KEY_FILE);
    let Some(phrase) = read_secret_file(&path)? else {
        return Ok(None);
    };
    let key = IdentityKey::from_phrase(phrase.trim())
        .map_err(|err| PrismError::Storage(format!("invalid identity key in {}: {err}", path.display())))?;
    Ok(Some(Arc::new(key)))
}

/// Persists `key`'s 24-word phrase inside `directory` (for the database creator).
pub(crate) fn save_root(directory: &Path, key: &RootKey) -> Result<(), PrismError> {
    write_secret_file(&directory.join(ROOT_KEY_FILE), &key.phrase())
}

fn write_secret_file(path: &Path, content: &str) -> Result<(), PrismError> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(storage_err)?;
    }
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create(true).truncate(true);
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut options, 0o600);
    let mut file = options.open(path).map_err(storage_err)?;
    writeln!(file, "{content}").map_err(storage_err)?;
    file.sync_all().map_err(storage_err)?;
    Ok(())
}

fn read_secret_file(path: &Path) -> Result<Option<String>, PrismError> {
    match std::fs::read_to_string(path) {
        Ok(content) => Ok(Some(content)),
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(err) => Err(storage_err(err)),
    }
}

fn invalid(message: impl Into<String>) -> PrismError {
    PrismError::InvalidInput(message.into())
}

fn storage_err(err: impl std::fmt::Display) -> PrismError {
    PrismError::Storage(err.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn device_names_are_dns_labels() {
        for good in ["prism-worker-0", "david-pixel", "a", "david-mb"] {
            assert!(is_valid_device_name(good), "{good}");
        }
        for bad in ["", "-a", "a-", "David", "a_b", "a.b", &"a".repeat(64)] {
            assert!(!is_valid_device_name(bad), "{bad}");
        }
    }

    #[test]
    fn a_contact_names_its_node() {
        let record = serde_json::json!({"v": 3, "node": "ab", "routes": []});
        let contact = format!(
            "{CONTACT_PREFIX}{}",
            BASE64URL_NOPAD.encode(&serde_json::to_vec(&record).unwrap())
        );
        assert_eq!(contact_node(&contact).unwrap(), serde_json::json!("ab"));
        assert!(contact_node("rocky://provision/x").is_err());
    }
}
