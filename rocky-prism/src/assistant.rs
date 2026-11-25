//! The phone assistant's conversations (`assistant_conversations`,
//! `assistant_entries`, `assistant_history`), so they outlive the app and
//! reach every replica.
//!
//! A conversation is a row with its title and times, one row per transcript
//! entry, and one row per message of the model's history. Bodies are the
//! app's JSON, opaque here. Rows stay small: Rocky refuses an intent frame
//! over 128 KiB, and a SQL value's bytes are CBOR integers there (about two
//! bytes per byte), so a body holds at most [`ASSISTANT_MAX_BODY_BYTES`] and
//! one transaction at most [`ASSISTANT_BATCH_BYTES`] of bodies.
//!
//! The app saves a conversation whole, as often as it likes:
//! [`PrismClient::save_assistant_conversation`] writes only the rows that
//! changed, so a save that failed (the replica busy or closed) is simply made
//! again later.

use crate::client::{PrismClient, storage_err};
use crate::types::PrismError;
use rocky_sdk::{Row, SqlValue, params};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// The largest body of one entry or message, in bytes.
pub const ASSISTANT_MAX_BODY_BYTES: usize = 16 * 1024;
/// The bodies one transaction writes at most, in bytes.
pub const ASSISTANT_BATCH_BYTES: usize = 24 * 1024;
/// Statements per transaction; Rocky takes at most 256 in one intent.
const ASSISTANT_BATCH_STATEMENTS: usize = 200;
/// The longest conversation ID, in bytes.
const MAX_ID_BYTES: usize = 100;
/// The longest title, in characters.
pub const ASSISTANT_TITLE_CHARS: usize = 120;
/// Entries and messages one conversation keeps at most.
const MAX_ENTRIES: usize = 2000;
const MAX_HISTORY: usize = 500;

/// A conversation as the history lists it.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct AssistantConversationRow {
    pub conversation_id: String,
    /// The device it was started on.
    pub device: String,
    pub title: String,
    pub created_at: i64,
    pub updated_at: i64,
}

/// A transcript entry: its ID in the transcript and its JSON.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct AssistantEntry {
    pub entry_id: String,
    pub body: String,
}

/// A whole conversation: its row, its entries in order, and the model's
/// history (each message's JSON) in order.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct AssistantConversation {
    pub conversation: AssistantConversationRow,
    pub entries: Vec<AssistantEntry>,
    pub history: Vec<String>,
}

/// One statement of a save.
enum Op {
    Delete(&'static str, SqlValue),
    InsertEntry {
        entry_id: String,
        position: i64,
        body: String,
    },
    UpdateEntry {
        id: SqlValue,
        position: i64,
        body: String,
    },
    InsertHistory {
        seq: i64,
        body: String,
    },
    Conversation(AssistantConversationRow, Option<SqlValue>),
}

impl Op {
    fn bytes(&self) -> usize {
        match self {
            Op::InsertEntry { body, .. }
            | Op::UpdateEntry { body, .. }
            | Op::InsertHistory { body, .. } => body.len(),
            Op::Conversation(row, _) => row.title.len(),
            Op::Delete(..) => 0,
        }
    }
}

/// Whether `id` can name a conversation: letters, digits and `-_.:`.
pub fn is_valid_conversation_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= MAX_ID_BYTES
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"-_.:".contains(&b))
}

/// A title as stored: one line, at most [`ASSISTANT_TITLE_CHARS`].
fn clean_title(title: &str) -> String {
    let line = title.split_whitespace().collect::<Vec<_>>().join(" ");
    if line.chars().count() <= ASSISTANT_TITLE_CHARS {
        return line;
    }
    let mut cut: String = line.chars().take(ASSISTANT_TITLE_CHARS - 1).collect();
    cut.push('…');
    cut
}

