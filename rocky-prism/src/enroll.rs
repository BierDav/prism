//! Enrollment: the database's root, device provisionings and peer contacts.
//!
//! Every Prism device is enrolled the same way. The root (the database's
//! creator) mints one Rocky provisioning per device with full access, the
//! device opens from it once, with no round trip to the root, and reuses its
//! data directory afterwards. Devices find each other through peer contacts.

use crate::schema::prism_schema;
use crate::types::PrismError;
use data_encoding::{BASE64, BASE64URL_NOPAD};
use rocky_sdk::{Configuration, GrantProfile, Network, Schema};
use std::io::{Read, Write};
use std::path::{Component, Path};

/// The text form of [`PrismRoot`] starts with this.
pub const ROOT_PREFIX: &str = "prism-root-v1:";
/// A device's provisioning starts with this: Rocky's provisioning URI,
/// gzipped (a fifth of the size, so it fits in an environment variable).
pub const DEVICE_PREFIX: &str = "prism-device-v1:";
const ROCKY_PROVISION_PREFIX: &str = "rocky://provision/";
const ROOT_KEY_ENTRY: &str = "root-key";
const DATABASE_ENTRY: &str = "database";
const CONTACT_PREFIX: &str = "rocky://peer/";

/// The database's root: the data directory of the device that created the
/// Prism database, and the root key phrase, packed into one string.
///
/// The root never syncs. It is used read-only: every [`PrismRoot::provision`]
/// opens a scratch copy, so one stored root provisions any number of devices.
pub struct PrismRoot {
    archive: Vec<u8>,
}

/// A device's enrollment, minted by [`PrismRoot::provision`].
#[derive(Clone, PartialEq, Eq)]
pub struct DeviceProvisioning {
    pub device: String,
    /// The device's Rocky node key, hex.
    pub node: String,
    /// What the device opens from (`prism-device-v1:...`): a secret.
    pub provisioning: String,
}

impl std::fmt::Debug for DeviceProvisioning {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DeviceProvisioning")
            .field("device", &self.device)
            .field("node", &self.node)
            .field("provisioning", &"<redacted>")
            .finish()
    }
}

impl PrismRoot {
    /// Creates a new, empty Prism database and returns its root.
    pub async fn create() -> Result<Self, PrismError> {
        let scratch = scratch()?;
        let directory = scratch.path().join(DATABASE_ENTRY);
        let schema = prism_schema()?;
        let opened = config(&directory, &schema)?
            .create()
            .await
            .map_err(storage_err)?;
        let root_key = opened
            .root
            .as_ref()
            .map(rocky_sdk::RootKey::phrase)
            .ok_or_else(|| PrismError::Storage("Rocky returned no root key".into()))?;
        opened.database.close().await.map_err(storage_err)?;
        Ok(Self {
            archive: pack(&directory, &root_key)?,
        })
    }

    /// Reads the text form ([`PrismRoot::encode`]); surrounding whitespace is ignored.
    pub fn parse(text: &str) -> Result<Self, PrismError> {
        let encoded = text
            .trim()
            .strip_prefix(ROOT_PREFIX)
            .ok_or_else(|| invalid("not a Prism root (prism-root-v1:...)"))?;
        let archive = BASE64
            .decode(encoded.as_bytes())
            .map_err(|err| invalid(format!("invalid Prism root: {err}")))?;
        let root = Self { archive };
        root.unpack(None)?;
        Ok(root)
    }

    /// The text form: [`ROOT_PREFIX`] and the base64 of a gzipped tar.
    pub fn encode(&self) -> String {
        format!("{ROOT_PREFIX}{}", BASE64.encode(&self.archive))
    }

    /// The root key phrase. Day-to-day enrollment doesn't need it; it signs
    /// what only the database's publisher may (revoking a device).
    pub fn root_key(&self) -> Result<String, PrismError> {
        self.unpack(None)
    }

