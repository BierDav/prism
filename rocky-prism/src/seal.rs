//! Sealing: which devices seal Prism's history, and what makes a seal final.
//!
//! Rocky prunes synced history only behind a final seal. Two things must hold
//! for one to exist:
//!
//! - A *trusted* device seals. Provisioned Prism devices are issuers
//!   (`GrantProfile::Issuer`) but not trusted, so until the root trusts a
//!   device ([`PrismClient::trust_self`]) Rocky never even tries to seal on it,
//!   and says nothing about it either.
//! - In a database whose owner-signed settings name a coordinator
//!   ([`PrismClient::declare_coordinated`]), a seal is final once that
//!   coordinator acknowledged it. Without one, trusted devices refuse to seal
//!   (`coordinator_required`).
//!
//! Devices that are not trusted (the Mac and the phone) neither seal nor talk
//! to the coordinator: they receive the seals and finality by sync, and
//! compact behind them like everyone else.
//!
//! The root key signs both steps. Prism never stores it on a device: the
//! caller passes it in for one call ([`RootSigner`]).

use crate::client::PrismClient;
use crate::enroll::{PrismRoot, ROOT_PREFIX};
use crate::types::PrismError;
use rocky_sdk::{IdentityProvider, RootKey};
use serde::{Deserialize, Serialize};
use std::sync::Arc;

/// The database's root key, held only for the call it is passed to.
///
/// Parsed from the 24-word phrase or from the whole Prism root
/// (`prism-root-v1:...`), which contains it.
#[derive(Clone)]
pub struct RootSigner(Arc<RootKey>);

impl RootSigner {
    pub fn parse(text: &str) -> Result<Self, PrismError> {
        let text = text.trim();
        let phrase = if text.starts_with(ROOT_PREFIX) {
            PrismRoot::parse(text)?.root_key()?
        } else {
            text.to_owned()
        };
        RootKey::from_phrase(phrase.trim())
            .map(|key| Self(Arc::new(key)))
            .map_err(|_| PrismError::InvalidInput("not a Rocky root key phrase".into()))
    }

    /// The 24-word phrase: what a caller sends on instead of the whole
    /// Prism root (about 320 KB). A secret.
    pub fn phrase(&self) -> String {
        self.0.phrase()
    }

    /// The root's public key, hex: the database's publisher.
    pub fn public_key(&self) -> String {
        self.0.public_key().to_hex()
    }

    pub(crate) fn provider(&self) -> Arc<dyn IdentityProvider> {
        self.0.clone()
    }

    #[cfg(feature = "coordinator")]
    pub(crate) fn secret(&self) -> [u8; 32] {
        self.0.secret()
    }
}

impl std::fmt::Debug for RootSigner {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "RootSigner({})", self.public_key())
    }
}

/// Where this replica stands on sealing, for status pages and rollout checks.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SealStatus {
    /// This device's Rocky node key, hex.
    pub node: String,
    /// This device's identity (what the root trusts and the coordinator's
    /// domain lists), hex.
    pub principal: String,
    /// Whether the root trusts this device, so it seals.
    pub trusted: bool,
    /// Whether this device was opened with a coordinator (`service`) or
    /// not (`not_configured`).
    pub coordination: String,
    /// Replayed operations on the followed tip since its last seal. Rocky
    /// seals routinely once this reaches 128.
    pub operations_since_seal: u64,
    /// The latest final seal, if any.
    pub finalized: Option<String>,
    /// The seal history was last compacted behind, if any.
    pub compacted: Option<String>,
    /// The synced history this replica retains, in bytes: what it refuses
    /// writes over (`RETAINED_HISTORY_BYTES`). Compaction behind final seals
    /// brings it down.
    pub retained_bytes: u64,
    /// Why this device skipped a due seal (`SealSkipped`), if it did.
    pub seal_skipped: Option<String>,
}

/// Any table of Prism's schema: [`PrismClient::seal_status`] reads the
/// replica-wide retained history through it.
const RETENTION_PROBE_TABLE: &str = "settings";

impl PrismClient {
    /// This device's identity, hex.
    pub async fn principal(&self) -> Result<String, PrismError> {
        let session = self.db.session();
        let info = session.info().await.map_err(storage_err);
        session.close().await;
        Ok(info?.principal.to_hex())
    }

    /// Makes this device's identity trusted, signed by `root`, so it seals.
    /// The grant is written to the replicated registry, so every device
    /// accepts this one's seals. Trusting an already trusted identity again
    /// is harmless. Returns the trusted identity, hex.
    pub async fn trust_self(&self, root: &RootSigner) -> Result<String, PrismError> {
        let session = self.db.session();
        let trusted = async {
            let principal = session.info().await.map_err(storage_err)?.principal;
            // Rocky trusts only an identity its registry records. A device
            // minted by `PrismRoot::provision` is recorded only in the root's
            // scratch copy, which never syncs; the device records itself
            // after its credentials renew, so renew (from a peer) first.
            let recorded = |identities: &[rocky_sdk::IdentityInfo]| {
                identities
                    .iter()
                    .any(|identity| identity.identity == principal && !identity.revoked)
            };
            if !recorded(&session.identities().await.map_err(storage_err)?) {
                session.renew().await.map_err(storage_err)?;
                if !recorded(&session.identities().await.map_err(storage_err)?) {
                    return Err(PrismError::Unavailable(
                        "this device's identity is not in its registry yet; \
                         it records itself once its credentials renew"
                            .into(),
                    ));
                }
            }
            let grant = session
                .trust(principal, root.provider())
                .await
                .map_err(storage_err)?;
            session
                .install_trust(&grant.grant)
                .await
                .map_err(storage_err)?;
            Ok(principal.to_hex())
        }
        .await;
        session.close().await;
        trusted
    }

