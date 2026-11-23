//! Android self-update (`GET /v1/android/release(.apk)`, `PUT
//! /v1/android/release`), ported from the Go server's `android.go`.
//!
//! The release row replicates through Rocky; the APK (up to 200 MiB) stays
//! on the data volume of the worker that took the upload and is streamed,
//! never held in memory: the pod's limit is 256 MiB.

use crate::http::{AppState, require_machine_token};
use axum::{
    Json,
    body::Body,
    extract::{Query, State},
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
};
use futures_util::StreamExt;
use rocky_prism::AndroidReleaseRow;
use serde_json::json;
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

pub const MAX_ANDROID_APK_BYTES: u64 = 200 << 20;
const DEFAULT_ANDROID_PACKAGE: &str = "at.quickme.prism";
const READ_CHUNK: usize = 64 << 10;

fn error(status: StatusCode, message: impl Into<String>, code: &str) -> Response {
    (
        status,
        Json(json!({ "error": message.into(), "code": code })),
    )
        .into_response()
}

fn invalid(message: impl Into<String>) -> Response {
    error(StatusCode::BAD_REQUEST, message, "invalid")
}

fn unavailable(err: impl std::fmt::Display) -> Response {
    eprintln!("prism-worker: android release failed: {err}");
    error(
        StatusCode::SERVICE_UNAVAILABLE,
        "Something went wrong. Try again.",
        "unavailable",
    )
}

/// Go's `androidPackageName`: `^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$`, at most 200 bytes.
pub fn valid_android_package(pkg: &str) -> bool {
    if pkg.len() > 200 || !pkg.contains('.') {
        return false;
    }
    pkg.split('.').all(|seg| {
        let b = seg.as_bytes();
        !b.is_empty()
            && b[0].is_ascii_lowercase()
            && b.iter()
                .all(|&c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'_')
    })
}

/// The request's `?package=`, the release app's by default.
#[allow(clippy::result_large_err)]
fn request_package(q: &BTreeMap<String, String>) -> Result<String, Response> {
    match q.get("package").map(String::as_str) {
        None | Some("") => Ok(DEFAULT_ANDROID_PACKAGE.into()),
        Some(p) if valid_android_package(p) => Ok(p.into()),
        Some(_) => Err(invalid("package must be an Android package name.")),
    }
}

fn package_dir(data_dir: &Path, package: &str) -> PathBuf {
    // `package` passed valid_android_package: no separators, no dots-only segments.
    data_dir.join("android").join(package)
}

pub fn apk_path(data_dir: &Path, package: &str, version_code: i64) -> PathBuf {
    package_dir(data_dir, package).join(format!("{version_code}.apk"))
}

/// A temporary file next to the APK, unique per upload or fetch.
pub fn temp_path(data_dir: &Path, package: &str, version_code: i64) -> PathBuf {
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let n = NEXT.fetch_add(1, Ordering::Relaxed);
    package_dir(data_dir, package)
        .join(format!("{version_code}.apk.{}-{n}.tmp", std::process::id()))
}

/// Removes every stored APK of `package` but `keep`, so the volume holds one
/// version per package (Go drops the older chunks the same way).
pub async fn prune_apks(data_dir: &Path, package: &str, keep: i64) {
    let Ok(mut dir) = tokio::fs::read_dir(package_dir(data_dir, package)).await else {
        return;
    };
    let keep = format!("{keep}.apk");
    while let Ok(Some(entry)) = dir.next_entry().await {
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if name.ends_with(".apk") && name != keep {
            let _ = tokio::fs::remove_file(entry.path()).await;
        }
    }
}

/// The stored APK of `rel`, if this worker has it complete.
pub async fn open_local_apk(data_dir: &Path, rel: &AndroidReleaseRow) -> Option<tokio::fs::File> {
    let file = tokio::fs::File::open(apk_path(data_dir, &rel.package, rel.version_code))
        .await
        .ok()?;
    let len = file.metadata().await.ok()?.len();
    (len == rel.size as u64).then_some(file)
}

/// A file as a response body, read in chunks.
pub fn file_body(file: tokio::fs::File) -> Body {
    let stream = futures_util::stream::unfold(Some(file), |file| async move {
        let mut file = file?;
        let mut buf = vec![0u8; READ_CHUNK];
        match file.read(&mut buf).await {
            Ok(0) => None,
            Ok(n) => {
                buf.truncate(n);
                Some((
                    Ok::<_, std::io::Error>(axum::body::Bytes::from(buf)),
                    Some(file),
                ))
            }
            Err(err) => Some((Err(err), None)),
        }
    });
    Body::from_stream(stream)
}

