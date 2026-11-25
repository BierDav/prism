//! The phone assistant's conversations (`assistant_conversations`,
//! `assistant_entries`, `assistant_history`), so they outlive the app and
//! reach every replica.
//!
//! A conversation is a row with its title and times, one row per transcript
//! entry (by position), and one row per message of the model's history (by
//! sequence number). Bodies are the app's JSON, opaque here. Rows stay
//! small: Rocky refuses an intent frame over 128 KiB, and a SQL value's bytes
//! are CBOR integers there (about two bytes per byte), so a body holds at
//! most [`ASSISTANT_MAX_BODY_BYTES`] and one transaction at most
//! [`ASSISTANT_BATCH_BYTES`] of bodies.
//!
//! **Revisions.** A save takes several transactions, and a reader must never
//! see half of one. Every entry and message row carries the revision that
//! wrote it, and the conversation's row names the committed revision with
//! how many entries and which messages it has. A save writes its new rows
//! under the next revision, then commits it in the conversation's row (one
//! statement), and only then deletes the rows it replaced. Readers take, per
//! position, the newest row up to the committed revision, so rows of a save
//! cut short are invisible, and the next save deletes them first.
//!
//! A save names the revision it builds on; when the conversation moved on
//! since (another save, another device) it is refused, as is any save of a
//! deleted conversation: a delete leaves a tombstone row behind, which
//! replicates like any other.

use crate::client::{PrismClient, storage_err};
use crate::types::PrismError;
use rocky_sdk::{Row, SqlValue, Transaction, params};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

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
/// Why a save of a deleted conversation is refused (`PrismError::Conflict`).
pub const ASSISTANT_DELETED: &str = "This conversation was deleted.";
/// Why a save that doesn't build on the committed revision is refused.
pub const ASSISTANT_STALE: &str =
    "This conversation changed since it was loaded (on another device?).";

/// A conversation as the history lists it.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct AssistantConversationRow {
    pub conversation_id: String,
    /// The device it was started on.
    pub device: String,
    pub title: String,
    pub created_at: i64,
    pub updated_at: i64,
    /// The committed revision, which the next save builds on; 0 before the first.
    pub revision: i64,
}

/// A transcript entry: its ID in the transcript and its JSON.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct AssistantEntry {
    pub entry_id: String,
    pub body: String,
}

/// A whole conversation at its committed revision: its row, its entries in
/// order, and the model's history (each message's JSON) in order.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct AssistantConversation {
    pub conversation: AssistantConversationRow,
    pub entries: Vec<AssistantEntry>,
    pub history: Vec<String>,
}

/// The committed state of a conversation's row.
#[derive(Clone, Debug)]
struct Head {
    row: AssistantConversationRow,
    rid: SqlValue,
    entry_count: i64,
    history_from: i64,
    history_to: i64,
    /// The save that committed `row.revision` (the app's ID for it).
    save_id: String,
}

enum Found {
    Nothing,
    Deleted,
    Live(Head),
}

/// A stored entry or message row.
struct Stored {
    id: SqlValue,
    /// The entry's position, or the message's sequence number.
    order: i64,
    revision: i64,
    entry_id: String,
    body: String,
}

