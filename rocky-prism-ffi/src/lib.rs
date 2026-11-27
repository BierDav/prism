//! rocky-prism for the Android and Mac apps, through UniFFI.
//!
//! gobley's bindgen (`uniffi-bindgen/`) turns this into Kotlin Multiplatform
//! sources for `shared/` (package `at.quickme.prism.rocky`): JNA on the JVM
//! and Android, cinterop on Kotlin/Native. Every function here is a Kotlin
//! `suspend fun` (an `async` function in Swift through PrismShared.framework).
//!
//! The futures run on this library's own tokio runtime, which Rocky and
//! rocky-tcp need; Kotlin only polls a handle to them. Cancelling the
//! coroutine aborts the task.
//!
//! Structured values cross as JSON in the HTTP API's shapes
//! (`docs/prism-api.md`): the placement view is `GET /v1/placement`'s body,
//! and each edit takes the body its `PUT` takes. `shared/` already models
//! those (`Api.kt`), so the apps decode them with the serializers they use
//! for the server, and keep one model while both paths exist.

use rocky_prism::{
    PlacementPolicy, PrismClient, PrismError, PutPlacementProfileRequest,
    SetPlacementActionsRequest,
};
use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, LazyLock};
use std::task::{Context, Poll};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::runtime::Runtime;
use tokio::sync::RwLock;
use tokio::task::JoinHandle;

mod api;

pub use api::{ApiRequest, ApiResponse};

uniffi::setup_scaffolding!();

static RUNTIME: LazyLock<Runtime> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("rocky-prism")
        .enable_all()
        .build()
        .expect("rocky-prism-ffi: starting the tokio runtime")
});

/// What a replica call fails with. Kotlin sees one exception class per
/// variant (`ReplicaException.NotFound`, …), each with the message.
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum ReplicaError {
    /// The input was refused (an invalid profile id, a policy out of range).
    #[error("{0}")]
    InvalidInput(String),
    #[error("{0}")]
    Conflict(String),
    #[error("not found")]
    NotFound,
    /// Rocky failed: opening, reading, writing, or the network.
    #[error("storage error: {0}")]
    Storage(String),
    /// The replica was closed.
    #[error("the replica is closed")]
    Closed,
    /// A JSON argument didn't parse.
    #[error("invalid JSON: {0}")]
    Json(String),
}

impl From<PrismError> for ReplicaError {
    fn from(err: PrismError) -> Self {
        match err {
            PrismError::InvalidInput(m) => Self::InvalidInput(m),
            PrismError::Conflict(m) => Self::Conflict(m),
            PrismError::NotFound => Self::NotFound,
            PrismError::Storage(m) | PrismError::SchemaMismatch(m) | PrismError::Unavailable(m) => {
                Self::Storage(m)
            }
        }
    }
}

impl From<serde_json::Error> for ReplicaError {
    fn from(err: serde_json::Error) -> Self {
        Self::Json(err.to_string())
    }
}

/// Runs `future` on the runtime; dropping the returned future aborts it.
async fn on_runtime<T, F>(future: F) -> Result<T, ReplicaError>
where
    T: Send + 'static,
    F: Future<Output = Result<T, ReplicaError>> + Send + 'static,
{
    AbortOnDrop(RUNTIME.spawn(future)).await
}

struct AbortOnDrop<T>(JoinHandle<Result<T, ReplicaError>>);

impl<T> Future for AbortOnDrop<T> {
    type Output = Result<T, ReplicaError>;
    fn poll(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Self::Output> {
        Pin::new(&mut self.0).poll(cx).map(|joined| {
            joined.unwrap_or_else(|err| Err(ReplicaError::Storage(format!("task failed: {err}"))))
        })
    }
}

impl<T> Drop for AbortOnDrop<T> {
    fn drop(&mut self) {
        self.0.abort();
    }
}

/// Rocky reports a write as `Revoked` while the session's authority changes
/// under it, typically during a new device's first syncs; the same write
/// succeeds once the new authority is in place, so writes retry it briefly.
async fn retrying<T, F, Fut>(mut write: F) -> Result<T, ReplicaError>
where
    F: FnMut() -> Fut,
    Fut: Future<Output = Result<T, PrismError>>,
{
    let mut attempt = 0u64;
    loop {
        match write().await {
            Err(PrismError::Unavailable(_)) if attempt < 20 => {
                attempt += 1;
                tokio::time::sleep(Duration::from_millis(50 * attempt)).await;
            }
            // A revocation that Rocky reports outside a SQL failure.
            Err(PrismError::Storage(message)) if message.contains("Revoked") && attempt < 20 => {
                attempt += 1;
                tokio::time::sleep(Duration::from_millis(50 * attempt)).await;
            }
            other => return other.map_err(ReplicaError::from),
        }
    }
}

fn now_unix() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or_default()
}

