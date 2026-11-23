//! The ntfy wake-up (Go's `push.go`). Only the leader posts. The phone wakes
//! up, syncs over Rocky and shows the notification from its replica; the
//! message is the same JSON the apps read today.

use crate::t3::Secrets;
use rocky_prism::{Notification, PUSH_STALE_SECS, PrismClient, PrismError};
use serde_json::json;
use std::time::Duration;

#[derive(Debug, thiserror::Error)]
pub enum PushError {
    #[error("ntfy request failed: {0}")]
    Request(String),
    #[error("ntfy answered HTTP {0}")]
    Status(u16),
    #[error(transparent)]
    Storage(#[from] PrismError),
}

pub struct Pusher {
    url: String,
    topic: String,
    secrets: Secrets,
    http: reqwest::Client,
}

impl Pusher {
    pub fn new(url: &str, topic: &str, secrets: Secrets) -> Self {
        Self {
            url: url.trim_end_matches('/').to_string(),
            topic: topic.to_string(),
            secrets,
            http: reqwest::Client::builder()
                .timeout(Duration::from_secs(15))
                .build()
                .expect("reqwest client"),
        }
    }

    /// Posts one message with ntfy's JSON publishing, which keeps UTF-8
    /// titles intact.
    async fn publish(&self, token: &str, n: &Notification) -> Result<(), PushError> {
        let body = json!({
            "topic": self.topic,
            "message": n.message(),
            "title": rocky_prism::truncate(rocky_prism::first_line(&n.title), 200),
            "priority": n.priority(),
            "tags": ["prism", n.kind.replace('.', "-")],
        });
        let resp = self
            .http
            .post(format!("{}/", self.url))
            .bearer_auth(token)
            .json(&body)
            .send()
            .await
            .map_err(|err| PushError::Request(err.to_string()))?;
        let status = resp.status().as_u16();
        if status >= 300 {
            return Err(PushError::Status(status));
        }
        Ok(())
    }

    /// Posts each notification not posted yet and records it. Without a
    /// token, or when it is too old to matter, it is recorded unposted.
    pub async fn deliver(
        &self,
        client: &PrismClient,
        notifications: &[Notification],
        now: i64,
    ) -> Result<usize, PushError> {
        if notifications.is_empty() {
            return Ok(0);
        }
        let sent = client.pushed_keys().await?;
        let token = (self.secrets)("PRISM_PUSH_TOKEN");
        let mut posted = 0;
        for n in notifications {
            if sent.contains(&n.key) {
                continue;
            }
            if let Some(token) = token.as_deref()
                && now - n.time <= PUSH_STALE_SECS
            {
                self.publish(token, n).await?;
                posted += 1;
            }
            client.record_push(&n.key, now).await?;
        }
        Ok(posted)
    }
}