/// Where `new` continues `stored`: `(start, matched)` with
/// `stored[start..start + matched] == new[..matched]`, the longest such run
/// (the latest of equals). The history loses messages at its start (the app
/// keeps the newest) and those of a run stopped halfway at its end, and gains
/// new ones at its end; what lies outside the run is deleted, what follows it
/// in `new` is inserted. `(stored.len(), 0)` when nothing continues.
pub fn align_history(stored: &[&str], new: &[&str]) -> (usize, usize) {
    let mut best = (stored.len(), 0);
    for start in 0..stored.len() {
        let matched = stored[start..]
            .iter()
            .zip(new)
            .take_while(|(a, b)| a == b)
            .count();
        if matched > 0 && matched >= best.1 {
            best = (start, matched);
        }
    }
    best
}

fn col_str(row: &Row, col: &str) -> String {
    row.get::<String>(col).unwrap_or_default()
}

fn col_i64(row: &Row, col: &str) -> i64 {
    row.get::<i64>(col).unwrap_or(0)
}

fn conversation_row(r: &Row) -> AssistantConversationRow {
    AssistantConversationRow {
        conversation_id: col_str(r, "conversation_id"),
        device: col_str(r, "device"),
        title: col_str(r, "title"),
        created_at: col_i64(r, "created_at"),
        updated_at: col_i64(r, "updated_at"),
    }
}

/// A stored entry or message with its Rocky id.
struct Stored {
    id: SqlValue,
    key: String,
    order: i64,
    body: String,
}

