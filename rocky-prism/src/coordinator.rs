//! The coordinator that makes Prism's seals final: the public Rocky
//! coordination service at rocky.quickme.at (three Raft voters, reached over
//! iroh through its relay).
//!
//! A trusted worker opens its replica with a [`CoordinatorLink`], so its seals
//! reach the voters. The coordinator only orders metadata (which seal ended
//! which tip); rows, schemas and keys never leave the devices.
//!
//! The service admits a database as a *domain* of a *tenant*. Registering it
//! takes a coordination grant the publisher (the root) signs, naming the
//! tenant key; the tenant key then signs the domain's control, which lists
//! the identities whose nodes may finalize seals and expires after at most
//! the service's `max_control_lifetime` (366 days). Prism derives the tenant
//! key from the root and the database's scope, so whoever holds the root can
//! renew the control or change its identities without another secret.

use crate::client::PrismClient;
use crate::schema::prism_schema;
use crate::seal::RootSigner;
use crate::types::PrismError;
use rocky_coordinator::certified::TrustBundle;
use rocky_coordinator::control::{ControlClient, ControlRequest, ControlRevision, Domain};
use rocky_sdk::credentials::{PrivateKey, PublicKey};
use rocky_sdk::transport::Transport;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::path::Path;
use std::sync::Arc;

/// The public service's trust root: the key that certifies its voters
/// (`root` in https://rocky.quickme.at/bundle.json).
pub const PUBLIC_COORDINATOR_ROOT: &str =
    "bd3c36e83a5773e81eb46d8863edb80b5712c882dcc0163bc21f73db978a6bf0";
/// The public service's iroh relay; its voters are reachable only through it.
pub const PUBLIC_COORDINATOR_RELAY: &str = "https://rocky-relay.quickme.at";

/// The published bundle is about 3 KiB.
const MAX_BUNDLE: usize = 256 * 1024;
/// Seconds kept off the service's maximum control lifetime: the control is
/// backdated by 60 s when signed.
const LIFETIME_MARGIN: u64 = 120;
/// Rocky's own bound for a control lifetime.
const MAX_LIFETIME: u64 = 366 * 86_400;
/// At most this many identities may finalize a domain's seals.
const MAX_TRUSTED: usize = 32;
const TENANT_LABEL: &[u8] = b"prism-coordinator-tenant-v1\0";
const REGISTRATION_LABEL: &[u8] = b"prism-coordinator-registration-v1\0";
const CONTROL_LABEL: &[u8] = b"prism-coordinator-control-v1\0";

/// A worker's link to the coordinator: an iroh endpoint that only dials out,
/// and the coordinator client bound to it.
pub struct CoordinatorLink {
    bundle: TrustBundle,
    transport: Arc<dyn Transport>,
    client: Arc<dyn rocky_sdk::Coordinator>,
}

impl CoordinatorLink {
    /// Starts an iroh endpoint homed on `relay` and the client of the
    /// coordinator `bundle_json` certifies. The bundle must be signed by
    /// `pinned_root` (hex) and its genesis must verify.
    pub async fn start(
        bundle_json: &str,
        pinned_root: &str,
        relay: &str,
    ) -> Result<Self, PrismError> {
        let bundle = pinned_bundle(bundle_json, pinned_root)?;
        let attachment = rocky_iroh::Options {
            relay: Some(relay.to_owned()),
            lookup: Some("disabled".into()),
            topic: false,
            local: false,
            bind: None,
        }
        .start(*PrivateKey::new().as_bytes())
        .await
        .map_err(|err| PrismError::Unavailable(format!("coordinator endpoint: {err}")))?;
        Self::with_transport(bundle, attachment.transport())
    }

    /// The client of `bundle`'s coordinator over `transport`, which the
    /// link owns from now on ([`CoordinatorLink::close`] closes it).
    pub fn with_transport(
        bundle: TrustBundle,
        transport: Arc<dyn Transport>,
    ) -> Result<Self, PrismError> {
        let client = rocky_coordinator::Client::connect(&bundle, transport.clone())
            .map_err(|err| invalid(format!("coordinator bundle: {err}")))?;
        Ok(Self {
            bundle,
            transport,
            client,
        })
    }