    /// Names the coordinator this device was opened with
    /// ([`PrismClient::open_provisioned_with`]) in the database's settings,
    /// signed by `root`. Every device learns it by sync; from then on only
    /// that coordinator's acknowledgements make a seal final, for good: Rocky
    /// never lets a database change or drop its coordinator. Declaring the
    /// same coordinator again does nothing.
    pub async fn declare_coordinated(&self, root: &RootSigner) -> Result<(), PrismError> {
        let session = self.db.session();
        let declared = session.declare_coordinated(root.provider()).await;
        session.close().await;
        declared.map(|_| ()).map_err(storage_err)
    }

    /// Where this replica stands on sealing ([`SealStatus`]).
    pub async fn seal_status(&self) -> Result<SealStatus, PrismError> {
        let session = self.db.session();
        let status = async {
            let info = session.info().await.map_err(storage_err)?;
            // Rocky's `coordinated_status().trusted` only says a coordinator
            // is composed; the registry says whether the root trusts us.
            // Only issuers read the registry; every Prism device is one.
            let trusted = session.identities().await.is_ok_and(|identities| {
                identities
                    .iter()
                    .any(|identity| identity.own && !identity.revoked && identity.trusted.is_some())
            });
            let tip = session.tip_status().await.map_err(storage_err)?;
            let compaction = session.compaction().await.map_err(storage_err)?;
            // The history Rocky holds against `RetentionLimits::hard_bytes`;
            // it is replica-wide, whichever table is asked.
            let retained_bytes = session
                .retention(RETENTION_PROBE_TABLE)
                .await
                .map_err(storage_err)?
                .checkpoint
                .retained_bytes;
            let status = session.status().await.map_err(storage_err)?;
            let coordination = match status.coordination.mode {
                rocky_sdk::CoordinationMode::NotConfigured => "not_configured",
                rocky_sdk::CoordinationMode::Service => "service",
            }
            .to_owned();
            let seal_skipped = status.alerts.iter().find_map(|alert| match &alert.kind {
                rocky_sdk::AlertKind::SealSkipped { reason } => Some(reason.to_string()),
                _ => None,
            });
            Ok(SealStatus {
                node: info.node.to_hex(),
                principal: info.principal.to_hex(),
                trusted,
                coordination,
                operations_since_seal: tip.operations_since_seal,
                finalized: compaction.finalized,
                compacted: compaction.compacted,
                retained_bytes,
                seal_skipped,
            })
        }
        .await;
        session.close().await;
        status
    }

    /// Compacts this replica's history behind its latest final seal, if
    /// there is anything to compact. Returns the operations pruned.
    ///
    /// Rocky compacts on its own only up to the first final seal here: later
    /// final seals stay `uncompacted` and history keeps growing (seen with
    /// the pinned Rocky in `tests/coordinated.rs`). Prism therefore compacts
    /// itself, every [`COMPACT_EVERY`] from [`spawn_compaction`], on every
    /// device: compaction is local and needs no trust or coordinator.
    pub async fn compact_history(&self) -> Result<u64, PrismError> {
        compact(&self.db).await
    }
}

/// How often every replica compacts behind its latest final seal.
pub const COMPACT_EVERY: std::time::Duration = std::time::Duration::from_secs(60);

async fn compact(db: &rocky_sdk::Database) -> Result<u64, PrismError> {
    let session = db.session();
    let compacted = async {
        let state = session.compaction().await.map_err(storage_err)?;
        if state.finalized.is_none()
            || (state.finalized == state.compacted && state.uncompacted.is_empty())
        {
            return Ok(0);
        }
        Ok(session.compact(0).await.map_err(storage_err)?.pruned)
    }
    .await;
    session.close().await;
    compacted
}

/// Compacts `db` every [`COMPACT_EVERY`] until its last handle is dropped.
/// Needs a Tokio runtime, which every Prism device runs.
pub(crate) fn spawn_compaction(db: &std::sync::Arc<rocky_sdk::Database>) {
    let Ok(runtime) = tokio::runtime::Handle::try_current() else {
        return;
    };
    let db = std::sync::Arc::downgrade(db);
    runtime.spawn(async move {
        loop {
            tokio::time::sleep(COMPACT_EVERY).await;
            let Some(db) = db.upgrade() else { return };
            match compact(&db).await {
                Ok(0) => {}
                Ok(pruned) => eprintln!("rocky-prism: compacted {pruned} operations of history"),
                // A closed database answers every call with an error; the
                // last handle going away ends the loop.
                Err(err) => eprintln!("rocky-prism: compacting history failed: {err}"),
            }
        }
    });
}

fn storage_err(err: impl std::fmt::Display) -> PrismError {
    PrismError::Storage(err.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn a_root_signer_reads_the_phrase_or_the_whole_root() {
        let root = PrismRoot::create().await.expect("root");
        let phrase = root.root_key().expect("phrase");
        let from_phrase = RootSigner::parse(&format!(" {phrase}\n")).expect("phrase");
        let from_root = RootSigner::parse(&root.encode()).expect("root");
        assert_eq!(from_phrase.public_key(), from_root.public_key());
        assert!(!format!("{from_phrase:?}").contains(&phrase));
        assert!(RootSigner::parse("not a phrase").is_err());
    }
}