#[allow(clippy::result_large_err)]
async fn current_release(state: &AppState, pkg: &str) -> Result<AndroidReleaseRow, Response> {
    match state.client.get_android_release(pkg).await {
        Ok(Some(rel)) => Ok(rel),
        Ok(None) => Err(error(
            StatusCode::NOT_FOUND,
            format!("No version of {pkg} is published."),
            "not_found",
        )),
        Err(err) => Err(unavailable(err)),
    }
}

/// `GET /v1/android/release`: the published version of a package.
pub async fn get_release(
    State(state): State<AppState>,
    Query(q): Query<BTreeMap<String, String>>,
) -> Response {
    let pkg = match request_package(&q) {
        Ok(p) => p,
        Err(resp) => return resp,
    };
    match current_release(&state, &pkg).await {
        Ok(rel) => (StatusCode::OK, Json(rel)).into_response(),
        Err(resp) => resp,
    }
}

/// `GET /v1/android/release.apk`: the published APK.
pub async fn get_release_apk(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(q): Query<BTreeMap<String, String>>,
) -> Response {
    let pkg = match request_package(&q) {
        Ok(p) => p,
        Err(resp) => return resp,
    };
    let rel = match current_release(&state, &pkg).await {
        Ok(rel) => rel,
        Err(resp) => return resp,
    };
    let file = match open_local_apk(&state.data_dir, &rel).await {
        Some(file) => Some(file),
        // Uploaded to another worker: fetch it from there once, unless this
        // request is itself such a fetch.
        None if !headers.contains_key(PEER_FETCH_HEADER) => {
            match fetch_from_peer(&state, &rel).await {
                Ok(file) => Some(file),
                Err(err) => {
                    eprintln!(
                        "prism-worker: could not fetch {} {} from {}: {err}",
                        rel.package, rel.version_code, rel.stored_on
                    );
                    None
                }
            }
        }
        None => None,
    };
    let Some(file) = file else {
        return error(
            StatusCode::NOT_FOUND,
            "The APK is not available on this worker.",
            "not_found",
        );
    };
    apk_response(&rel, file)
}

const PEER_FETCH_HEADER: &str = "x-prism-peer-fetch";

/// Copies `rel`'s APK from the worker that stores it, checking its version,
/// size and SHA-256 against the replicated release row.
async fn fetch_from_peer(
    state: &AppState,
    rel: &AndroidReleaseRow,
) -> Result<tokio::fs::File, String> {
    if rel.stored_on.is_empty() || rel.stored_on == state.node {
        return Err("no other worker stores it".into());
    }
    let base = state
        .peer_http
        .as_ref()
        .and_then(|peer| peer(&rel.stored_on))
        .ok_or("no route to the worker")?;
    let url = format!(
        "{}/v1/android/release.apk?package={}",
        base.trim_end_matches('/'),
        rel.package
    );
    let http = reqwest::Client::builder()
        .connect_timeout(std::time::Duration::from_secs(5))
        .timeout(std::time::Duration::from_secs(30 * 60))
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .map_err(|e| e.to_string())?;
    let mut resp = http
        .get(&url)
        .header(PEER_FETCH_HEADER, "1")
        .send()
        .await
        .map_err(|e| e.to_string())?;
    let version = resp
        .headers()
        .get("x-version-code")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    if resp.status() != reqwest::StatusCode::OK || version != rel.version_code.to_string() {
        return Err(format!(
            "answered {} for version {version:?}",
            resp.status()
        ));
    }
    tokio::fs::create_dir_all(package_dir(&state.data_dir, &rel.package))
        .await
        .map_err(|e| e.to_string())?;
    let tmp = temp_path(&state.data_dir, &rel.package, rel.version_code);
    let result = async {
        let mut file = tokio::fs::File::create(&tmp)
            .await
            .map_err(|e| e.to_string())?;
        let mut hash = Sha256::new();
        let mut total: u64 = 0;
        while let Some(chunk) = resp.chunk().await.map_err(|e| e.to_string())? {
            total += chunk.len() as u64;
            if total > rel.size as u64 {
                return Err("longer than the release".to_string());
            }
            hash.update(&chunk);
            file.write_all(&chunk).await.map_err(|e| e.to_string())?;
        }
        if total != rel.size as u64 || hex::encode(hash.finalize()) != rel.sha256 {
            return Err("not the published APK".to_string());
        }
        file.sync_all().await.map_err(|e| e.to_string())
    }
    .await;
    if let Err(err) = result {
        let _ = tokio::fs::remove_file(&tmp).await;
        return Err(err);
    }
    let path = apk_path(&state.data_dir, &rel.package, rel.version_code);
    tokio::fs::rename(&tmp, &path)
        .await
        .map_err(|e| e.to_string())?;
    prune_apks(&state.data_dir, &rel.package, rel.version_code).await;
    open_local_apk(&state.data_dir, rel)
        .await
        .ok_or_else(|| "stored copy vanished".to_string())
}