    /// What [`PrismClient::open_provisioned_with`] composes.
    pub fn slot(&self) -> rocky_sdk::CoordinatorSlot {
        rocky_sdk::CoordinatorSlot::Client(self.client.clone())
    }

    /// The longest control the service accepts, less a margin.
    pub fn control_lifetime(&self) -> Result<u64, PrismError> {
        let maximum = self
            .bundle
            .genesis()
            .map_err(|err| invalid(format!("coordinator bundle: {err}")))?
            .genesis
            .max_control_lifetime
            .min(MAX_LIFETIME);
        maximum
            .checked_sub(LIFETIME_MARGIN)
            .filter(|seconds| *seconds >= 60)
            .ok_or_else(|| invalid("the coordinator allows no usable control lifetime"))
    }

    pub async fn close(&self) {
        self.transport.close().await;
    }
}

/// Parses `json` and checks it is signed by `root` (hex) and its certified
/// genesis verifies.
pub fn pinned_bundle(json: &str, root: &str) -> Result<TrustBundle, PrismError> {
    if json.len() > MAX_BUNDLE {
        return Err(invalid("the coordinator bundle exceeds 256 KiB"));
    }
    let bundle = TrustBundle::from_json(json)
        .map_err(|err| invalid(format!("the coordinator bundle is unreadable: {err}")))?;
    if bundle.root.to_hex() != root.trim() {
        return Err(invalid(
            "the coordinator bundle is not signed by the pinned coordinator root",
        ));
    }
    bundle
        .genesis()
        .map_err(|err| invalid(format!("the coordinator bundle does not verify: {err}")))?;
    Ok(bundle)
}

/// The domain's control as the service took it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CoordinationReceipt {
    /// The tenant's public key, hex.
    pub tenant: String,
    /// 1 for the registration, then one more per renewal or change.
    pub revision: u64,
    /// When the control expires, Unix seconds. Seals stop being final after
    /// it: renew before (the same call with the next revision).
    pub expires: u64,
    /// The identities whose nodes may finalize seals, hex.
    pub trusted: Vec<String>,
}

impl PrismClient {
    /// Registers (`revision` 1) or renews (2, 3, ...) the database's domain
    /// at `link`'s coordinator, trusting the nodes of the identities in
    /// `trusted` (hex, see [`PrismClient::principal`]). Signed by `root`.
    ///
    /// A retry of the same revision is the same request, and the service
    /// answers it again: signing times are kept in `state_dir`, which must
    /// survive restarts. Every revision lists every trusted identity: a
    /// renewal replaces the list.
    pub async fn register_coordination(
        &self,
        link: &CoordinatorLink,
        root: &RootSigner,
        trusted: &[String],
        revision: u64,
        state_dir: &Path,
    ) -> Result<CoordinationReceipt, PrismError> {
        let trusted_keys = trusted_identities(trusted)?;
        let scope = self.db.scope();
        let scope_id =
            serde_json::to_string(&scope).map_err(|err| PrismError::Storage(err.to_string()))?;
        let tenant = tenant_key(root, &scope_id);
        let publisher: PublicKey = root
            .public_key()
            .parse()
            .map_err(|_| invalid("the root has no public key"))?;
        let domain = Domain {
            scope,
            application: prism_schema()?.application,
            publisher,
        };
        let (id, revision) = match revision {
            0 => return Err(invalid("control revisions start at 1")),
            1 => (
                digest(REGISTRATION_LABEL, &[scope_id.as_bytes()]),
                ControlRevision::Register {
                    grant: None,
                    admission: None,
                },
            ),
            n => (
                digest(CONTROL_LABEL, &[scope_id.as_bytes(), &n.to_be_bytes()]),
                ControlRevision::Update(n),
            ),
        };
        let registering = matches!(revision, ControlRevision::Register { .. });
        let request = ControlRequest {
            tenant,
            trusted: trusted_keys,
            lifetime: link.control_lifetime()?,
            id,
            revision,
        };
        std::fs::create_dir_all(state_dir).map_err(|err| PrismError::Storage(err.to_string()))?;
        let control = ControlClient::new(
            link.bundle.clone(),
            link.transport.clone(),
            state_dir.to_path_buf(),
        );
        let receipt = control
            .publish(domain, request, registering.then(|| root.provider()))
            .await
            .map_err(|err| PrismError::Unavailable(format!("coordinator control: {err}")))?;
        Ok(CoordinationReceipt {
            tenant: receipt.tenant.to_hex(),
            revision: receipt.revision,
            expires: receipt.expires,
            trusted: trusted.iter().map(|key| key.trim().to_owned()).collect(),
        })
    }
}