    /// Mints the provisioning of `device` (a DNS label, e.g. `prism-worker-0`
    /// or `david-pixel`) with full access: Rocky's `Issuer` profile, which
    /// reads and writes every table and can renew other devices' credentials.
    pub async fn provision(&self, device: &str) -> Result<DeviceProvisioning, PrismError> {
        if !is_valid_device_name(device) {
            return Err(invalid(format!(
                "device name {device:?} must be a DNS label (a-z, 0-9, -)"
            )));
        }
        let scratch = scratch()?;
        let directory = scratch.path().join(DATABASE_ENTRY);
        self.unpack(Some(&directory))?;
        let schema = prism_schema()?;
        let opened = config(&directory, &schema)?
            .open()
            .await
            .map_err(storage_err)?;
        let minted = opened
            .database
            .session()
            .provision(GrantProfile::Issuer, Some(device.as_bytes().to_vec()))
            .await
            .map_err(storage_err);
        opened.database.close().await.map_err(storage_err)?;
        let minted = minted?;
        Ok(DeviceProvisioning {
            device: device.to_owned(),
            node: minted.node.to_hex(),
            provisioning: encode_provisioning(&minted.uri)?,
        })
    }

    /// Reads the archive: returns the root key, and unpacks the database into
    /// `into` when given.
    fn unpack(&self, into: Option<&Path>) -> Result<String, PrismError> {
        let mut archive = tar::Archive::new(flate2::read::GzDecoder::new(&self.archive[..]));
        let mut root_key = None;
        let entries = archive.entries().map_err(broken_root)?;
        for entry in entries {
            let mut entry = entry.map_err(broken_root)?;
            let path = entry.path().map_err(broken_root)?.into_owned();
            if path
                .components()
                .any(|part| !matches!(part, Component::Normal(_)))
            {
                return Err(invalid("invalid Prism root: unsafe path"));
            }
            if path == Path::new(ROOT_KEY_ENTRY) {
                let mut phrase = String::new();
                entry.read_to_string(&mut phrase).map_err(broken_root)?;
                root_key = Some(phrase);
            } else if let Ok(relative) = path.strip_prefix(DATABASE_ENTRY) {
                if let (Some(into), tar::EntryType::Regular) = (into, entry.header().entry_type()) {
                    let target = into.join(relative);
                    if let Some(parent) = target.parent() {
                        std::fs::create_dir_all(parent).map_err(storage_err)?;
                    }
                    entry.unpack(&target).map_err(storage_err)?;
                }
            } else {
                return Err(invalid(format!(
                    "invalid Prism root: unexpected entry {}",
                    path.display()
                )));
            }
        }
        root_key.ok_or_else(|| invalid("invalid Prism root: no root key"))
    }
}

/// The peer contact (`rocky://peer/...`) of the device `provisioning` is
/// for, reachable at the address `network` advertises: for rocky-tcp,
/// `wss://prism.quickme.at/rocky/<pod>` for phones and Macs, or the pod's
/// in-cluster `host:port` for other workers.
///
/// Contacts are not secret. This opens a scratch copy of the device, so the
/// contact is Rocky's own and names the device's node key.
pub async fn peer_contact(provisioning: &str, network: Network) -> Result<String, PrismError> {
    let scratch = scratch()?;
    let schema = prism_schema()?;
    let opened = config(&scratch.path().join(DATABASE_ENTRY), &schema)?
        .network(network)
        .join(&decode_provisioning(provisioning)?)
        .await
        .map_err(storage_err)?;
    let contact = opened
        .database
        .session()
        .contact()
        .await
        .map_err(storage_err);
    opened.database.close().await.map_err(storage_err)?;
    contact
}

fn encode_provisioning(uri: &str) -> Result<String, PrismError> {
    let mut gz = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::best());
    gz.write_all(uri.as_bytes()).map_err(storage_err)?;
    Ok(format!(
        "{DEVICE_PREFIX}{}",
        BASE64.encode(&gz.finish().map_err(storage_err)?)
    ))
}

/// Rocky's provisioning URI from a device's provisioning (`prism-device-v1:...`,
/// or the bare `rocky://provision/...`).
pub(crate) fn decode_provisioning(provisioning: &str) -> Result<String, PrismError> {
    let provisioning = provisioning.trim();
    if provisioning.starts_with(ROCKY_PROVISION_PREFIX) {
        return Ok(provisioning.to_owned());
    }
    let broken = |err: &dyn std::fmt::Display| invalid(format!("invalid provisioning: {err}"));
    let encoded = provisioning
        .strip_prefix(DEVICE_PREFIX)
        .ok_or_else(|| invalid("not a Prism provisioning (prism-device-v1:...)"))?;
    let gz = BASE64
        .decode(encoded.as_bytes())
        .map_err(|err| broken(&err))?;
    let mut uri = String::new();
    flate2::read::GzDecoder::new(&gz[..])
        .take(4 << 20)
        .read_to_string(&mut uri)
        .map_err(|err| broken(&err))?;
    if !uri.starts_with(ROCKY_PROVISION_PREFIX) {
        return Err(invalid(
            "invalid provisioning: no Rocky provisioning inside",
        ));
    }
    Ok(uri)
}