/// Opens the replica in `directory`, creating an empty database (seeded with
/// the default profiles) when the directory is missing or empty.
///
/// With `dial`, the replica gets a rocky-tcp attachment with WebSockets (see
/// [`device_attachment`]). Without it the replica stays local.
#[uniffi::export]
pub async fn open_replica(directory: String, dial: bool) -> Result<Arc<Replica>, ReplicaError> {
    on_runtime(async move {
        let attachment = match dial {
            true => Some(device_attachment().await?),
            false => None,
        };
        let network = attachment
            .as_ref()
            .map(|a| rocky_sdk::Network::Borrowed(a.transport()));
        let client = match PrismClient::open_or_create(directory, network).await {
            Ok(client) => client,
            Err(err) => {
                if let Some(attachment) = attachment {
                    attachment.close().await;
                }
                return Err(err.into());
            }
        };
        Ok(Arc::new(Replica {
            client: RwLock::new(Some(Arc::new(client))),
            attachment,
        }))
    })
    .await
}

/// The route a phone or a Mac advertises: nobody dials it (`prism-worker
/// peer -` mints the workers' contact for it with the same placeholder).
const DIAL_ONLY: &str = "tcp://127.0.0.1:9";

/// A phone's or a Mac's network: it dials the workers' routes
/// (`wss://prism.quickme.at/rocky/<pod>`, docs/worker.md) and syncs over the
/// links it opens. Rocky refuses an attachment that accepts nothing as a
/// device's network, so it listens on loopback, where no one else reaches it.
async fn device_attachment() -> Result<rocky_tcp::Attachment, ReplicaError> {
    rocky_tcp::Options::new(std::net::SocketAddr::from(([127, 0, 0, 1], 0)))
        .advertise(DIAL_ONLY)
        .start()
        .await
        .map_err(|err| ReplicaError::Storage(format!("starting rocky-tcp: {err}")))
}

/// Opens this device's enrolled replica in `directory`: installed from
/// `provisioning` (`prism-device-v1:...`, minted by fleet with
/// `prism-worker provision`) on the first open, reused afterwards. `peers`
/// are the workers' `wss://` contacts (`prism-worker peer`); the replica
/// syncs with them on its own.
#[uniffi::export]
pub async fn open_provisioned_replica(
    directory: String,
    provisioning: String,
    peers: Vec<String>,
) -> Result<Arc<Replica>, ReplicaError> {
    on_runtime(async move {
        let attachment = device_attachment().await?;
        let network = rocky_sdk::Network::Borrowed(attachment.transport());
        let opened = async {
            let (client, _) = PrismClient::open_invited_with_peers(
                directory,
                provisioning,
                Some(network),
                peers,
            )
            .await?;
            Ok::<_, PrismError>(client)
        }
        .await;
        match opened {
            Ok(client) => Ok(Arc::new(Replica {
                client: RwLock::new(Some(Arc::new(client))),
                attachment: Some(attachment),
            })),
            Err(err) => {
                attachment.close().await;
                Err(err.into())
            }
        }
    })
    .await
}

/// A local Rocky replica of Prism's database.
#[derive(uniffi::Object)]
pub struct Replica {
    client: RwLock<Option<Arc<PrismClient>>>,
    attachment: Option<rocky_tcp::Attachment>,
}

impl Replica {
    async fn client(&self) -> Result<Arc<PrismClient>, ReplicaError> {
        self.client.read().await.clone().ok_or(ReplicaError::Closed)
    }
}

#[uniffi::export]
impl Replica {
    /// Closes the database and the network. Calls still running finish first
    /// (up to five seconds); calls after this fail with `Closed`. (Not
    /// `close`: the Kotlin object's `close()` is AutoCloseable's, which frees
    /// the handle.)
    pub async fn shutdown(&self) -> Result<(), ReplicaError> {
        let Some(mut client) = self.client.write().await.take() else {
            return Ok(());
        };
        let attachment = self.attachment.clone();
        on_runtime(async move {
            let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
            let closed = loop {
                match Arc::try_unwrap(client) {
                    Ok(owned) => break owned.close().await.map_err(ReplicaError::from),
                    Err(shared) if tokio::time::Instant::now() < deadline => {
                        client = shared;
                        tokio::time::sleep(Duration::from_millis(10)).await;
                    }
                    Err(_) => {
                        break Err(ReplicaError::Storage(
                            "calls were still running after five seconds".into(),
                        ));
                    }
                }
            };
            if let Some(attachment) = attachment {
                attachment.close().await;
            }
            closed
        })
        .await
    }