/// One statement of a save.
enum Op {
    Delete(&'static str, SqlValue),
    InsertEntry {
        position: i64,
        revision: i64,
        entry_id: String,
        body: String,
    },
    InsertHistory {
        seq: i64,
        revision: i64,
        body: String,
    },
    Commit(Box<Head>, bool),
    Tombstone(AssistantConversationRow),
    MarkDeleted,
}

impl Op {
    fn bytes(&self) -> usize {
        match self {
            Op::InsertEntry { body, .. } | Op::InsertHistory { body, .. } => body.len(),
            Op::Commit(head, _) => head.row.title.len(),
            _ => 0,
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
/// new ones at its end; what lies outside the run goes, what follows it in
/// `new` is written. `(stored.len(), 0)` when nothing continues.
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

/// The rows a reader sees at `committed`: per position in `from..to`, the
/// row with the highest revision up to it (of equals, the first, which is
/// the smallest Rocky id). Indexes into `rows`, by position.
fn visible(rows: &[Stored], from: i64, to: i64, committed: i64) -> BTreeMap<i64, usize> {
    let mut out: BTreeMap<i64, usize> = BTreeMap::new();
    for (i, r) in rows.iter().enumerate() {
        if r.order < from || r.order >= to || r.revision > committed {
            continue;
        }
        match out.get(&r.order) {
            Some(&j) if rows[j].revision >= r.revision => {}
            _ => {
                out.insert(r.order, i);
            }
        }
    }
    out
}

fn col_str(row: &Row, col: &str) -> String {
    row.get::<String>(col).unwrap_or_default()
}

fn col_i64(row: &Row, col: &str) -> i64 {
    row.get::<i64>(col).unwrap_or(0)
}

/// The conversation rows of one ID, in Rocky id order: deleted if any says
/// so, else the one with the highest revision (of equals, the first).
fn head_of(rows: Vec<(Head, bool)>) -> Found {
    if rows.iter().any(|(_, deleted)| *deleted) {
        return Found::Deleted;
    }
    let mut best: Option<Head> = None;
    for (head, _) in rows {
        if best
            .as_ref()
            .is_none_or(|b| head.row.revision > b.row.revision)
        {
            best = Some(head);
        }
    }
    best.map_or(Found::Nothing, Found::Live)
}

const HEAD_COLUMNS: &str = "id, conversation_id, device, title, created_at, updated_at, \
     revision, entry_count, history_from, history_to, save_id, deleted";

fn head_row(r: &Row) -> Result<(Head, bool), PrismError> {
    Ok((
        Head {
            row: AssistantConversationRow {
                conversation_id: col_str(r, "conversation_id"),
                device: col_str(r, "device"),
                title: col_str(r, "title"),
                created_at: col_i64(r, "created_at"),
                updated_at: col_i64(r, "updated_at"),
                revision: col_i64(r, "revision"),
            },
            rid: r.get::<SqlValue>("id").map_err(storage_err)?,
            entry_count: col_i64(r, "entry_count"),
            history_from: col_i64(r, "history_from"),
            history_to: col_i64(r, "history_to"),
            save_id: col_str(r, "save_id"),
        },
        col_i64(r, "deleted") != 0,
    ))
}

impl PrismClient {
    /// The conversations, the most recently updated first, at most `limit`;
    /// deleted ones are left out.
    pub async fn list_assistant_conversations(
        &self,
        limit: usize,
    ) -> Result<Vec<AssistantConversationRow>, PrismError> {
        let mut rows = self
            .db
            .query(
                &format!("SELECT {HEAD_COLUMNS} FROM assistant_conversations ORDER BY id ASC"),
                params![],
            )
            .await
            .map_err(storage_err)?;
        let mut by_id: BTreeMap<String, Vec<(Head, bool)>> = BTreeMap::new();
        while let Some(r) = rows.next().await.map_err(storage_err)? {
            let (head, deleted) = head_row(&r)?;
            by_id
                .entry(head.row.conversation_id.clone())
                .or_default()
                .push((head, deleted));
        }
        let mut out: Vec<AssistantConversationRow> = by_id
            .into_values()
            .filter_map(|rows| match head_of(rows) {
                Found::Live(head) => Some(head.row),
                _ => None,
            })
            .collect();
        out.sort_by(|a, b| {
            b.updated_at
                .cmp(&a.updated_at)
                .then(a.conversation_id.cmp(&b.conversation_id))
        });
        out.truncate(limit);
        Ok(out)
    }

    /// Conversation `id` at its committed revision, or `NotFound` (also once deleted).
    pub async fn assistant_conversation(
        &self,
        id: &str,
    ) -> Result<AssistantConversation, PrismError> {
        // The head and its rows from one snapshot: a save committing
        // meanwhile (and deleting the rows it replaced) is seen whole or not
        // at all. Of the rows, only those up to the committed revision count.
        let (Found::Live(head), entries, history) = self.assistant_read(id).await? else {
            return Err(PrismError::NotFound);
        };
        let committed = head.row.revision;
        Ok(AssistantConversation {
            entries: visible(&entries, 0, head.entry_count, committed)
                .into_values()
                .map(|i| AssistantEntry {
                    entry_id: entries[i].entry_id.clone(),
                    body: entries[i].body.clone(),
                })
                .collect(),
            history: visible(&history, head.history_from, head.history_to, committed)
                .into_values()
                .map(|i| history[i].body.clone())
                .collect(),
            conversation: head.row,
        })
    }

    /// Stores conversation `conversation` as it is now, `entries` and the
    /// model's `history` in order, as the revision after `base` (0 for a new
    /// conversation). Only what changed is written: new rows under the next
    /// revision, then the commit, then the replaced rows deleted (see the
    /// module). A save of a deleted conversation is refused with
    /// [`ASSISTANT_DELETED`], one whose `base` isn't the committed revision
    /// with [`ASSISTANT_STALE`]. The first save keeps `device` and
    /// `created_at`. Returns the committed revision, `base` when nothing
    /// changed.
    ///
    /// `save_id` names the revision this save creates (the app picks it, one
    /// per version it queues). When the committed revision is that save's
    /// already, the save is answered as the first time, with that revision:
    /// a retry of a save that committed but whose answer was lost (Rocky
    /// timed out after the commit) succeeds instead of finding its base gone.
    pub async fn save_assistant_conversation(
        &self,
        conversation: AssistantConversationRow,
        base: i64,
        save_id: &str,
        entries: Vec<AssistantEntry>,
        history: Vec<String>,
    ) -> Result<i64, PrismError> {
        let id = conversation.conversation_id.clone();
        if !is_valid_conversation_id(&id) {
            return Err(PrismError::InvalidInput(
                "A conversation's ID is 1 to 100 letters, digits or -_.:".into(),
            ));
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
        if save_id.len() > MAX_ID_BYTES {
            return Err(PrismError::InvalidInput(
                "A save's ID is at most 100 bytes.".into(),
            ));
        }
        let mut ids = BTreeSet::new();
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
        let (head, stored_entries, stored_history) = self.assistant_read(&id).await?;
        let head = match head {
            Found::Deleted => return Err(PrismError::Conflict(ASSISTANT_DELETED.into())),
            Found::Nothing => None,
            Found::Live(head) => Some(head),
        };
        let committed = head.as_ref().map_or(0, |h| h.row.revision);
        if !save_id.is_empty() && head.as_ref().is_some_and(|h| h.save_id == save_id) {
            return Ok(committed);
        }
        if base != committed {
            return Err(PrismError::Conflict(ASSISTANT_STALE.into()));
        }
        let revision = committed + 1;
        let (count, from, to) = head
            .as_ref()
            .map_or((0, 0, 0), |h| (h.entry_count, h.history_from, h.history_to));

        // Rows of saves cut short, which no reader sees: they go first.
        let mut garbage = Vec::new();
        for (table, rows) in [
            ("assistant_entries", &stored_entries),
            ("assistant_history", &stored_history),
        ] {
            garbage.extend(
                rows.iter()
                    .filter(|r| r.revision > committed)
                    .map(|r| Op::Delete(table, r.id.clone())),
            );
        }

        // Entries: those changed at their position are written again.
        let mut writes = Vec::new();
        let mut kept_entries = BTreeSet::new();
        let shown = visible(&stored_entries, 0, count, committed);
        for (position, entry) in entries.iter().enumerate() {
            let position = position as i64;
            match shown.get(&position) {
                Some(&i)
                    if stored_entries[i].entry_id == entry.entry_id
                        && stored_entries[i].body == entry.body =>
                {
                    kept_entries.insert(i);
                }
                _ => writes.push(Op::InsertEntry {
                    position,
                    revision,
                    entry_id: entry.entry_id.clone(),
                    body: entry.body.clone(),
                }),
            }
        }

        // The history: what continues the committed messages stays where it is.
        let old: Vec<(i64, usize)> = visible(&stored_history, from, to, committed)
            .into_iter()
            .collect();
        let bodies: Vec<&str> = old
            .iter()
            .map(|&(_, i)| stored_history[i].body.as_str())
            .collect();
        let new: Vec<&str> = history.iter().map(String::as_str).collect();
        let (start, mut matched) = align_history(&bodies, &new);
        let contiguous = old[start.min(old.len())..start.min(old.len()) + matched]
            .windows(2)
            .all(|w| w[1].0 == w[0].0 + 1);
        if !contiguous {
            matched = 0;
        }
        let new_from = if matched > 0 { old[start].0 } else { to };
        let new_to = new_from + history.len() as i64;
        let kept_history: BTreeSet<usize> = old
            [start.min(old.len())..start.min(old.len()) + matched]
            .iter()
            .map(|&(_, i)| i)
            .collect();
        for (j, body) in history.iter().enumerate().skip(matched) {
            writes.push(Op::InsertHistory {
                seq: new_from + j as i64,
                revision,
                body: body.clone(),
            });
        }

        let title = clean_title(&conversation.title);
        let unchanged = head.as_ref().is_some_and(|h| {
            writes.is_empty()
                && h.entry_count == entries.len() as i64
                && (h.history_from, h.history_to) == (new_from, new_to)
                && h.row.title == title
        });
        if unchanged {
            self.run_assistant_ops(&id, garbage).await?;
            return Ok(committed);
        }

        // The commit, then what it replaced.
        let commit = Head {
            row: AssistantConversationRow {
                title,
                revision,
                updated_at: conversation.updated_at,
                ..head
                    .as_ref()
                    .map_or(conversation.clone(), |h| h.row.clone())
            },
            rid: head.as_ref().map_or(SqlValue::Null, |h| h.rid.clone()),
            save_id: save_id.to_string(),
            entry_count: entries.len() as i64,
            history_from: new_from,
            history_to: new_to,
        };
        let mut replaced = Vec::new();
        for (i, r) in stored_entries.iter().enumerate() {
            if r.revision <= committed && !kept_entries.contains(&i) {
                replaced.push(Op::Delete("assistant_entries", r.id.clone()));
            }
        }
        for (i, r) in stored_history.iter().enumerate() {
            if r.revision <= committed && !kept_history.contains(&i) {
                replaced.push(Op::Delete("assistant_history", r.id.clone()));
            }
        }
        let ops = garbage
            .into_iter()
            .chain(writes)
            .chain([Op::Commit(Box::new(commit), head.is_some())])
            .collect();
        self.run_assistant_ops(&id, ops).await?;
        // Committed: what it replaced is invisible now, and a cleanup cut
        // short is finished by the next save, so its failure is no failure
        // of this save (whose retry would find its base gone).
        let _ = self.run_assistant_ops(&id, replaced).await;
        Ok(revision)
    }

    /// Deletes conversation `id` everywhere: its row becomes a tombstone (one
    /// is written if this replica has none, so a save from elsewhere is
    /// refused too), then its entries and history go.
    pub async fn delete_assistant_conversation(
        &self,
        id: &str,
        device: &str,
        now: i64,
    ) -> Result<(), PrismError> {
        if !is_valid_conversation_id(id) {
            return Err(PrismError::InvalidInput(
                "A conversation's ID is 1 to 100 letters, digits or -_.:".into(),
            ));
        }
        let _guard = self.assistant_lock.lock().await;
        let (head, entries, history) = self.assistant_read(id).await?;
        let mut ops = vec![match head {
            Found::Nothing => Op::Tombstone(AssistantConversationRow {
                conversation_id: id.into(),
                device: device.into(),
                created_at: now,
                updated_at: now,
                ..AssistantConversationRow::default()
            }),
            _ => Op::MarkDeleted,
        }];
        for (table, rows) in [
            ("assistant_entries", entries),
            ("assistant_history", history),
        ] {
            ops.extend(rows.into_iter().map(|r| Op::Delete(table, r.id)));
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
                    position,
                    revision,
                    entry_id,
                    body,
                } => {
                    let rid = tx
                        .next_id("assistant_entries", vec![])
                        .await
                        .map_err(storage_err)?;
                    tx.execute(
                        "INSERT INTO assistant_entries \
                         (id, conversation_id, position, revision, entry_id, body) \
                         VALUES (?, ?, ?, ?, ?, ?)",
                        params![rid, id, position, revision, entry_id, body],
                    )
                    .await
                }
                Op::InsertHistory {
                    seq,
                    revision,
                    body,
                } => {
                    let rid = tx
                        .next_id("assistant_history", vec![])
                        .await
                        .map_err(storage_err)?;
                    tx.execute(
                        "INSERT INTO assistant_history (id, conversation_id, seq, revision, body) \
                         VALUES (?, ?, ?, ?, ?)",
                        params![rid, id, seq, revision, body],
                    )
                    .await
                }
                Op::Commit(head, true) => {
                    tx.execute(
                        "UPDATE assistant_conversations SET title = ?, updated_at = ?, revision = ?, \
                         entry_count = ?, history_from = ?, history_to = ?, save_id = ? WHERE id = ?",
                        params![
                            head.row.title,
                            head.row.updated_at,
                            head.row.revision,
                            head.entry_count,
                            head.history_from,
                            head.history_to,
                            head.save_id,
                            head.rid
                        ],
                    )
                    .await
                }
                Op::Commit(head, false) => {
                    let rid = tx
                        .next_id("assistant_conversations", vec![])
                        .await
                        .map_err(storage_err)?;
                    tx.execute(
                        "INSERT INTO assistant_conversations \
                         (id, conversation_id, device, title, created_at, updated_at, revision, \
                          entry_count, history_from, history_to, save_id) \
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        params![
                            rid,
                            head.row.conversation_id,
                            head.row.device,
                            head.row.title,
                            head.row.created_at,
                            head.row.updated_at,
                            head.row.revision,
                            head.entry_count,
                            head.history_from,
                            head.history_to,
                            head.save_id
                        ],
                    )
                    .await
                }
                Op::Tombstone(row) => {
                    let rid = tx
                        .next_id("assistant_conversations", vec![])
                        .await
                        .map_err(storage_err)?;
                    tx.execute(
                        "INSERT INTO assistant_conversations \
                         (id, conversation_id, device, title, created_at, updated_at, deleted) \
                         VALUES (?, ?, ?, '', ?, ?, 1)",
                        params![
                            rid,
                            row.conversation_id,
                            row.device,
                            row.created_at,
                            row.updated_at
                        ],
                    )
                    .await
                }
                Op::MarkDeleted => {
                    tx.execute(
                        "UPDATE assistant_conversations SET deleted = 1 WHERE conversation_id = ?",
                        params![id],
                    )
                    .await
                }
            }
            .map_err(storage_err)?;
        }
        tx.commit().await.map_err(storage_err)?;
        Ok(())
    }

    /// Conversation `id`'s head and every row of its entries and history,
    /// read in one transaction (rolled back), so all of it is of one moment.
    async fn assistant_read(
        &self,
        id: &str,
    ) -> Result<(Found, Vec<Stored>, Vec<Stored>), PrismError> {
        let mut tx = self.db.transaction().await.map_err(storage_err)?;
        let read = async {
            let head = assistant_head(&mut tx, id).await?;
            let entries = assistant_rows(&mut tx, "assistant_entries", id).await?;
            let history = assistant_rows(&mut tx, "assistant_history", id).await?;
            Ok((head, entries, history))
        }
        .await;
        tx.rollback().await.map_err(storage_err)?;
        read
    }
}

async fn assistant_head(tx: &mut Transaction, id: &str) -> Result<Found, PrismError> {
    let mut rows = tx
        .query(
            &format!(
                "SELECT {HEAD_COLUMNS} FROM assistant_conversations \
                     WHERE conversation_id = ? ORDER BY id ASC"
            ),
            params![id],
        )
        .await
        .map_err(storage_err)?;
    let mut all = Vec::new();
    while let Some(r) = rows.next().await.map_err(storage_err)? {
        all.push(head_row(&r)?);
    }
    Ok(head_of(all))
}

/// Every row of conversation `id` in `table`, in Rocky id order.
async fn assistant_rows(
    tx: &mut Transaction,
    table: &str,
    id: &str,
) -> Result<Vec<Stored>, PrismError> {
    let (order, entry_id) = match table {
        "assistant_entries" => ("position", "entry_id"),
        _ => ("seq", "''"),
    };
    let mut rows = tx
        .query(
            &format!(
                "SELECT id, {order} AS ord, revision, {entry_id} AS entry_id, body \
                     FROM {table} WHERE conversation_id = ? ORDER BY id ASC"
            ),
            params![id],
        )
        .await
        .map_err(storage_err)?;
    let mut out = Vec::new();
    while let Some(r) = rows.next().await.map_err(storage_err)? {
        out.push(Stored {
            id: r.get::<SqlValue>("id").map_err(storage_err)?,
            order: col_i64(&r, "ord"),
            revision: col_i64(&r, "revision"),
            entry_id: col_str(&r, "entry_id"),
            body: col_str(&r, "body"),
        });
    }
    Ok(out)
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
    fn readers_see_the_newest_row_up_to_the_committed_revision() {
        let row = |order, revision, body: &str| Stored {
            id: SqlValue::Null,
            order,
            revision,
            entry_id: String::new(),
            body: body.into(),
        };
        let rows = [
            row(0, 1, "a"),
            row(1, 1, "b"),
            row(1, 2, "b2"),
            row(2, 3, "uncommitted"),
            row(5, 1, "outside"),
        ];
        let seen: Vec<&str> = visible(&rows, 0, 3, 2)
            .into_values()
            .map(|i| rows[i].body.as_str())
            .collect();
        assert_eq!(seen, ["a", "b2"]);
    }

    /// A save cut short after writing some rows of its revision (Rocky busy,
    /// the app gone): readers keep seeing the committed revision, and the
    /// next save on it clears those rows instead of mixing them in.
    #[tokio::test]
    async fn a_save_cut_short_is_never_seen_and_the_next_save_clears_it() {
        let dir = tempfile::tempdir().expect("tempdir");
        let client = PrismClient::open_or_create(dir.path().join("db").display().to_string(), None)
            .await
            .expect("open");
        let e = |id: &str, body: &str| AssistantEntry {
            entry_id: id.into(),
            body: body.into(),
        };
        let row = AssistantConversationRow {
            conversation_id: "c-1".into(),
            device: "david-pixel".into(),
            title: "t".into(),
            created_at: 1,
            updated_at: 1,
            revision: 0,
        };
        let saved = client
            .save_assistant_conversation(
                row.clone(),
                0,
                "s1",
                vec![e("e0", "a"), e("e1", "b")],
                vec!["m1".into(), "m2".into()],
            )
            .await
            .expect("save");
        assert_eq!(saved, 1);

        // What revision 2 wrote before it was cut short: rows, no commit.
        client
            .run_assistant_ops(
                "c-1",
                vec![
                    Op::InsertEntry {
                        position: 1,
                        revision: 2,
                        entry_id: "e1".into(),
                        body: "b2".into(),
                    },
                    Op::InsertEntry {
                        position: 2,
                        revision: 2,
                        entry_id: "e2".into(),
                        body: "c".into(),
                    },
                    Op::InsertHistory {
                        seq: 2,
                        revision: 2,
                        body: "m3".into(),
                    },
                ],
            )
            .await
            .expect("half a save");
        let c = client.assistant_conversation("c-1").await.expect("read");
        let bodies = |c: &AssistantConversation| {
            c.entries.iter().map(|e| e.body.clone()).collect::<Vec<_>>()
        };
        assert_eq!(bodies(&c), ["a", "b"]);
        assert_eq!(c.history, ["m1", "m2"]);
        assert_eq!(c.conversation.revision, 1);

        // The next save builds on revision 1 and owns revision 2.
        let saved = client
            .save_assistant_conversation(
                row,
                1,
                "s2",
                vec![e("e0", "a"), e("e1", "b"), e("e9", "z")],
                vec!["m1".into(), "m2".into(), "m9".into()],
            )
            .await
            .expect("save on 1");
        assert_eq!(saved, 2);
        let c = client.assistant_conversation("c-1").await.expect("read");
        assert_eq!(bodies(&c), ["a", "b", "z"]);
        assert_eq!(c.history, ["m1", "m2", "m9"]);
        // Nothing of the cut-short save is left behind.
        let (_, entries, history) = client.assistant_read("c-1").await.expect("rows");
        assert_eq!((entries.len(), history.len()), (3, 3));
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