impl PrismClient {
    /// The conversations, the most recently updated first, at most `limit`.
    pub async fn list_assistant_conversations(
        &self,
        limit: usize,
    ) -> Result<Vec<AssistantConversationRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT conversation_id, device, title, created_at, updated_at \
                 FROM assistant_conversations ORDER BY updated_at DESC, id ASC",
                params![],
            )
            .await
            .map_err(storage_err)?;
        let mut seen = std::collections::BTreeSet::new();
        let mut out = Vec::new();
        while let Some(r) = rows.next().await.map_err(storage_err)? {
            let row = conversation_row(&r);
            // Two devices may each have written a row: the newest counts.
            if seen.insert(row.conversation_id.clone()) {
                out.push(row);
                if out.len() >= limit {
                    break;
                }
            }
        }
        Ok(out)
    }

    /// Conversation `id` with its entries and history, or `NotFound`.
    pub async fn assistant_conversation(
        &self,
        id: &str,
    ) -> Result<AssistantConversation, PrismError> {
        let (conversation, _) = self
            .assistant_conversation_row(id)
            .await?
            .ok_or(PrismError::NotFound)?;
        let mut entries: Vec<Stored> = self
            .stored_entries(id)
            .await?
            .into_values()
            .flat_map(|rows| rows.into_iter().next())
            .collect();
        entries.sort_by(|a, b| a.order.cmp(&b.order).then(a.key.cmp(&b.key)));
        let history = self.stored_history(id).await?;
        Ok(AssistantConversation {
            conversation,
            entries: entries
                .into_iter()
                .map(|e| AssistantEntry {
                    entry_id: e.key,
                    body: e.body,
                })
                .collect(),
            history: history.into_iter().map(|m| m.body).collect(),
        })
    }

    /// Stores conversation `conversation` as it is now: `entries` in order
    /// and the model's `history` in order. Only what changed since the last
    /// save is written, in transactions of at most [`ASSISTANT_BATCH_BYTES`]:
    /// changed and new entries, the history's new messages (and those it
    /// dropped deleted), then the conversation's row. A conversation saved
    /// for the first time keeps `device` and `created_at`; later saves keep
    /// the first ones. Returns how many statements it wrote.
    pub async fn save_assistant_conversation(
        &self,
        conversation: AssistantConversationRow,
        entries: Vec<AssistantEntry>,
        history: Vec<String>,
    ) -> Result<usize, PrismError> {
        let id = conversation.conversation_id.clone();
        if !is_valid_conversation_id(&id) {
            return Err(PrismError::InvalidInput(
                "A conversation's ID is 1 to 100 letters, digits or -_.:".into(),
            ));
        }
        if entries.len() > MAX_ENTRIES || history.len() > MAX_HISTORY {
            return Err(PrismError::InvalidInput(format!(
                "A conversation keeps at most {MAX_ENTRIES} entries and {MAX_HISTORY} messages."
            )));
        }
        let too_big = entries
            .iter()
            .map(|e| e.body.len())
            .chain(history.iter().map(String::len))
            .any(|n| n > ASSISTANT_MAX_BODY_BYTES);
        if too_big {
            return Err(PrismError::InvalidInput(format!(
                "An entry or message is at most {ASSISTANT_MAX_BODY_BYTES} bytes."
            )));
        }
        let mut ids = std::collections::BTreeSet::new();
        if let Some(e) = entries
            .iter()
            .find(|e| e.entry_id.is_empty() || !ids.insert(e.entry_id.as_str()))
        {
            return Err(PrismError::InvalidInput(format!(
                "Entry IDs are unique and not empty: {:?}",
                e.entry_id
            )));
        }

        let _guard = self.assistant_lock.lock().await;
        let mut deletes = Vec::new();
        let mut writes = Vec::new();

        // Entries, by their ID: duplicates (two devices wrote one) and
        // entries no longer there go; changed ones are rewritten.
        let mut stored = self.stored_entries(&id).await?;
        for (position, entry) in entries.into_iter().enumerate() {
            let position = position as i64;
            match stored.remove(&entry.entry_id) {
                Some(mut rows) => {
                    let first = rows.remove(0);
                    deletes.extend(
                        rows.into_iter()
                            .map(|r| Op::Delete("assistant_entries", r.id)),
                    );
                    if first.order != position || first.body != entry.body {
                        writes.push(Op::UpdateEntry {
                            id: first.id,
                            position,
                            body: entry.body,
                        });
                    }
                }
                None => writes.push(Op::InsertEntry {
                    entry_id: entry.entry_id,
                    position,
                    body: entry.body,
                }),
            }
        }
        deletes.extend(
            stored
                .into_values()
                .flatten()
                .map(|r| Op::Delete("assistant_entries", r.id)),
        );

        // The history: what continues the stored messages stays.
        let stored = self.stored_history(&id).await?;
        let bodies: Vec<&str> = stored.iter().map(|m| m.body.as_str()).collect();
        let new: Vec<&str> = history.iter().map(String::as_str).collect();
        let (start, matched) = align_history(&bodies, &new);
        let mut next = if matched > 0 {
            stored[start + matched - 1].order + 1
        } else {
            stored.iter().map(|m| m.order + 1).max().unwrap_or(0)
        };
        for (i, m) in stored.iter().enumerate() {
            if i < start || i >= start + matched {
                deletes.push(Op::Delete("assistant_history", m.id.clone()));
            }
        }
        for body in &history[matched..] {
            writes.push(Op::InsertHistory {
                seq: next,
                body: body.clone(),
            });
            next += 1;
        }

        // The conversation's row, last: it lists once its rows are there.
        let existing = self.assistant_conversation_row(&id).await?;
        let row = AssistantConversationRow {
            title: clean_title(&conversation.title),
            ..match &existing {
                Some((first, _)) => AssistantConversationRow {
                    updated_at: conversation.updated_at,
                    ..first.clone()
                },
                None => conversation.clone(),
            }
        };
        let changed = match &existing {
            Some((old, _)) => *old != row,
            None => true,
        };
        if changed || !deletes.is_empty() || !writes.is_empty() {
            writes.push(Op::Conversation(row, existing.map(|(_, rid)| rid)));
        }

        // Deletes first, so a save cut short never leaves two messages at
        // one place; the next save finishes it.
        let ops: Vec<Op> = deletes.into_iter().chain(writes).collect();
        let count = ops.len();
        self.run_assistant_ops(&id, ops).await?;
        Ok(count)
    }

    /// Deletes conversation `id` with its entries and history.
    pub async fn delete_assistant_conversation(&self, id: &str) -> Result<(), PrismError> {
        let _guard = self.assistant_lock.lock().await;
        let mut ops = Vec::new();
        for table in [
            "assistant_conversations",
            "assistant_entries",
            "assistant_history",
        ] {
            let mut rows = self
                .db
                .query(
                    &format!("SELECT id FROM {table} WHERE conversation_id = ?"),
                    params![id],
                )
                .await
                .map_err(storage_err)?;
            while let Some(r) = rows.next().await.map_err(storage_err)? {
                ops.push(Op::Delete(
                    table,
                    r.get::<SqlValue>("id").map_err(storage_err)?,
                ));
            }
        }
        self.run_assistant_ops(id, ops).await
    }

    async fn run_assistant_ops(&self, id: &str, ops: Vec<Op>) -> Result<(), PrismError> {
        let mut batch: Vec<Op> = Vec::new();
        let mut bytes = 0;
        for op in ops {
            if !batch.is_empty()
                && (bytes + op.bytes() > ASSISTANT_BATCH_BYTES
                    || batch.len() >= ASSISTANT_BATCH_STATEMENTS)
            {
                self.assistant_batch(id, std::mem::take(&mut batch)).await?;
                bytes = 0;
            }
            bytes += op.bytes();
            batch.push(op);
        }
        if !batch.is_empty() {
            self.assistant_batch(id, batch).await?;
        }
        Ok(())
    }

    async fn assistant_batch(&self, id: &str, ops: Vec<Op>) -> Result<(), PrismError> {
        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        for op in ops {
            match op {
                Op::Delete(table, rid) => {
                    tx.execute(&format!("DELETE FROM {table} WHERE id = ?"), vec![rid])
                        .await
                }
                Op::InsertEntry {
                    entry_id,
                    position,
                    body,
                } => {
                    let rid = tx
                        .next_id("assistant_entries", vec![])
                        .await
                        .map_err(storage_err)?;
                    tx.execute(
                        "INSERT INTO assistant_entries (id, conversation_id, entry_id, position, body) \
                         VALUES (?, ?, ?, ?, ?)",
                        params![rid, id, entry_id, position, body],
                    )
                    .await
                }
                Op::UpdateEntry { id: rid, position, body } => {
                    tx.execute(
                        "UPDATE assistant_entries SET position = ?, body = ? WHERE id = ?",
                        params![position, body, rid],
                    )
                    .await
                }
                Op::InsertHistory { seq, body } => {
                    let rid = tx
                        .next_id("assistant_history", vec![])
                        .await
                        .map_err(storage_err)?;
                    tx.execute(
                        "INSERT INTO assistant_history (id, conversation_id, seq, body) VALUES (?, ?, ?, ?)",
                        params![rid, id, seq, body],
                    )
                    .await
                }
                Op::Conversation(row, Some(rid)) => {
                    tx.execute(
                        "UPDATE assistant_conversations SET title = ?, updated_at = ? WHERE id = ?",
                        params![row.title, row.updated_at, rid],
                    )
                    .await
                }
                Op::Conversation(row, None) => {
                    let rid = tx
                        .next_id("assistant_conversations", vec![])
                        .await
                        .map_err(storage_err)?;
                    tx.execute(
                        "INSERT INTO assistant_conversations \
                         (id, conversation_id, device, title, created_at, updated_at) \
                         VALUES (?, ?, ?, ?, ?, ?)",
                        params![
                            rid,
                            row.conversation_id,
                            row.device,
                            row.title,
                            row.created_at,
                            row.updated_at
                        ],
                    )
                    .await
                }
            }
            .map_err(storage_err)?;
        }
        tx.commit().await.map_err(storage_err)?;
        Ok(())
    }

    /// The newest row of conversation `id`, with its Rocky id.
    async fn assistant_conversation_row(
        &self,
        id: &str,
    ) -> Result<Option<(AssistantConversationRow, SqlValue)>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT id, conversation_id, device, title, created_at, updated_at \
                 FROM assistant_conversations WHERE conversation_id = ? \
                 ORDER BY updated_at DESC, id ASC LIMIT 1",
                params![id],
            )
            .await
            .map_err(storage_err)?;
        match rows.next().await.map_err(storage_err)? {
            Some(r) => Ok(Some((
                conversation_row(&r),
                r.get::<SqlValue>("id").map_err(storage_err)?,
            ))),
            None => Ok(None),
        }
    }

    /// Conversation `id`'s entry rows by entry ID, the oldest Rocky row first.
    async fn stored_entries(&self, id: &str) -> Result<BTreeMap<String, Vec<Stored>>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT id, entry_id, position, body FROM assistant_entries \
                 WHERE conversation_id = ? ORDER BY id ASC",
                params![id],
            )
            .await
            .map_err(storage_err)?;
        let mut out: BTreeMap<String, Vec<Stored>> = BTreeMap::new();
        while let Some(r) = rows.next().await.map_err(storage_err)? {
            let key = col_str(&r, "entry_id");
            out.entry(key.clone()).or_default().push(Stored {
                id: r.get::<SqlValue>("id").map_err(storage_err)?,
                key,
                order: col_i64(&r, "position"),
                body: col_str(&r, "body"),
            });
        }
        Ok(out)
    }

    /// Conversation `id`'s history rows in order.
    async fn stored_history(&self, id: &str) -> Result<Vec<Stored>, PrismError> {
        let mut rows = self
            .db
            .query(
                "SELECT id, seq, body FROM assistant_history \
                 WHERE conversation_id = ? ORDER BY seq ASC, id ASC",
                params![id],
            )
            .await
            .map_err(storage_err)?;
        let mut out = Vec::new();
        while let Some(r) = rows.next().await.map_err(storage_err)? {
            out.push(Stored {
                id: r.get::<SqlValue>("id").map_err(storage_err)?,
                key: String::new(),
                order: col_i64(&r, "seq"),
                body: col_str(&r, "body"),
            });
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_history_continues_where_it_matches() {
        let s = |v: &[&'static str]| v.to_vec();
        // Appended.
        assert_eq!(align_history(&s(&["a", "b"]), &s(&["a", "b", "c"])), (0, 2));
        // The oldest dropped, one appended.
        assert_eq!(
            align_history(&s(&["a", "b", "c"]), &s(&["b", "c", "d"])),
            (1, 2)
        );
        // A stopped run's tail dropped.
        assert_eq!(
            align_history(&s(&["a", "b", "x"]), &s(&["a", "b", "c"])),
            (0, 2)
        );
        // Nothing in common.
        assert_eq!(align_history(&s(&["a"]), &s(&["b"])), (1, 0));
        assert_eq!(align_history(&s(&[]), &s(&["b"])), (0, 0));
        // The latest of equal runs.
        assert_eq!(
            align_history(&s(&["a", "b", "a", "b"]), &s(&["a", "b", "c"])),
            (2, 2)
        );
    }

    #[test]
    fn titles_are_one_short_line() {
        assert_eq!(
            clean_title("  What's\nthe   weather? "),
            "What's the weather?"
        );
        let long = clean_title(&"x".repeat(300));
        assert_eq!(long.chars().count(), ASSISTANT_TITLE_CHARS);
        assert!(long.ends_with('…'));
    }

    #[test]
    fn conversation_ids() {
        assert!(is_valid_conversation_id("c-1700000000000-ab12"));
        assert!(!is_valid_conversation_id(""));
        assert!(!is_valid_conversation_id("a b"));
        assert!(!is_valid_conversation_id(&"a".repeat(101)));
    }
}
