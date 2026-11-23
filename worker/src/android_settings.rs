//! A phone's server-provided settings (`PUT|GET /v1/android/settings`),
//! ported from the Go server's `android.go`.
//!
//! The publisher (`android/publish.py`) uploads each device's settings, the
//! keys the app's managed configuration had; the device reads its own from
//! its replica (`GET /v1/android/settings`, rocky-prism-ffi). They live in
//! the replicated `settings` table under `android.settings.<device>`; a
//! change makes the leader push app.settings, which carries no values.
//! Values are never logged.

use crate::admin::{error, unavailable};
use crate::http::{AppState, require_machine_token};
use axum::{
    Json,
    body::Bytes,
    extract::State,
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use rocky_prism::{Notification, SettingRow};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::BTreeMap;

pub const SETTINGS_KEY: &str = "android.settings.";
const MAX_SETTING_VALUE: usize = 4096;
const MAX_BODY: usize = 64 << 10;

/// The settings the server may hand a phone: every key of the app's
/// res/xml/app_restrictions.xml except how the phone reaches this server and
/// NetBird's enrollment, which only enrollment over USB sets.
const SETTING_KEYS: [&str; 17] = [
    "relay_url",
    "relay_token",
    "t3_url",
    "device_name",
    "telemetry_url",
    "telemetry_user",
    "telemetry_password",
    "background_restricted",
    "background_charging_only",
    "activity_recognition_denied",
    "hotspot_key",
    // The assistant overlay's harness (android/src/at/quickme/prism/assistant).
    "assistant_url",
    "assistant_api_key",
    "assistant_model",
    "matrix_homeserver",
    "matrix_access_token",
    "call_mac",
];

/// A device's settings as the phone reads them (Go's `AndroidSettings`).
/// A null value clears the setting on the phone.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AndroidSettings {
    pub device: String,
    pub settings: BTreeMap<String, Option<String>>,
    pub updated_at: i64,
    pub updated_by: String,
}

#[derive(Default, Deserialize)]
struct PutBody {
    #[serde(default)]
    device: String,
    #[serde(default)]
    settings: BTreeMap<String, Option<String>>,
}

fn check(body: &PutBody) -> Result<(), String> {
    if !crate::auth::is_valid_machine_name(&body.device) {
        return Err("device must be a device name.".into());
    }
    if body.settings.is_empty() {
        return Err("settings must name at least one key.".into());
    }
    for (key, value) in &body.settings {
        if !SETTING_KEYS.contains(&key.as_str()) {
            return Err(format!("The server can't set {}.", json!(key)));
        }
        if value.as_ref().is_some_and(|v| v.len() > MAX_SETTING_VALUE) {
            return Err(format!(
                "The value of {} is longer than {MAX_SETTING_VALUE} bytes.",
                json!(key)
            ));
        }
    }
    Ok(())
}

async fn stored(state: &AppState, device: &str) -> Result<Option<AndroidSettings>, String> {
    Ok(state
        .client
        .get_setting(&format!("{SETTINGS_KEY}{device}"))
        .await
        .map_err(|e| e.to_string())?
        .and_then(|raw| serde_json::from_str(&raw).ok()))
}

/// `PUT /v1/android/settings` (publisher only) → `{device, changed}`.
pub async fn put_settings(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> Response {
    let publisher = match require_machine_token(&state, &headers) {
        Ok(m) if state.cfg.android_publishers.iter().any(|p| p == &m) => m,
        _ => {
            eprintln!("prism-worker: rejected Android settings");
            return error(
                StatusCode::UNAUTHORIZED,
                "Only a publisher machine may set a device's settings.",
                "unauthorized",
            );
        }
    };
    if body.len() > MAX_BODY {
        return error(
            StatusCode::BAD_REQUEST,
            "The request body is too large.",
            "invalid",
        );
    }
    let req: PutBody = if body.trim_ascii().is_empty() {
        PutBody::default()
    } else {
        match serde_json::from_slice(&body) {
            Ok(r) => r,
            Err(_) => {
                return error(
                    StatusCode::BAD_REQUEST,
                    "The request body is not valid JSON.",
                    "invalid",
                );
            }
        }
    };
    if let Err(message) = check(&req) {
        return error(StatusCode::BAD_REQUEST, message, "invalid");
    }
    let current = match stored(&state, &req.device).await {
        Ok(c) => c,
        Err(err) => return unavailable("android settings", err),
    };
    let changed = current.is_none_or(|c| c.settings != req.settings);
    if changed {
        let now = (state.now_fn)();
        let row = AndroidSettings {
            device: req.device.clone(),
            settings: req.settings.clone(),
            updated_at: now,
            updated_by: publisher.clone(),
        };
        let raw = serde_json::to_string(&row).unwrap_or_default();
        if let Err(err) = state
            .client
            .set_setting(&format!("{SETTINGS_KEY}{}", req.device), &raw, now)
            .await
        {
            return unavailable("android settings", err);
        }
        let keys: Vec<&str> = req.settings.keys().map(String::as_str).collect();
        println!(
            "prism-worker: android settings published for {} ({}) by {publisher}",
            req.device,
            keys.join(",")
        );
    }
    (
        StatusCode::OK,
        Json(json!({ "device": req.device, "changed": changed })),
    )
        .into_response()
}

/// The app.settings pushes for settings changed in the last day: one per
/// change, keyed by the change's time, so the pusher sends each once.
pub fn settings_pushes(rows: &[SettingRow], now: i64) -> Vec<Notification> {
    rows.iter()
        .filter(|row| now - row.updated_at < 86_400)
        .filter_map(|row| {
            let device = row.key.strip_prefix(SETTINGS_KEY)?;
            let s: AndroidSettings = serde_json::from_str(&row.value).ok()?;
            Some(Notification {
                kind: "app.settings".into(),
                key: format!("app.settings:{device}:{}", s.updated_at),
                time: s.updated_at,
                title: "Prism settings".into(),
                body: format!("New settings for {device}."),
                device: device.to_string(),
                ..Notification::default()
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_assistant_settings_can_be_published() {
        for key in [
            "assistant_url",
            "assistant_api_key",
            "assistant_model",
            "matrix_homeserver",
            "matrix_access_token",
        ] {
            let body = PutBody {
                device: "david-pixel".into(),
                settings: BTreeMap::from([(key.to_string(), Some("x".to_string()))]),
            };
            assert_eq!(check(&body), Ok(()), "{key}");
        }
        let body = PutBody {
            device: "david-pixel".into(),
            settings: BTreeMap::from([("rocky_peers".to_string(), Some("x".to_string()))]),
        };
        assert!(check(&body).is_err(), "enrollment-only keys stay refused");
    }
}
