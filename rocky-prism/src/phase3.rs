//! Phase 3 Rocky tables and typed client API:
//! - T3 MCP sessions (`t3_sessions`) and client sessions (`t3_client_sessions`)
//! - Approvals (`approvals`, `approval_answers`)
//! - Questions (`questions`, `question_answers`)
//! - Agents (`agents`, `agent_requests`, `agent_messages`, `agent_activity`)
//! - Machines, projects, settings, and Android releases

use crate::client::{PrismClient, storage_err};
use crate::types::PrismError;
use rocky_sdk::{Row, SqlValue, params};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

fn col_str(row: &Row, col: &str) -> String {
    row.get::<String>(col).unwrap_or_default()
}

fn col_opt_str(row: &Row, col: &str) -> Option<String> {
    row.get::<Option<String>>(col)
        .ok()
        .flatten()
        .filter(|s| !s.is_empty())
}

fn col_i64(row: &Row, col: &str) -> i64 {
    row.get::<i64>(col).unwrap_or(0)
}

fn col_opt_i64(row: &Row, col: &str) -> Option<i64> {
    row.get::<Option<i64>>(col).ok().flatten()
}

fn col_bool(row: &Row, col: &str) -> bool {
    col_i64(row, col) != 0
}

// ---------------------------------------------------------------------------
// T3 Sessions (paired MCP sessions & client API sessions)
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct T3SessionRow {
    pub machine: String,
    pub origin: String,
    pub token: String,
    pub environment_id: String,
    pub label: String,
    pub access: String,
    pub paired_at: i64,
    pub expires_at: i64,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct T3ClientSessionRow {
    pub machine: String,
    pub origin: String,
    pub token: String,
    pub environment_id: String,
    pub expires_at: i64,
}

// ---------------------------------------------------------------------------
// Approvals
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct ApprovalRow {
    pub approval_id: String,
    pub machine: String,
    pub thread_id: String,
    pub request_id: String,
    pub payload: String,
    pub state: String,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct ApprovalAnswerRow {
    pub request_id: String,
    pub approval_id: String,
    pub message: String,
    pub signature: String,
    pub requested_by: String,
    pub requested_at: i64,
    pub claimed_by: Option<String>,
    pub claimed_at: Option<i64>,
    pub done_at: Option<i64>,
    pub error: String,
}

// ---------------------------------------------------------------------------
// Questions
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct QuestionRow {
    pub question_id: String,
    pub thread_id: String,
    pub environment_id: String,
    pub host: String,
    pub client: String,
    pub via: String,
    pub title: String,
    pub payload: String,
    pub state: String,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct QuestionAnswerRow {
    pub request_id: String,
    pub question_id: String,
    pub answers_json: String,
    pub requested_by: String,
    pub requested_at: i64,
    pub claimed_by: Option<String>,
    pub claimed_at: Option<i64>,
    pub done_at: Option<i64>,
    pub error: String,
}

// ---------------------------------------------------------------------------
// Agents, requests, messages, activity
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct AgentRow {
    pub thread_id: String,
    pub kind: String,
    pub title: String,
    pub alert_id: i64,
    pub target: String,
    pub client: String,
    pub environment_id: String,
    pub host: String,
    pub link: String,
    pub request_text: String,
    pub state: String,
    pub run_status: String,
    pub question_pending: bool,
    pub last_message: String,
    pub requested_by: String,
    pub created_at: i64,
    pub updated_at: i64,
    pub next_check: i64,
    pub reported_run: String,
    pub terminal_run: String,
    pub terminal_seen: i64,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct AgentRequestRow {
    pub request_id: String,
    pub task: String,
    pub target: String,
    pub request_text: String,
    pub model_json: String,
    pub project_id: String,
    pub requested_by: String,
    pub requested_at: i64,
    pub claimed_by: Option<String>,
    pub claimed_at: Option<i64>,
    pub done_at: Option<i64>,
    pub thread_id: String,
    pub link: String,
    pub error: String,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct AgentMessageRow {
    pub request_id: String,
    pub agent_id: String,
    /// [`AGENT_MESSAGE`] or [`AGENT_STOP`].
    pub kind: String,
    pub text: String,
    pub requested_by: String,
    pub requested_at: i64,
    pub claimed_by: Option<String>,
    pub claimed_at: Option<i64>,
    pub done_at: Option<i64>,
    pub error: String,
}

/// An `agent_messages` row that sends its text to the agent.
pub const AGENT_MESSAGE: &str = "message";
/// An `agent_messages` row that stops the agent's run.
pub const AGENT_STOP: &str = "stop";

/// How a device request ended, as the leader recorded it: `done_at` is unset
/// while it is pending, `error` is empty when it worked. `thread_id` and
/// `link` are an agent start's thread.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct RequestOutcome {
    pub requested_at: i64,
    pub claimed_by: Option<String>,
    pub done_at: Option<i64>,
    pub error: String,
    pub thread_id: String,
    pub link: String,
}

/// The tables of device requests a [`RequestOutcome`] is read from.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RequestTable {
    ApprovalAnswers,
    QuestionAnswers,
    AgentRequests,
    AgentMessages,
}

impl RequestTable {
    fn name(self) -> &'static str {
        match self {
            Self::ApprovalAnswers => "approval_answers",
            Self::QuestionAnswers => "question_answers",
            Self::AgentRequests => "agent_requests",
            Self::AgentMessages => "agent_messages",
        }
    }
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct AgentActivityRow {
    pub agent_id: String,
    pub status: String,
    pub items_json: String,
    pub updated_at: i64,
}

// ---------------------------------------------------------------------------
// Machines, projects, settings, Android releases
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct MachineRow {
    pub name: String,
    pub status_json: String,
    pub metrics_json: String,
    pub updated_at: i64,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct ProjectRow {
    pub project_id: String,
    pub name: String,
    pub icon_json: String,
    pub machines_json: String,
    pub repository: bool,
    pub updated_at: i64,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct SettingRow {
    pub key: String,
    pub value: String,
    pub updated_at: i64,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AndroidReleaseRow {
    pub package: String,
    pub version_code: i64,
    pub version_name: String,
    pub sha256: String,
    pub size: i64,
    pub uploaded_at: i64,
    pub uploaded_by: String,
    /// The worker whose data volume holds the APK.
    #[serde(skip)]
    pub stored_on: String,
}

// ---------------------------------------------------------------------------
// PrismClient implementations
// ---------------------------------------------------------------------------

impl PrismClient {
    async fn exec_one(&self, sql: &str, args: Vec<SqlValue>) -> Result<(), PrismError> {
        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        tx.execute(sql, args).await.map_err(storage_err)?;
        tx.commit().await.map_err(storage_err)?;
        Ok(())
    }

    async fn insert_one(
        &self,
        table: &str,
        sql: &str,
        mut args: Vec<SqlValue>,
    ) -> Result<(), PrismError> {
        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        let id = tx.next_id(table, vec![]).await.map_err(storage_err)?;
        args.insert(0, id.into());
        tx.execute(sql, args).await.map_err(storage_err)?;
        tx.commit().await.map_err(storage_err)?;
        Ok(())
    }

    async fn request_exists(&self, table: &str, request_id: &str) -> Result<bool, PrismError> {
        let mut rows = self
            .db
            .query(
                &format!("SELECT 1 FROM {table} WHERE request_id = ? LIMIT 1"),
                params![request_id],
            )
            .await
            .map_err(storage_err)?;
        Ok(rows.next().await.map_err(storage_err)?.is_some())
    }

    async fn claim_request(
        &self,
        table: &str,
        request_id: &str,
        worker: &str,
        now: i64,
        stale_before: i64,
    ) -> Result<bool, PrismError> {
        let _guard = self.alerts_lock.lock().await;
        self.exec_one(
            &format!(
                "UPDATE {table} SET claimed_by = ?, claimed_at = ? \
                 WHERE request_id = ? AND done_at IS NULL \
                 AND (claimed_by IS NULL OR claimed_by = ? OR (claimed_at < ? AND claimed_by NOT LIKE 'device:%'))"
            ),
            params![worker, now, request_id, worker, stale_before],
        )
        .await?;
        let mut rows = self
            .db
            .query(
                &format!("SELECT claimed_by, done_at FROM {table} WHERE request_id = ?"),
                params![request_id],
            )
            .await
            .map_err(storage_err)?;
        let mut held = false;
        while let Some(r) = rows.next().await.map_err(storage_err)? {
            if col_opt_i64(&r, "done_at").is_some()
                || col_opt_str(&r, "claimed_by").as_deref() != Some(worker)
            {
                return Ok(false);
            }
            held = true;
        }
        Ok(held)
    }

    /// Forgets finished requests and closed approvals and questions older
    /// than `keep_secs` (leader only), so these replicated tables, which
    /// every phone and Mac holds, don't grow without bound. A device retrying
    /// a request after that long writes a new one.
    pub async fn prune_phase3(&self, now: i64, keep_secs: i64) -> Result<(), PrismError> {
        let before = now - keep_secs;
        let _guard = self.alerts_lock.lock().await;
        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        for table in [
            "approval_answers",
            "question_answers",
            "agent_requests",
            "agent_messages",
        ] {
            tx.execute(
                &format!("DELETE FROM {table} WHERE done_at IS NOT NULL AND done_at < ?"),
                params![before],
            )
            .await
            .map_err(storage_err)?;
        }
        for table in ["approvals", "questions"] {
            tx.execute(
                &format!("DELETE FROM {table} WHERE state != 'open' AND updated_at < ?"),
                params![before],
            )
            .await
            .map_err(storage_err)?;
        }
        tx.commit().await.map_err(storage_err)?;
        Ok(())
    }

    /// How the device request `request_id` in `table` ended, or `None`
    /// while it has not replicated here (or was pruned).
    pub async fn request_outcome(
        &self,
        table: RequestTable,
        request_id: &str,
    ) -> Result<Option<RequestOutcome>, PrismError> {
        let thread = match table {
            RequestTable::AgentRequests => "thread_id, link",
            _ => "'' AS thread_id, '' AS link",
        };
        let mut rows = self
            .db
            .query(
                &format!(
                    "SELECT requested_at, claimed_by, done_at, error, {thread} FROM {} \
                     WHERE request_id = ? ORDER BY done_at DESC LIMIT 1",
                    table.name()
                ),
                params![request_id],
            )
            .await
            .map_err(storage_err)?;
        Ok(rows
            .next()
            .await
            .map_err(storage_err)?
            .map(|r| RequestOutcome {
                requested_at: col_i64(&r, "requested_at"),
                claimed_by: col_opt_str(&r, "claimed_by"),
                done_at: col_opt_i64(&r, "done_at"),
                error: col_str(&r, "error"),
                thread_id: col_str(&r, "thread_id"),
                link: col_str(&r, "link"),
            }))
    }

    // --- T3 Sessions ---

    pub async fn upsert_t3_session(&self, row: &T3SessionRow) -> Result<(), PrismError> {
        let _guard = self.alerts_lock.lock().await;
        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        tx.execute(
            "DELETE FROM t3_sessions WHERE machine = ?",
            params![row.machine.clone()],
        )
        .await
        .map_err(storage_err)?;
        let id = tx
            .next_id("t3_sessions", vec![])
            .await
            .map_err(storage_err)?;
        tx.execute(
            "INSERT INTO t3_sessions (id, machine, origin, token, environment_id, label, access, paired_at, expires_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            params![id,
                row.machine.clone(),
                row.origin.clone(),
                row.token.clone(),
                row.environment_id.clone(),
                row.label.clone(),
                row.access.clone(),
                row.paired_at,
                row.expires_at,
            ],
        )
        .await
        .map_err(storage_err)?;
        tx.commit().await.map_err(storage_err)?;
        Ok(())
    }

    pub async fn list_t3_sessions(&self) -> Result<Vec<T3SessionRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT machine, origin, token, environment_id, label, access, paired_at, expires_at
                 FROM t3_sessions ORDER BY machine ASC, paired_at DESC",
                params![],
            )
            .await
            .map_err(storage_err)?;
        let mut by_machine: BTreeMap<String, T3SessionRow> = BTreeMap::new();
        while let Some(r) = rows.next().await.map_err(storage_err)? {
            let item = T3SessionRow {
                machine: col_str(&r, "machine"),
                origin: col_str(&r, "origin"),
                token: col_str(&r, "token"),
                environment_id: col_str(&r, "environment_id"),
                label: col_str(&r, "label"),
                access: col_str(&r, "access"),
                paired_at: col_i64(&r, "paired_at"),
                expires_at: col_i64(&r, "expires_at"),
            };
            by_machine.entry(item.machine.clone()).or_insert(item);
        }
        Ok(by_machine.into_values().collect())
    }

    pub async fn upsert_t3_client_session(
        &self,
        row: &T3ClientSessionRow,
    ) -> Result<(), PrismError> {
        let _guard = self.alerts_lock.lock().await;
        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        tx.execute(
            "DELETE FROM t3_client_sessions WHERE machine = ?",
            params![row.machine.clone()],
        )
        .await
        .map_err(storage_err)?;
        let id = tx
            .next_id("t3_client_sessions", vec![])
            .await
            .map_err(storage_err)?;
        tx.execute(
            "INSERT INTO t3_client_sessions (id, machine, origin, token, environment_id, expires_at)
             VALUES (?, ?, ?, ?, ?, ?)",
            params![id,
                row.machine.clone(),
                row.origin.clone(),
                row.token.clone(),
                row.environment_id.clone(),
                row.expires_at,
            ],
        )
        .await
        .map_err(storage_err)?;
        tx.commit().await.map_err(storage_err)?;
        Ok(())
    }

    pub async fn list_t3_client_sessions(
        &self,
        now: i64,
    ) -> Result<Vec<T3ClientSessionRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT machine, origin, token, environment_id, expires_at
                 FROM t3_client_sessions WHERE expires_at > ? ORDER BY machine ASC, expires_at DESC",
                params![now],
            )
            .await
            .map_err(storage_err)?;
        let mut by_machine: BTreeMap<String, T3ClientSessionRow> = BTreeMap::new();
        while let Some(r) = rows.next().await.map_err(storage_err)? {
            let item = T3ClientSessionRow {
                machine: col_str(&r, "machine"),
                origin: col_str(&r, "origin"),
                token: col_str(&r, "token"),
                environment_id: col_str(&r, "environment_id"),
                expires_at: col_i64(&r, "expires_at"),
            };
            by_machine.entry(item.machine.clone()).or_insert(item);
        }
        Ok(by_machine.into_values().collect())
    }

    // --- Approvals ---

    /// Inserts or re-opens an approval. Returns `true` if it was newly opened.
    pub async fn upsert_open_approval(&self, row: &ApprovalRow) -> Result<bool, PrismError> {
        let _guard = self.alerts_lock.lock().await;
        let mut existing = self
            .db
            .query(
                "SELECT state FROM approvals WHERE approval_id = ?",
                params![row.approval_id.clone()],
            )
            .await
            .map_err(storage_err)?;
        if let Some(first) = existing.next().await.map_err(storage_err)? {
            let state = col_str(&first, "state");
            drop(existing);
            if state == "open" {
                return Ok(false);
            }
            if state == "closed" {
                self.exec_one(
                    "UPDATE approvals SET state = 'open', payload = ?, updated_at = ? WHERE approval_id = ?",
                    params![row.payload.clone(), row.updated_at, row.approval_id.clone()],
                )
                .await?;
                return Ok(true);
            }
            return Ok(false);
        }
        drop(existing);
        self.insert_one(
            "approvals",
            "INSERT INTO approvals (id, approval_id, machine, thread_id, request_id, payload, state, created_at, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, 'open', ?, ?)",
            params![
                row.approval_id.clone(),
                row.machine.clone(),
                row.thread_id.clone(),
                row.request_id.clone(),
                row.payload.clone(),
                row.created_at,
                row.updated_at,
            ],
        )
        .await?;
        Ok(true)
    }

    fn row_to_approval(r: &Row) -> ApprovalRow {
        ApprovalRow {
            approval_id: col_str(r, "approval_id"),
            machine: col_str(r, "machine"),
            thread_id: col_str(r, "thread_id"),
            request_id: col_str(r, "request_id"),
            payload: col_str(r, "payload"),
            state: col_str(r, "state"),
            created_at: col_i64(r, "created_at"),
            updated_at: col_i64(r, "updated_at"),
        }
    }

    pub async fn list_open_approvals(
        &self,
        machine: Option<&str>,
    ) -> Result<Vec<ApprovalRow>, PrismError> {
        let mut rows = match machine {
            Some(m) => {
                self.db
                    .query(
                        "SELECT approval_id, machine, thread_id, request_id, payload, state, created_at, updated_at
                         FROM approvals WHERE state = 'open' AND machine = ? ORDER BY created_at ASC, approval_id ASC",
                        params![m],
                    )
                    .await
                    .map_err(storage_err)?
            }
            None => {
                self.db
                    .query(
                        "SELECT approval_id, machine, thread_id, request_id, payload, state, created_at, updated_at
                         FROM approvals WHERE state = 'open' ORDER BY created_at ASC, approval_id ASC",
                        params![],
                    )
                    .await
                    .map_err(storage_err)?
            }
        };
        let mut seen = BTreeMap::new();
        while let Some(r) = rows.next().await.map_err(storage_err)? {
            let item = Self::row_to_approval(&r);
            seen.entry(item.approval_id.clone()).or_insert(item);
        }
        Ok(seen.into_values().collect())
    }

    pub async fn get_open_approval(
        &self,
        approval_id: &str,
    ) -> Result<Option<ApprovalRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT approval_id, machine, thread_id, request_id, payload, state, created_at, updated_at
                 FROM approvals WHERE approval_id = ? AND state = 'open' LIMIT 1",
                params![approval_id],
            )
            .await
            .map_err(storage_err)?;
        Ok(rows
            .next()
            .await
            .map_err(storage_err)?
            .map(|r| Self::row_to_approval(&r)))
    }

    pub async fn close_approval(
        &self,
        approval_id: &str,
        state: &str,
        now: i64,
    ) -> Result<(), PrismError> {
        let _guard = self.alerts_lock.lock().await;
        self.exec_one(
            "UPDATE approvals SET state = ?, updated_at = ? WHERE approval_id = ? AND state = 'open'",
            params![state, now, approval_id],
        )
        .await
    }

    pub async fn request_approval_answer(
        &self,
        request_id: &str,
        approval_id: &str,
        message: &str,
        signature: &str,
        requested_by: &str,
        now: i64,
    ) -> Result<(), PrismError> {
        let _guard = self.alerts_lock.lock().await;
        // The same request ID (a device retrying) is written once.
        if self.request_exists("approval_answers", request_id).await? {
            return Ok(());
        }
        self.insert_one(
            "approval_answers",
            "INSERT INTO approval_answers (id, request_id, approval_id, message, signature, requested_by, requested_at, claimed_by, claimed_at, done_at, error)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '')",
            params![
                request_id,
                approval_id,
                message,
                signature,
                requested_by,
                now,
                SqlValue::Null,
                SqlValue::Null,
                SqlValue::Null,
            ],
        )
        .await
    }

    pub async fn claimable_approval_answers(
        &self,
        stale_before: i64,
    ) -> Result<Vec<ApprovalAnswerRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT request_id, approval_id, message, signature, requested_by, requested_at, claimed_by, claimed_at, done_at, error
                 FROM approval_answers WHERE done_at IS NULL AND (claimed_at IS NULL OR (claimed_at < ? AND claimed_by NOT LIKE 'device:%'))
                 ORDER BY requested_at ASC",
                params![stale_before],
            )
            .await
            .map_err(storage_err)?;
        let mut out = Vec::new();
        let mut seen = std::collections::BTreeSet::new();
        while let Some(r) = rows.next().await.map_err(storage_err)? {
            if !seen.insert(col_str(&r, "request_id")) {
                continue;
            }
            out.push(ApprovalAnswerRow {
                request_id: col_str(&r, "request_id"),
                approval_id: col_str(&r, "approval_id"),
                message: col_str(&r, "message"),
                signature: col_str(&r, "signature"),
                requested_by: col_str(&r, "requested_by"),
                requested_at: col_i64(&r, "requested_at"),
                claimed_by: col_opt_str(&r, "claimed_by"),
                claimed_at: col_opt_i64(&r, "claimed_at"),
                done_at: col_opt_i64(&r, "done_at"),
                error: col_str(&r, "error"),
            });
        }
        Ok(out)
    }

    /// Claims a request for `worker` (leader only), as `claim_alert_action`
    /// does: true when the worker now holds it, i.e. it was unclaimed, its
    /// claim went stale, or it was already this worker's, and it is not done.
    pub async fn claim_approval_answer(
        &self,
        request_id: &str,
        worker: &str,
        now: i64,
        stale_before: i64,
    ) -> Result<bool, PrismError> {
        self.claim_request("approval_answers", request_id, worker, now, stale_before)
            .await
    }

    pub async fn finish_approval_answer(
        &self,
        request_id: &str,
        worker: &str,
        now: i64,
        error: &str,
    ) -> Result<(), PrismError> {
        let _guard = self.alerts_lock.lock().await;
        self.exec_one(
            "UPDATE approval_answers SET done_at = ?, error = ? WHERE request_id = ? AND claimed_by = ? AND done_at IS NULL",
            params![now, error, request_id, worker],
        )
        .await
    }

    // --- Questions ---

    pub async fn has_open_question(&self, question_id: &str) -> Result<bool, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT question_id FROM questions WHERE question_id = ? AND state = 'open' LIMIT 1",
                params![question_id],
            )
            .await
            .map_err(storage_err)?;
        Ok(rows.next().await.map_err(storage_err)?.is_some())
    }

    /// Inserts or re-opens a question. Returns `true` if newly opened.
    pub async fn upsert_open_question(&self, row: &QuestionRow) -> Result<bool, PrismError> {
        let _guard = self.alerts_lock.lock().await;
        let mut existing = self
            .db
            .query(
                "SELECT state FROM questions WHERE question_id = ?",
                params![row.question_id.clone()],
            )
            .await
            .map_err(storage_err)?;
        if let Some(first) = existing.next().await.map_err(storage_err)? {
            let state = col_str(&first, "state");
            drop(existing);
            if state == "open" {
                return Ok(false);
            }
            self.exec_one(
                "UPDATE questions SET state = 'open', payload = ?, title = ?, updated_at = ? WHERE question_id = ?",
                params![
                    row.payload.clone(),
                    row.title.clone(),
                    row.updated_at,
                    row.question_id.clone()
                ],
            )
            .await?;
            return Ok(true);
        }
        drop(existing);
        self.insert_one(
            "questions",
            "INSERT INTO questions (id, question_id, thread_id, environment_id, host, client, via, title, payload, state, created_at, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', ?, ?)",
            params![
                row.question_id.clone(),
                row.thread_id.clone(),
                row.environment_id.clone(),
                row.host.clone(),
                row.client.clone(),
                row.via.clone(),
                row.title.clone(),
                row.payload.clone(),
                row.created_at,
                row.updated_at,
            ],
        )
        .await?;
        Ok(true)
    }

    fn row_to_question(r: &Row) -> QuestionRow {
        QuestionRow {
            question_id: col_str(r, "question_id"),
            thread_id: col_str(r, "thread_id"),
            environment_id: col_str(r, "environment_id"),
            host: col_str(r, "host"),
            client: col_str(r, "client"),
            via: col_str(r, "via"),
            title: col_str(r, "title"),
            payload: col_str(r, "payload"),
            state: col_str(r, "state"),
            created_at: col_i64(r, "created_at"),
            updated_at: col_i64(r, "updated_at"),
        }
    }

    pub async fn list_open_questions(&self) -> Result<Vec<QuestionRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT question_id, thread_id, environment_id, host, client, via, title, payload, state, created_at, updated_at
                 FROM questions WHERE state = 'open' ORDER BY created_at ASC, question_id ASC",
                params![],
            )
            .await
            .map_err(storage_err)?;
        let mut seen = BTreeMap::new();
        while let Some(r) = rows.next().await.map_err(storage_err)? {
            let item = Self::row_to_question(&r);
            seen.entry(item.question_id.clone()).or_insert(item);
        }
        Ok(seen.into_values().collect())
    }

    pub async fn get_open_question(
        &self,
        question_id: &str,
    ) -> Result<Option<QuestionRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT question_id, thread_id, environment_id, host, client, via, title, payload, state, created_at, updated_at
                 FROM questions WHERE question_id = ? AND state = 'open' LIMIT 1",
                params![question_id],
            )
            .await
            .map_err(storage_err)?;
        Ok(rows
            .next()
            .await
            .map_err(storage_err)?
            .map(|r| Self::row_to_question(&r)))
    }

    pub async fn close_question(
        &self,
        question_id: &str,
        state: &str,
        now: i64,
    ) -> Result<(), PrismError> {
        let _guard = self.alerts_lock.lock().await;
        self.exec_one(
            "UPDATE questions SET state = ?, updated_at = ? WHERE question_id = ? AND state = 'open'",
            params![state, now, question_id],
        )
        .await
    }

    pub async fn request_question_answer(
        &self,
        request_id: &str,
        question_id: &str,
        answers_json: &str,
        requested_by: &str,
        now: i64,
    ) -> Result<(), PrismError> {
        let _guard = self.alerts_lock.lock().await;
        // The same request ID (a device retrying) is written once.
        if self.request_exists("question_answers", request_id).await? {
            return Ok(());
        }
        self.insert_one(
            "question_answers",
            "INSERT INTO question_answers (id, request_id, question_id, answers_json, requested_by, requested_at, claimed_by, claimed_at, done_at, error)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '')",
            params![
                request_id,
                question_id,
                answers_json,
                requested_by,
                now,
                SqlValue::Null,
                SqlValue::Null,
                SqlValue::Null,
            ],
        )
        .await
    }

    pub async fn claimable_question_answers(
        &self,
        stale_before: i64,
    ) -> Result<Vec<QuestionAnswerRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT request_id, question_id, answers_json, requested_by, requested_at, claimed_by, claimed_at, done_at, error
                 FROM question_answers WHERE done_at IS NULL AND (claimed_at IS NULL OR (claimed_at < ? AND claimed_by NOT LIKE 'device:%'))
                 ORDER BY requested_at ASC",
                params![stale_before],
            )
            .await
            .map_err(storage_err)?;
        let mut out = Vec::new();
        let mut seen = std::collections::BTreeSet::new();
        while let Some(r) = rows.next().await.map_err(storage_err)? {
            if !seen.insert(col_str(&r, "request_id")) {
                continue;
            }
            out.push(QuestionAnswerRow {
                request_id: col_str(&r, "request_id"),
                question_id: col_str(&r, "question_id"),
                answers_json: col_str(&r, "answers_json"),
                requested_by: col_str(&r, "requested_by"),
                requested_at: col_i64(&r, "requested_at"),
                claimed_by: col_opt_str(&r, "claimed_by"),
                claimed_at: col_opt_i64(&r, "claimed_at"),
                done_at: col_opt_i64(&r, "done_at"),
                error: col_str(&r, "error"),
            });
        }
        Ok(out)
    }

    /// Claims a request for `worker` (leader only), as `claim_alert_action`
    /// does: true when the worker now holds it, i.e. it was unclaimed, its
    /// claim went stale, or it was already this worker's, and it is not done.
    pub async fn claim_question_answer(
        &self,
        request_id: &str,
        worker: &str,
        now: i64,
        stale_before: i64,
    ) -> Result<bool, PrismError> {
        self.claim_request("question_answers", request_id, worker, now, stale_before)
            .await
    }

    pub async fn finish_question_answer(
        &self,
        request_id: &str,
        worker: &str,
        now: i64,
        error: &str,
    ) -> Result<(), PrismError> {
        let _guard = self.alerts_lock.lock().await;
        self.exec_one(
            "UPDATE question_answers SET done_at = ?, error = ? WHERE request_id = ? AND claimed_by = ? AND done_at IS NULL",
            params![now, error, request_id, worker],
        )
        .await
    }

    // --- Agents ---

    pub async fn save_agent(&self, a: &AgentRow) -> Result<(), PrismError> {
        let _guard = self.alerts_lock.lock().await;
        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        tx.execute(
            "DELETE FROM agents WHERE thread_id = ?",
            params![a.thread_id.clone()],
        )
        .await
        .map_err(storage_err)?;
        let id = tx.next_id("agents", vec![]).await.map_err(storage_err)?;
        tx.execute(
            "INSERT INTO agents (id,
                thread_id, kind, title, alert_id, target, client, environment_id, host, link,
                request_text, state, run_status, question_pending, last_message, requested_by,
                created_at, updated_at, next_check, reported_run, terminal_run, terminal_seen
             ) VALUES (?, ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            params![
                id,
                a.thread_id.clone(),
                a.kind.clone(),
                a.title.clone(),
                a.alert_id,
                a.target.clone(),
                a.client.clone(),
                a.environment_id.clone(),
                a.host.clone(),
                a.link.clone(),
                a.request_text.clone(),
                a.state.clone(),
                a.run_status.clone(),
                if a.question_pending { 1_i64 } else { 0_i64 },
                a.last_message.clone(),
                a.requested_by.clone(),
                a.created_at,
                a.updated_at,
                a.next_check,
                a.reported_run.clone(),
                a.terminal_run.clone(),
                a.terminal_seen,
            ],
        )
        .await
        .map_err(storage_err)?;
        tx.commit().await.map_err(storage_err)?;
        Ok(())
    }

    fn row_to_agent(r: &Row) -> AgentRow {
        AgentRow {
            thread_id: col_str(r, "thread_id"),
            kind: col_str(r, "kind"),
            title: col_str(r, "title"),
            alert_id: col_i64(r, "alert_id"),
            target: col_str(r, "target"),
            client: col_str(r, "client"),
            environment_id: col_str(r, "environment_id"),
            host: col_str(r, "host"),
            link: col_str(r, "link"),
            request_text: col_str(r, "request_text"),
            state: col_str(r, "state"),
            run_status: col_str(r, "run_status"),
            question_pending: col_bool(r, "question_pending"),
            last_message: col_str(r, "last_message"),
            requested_by: col_str(r, "requested_by"),
            created_at: col_i64(r, "created_at"),
            updated_at: col_i64(r, "updated_at"),
            next_check: col_i64(r, "next_check"),
            reported_run: col_str(r, "reported_run"),
            terminal_run: col_str(r, "terminal_run"),
            terminal_seen: col_i64(r, "terminal_seen"),
        }
    }

    pub async fn get_agent(&self, thread_id: &str) -> Result<Option<AgentRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT thread_id, kind, title, alert_id, target, client, environment_id, host, link,
                        request_text, state, run_status, question_pending, last_message, requested_by,
                        created_at, updated_at, next_check, reported_run, terminal_run, terminal_seen
                 FROM agents WHERE thread_id = ? LIMIT 1",
                params![thread_id],
            )
            .await
            .map_err(storage_err)?;
        Ok(rows
            .next()
            .await
            .map_err(storage_err)?
            .map(|r| Self::row_to_agent(&r)))
    }

    pub async fn list_agents(&self) -> Result<Vec<AgentRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT thread_id, kind, title, alert_id, target, client, environment_id, host, link,
                        request_text, state, run_status, question_pending, last_message, requested_by,
                        created_at, updated_at, next_check, reported_run, terminal_run, terminal_seen
                 FROM agents ORDER BY created_at DESC",
                params![],
            )
            .await
            .map_err(storage_err)?;
        let mut out = Vec::new();
        while let Some(r) = rows.next().await.map_err(storage_err)? {
            out.push(Self::row_to_agent(&r));
        }
        Ok(out)
    }

    pub async fn due_agents(&self, now: i64) -> Result<Vec<AgentRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT thread_id, kind, title, alert_id, target, client, environment_id, host, link,
                        request_text, state, run_status, question_pending, last_message, requested_by,
                        created_at, updated_at, next_check, reported_run, terminal_run, terminal_seen
                 FROM agents WHERE state != 'done' AND next_check <= ? ORDER BY next_check ASC",
                params![now],
            )
            .await
            .map_err(storage_err)?;
        let mut out = Vec::new();
        while let Some(r) = rows.next().await.map_err(storage_err)? {
            out.push(Self::row_to_agent(&r));
        }
        Ok(out)
    }

    // --- Agent Requests ---

    #[allow(clippy::too_many_arguments)]
    pub async fn request_agent(
        &self,
        request_id: &str,
        task: &str,
        target: &str,
        request_text: &str,
        model_json: &str,
        project_id: &str,
        requested_by: &str,
        now: i64,
    ) -> Result<(), PrismError> {
        let _guard = self.alerts_lock.lock().await;
        // The same request ID (a device retrying) is written once.
        if self.request_exists("agent_requests", request_id).await? {
            return Ok(());
        }
        self.insert_one(
            "agent_requests",
            "INSERT INTO agent_requests (id, request_id, task, target, request_text, model_json, project_id, requested_by, requested_at, claimed_by, claimed_at, done_at, thread_id, link, error)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '', '', '')",
            params![
                request_id,
                task,
                target,
                request_text,
                model_json,
                project_id,
                requested_by,
                now,
                SqlValue::Null,
                SqlValue::Null,
                SqlValue::Null,
            ],
        )
        .await
    }

    pub async fn claimable_agent_requests(
        &self,
        stale_before: i64,
    ) -> Result<Vec<AgentRequestRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT request_id, task, target, request_text, model_json, project_id, requested_by, requested_at, claimed_by, claimed_at, done_at, thread_id, link, error
                 FROM agent_requests WHERE done_at IS NULL AND (claimed_at IS NULL OR (claimed_at < ? AND claimed_by NOT LIKE 'device:%'))
                 ORDER BY requested_at ASC",
                params![stale_before],
            )
            .await
            .map_err(storage_err)?;
        let mut out = Vec::new();
        let mut seen = std::collections::BTreeSet::new();
        while let Some(r) = rows.next().await.map_err(storage_err)? {
            if !seen.insert(col_str(&r, "request_id")) {
                continue;
            }
            out.push(AgentRequestRow {
                request_id: col_str(&r, "request_id"),
                task: col_str(&r, "task"),
                target: col_str(&r, "target"),
                request_text: col_str(&r, "request_text"),
                model_json: col_str(&r, "model_json"),
                project_id: col_str(&r, "project_id"),
                requested_by: col_str(&r, "requested_by"),
                requested_at: col_i64(&r, "requested_at"),
                claimed_by: col_opt_str(&r, "claimed_by"),
                claimed_at: col_opt_i64(&r, "claimed_at"),
                done_at: col_opt_i64(&r, "done_at"),
                thread_id: col_str(&r, "thread_id"),
                link: col_str(&r, "link"),
                error: col_str(&r, "error"),
            });
        }
        Ok(out)
    }

    /// Claims a request for `worker` (leader only), as `claim_alert_action`
    /// does: true when the worker now holds it, i.e. it was unclaimed, its
    /// claim went stale, or it was already this worker's, and it is not done.
    pub async fn claim_agent_request(
        &self,
        request_id: &str,
        worker: &str,
        now: i64,
        stale_before: i64,
    ) -> Result<bool, PrismError> {
        self.claim_request("agent_requests", request_id, worker, now, stale_before)
            .await
    }

    pub async fn finish_agent_request(
        &self,
        request_id: &str,
        worker: &str,
        now: i64,
        thread_id: &str,
        link: &str,
        error: &str,
    ) -> Result<(), PrismError> {
        let _guard = self.alerts_lock.lock().await;
        self.exec_one(
            "UPDATE agent_requests SET done_at = ?, thread_id = ?, link = ?, error = ? WHERE request_id = ? AND claimed_by = ? AND done_at IS NULL",
            params![now, thread_id, link, error, request_id, worker],
        )
        .await
    }

    // --- Agent Messages ---

    pub async fn request_agent_message(
        &self,
        request_id: &str,
        agent_id: &str,
        text: &str,
        requested_by: &str,
        now: i64,
    ) -> Result<(), PrismError> {
        self.write_agent_message(request_id, agent_id, AGENT_MESSAGE, text, requested_by, now)
            .await
    }

    /// Asks the leader to stop the agent's run (Go's `StopAgent`).
    pub async fn request_agent_stop(
        &self,
        request_id: &str,
        agent_id: &str,
        requested_by: &str,
        now: i64,
    ) -> Result<(), PrismError> {
        self.write_agent_message(request_id, agent_id, AGENT_STOP, "", requested_by, now)
            .await
    }

    async fn write_agent_message(
        &self,
        request_id: &str,
        agent_id: &str,
        kind: &str,
        text: &str,
        requested_by: &str,
        now: i64,
    ) -> Result<(), PrismError> {
        let _guard = self.alerts_lock.lock().await;
        // The same request ID (a device retrying) is written once.
        if self.request_exists("agent_messages", request_id).await? {
            return Ok(());
        }
        self.insert_one(
            "agent_messages",
            "INSERT INTO agent_messages (id, request_id, agent_id, kind, text, requested_by, requested_at, claimed_by, claimed_at, done_at, error)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '')",
            params![
                request_id,
                agent_id,
                kind,
                text,
                requested_by,
                now,
                SqlValue::Null,
                SqlValue::Null,
                SqlValue::Null,
            ],
        )
        .await
    }

    pub async fn claimable_agent_messages(
        &self,
        stale_before: i64,
    ) -> Result<Vec<AgentMessageRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT request_id, agent_id, kind, text, requested_by, requested_at, claimed_by, claimed_at, done_at, error
                 FROM agent_messages WHERE done_at IS NULL AND (claimed_at IS NULL OR (claimed_at < ? AND claimed_by NOT LIKE 'device:%'))
                 ORDER BY requested_at ASC",
                params![stale_before],
            )
            .await
            .map_err(storage_err)?;
        let mut out = Vec::new();
        let mut seen = std::collections::BTreeSet::new();
        while let Some(r) = rows.next().await.map_err(storage_err)? {
            if !seen.insert(col_str(&r, "request_id")) {
                continue;
            }
            out.push(AgentMessageRow {
                request_id: col_str(&r, "request_id"),
                agent_id: col_str(&r, "agent_id"),
                kind: col_opt_str(&r, "kind").unwrap_or_else(|| AGENT_MESSAGE.into()),
                text: col_str(&r, "text"),
                requested_by: col_str(&r, "requested_by"),
                requested_at: col_i64(&r, "requested_at"),
                claimed_by: col_opt_str(&r, "claimed_by"),
                claimed_at: col_opt_i64(&r, "claimed_at"),
                done_at: col_opt_i64(&r, "done_at"),
                error: col_str(&r, "error"),
            });
        }
        Ok(out)
    }

    /// Claims a request for `worker` (leader only), as `claim_alert_action`
    /// does: true when the worker now holds it, i.e. it was unclaimed, its
    /// claim went stale, or it was already this worker's, and it is not done.
    pub async fn claim_agent_message(
        &self,
        request_id: &str,
        worker: &str,
        now: i64,
        stale_before: i64,
    ) -> Result<bool, PrismError> {
        self.claim_request("agent_messages", request_id, worker, now, stale_before)
            .await
    }

    pub async fn finish_agent_message(
        &self,
        request_id: &str,
        worker: &str,
        now: i64,
        error: &str,
    ) -> Result<(), PrismError> {
        let _guard = self.alerts_lock.lock().await;
        self.exec_one(
            "UPDATE agent_messages SET done_at = ?, error = ? WHERE request_id = ? AND claimed_by = ? AND done_at IS NULL",
            params![now, error, request_id, worker],
        )
        .await
    }

    // --- Agent Activity ---

    pub async fn save_agent_activity(&self, row: &AgentActivityRow) -> Result<(), PrismError> {
        let _guard = self.alerts_lock.lock().await;
        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        tx.execute(
            "DELETE FROM agent_activity WHERE agent_id = ?",
            params![row.agent_id.clone()],
        )
        .await
        .map_err(storage_err)?;
        let id = tx
            .next_id("agent_activity", vec![])
            .await
            .map_err(storage_err)?;
        tx.execute(
            "INSERT INTO agent_activity (id, agent_id, status, items_json, updated_at) VALUES (?, ?, ?, ?, ?)",
            params![id,
                row.agent_id.clone(),
                row.status.clone(),
                row.items_json.clone(),
                row.updated_at,
            ],
        )
        .await
        .map_err(storage_err)?;
        tx.commit().await.map_err(storage_err)?;
        Ok(())
    }

    pub async fn get_agent_activity(
        &self,
        agent_id: &str,
    ) -> Result<Option<AgentActivityRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT agent_id, status, items_json, updated_at FROM agent_activity WHERE agent_id = ? LIMIT 1",
                params![agent_id],
            )
            .await
            .map_err(storage_err)?;
        Ok(rows
            .next()
            .await
            .map_err(storage_err)?
            .map(|r| AgentActivityRow {
                agent_id: col_str(&r, "agent_id"),
                status: col_str(&r, "status"),
                items_json: col_str(&r, "items_json"),
                updated_at: col_i64(&r, "updated_at"),
            }))
    }

    // --- Machines ---

    pub async fn upsert_machine(&self, row: &MachineRow) -> Result<(), PrismError> {
        let _guard = self.alerts_lock.lock().await;
        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        tx.execute(
            "DELETE FROM machines WHERE name = ?",
            params![row.name.clone()],
        )
        .await
        .map_err(storage_err)?;
        let id = tx.next_id("machines", vec![]).await.map_err(storage_err)?;
        tx.execute(
            "INSERT INTO machines (id, name, status_json, metrics_json, updated_at) VALUES (?, ?, ?, ?, ?)",
            params![id,
                row.name.clone(),
                row.status_json.clone(),
                row.metrics_json.clone(),
                row.updated_at,
            ],
        )
        .await
        .map_err(storage_err)?;
        tx.commit().await.map_err(storage_err)?;
        Ok(())
    }

    pub async fn list_machines(&self) -> Result<Vec<MachineRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT name, status_json, metrics_json, updated_at FROM machines ORDER BY name ASC",
                params![],
            )
            .await
            .map_err(storage_err)?;
        let mut by_name = BTreeMap::new();
        while let Some(r) = rows.next().await.map_err(storage_err)? {
            let item = MachineRow {
                name: col_str(&r, "name"),
                status_json: col_str(&r, "status_json"),
                metrics_json: col_str(&r, "metrics_json"),
                updated_at: col_i64(&r, "updated_at"),
            };
            by_name.entry(item.name.clone()).or_insert(item);
        }
        Ok(by_name.into_values().collect())
    }

    // --- Projects ---

    pub async fn replace_projects(&self, projects: &[ProjectRow]) -> Result<(), PrismError> {
        let _guard = self.alerts_lock.lock().await;
        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        tx.execute("DELETE FROM projects", params![])
            .await
            .map_err(storage_err)?;
        for p in projects {
            let id = tx.next_id("projects", vec![]).await.map_err(storage_err)?;
            tx.execute(
                "INSERT INTO projects (id, project_id, name, icon_json, machines_json, repository, updated_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?)",
                params![id,
                    p.project_id.clone(),
                    p.name.clone(),
                    p.icon_json.clone(),
                    p.machines_json.clone(),
                    if p.repository { 1_i64 } else { 0_i64 },
                    p.updated_at,
                ],
            )
            .await
            .map_err(storage_err)?;
        }
        tx.commit().await.map_err(storage_err)?;
        Ok(())
    }

    pub async fn list_projects(&self) -> Result<Vec<ProjectRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT project_id, name, icon_json, machines_json, repository, updated_at
                 FROM projects ORDER BY name ASC, project_id ASC",
                params![],
            )
            .await
            .map_err(storage_err)?;
        let mut out = Vec::new();
        while let Some(r) = rows.next().await.map_err(storage_err)? {
            out.push(ProjectRow {
                project_id: col_str(&r, "project_id"),
                name: col_str(&r, "name"),
                icon_json: col_str(&r, "icon_json"),
                machines_json: col_str(&r, "machines_json"),
                repository: col_bool(&r, "repository"),
                updated_at: col_i64(&r, "updated_at"),
            });
        }
        Ok(out)
    }

    // --- Settings ---

    pub async fn set_setting(&self, key: &str, value: &str, now: i64) -> Result<(), PrismError> {
        let _guard = self.alerts_lock.lock().await;
        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        tx.execute("DELETE FROM settings WHERE key = ?", params![key])
            .await
            .map_err(storage_err)?;
        let id = tx.next_id("settings", vec![]).await.map_err(storage_err)?;
        tx.execute(
            "INSERT INTO settings (id, key, value, updated_at) VALUES (?, ?, ?, ?)",
            params![id, key, value, now],
        )
        .await
        .map_err(storage_err)?;
        tx.commit().await.map_err(storage_err)?;
        Ok(())
    }

    pub async fn get_setting(&self, key: &str) -> Result<Option<String>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT value FROM settings WHERE key = ? ORDER BY updated_at DESC LIMIT 1",
                params![key],
            )
            .await
            .map_err(storage_err)?;
        Ok(rows
            .next()
            .await
            .map_err(storage_err)?
            .map(|r| col_str(&r, "value")))
    }

    pub async fn list_settings(&self) -> Result<Vec<SettingRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT key, value, updated_at FROM settings ORDER BY key ASC, updated_at DESC",
                params![],
            )
            .await
            .map_err(storage_err)?;
        let mut by_key = BTreeMap::new();
        while let Some(r) = rows.next().await.map_err(storage_err)? {
            let item = SettingRow {
                key: col_str(&r, "key"),
                value: col_str(&r, "value"),
                updated_at: col_i64(&r, "updated_at"),
            };
            by_key.entry(item.key.clone()).or_insert(item);
        }
        Ok(by_key.into_values().collect())
    }

    // --- Android Releases ---

    pub async fn get_android_release(
        &self,
        package: &str,
    ) -> Result<Option<AndroidReleaseRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT package, version_code, version_name, sha256, size, uploaded_at, uploaded_by, stored_on
                 FROM android_releases WHERE package = ? ORDER BY version_code DESC LIMIT 1",
                params![package],
            )
            .await
            .map_err(storage_err)?;
        Ok(rows
            .next()
            .await
            .map_err(storage_err)?
            .map(|r| AndroidReleaseRow {
                package: col_str(&r, "package"),
                version_code: col_i64(&r, "version_code"),
                version_name: col_str(&r, "version_name"),
                sha256: col_str(&r, "sha256"),
                size: col_i64(&r, "size"),
                uploaded_at: col_i64(&r, "uploaded_at"),
                uploaded_by: col_str(&r, "uploaded_by"),
                stored_on: col_str(&r, "stored_on"),
            }))
    }

    /// Publishes a new Android release if `version_code` is strictly greater
    /// than the currently published version for that package. Returns `Ok(false)`
    /// on version conflict.
    /// The published release of every package.
    pub async fn list_android_releases(&self) -> Result<Vec<AndroidReleaseRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT DISTINCT package FROM android_releases ORDER BY package",
                params![],
            )
            .await
            .map_err(storage_err)?;
        let mut packages = Vec::new();
        while let Some(r) = rows.next().await.map_err(storage_err)? {
            packages.push(col_str(&r, "package"));
        }
        drop(rows);
        let mut out = Vec::new();
        for p in packages {
            if let Some(rel) = self.get_android_release(&p).await? {
                out.push(rel);
            }
        }
        Ok(out)
    }

    pub async fn put_android_release(&self, row: &AndroidReleaseRow) -> Result<bool, PrismError> {
        let _guard = self.alerts_lock.lock().await;
        let mut existing = self
            .db
            .query(
                "SELECT version_code FROM android_releases WHERE package = ? ORDER BY version_code DESC LIMIT 1",
                params![row.package.clone()],
            )
            .await
            .map_err(storage_err)?;
        if let Some(first) = existing.next().await.map_err(storage_err)?
            && row.version_code <= col_i64(&first, "version_code")
        {
            return Ok(false);
        }
        drop(existing);
        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        tx.execute(
            "DELETE FROM android_releases WHERE package = ?",
            params![row.package.clone()],
        )
        .await
        .map_err(storage_err)?;
        let id = tx
            .next_id("android_releases", vec![])
            .await
            .map_err(storage_err)?;
        tx.execute(
            "INSERT INTO android_releases (id, package, version_code, version_name, sha256, size, uploaded_at, uploaded_by, stored_on)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            params![id,
                row.package.clone(),
                row.version_code,
                row.version_name.clone(),
                row.sha256.clone(),
                row.size,
                row.uploaded_at,
                row.uploaded_by.clone(),
                row.stored_on.clone(),
            ],
        )
        .await
        .map_err(storage_err)?;
        tx.commit().await.map_err(storage_err)?;
        Ok(true)
    }
}