fn apk_response(rel: &AndroidReleaseRow, file: tokio::fs::File) -> Response {
    (
        StatusCode::OK,
        [
            (
                header::CONTENT_TYPE,
                "application/vnd.android.package-archive".to_string(),
            ),
            (header::CONTENT_LENGTH, rel.size.to_string()),
            (header::CACHE_CONTROL, "no-store".to_string()),
            (
                header::HeaderName::from_static("x-version-code"),
                rel.version_code.to_string(),
            ),
            (
                header::HeaderName::from_static("x-content-sha256"),
                rel.sha256.clone(),
            ),
        ],
        file_body(file),
    )
        .into_response()
}

/// Streams `body` into `tmp`, checking what Go's `PublishAndroid` checks:
/// an APK (zip) of exactly `size` bytes whose SHA-256 is `sha`.
#[allow(clippy::result_large_err)]
async fn receive_apk(body: Body, tmp: &Path, size: u64, sha: &str) -> Result<(), Response> {
    let mut file = tokio::fs::File::create(tmp).await.map_err(unavailable)?;
    let mut stream = body.into_data_stream();
    let mut hash = Sha256::new();
    let mut total: u64 = 0;
    let mut head = Vec::with_capacity(4);
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|err| invalid(format!("The upload broke off: {err}")))?;
        if head.len() < 4 {
            let take = (4 - head.len()).min(chunk.len());
            head.extend_from_slice(&chunk[..take]);
            if !b"PK\x03\x04".starts_with(&head) {
                return Err(invalid("The upload is not an APK."));
            }
        }
        total += chunk.len() as u64;
        if total > size {
            return Err(invalid("The upload is larger than its Content-Length."));
        }
        hash.update(&chunk);
        file.write_all(&chunk).await.map_err(unavailable)?;
    }
    if total != size {
        return Err(invalid(format!(
            "The upload ended after {total} of {size} bytes."
        )));
    }
    let got = hex::encode(hash.finalize());
    if got != sha {
        return Err(invalid(format!(
            "The upload's SHA-256 is {got}, not the announced one."
        )));
    }
    file.flush().await.map_err(unavailable)?;
    file.sync_all().await.map_err(unavailable)?;
    Ok(())
}

