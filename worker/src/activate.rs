//! Starts a profile account's next weekly window as soon as the last one
//! ended: the leader sends the account a very small prompt through the AI
//! gateway (`text_generation.url`, which routes `<account>/<model>` to that
//! account), so the new window starts now and not at the next thread's first
//! request (`rocky_prism::weekly`, `docs/placement.md`). Which windows ended
//! comes from ClickHouse (`usage.rs`); without `usage_url`, nothing is sent.

use crate::config::Config;
use crate::t3::Secrets;
use rocky_prism::{PrismClient, UsageByAccount, WeeklyActivation};
use serde_json::json;
use std::time::Duration;

/// How often the leader looks for ended weekly windows.
pub const ACTIVATE_EVERY: Duration = Duration::from_secs(60);
/// A model answering "OK" takes seconds; a cold account can take longer.
const PROMPT_TIMEOUT: Duration = Duration::from_secs(120);
const PROMPT: &str = "Reply with OK.";

/// One pass: asks the replica which accounts are due and prompts each.
pub async fn activate_weekly_windows(
    client: &PrismClient,
    cfg: &Config,
    secrets: &Secrets,
    usage: &UsageByAccount,
    now: i64,
) -> Result<(), String> {
    let url = cfg.text_generation.url.trim_end_matches('/');
    if url.is_empty() || usage.is_empty() {
        return Ok(());
    }
    let due = rocky_prism::retry_unavailable(|| client.due_weekly_activations(now, usage))
        .await
        .map_err(|err| err.to_string())?;
    if due.is_empty() {
        return Ok(());
    }
    let http = reqwest::Client::builder()
        .timeout(PROMPT_TIMEOUT)
        .build()
        .map_err(|err| err.to_string())?;
    let key = secrets("PRISM_TEXT_GENERATION_KEY").filter(|k| !k.is_empty());
    for activation in due {
        match prompt(&http, url, key.as_deref(), &activation).await {
            Ok(()) => eprintln!(
                "prism-worker: started {}'s weekly window ({})",
                activation.account, activation.model
            ),
            Err(err) => eprintln!(
                "prism-worker: starting {}'s weekly window failed; trying again later: {err}",
                activation.account
            ),
        }
    }
    Ok(())
}

async fn prompt(
    http: &reqwest::Client,
    url: &str,
    key: Option<&str>,
    activation: &WeeklyActivation,
) -> Result<(), String> {
    let mut req = http.post(format!("{url}/chat/completions")).json(&json!({
        "model": activation.model,
        "messages": [{"role": "user", "content": PROMPT}],
    }));
    if let Some(key) = key {
        req = req.bearer_auth(key);
    }
    let resp = req.send().await.map_err(|err| err.to_string())?;
    let status = resp.status();
    if status.is_success() {
        return Ok(());
    }
    let body = resp.text().await.unwrap_or_default();
    Err(format!(
        "{status}: {}",
        body.chars().take(200).collect::<String>()
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{Json, Router, extract::State, routing::post};
    use rocky_prism::{
        AccountUsage, PlacementWindow, PutPlacementEntryInput, PutPlacementProfileRequest,
    };
    use serde_json::Value;
    use std::sync::{Arc, Mutex};
    use tokio::net::TcpListener;

    // 2026-11-23T20:50:56Z, the moment codex-2's weekly window ended.
    const ENDED: i64 = 1_795_467_056;

    /// What ClickHouse says at `at`: codex-2's weekly window, and codex-3's,
    /// which no profile names.
    fn usage(weekly: PlacementWindow, at: i64) -> UsageByAccount {
        let reading = |weekly| AccountUsage {
            session: None,
            weekly: Some(weekly),
            observed_at: at,
        };
        UsageByAccount::from([
            ("codex-2".to_string(), reading(weekly)),
            (
                "codex-3".to_string(),
                reading(PlacementWindow {
                    used_percent: 100.0,
                    resets_at: "2026-11-23T20:00:00Z".into(),
                }),
            ),
        ])
    }

    #[tokio::test]
    async fn prompts_an_account_once_its_weekly_window_ended() {
        let posts: Arc<Mutex<Vec<Value>>> = Arc::default();
        let app = Router::new()
            .route(
                "/chat/completions",
                post(
                    |State(posts): State<Arc<Mutex<Vec<Value>>>>, Json(body): Json<Value>| async move {
                        posts.lock().unwrap().push(body);
                        Json(json!({"choices": [{"message": {"content": "OK"}}]}))
                    },
                ),
            )
            .with_state(Arc::clone(&posts));
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let addr = listener.local_addr().expect("addr");
        tokio::spawn(async move {
            let _ = axum::serve(listener, app).await;
        });
        let mut cfg = Config::parse("{}").expect("cfg");
        cfg.text_generation.url = format!("http://{addr}");
        let secrets: Secrets = Arc::new(|_| Some("key".into()));

        let dir = tempfile::tempdir().expect("tempdir");
        let client = PrismClient::open_or_create(dir.path().display().to_string(), None)
            .await
            .expect("open");
        client
            .put_placement_profile(
                "default".into(),
                PutPlacementProfileRequest {
                    label: "Default".into(),
                    entries: vec![PutPlacementEntryInput {
                        instance_id: "codex".into(),
                        model: "codex-2/gpt-6.2-sol".into(),
                        reasoning: String::new(),
                        enabled: true,
                        session_share_of_weekly: None,
                    }],
                },
                ENDED - 3600,
            )
            .await
            .expect("profile");

        // An hour before: the window runs, nothing is sent.
        let running = PlacementWindow {
            used_percent: 99.0,
            resets_at: "2026-11-23T20:50:56Z".into(),
        };
        let at = ENDED - 3600;
        activate_weekly_windows(&client, &cfg, &secrets, &usage(running.clone(), at), at)
            .await
            .expect("pass");
        assert!(posts.lock().unwrap().is_empty());

        // Two minutes after the reset, ClickHouse still has the ended window.
        let at = ENDED + 120;
        activate_weekly_windows(&client, &cfg, &secrets, &usage(running.clone(), at), at)
            .await
            .expect("pass");
        {
            let posts = posts.lock().unwrap();
            assert_eq!(posts.len(), 1);
            assert_eq!(posts[0]["model"], "codex-2/gpt-6.2-sol");
        }
        // The next pass waits for the prompt to show.
        activate_weekly_windows(
            &client,
            &cfg,
            &secrets,
            &usage(running.clone(), at),
            at + 60,
        )
        .await
        .expect("pass");
        assert_eq!(posts.lock().unwrap().len(), 1);

        // The new window shows: no more prompts.
        let started = PlacementWindow {
            used_percent: 0.0,
            resets_at: "2026-11-30T20:53:00Z".into(),
        };
        let at = ENDED + 15 * 60;
        activate_weekly_windows(&client, &cfg, &secrets, &usage(started, at), at)
            .await
            .expect("pass");
        assert_eq!(posts.lock().unwrap().len(), 1);
        client.close().await.expect("close");
    }
}