/// The database a provisioning (`rocky://provision/...`) or a peer contact
/// (`rocky://peer/...`) belongs to: its publisher, hex. None when it can't
/// be read.
pub(crate) fn publisher_of(uri: &str) -> Option<String> {
    let body = uri
        .trim()
        .strip_prefix(ROCKY_PROVISION_PREFIX)
        .or_else(|| uri.trim().strip_prefix("rocky://peer/"))?;
    let bytes = BASE64URL_NOPAD
        .decode(body.trim_end_matches('=').as_bytes())
        .or_else(|_| BASE64.decode(body.as_bytes()))
        .ok()?;
    serde_json::from_slice::<serde_json::Value>(&bytes)
        .ok()?
        .get("publisher")?
        .as_str()
        .map(str::to_owned)
}

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

fn pack(directory: &Path, root_key: &str) -> Result<Vec<u8>, PrismError> {
    let gz = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::best());
    let mut builder = tar::Builder::new(gz);
    builder.mode(tar::HeaderMode::Deterministic);
    let mut header = tar::Header::new_gnu();
    header.set_size(root_key.len() as u64);
    header.set_mode(0o600);
    header.set_cksum();
    builder
        .append_data(&mut header, ROOT_KEY_ENTRY, root_key.as_bytes())
        .map_err(storage_err)?;
    append_dir(&mut builder, directory, Path::new(DATABASE_ENTRY))?;
    let mut gz = builder.into_inner().map_err(storage_err)?;
    gz.flush().map_err(storage_err)?;
    gz.finish().map_err(storage_err)
}

fn append_dir<W: Write>(
    builder: &mut tar::Builder<W>,
    directory: &Path,
    name: &Path,
) -> Result<(), PrismError> {
    let mut entries = std::fs::read_dir(directory)
        .map_err(storage_err)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(storage_err)?;
    entries.sort_by_key(|entry| entry.file_name());
    for entry in entries {
        let file_name = entry.file_name();
        // Lock files belong to the process that had the directory open.
        if file_name.to_string_lossy().ends_with(".lock") {
            continue;
        }
        let kind = entry.file_type().map_err(storage_err)?;
        let path = name.join(&file_name);
        if kind.is_dir() {
            append_dir(builder, &entry.path(), &path)?;
        } else if kind.is_file() {
            builder
                .append_path_with_name(entry.path(), &path)
                .map_err(storage_err)?;
        }
    }
    Ok(())
}

fn config(directory: &Path, schema: &Schema) -> Result<Configuration, PrismError> {
    Configuration::new(directory, schema).map_err(storage_err)
}

fn scratch() -> Result<tempfile::TempDir, PrismError> {
    tempfile::Builder::new()
        .prefix("prism-enroll-")
        .tempdir()
        .map_err(storage_err)
}

fn invalid(message: impl Into<String>) -> PrismError {
    PrismError::InvalidInput(message.into())
}

fn broken_root(err: std::io::Error) -> PrismError {
    invalid(format!("invalid Prism root: {err}"))
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
    fn a_root_rejects_foreign_text() {
        assert!(PrismRoot::parse("rocky://provision/abc").is_err());
        assert!(PrismRoot::parse("prism-root-v1:!!").is_err());
        assert!(PrismRoot::parse(&format!("prism-root-v1:{}", BASE64.encode(b"junk"))).is_err());
    }

    #[test]
    fn a_provisioning_round_trips_and_a_bare_uri_passes() {
        let uri = format!("{ROCKY_PROVISION_PREFIX}{}", "e30".repeat(100));
        let encoded = encode_provisioning(&uri).unwrap();
        assert!(encoded.starts_with(DEVICE_PREFIX) && encoded.len() < uri.len());
        assert_eq!(decode_provisioning(&format!(" {encoded}\n")).unwrap(), uri);
        assert_eq!(decode_provisioning(&uri).unwrap(), uri);
        assert!(decode_provisioning("prism-device-v1:!!").is_err());
        assert!(decode_provisioning("hello").is_err());
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