/// `PUT /v1/android/release?package=&versionCode=&versionName=&sha256=`:
/// a publisher machine uploads a new version.
pub async fn put_release(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(q): Query<BTreeMap<String, String>>,
    body: Body,
) -> Response {
    let publisher = match require_machine_token(&state, &headers) {
        Ok(m) if state.cfg.android_publishers.iter().any(|p| p == &m) => m,
        _ => {
            eprintln!("prism-worker: rejected an Android upload");
            return error(
                StatusCode::UNAUTHORIZED,
                "Only a publisher machine may upload.",
                "unauthorized",
            );
        }
    };
    let pkg = match request_package(&q) {
        Ok(p) => p,
        Err(resp) => return resp,
    };
    let raw_code = q.get("versionCode").map(String::as_str).unwrap_or("");
    let version_code: i64 = match raw_code.parse() {
        Ok(v) if v > 0 => v,
        _ => return invalid("versionCode must be a positive integer."),
    };
    let size = headers
        .get(header::CONTENT_LENGTH)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(0);
    if size == 0 || size > MAX_ANDROID_APK_BYTES {
        return invalid(format!(
            "The upload needs a Content-Length of at most {MAX_ANDROID_APK_BYTES} bytes."
        ));
    }
    let sha = q
        .get("sha256")
        .map(|s| s.trim().to_ascii_lowercase())
        .unwrap_or_default();
    if sha.len() != 64 || !sha.bytes().all(|b| b.is_ascii_hexdigit()) {
        return invalid("sha256 (64 hex digits) is required.");
    }
    let mut version_name =
        rocky_prism::truncate(q.get("versionName").map(|s| s.trim()).unwrap_or(""), 100);
    if version_name.is_empty() {
        version_name = raw_code.to_string();
    }
    match state.client.get_android_release(&pkg).await {
        Ok(Some(current)) if version_code <= current.version_code => {
            return error(
                StatusCode::CONFLICT,
                format!(
                    "Version {} is published; an upload needs a higher version code.",
                    current.version_code
                ),
                "conflict",
            );
        }
        Ok(_) => {}
        Err(err) => return unavailable(err),
    }

    if let Err(err) = tokio::fs::create_dir_all(package_dir(&state.data_dir, &pkg)).await {
        return unavailable(err);
    }
    let tmp = temp_path(&state.data_dir, &pkg, version_code);
    if let Err(resp) = receive_apk(body, &tmp, size, &sha).await {
        let _ = tokio::fs::remove_file(&tmp).await;
        return resp;
    }
    let rel = AndroidReleaseRow {
        package: pkg.clone(),
        version_code,
        version_name,
        sha256: sha,
        size: size as i64,
        uploaded_at: (state.now_fn)(),
        uploaded_by: publisher,
        stored_on: state.node.clone(),
    };
    // The row first: an upload that lost a race must not replace the
    // winner's file.
    match state.client.put_android_release(&rel).await {
        Ok(true) => {}
        Ok(false) => {
            let _ = tokio::fs::remove_file(&tmp).await;
            return error(
                StatusCode::CONFLICT,
                "A newer version was published during this upload.",
                "conflict",
            );
        }
        Err(err) => {
            let _ = tokio::fs::remove_file(&tmp).await;
            return unavailable(err);
        }
    }
    let path = apk_path(&state.data_dir, &pkg, version_code);
    if let Err(err) = tokio::fs::rename(&tmp, &path).await {
        let _ = tokio::fs::remove_file(&tmp).await;
        return unavailable(err);
    }
    prune_apks(&state.data_dir, &pkg, version_code).await;
    println!(
        "prism-worker: android app published: {pkg} {version_code} ({size} bytes) by {}",
        rel.uploaded_by
    );
    (StatusCode::CREATED, Json(rel)).into_response()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth::machine_token;
    use crate::config::Config;
    use crate::http::router;
    use axum::http::Request;
    use http_body_util::BodyExt;
    use rocky_prism::PrismClient;
    use std::sync::Arc;
    use tower::ServiceExt;

    const KEY: &[u8] = b"test-event-key-0123456789abcdef0123456789";

    pub(crate) fn apk(tag: &str) -> Vec<u8> {
        let mut v = b"PK\x03\x04".to_vec();
        v.extend_from_slice(tag.repeat(40_000).as_bytes());
        v
    }

    pub(crate) fn sha(data: &[u8]) -> String {
        hex::encode(Sha256::digest(data))
    }

    pub(crate) async fn state(client: &Arc<PrismClient>, data: &Path, node: &str) -> AppState {
        let cfg = Config {
            android_publishers: vec!["builder".into()],
            ..Default::default()
        };
        AppState::new(Arc::clone(client), Some(KEY.to_vec()))
            .with_config(Arc::new(cfg))
            .with_data_dir(data.to_path_buf())
            .with_node(node)
    }

    pub(crate) async fn put(
        app: &axum::Router,
        machine: &str,
        query: &str,
        body: Vec<u8>,
        content_length: Option<usize>,
    ) -> (StatusCode, serde_json::Value) {
        let token = machine_token(KEY, machine).unwrap();
        let mut req = Request::builder()
            .method("PUT")
            .uri(format!("/v1/android/release?{query}"))
            .header("authorization", format!("Bearer {token}"));
        if let Some(len) = content_length.or(Some(body.len())) {
            req = req.header("content-length", len.to_string());
        }
        let res = app
            .clone()
            .oneshot(req.body(Body::from(body)).unwrap())
            .await
            .unwrap();
        let status = res.status();
        let body = res.into_body().collect().await.unwrap().to_bytes();
        (status, serde_json::from_slice(&body).unwrap_or_default())
    }

    pub(crate) async fn get(app: &axum::Router, uri: &str) -> (StatusCode, HeaderMap, Vec<u8>) {
        let res = app
            .clone()
            .oneshot(Request::builder().uri(uri).body(Body::empty()).unwrap())
            .await
            .unwrap();
        let (status, headers) = (res.status(), res.headers().clone());
        let body = res.into_body().collect().await.unwrap().to_bytes().to_vec();
        (status, headers, body)
    }

    #[tokio::test]
    async fn uploads_are_checked_like_the_go_server_checks_them() {
        let dir = tempfile::tempdir().unwrap();
        let client = Arc::new(
            PrismClient::open_or_create(dir.path().join("db").display().to_string(), None)
                .await
                .unwrap(),
        );
        let data = dir.path().join("data");
        let app = router(state(&client, &data, "prism-worker-0").await);
        let v10 = apk("ten");
        let q10 = format!("versionCode=10&versionName=1.0&sha256={}", sha(&v10));

        let (status, _) = put(&app, "home-mac", &q10, v10.clone(), None).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED, "not a publisher");
        for (query, body, len) in [
            ("versionCode=10".to_string(), v10.clone(), None),
            (
                format!("versionCode=0&sha256={}", sha(&v10)),
                v10.clone(),
                None,
            ),
            (q10.clone(), b"not a zip at all".to_vec(), None),
            (
                format!("versionCode=10&sha256={}", sha(b"other")),
                v10.clone(),
                None,
            ),
            (q10.clone(), v10.clone(), Some(v10.len() + 10)),
            (q10.clone(), v10.clone(), Some(0)),
            (format!("{q10}&package=../../etc"), v10.clone(), None),
            (format!("{q10}&package=At.Quickme"), v10.clone(), None),
        ] {
            let (status, body) = put(&app, "builder", &query, body, len).await;
            assert_eq!(status, StatusCode::BAD_REQUEST, "{query} {body}");
        }
        assert!(
            client
                .get_android_release(DEFAULT_ANDROID_PACKAGE)
                .await
                .unwrap()
                .is_none()
        );

        let (status, rel) = put(&app, "builder", &q10, v10.clone(), None).await;
        assert_eq!(status, StatusCode::CREATED, "{rel}");
        assert_eq!(rel["versionCode"], 10);
        assert_eq!(rel["uploadedBy"], "builder");
        assert!(rel.get("storedOn").is_none());
        let (status, _) = put(&app, "builder", &q10, v10.clone(), None).await;
        assert_eq!(status, StatusCode::CONFLICT);

        let (status, _, body) = get(&app, "/v1/android/release").await;
        assert_eq!(status, StatusCode::OK);
        let meta: serde_json::Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(meta["sha256"], sha(&v10));
        let (status, headers, body) = get(&app, "/v1/android/release.apk").await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(headers["x-version-code"], "10");
        assert_eq!(body, v10);

        // A newer version replaces the stored one; the volume keeps one per package.
        let v11 = apk("eleven");
        let q11 = format!("versionCode=11&sha256={}", sha(&v11));
        let (status, rel) = put(&app, "builder", &q11, v11.clone(), None).await;
        assert_eq!(status, StatusCode::CREATED);
        assert_eq!(rel["versionName"], "11", "defaults to the version code");
        let (_, _, body) = get(&app, "/v1/android/release.apk").await;
        assert_eq!(body, v11);
        let mut files: Vec<String> =
            std::fs::read_dir(data.join("android").join(DEFAULT_ANDROID_PACKAGE))
                .unwrap()
                .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
                .collect();
        files.sort();
        assert_eq!(files, ["11.apk"]);

        // Another package has its own channel.
        let (status, _, _) = get(
            &app,
            "/v1/android/release.apk?package=at.quickme.prism.debug",
        )
        .await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn a_worker_serves_an_apk_uploaded_to_another_worker() {
        let dir = tempfile::tempdir().unwrap();
        // One database stands in for the replicated one; each worker has its own volume.
        let client = Arc::new(
            PrismClient::open_or_create(dir.path().join("db").display().to_string(), None)
                .await
                .unwrap(),
        );
        let a = router(state(&client, &dir.path().join("a"), "prism-worker-0").await);
        let a_url = crate::testutil::serve(a.clone()).await;
        let b_state = state(&client, &dir.path().join("b"), "prism-worker-1")
            .await
            .with_peer_http(Arc::new(move |node: &str| {
                (node == "prism-worker-0").then(|| a_url.clone())
            }));
        let b = router(b_state);

        let v10 = apk("ten");
        let q = format!("versionCode=10&sha256={}", sha(&v10));
        assert_eq!(
            put(&a, "builder", &q, v10.clone(), None).await.0,
            StatusCode::CREATED
        );

        let (status, _, body) = get(&b, "/v1/android/release.apk").await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(body, v10);
        // Cached on b's volume now.
        assert!(
            dir.path()
                .join("b/android/at.quickme.prism/10.apk")
                .exists()
        );

        // A peer fetch is never forwarded again.
        std::fs::remove_file(dir.path().join("a/android/at.quickme.prism/10.apk")).unwrap();
        std::fs::remove_file(dir.path().join("b/android/at.quickme.prism/10.apk")).unwrap();
        let res = b
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/v1/android/release.apk")
                    .header(PEER_FETCH_HEADER, "1")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(res.status(), StatusCode::NOT_FOUND);
        let (status, _, _) = get(&b, "/v1/android/release.apk").await;
        assert_eq!(status, StatusCode::NOT_FOUND);
    }
}