/// The database's tenant key: derived from the root and the scope, so
/// renewing needs only the root, and each database has its own tenant.
fn tenant_key(root: &RootSigner, scope_id: &str) -> PrivateKey {
    let secret = root.secret();
    PrivateKey::from_bytes(&digest(TENANT_LABEL, &[&secret, scope_id.as_bytes()]))
}

fn trusted_identities(keys: &[String]) -> Result<Vec<PublicKey>, PrismError> {
    if keys.is_empty() || keys.len() > MAX_TRUSTED {
        return Err(invalid(format!(
            "a coordinator domain trusts 1 through {MAX_TRUSTED} identities"
        )));
    }
    keys.iter()
        .map(|key| {
            key.trim()
                .parse()
                .map_err(|_| invalid(format!("{key:?} is not an identity key")))
        })
        .collect()
}

/// SHA-256 over `label` and length-prefixed `parts`.
fn digest(label: &[u8], parts: &[&[u8]]) -> [u8; 32] {
    let mut hash = Sha256::new();
    hash.update(label);
    for part in parts {
        hash.update((part.len() as u64).to_be_bytes());
        hash.update(part);
    }
    hash.finalize().into()
}

fn invalid(message: impl Into<String>) -> PrismError {
    PrismError::InvalidInput(message.into())
}

#[cfg(test)]
mod tests {
    use super::*;

    const BUNDLE: &str = include_str!("../../deploy/rocky-coordinator-bundle.json");

    #[test]
    fn the_public_bundle_verifies_against_its_pinned_root() {
        let bundle = pinned_bundle(BUNDLE, PUBLIC_COORDINATOR_ROOT).expect("bundle");
        assert!(pinned_bundle(BUNDLE, &"00".repeat(32)).is_err());
        assert!(pinned_bundle("{}", PUBLIC_COORDINATOR_ROOT).is_err());
        let genesis = bundle.genesis().expect("genesis").genesis;
        assert!(genesis.max_control_lifetime > 86_400);
    }

    #[tokio::test]
    async fn the_tenant_is_stable_per_database_and_never_the_root() {
        let root = RootSigner::parse(
            &crate::enroll::PrismRoot::create()
                .await
                .expect("root")
                .encode(),
        )
        .expect("signer");
        let a = tenant_key(&root, "scope-a");
        assert_eq!(a.to_hex(), tenant_key(&root, "scope-a").to_hex());
        assert_ne!(a.to_hex(), tenant_key(&root, "scope-b").to_hex());
        assert_ne!(a.public_key().to_hex(), root.public_key());
    }

    #[test]
    fn a_domain_trusts_one_to_thirty_two_identities() {
        assert!(trusted_identities(&[]).is_err());
        assert!(trusted_identities(&["xyz".into()]).is_err());
        let key = PrivateKey::from_bytes(&[3; 32]).public_key().to_hex();
        assert_eq!(
            trusted_identities(std::slice::from_ref(&key))
                .unwrap()
                .len(),
            1
        );
        assert!(trusted_identities(&vec![key; 33]).is_err());
    }
}