    /// The placement view, as `GET /v1/placement`'s JSON body.
    /// `cluster_machine` is the T3 host whose models count as the cluster's.
    pub async fn placement(&self, cluster_machine: String) -> Result<String, ReplicaError> {
        let client = self.client().await?;
        on_runtime(async move {
            let view = client.placement_view(cluster_machine, now_unix()).await?;
            Ok(serde_json::to_string(&view)?)
        })
        .await
    }

    /// Creates or replaces profile `id`; `request` is `PUT
    /// /v1/placement/profiles/<id>`'s JSON body (`label`, `entries`).
    pub async fn put_placement_profile(
        &self,
        id: String,
        request: String,
    ) -> Result<(), ReplicaError> {
        let request: PutPlacementProfileRequest = serde_json::from_str(&request)?;
        let client = self.client().await?;
        on_runtime(async move {
            retrying(|| client.put_placement_profile(id.clone(), request.clone(), now_unix())).await
        })
        .await
    }

    pub async fn delete_placement_profile(&self, id: String) -> Result<(), ReplicaError> {
        let client = self.client().await?;
        on_runtime(async move { retrying(|| client.delete_placement_profile(id.clone())).await })
            .await
    }

    /// Orders the profiles as `ids` lists them.
    pub async fn order_placement_profiles(&self, ids: Vec<String>) -> Result<(), ReplicaError> {
        let client = self.client().await?;
        on_runtime(async move { retrying(|| client.order_placement_profiles(ids.clone())).await })
            .await
    }

    pub async fn set_alert_fix_profile(&self, profile: String) -> Result<(), ReplicaError> {
        let client = self.client().await?;
        on_runtime(async move { retrying(|| client.set_alert_fix_profile(profile.clone())).await })
            .await
    }

    /// `request` is `PUT /v1/placement/actions`'s JSON body.
    pub async fn set_placement_actions(&self, request: String) -> Result<(), ReplicaError> {
        let request: SetPlacementActionsRequest = serde_json::from_str(&request)?;
        let client = self.client().await?;
        on_runtime(async move { retrying(|| client.set_placement_actions(request.clone())).await })
            .await
    }

    /// `policy` is `PUT /v1/placement/policy`'s JSON body.
    pub async fn set_placement_policy(&self, policy: String) -> Result<(), ReplicaError> {
        let policy: PlacementPolicy = serde_json::from_str(&policy)?;
        let client = self.client().await?;
        on_runtime(async move { retrying(|| client.set_placement_policy(policy.clone())).await })
            .await
    }

    /// Answers an app's Prism API request (`docs/prism-api.md`) from this
    /// replica: reads from its tables, actions as request rows the leader
    /// runs (see `api.rs`). Errors come back as the API's `{"error",
    /// "code"}` bodies with their status.
    pub async fn call(&self, request: ApiRequest) -> ApiResponse {
        let client = match self.client().await {
            Ok(client) => client,
            Err(err) => {
                return ApiResponse {
                    status: 503,
                    body: serde_json::json!({"error": err.to_string(), "code": "unavailable"})
                        .to_string(),
                };
            }
        };
        on_runtime(async move { Ok(api::call(client, request).await) })
            .await
            .unwrap_or_else(|err| ApiResponse {
                status: 503,
                body: serde_json::json!({"error": err.to_string(), "code": "unavailable"})
                    .to_string(),
            })
    }

    /// Watches the replica for changes to `tables` (every table when null),
    /// whether this device wrote them or they arrived by sync.
    pub async fn changes(&self, tables: Option<Vec<String>>) -> Result<Arc<Changes>, ReplicaError> {
        let client = self.client().await?;
        on_runtime(async move {
            let events = client.observe(tables).await?;
            Ok(Arc::new(Changes {
                events: Arc::new(tokio::sync::Mutex::new(events)),
            }))
        })
        .await
    }
}

/// A stream of the replica's changes (`Replica.changes`).
#[derive(uniffi::Object)]
pub struct Changes {
    events: Arc<tokio::sync::Mutex<rocky_sdk::TableEvents>>,
}

#[uniffi::export]
impl Changes {
    /// Waits for the next committed change and returns the tables it touched.
    /// Fails once the replica is closed.
    pub async fn next(&self) -> Result<Vec<String>, ReplicaError> {
        let events = Arc::clone(&self.events);
        on_runtime(async move {
            let change = events
                .lock()
                .await
                .next()
                .await
                .map_err(|err| ReplicaError::Storage(err.to_string()))?;
            Ok(change.tables)
        })
        .await
    }
}
