//! The phone assistant's conversations: saved whole by revision, written by
//! the row, read back in order, listed newest first, refused when stale or
//! deleted; and a replica of the first schema taking the new tables.

use rocky_prism::{
    ASSISTANT_DELETED, ASSISTANT_MAX_BODY_BYTES, ASSISTANT_STALE, AssistantConversationRow,
    AssistantEntry, PrismClient, PrismError, SCHEMA_VERSION, prism_schema,
};

async fn open() -> (tempfile::TempDir, PrismClient) {
    let dir = tempfile::tempdir().expect("tempdir");
    let client = PrismClient::open_or_create(dir.path().join("db").display().to_string(), None)
        .await
        .expect("open");
    (dir, client)
}

fn row(id: &str, title: &str, at: i64) -> AssistantConversationRow {
    AssistantConversationRow {
        conversation_id: id.into(),
        device: "david-pixel".into(),
        title: title.into(),
        created_at: at,
        updated_at: at,
        revision: 0,
    }
}

fn entry(id: &str, body: &str) -> AssistantEntry {
    AssistantEntry {
        entry_id: id.into(),
        body: body.into(),
    }
}

fn strings(v: &[&str]) -> Vec<String> {
    v.iter().map(|s| s.to_string()).collect()
}

fn conflict(result: Result<i64, PrismError>) -> String {
    match result {
        Err(PrismError::Conflict(m)) => m,
        other => panic!("expected a conflict, got {other:?}"),
    }
}

#[tokio::test]
async fn a_conversation_is_saved_by_revision_and_read_back_in_order() {
    let (_dir, client) = open().await;
    let first = client
        .save_assistant_conversation(
            row("c-1", "  Weather\nin Vienna? ", 100),
            0,
            "",
            vec![
                entry("e0", r#"{"type":"user","text":"Weather?"}"#),
                entry("tool:1", r#"{"type":"step"}"#),
            ],
            strings(&["m1", "m2"]),
        )
        .await
        .expect("save");
    assert_eq!(first, 1);

    let c = client.assistant_conversation("c-1").await.expect("read");
    assert_eq!(c.conversation.title, "Weather in Vienna?");
    assert_eq!(c.conversation.device, "david-pixel");
    assert_eq!(c.conversation.revision, 1);
    assert_eq!(
        c.entries
            .iter()
            .map(|e| e.entry_id.as_str())
            .collect::<Vec<_>>(),
        ["e0", "tool:1"]
    );
    assert_eq!(c.history, strings(&["m1", "m2"]));

    // Saved again unchanged: still revision 1.
    let again = client
        .save_assistant_conversation(
            row("c-1", "Weather in Vienna?", 100),
            1,
            "",
            c.entries.clone(),
            c.history.clone(),
        )
        .await
        .expect("save again");
    assert_eq!(again, 1);

    // A tool's line became its card, an answer came, the oldest message was
    // dropped and two were added. Another device continuing it doesn't
    // change who started it.
    let second = client
        .save_assistant_conversation(
            AssistantConversationRow {
                device: "david-mb".into(),
                created_at: 999,
                ..row("c-1", "Weather in Vienna?", 200)
            },
            1,
            "",
            vec![
                entry("e0", r#"{"type":"user","text":"Weather?"}"#),
                entry("tool:1", r#"{"type":"card"}"#),
                entry("e1", r#"{"type":"answer","text":"2°"}"#),
            ],
            strings(&["m2", "m3", "m4"]),
        )
        .await
        .expect("save more");
    assert_eq!(second, 2);
    let c = client.assistant_conversation("c-1").await.expect("read");
    assert_eq!(c.entries[1].body, r#"{"type":"card"}"#);
    assert_eq!(c.entries[2].entry_id, "e1");
    assert_eq!(c.history, strings(&["m2", "m3", "m4"]));
    assert_eq!(
        (
            c.conversation.device.as_str(),
            c.conversation.created_at,
            c.conversation.updated_at
        ),
        ("david-pixel", 100, 200)
    );

    // A run stopped halfway: its tail goes, the new turn follows.
    client
        .save_assistant_conversation(
            row("c-1", "Weather in Vienna?", 300),
            2,
            "",
            c.entries.clone(),
            strings(&["m2", "m3", "m5"]),
        )
        .await
        .expect("save after a stop");
    let c = client.assistant_conversation("c-1").await.expect("read");
    assert_eq!(c.history, strings(&["m2", "m3", "m5"]));
    assert_eq!(c.conversation.revision, 3);
}

/// Two sessions that loaded the same revision: the second save is refused
/// instead of writing over the first.
#[tokio::test]
async fn a_save_on_an_old_revision_is_refused() {
    let (_dir, client) = open().await;
    let save = |base, text: &'static str| {
        client.save_assistant_conversation(
            row("c-1", "x", 1),
            base,
            "",
            vec![entry("e0", text)],
            strings(&[text]),
        )
    };
    assert_eq!(save(0, "a").await.expect("first"), 1);
    assert_eq!(conflict(save(0, "b").await), ASSISTANT_STALE);
    assert_eq!(save(1, "c").await.expect("on the latest"), 2);
    assert_eq!(conflict(save(1, "d").await), ASSISTANT_STALE);
    let c = client.assistant_conversation("c-1").await.expect("read");
    assert_eq!(c.history, strings(&["c"]));
}

/// A save that committed but whose answer was lost (Rocky timed out after
/// the commit) is sent again as it was: it succeeds with the same revision,
/// writes nothing more, and the next save builds on it. Another save on the
/// old base is still refused.
#[tokio::test]
async fn the_retry_of_a_committed_save_succeeds_with_its_revision() {
    let (_dir, client) = open().await;
    let save = |base, save_id: &'static str, text: &'static str| {
        client.save_assistant_conversation(
            row("c-1", "x", 1),
            base,
            save_id,
            vec![entry("e0", text)],
            strings(&[text]),
        )
    };
    assert_eq!(save(0, "s-a", "a").await.expect("first save"), 1);
    // The phone never heard back, and sends the identical save again.
    assert_eq!(save(0, "s-a", "a").await.expect("its retry"), 1);
    assert_eq!(save(0, "s-a", "a").await.expect("and again"), 1);
    let c = client.assistant_conversation("c-1").await.expect("read");
    assert_eq!(
        (c.conversation.revision, c.history.clone()),
        (1, strings(&["a"]))
    );

    assert_eq!(save(1, "s-b", "b").await.expect("the next save"), 2);
    assert_eq!(save(1, "s-b", "b").await.expect("its retry"), 2);
    // A save of an older version is answered only while it is the committed one.
    assert_eq!(conflict(save(0, "s-a", "a").await), ASSISTANT_STALE);
    assert_eq!(conflict(save(1, "s-c", "c").await), ASSISTANT_STALE);
    let c = client.assistant_conversation("c-1").await.expect("read");
    assert_eq!((c.conversation.revision, c.history), (2, strings(&["b"])));
}

/// A deleted conversation stays deleted: its tombstone refuses later saves
/// (a session still running, another device), also of a conversation this
/// replica never had.
#[tokio::test]
async fn a_deleted_conversation_refuses_saves() {
    let (_dir, client) = open().await;
    for (id, at) in [("c-old", 100), ("c-new", 300), ("c-mid", 200)] {
        client
            .save_assistant_conversation(
                row(id, id, at),
                0,
                "",
                vec![entry("e0", "{}")],
                strings(&["m"]),
            )
            .await
            .expect("save");
    }
    let ids = |list: Vec<AssistantConversationRow>| {
        list.into_iter()
            .map(|c| c.conversation_id)
            .collect::<Vec<_>>()
    };
    assert_eq!(
        ids(client.list_assistant_conversations(50).await.expect("list")),
        ["c-new", "c-mid", "c-old"]
    );
    assert_eq!(
        ids(client.list_assistant_conversations(1).await.expect("list")),
        ["c-new"]
    );

    client
        .delete_assistant_conversation("c-mid", "david-pixel", 400)
        .await
        .expect("delete");
    assert_eq!(
        ids(client.list_assistant_conversations(50).await.expect("list")),
        ["c-new", "c-old"]
    );
    assert!(matches!(
        client.assistant_conversation("c-mid").await,
        Err(PrismError::NotFound)
    ));
    for base in [0, 1] {
        assert_eq!(
            conflict(
                client
                    .save_assistant_conversation(
                        row("c-mid", "back", 500),
                        base,
                        "",
                        vec![],
                        vec![]
                    )
                    .await
            ),
            ASSISTANT_DELETED
        );
    }

    // Deleted before it was ever stored here (an outbox's delete).
    client
        .delete_assistant_conversation("c-never", "david-pixel", 400)
        .await
        .expect("delete unknown");
    assert_eq!(
        conflict(
            client
                .save_assistant_conversation(row("c-never", "x", 1), 0, "", vec![], vec![])
                .await
        ),
        ASSISTANT_DELETED
    );
    assert_eq!(
        ids(client.list_assistant_conversations(50).await.expect("list")),
        ["c-new", "c-old"]
    );
}

#[tokio::test]
async fn large_conversations_go_in_small_transactions_and_oversized_rows_are_refused() {
    let (_dir, client) = open().await;
    // 60 entries and 60 messages near the limit: about 2 MB, which no one
    // transaction may carry.
    let big = "x".repeat(ASSISTANT_MAX_BODY_BYTES);
    let entries: Vec<_> = (0..60).map(|i| entry(&format!("e{i}"), &big)).collect();
    let history: Vec<_> = (0..60).map(|i| format!("{i:03}{}", &big[3..])).collect();
    client
        .save_assistant_conversation(
            row("c-big", "big", 1),
            0,
            "",
            entries.clone(),
            history.clone(),
        )
        .await
        .expect("save big");
    let c = client.assistant_conversation("c-big").await.expect("read");
    assert_eq!(c.entries.len(), 60);
    assert_eq!(c.history, history);

    // No limit on how long a conversation gets: 3000 entries.
    let many: Vec<_> = (0..3000).map(|i| entry(&format!("e{i}"), "{}")).collect();
    client
        .save_assistant_conversation(row("c-long", "long", 1), 0, "", many, vec![])
        .await
        .expect("save a long conversation");
    let c = client.assistant_conversation("c-long").await.expect("read");
    assert_eq!(c.entries.len(), 3000);
    assert_eq!(c.entries[2999].entry_id, "e2999");

    let too_big = "x".repeat(ASSISTANT_MAX_BODY_BYTES + 1);
    for (entries, history) in [
        (vec![entry("e0", &too_big)], vec![]),
        (vec![], vec![too_big.clone()]),
        (vec![entry("e0", "{}"), entry("e0", "{}")], vec![]),
    ] {
        assert!(matches!(
            client
                .save_assistant_conversation(row("c-x", "x", 1), 0, "", entries, history)
                .await,
            Err(PrismError::InvalidInput(_))
        ));
    }
    assert!(matches!(
        client
            .save_assistant_conversation(row("not an id", "x", 1), 0, "", vec![], vec![])
            .await,
        Err(PrismError::InvalidInput(_))
    ));
}

/// A replica created with the first schema (before the assistant's tables)
/// opens with this one and stores conversations: the change is additive.
#[tokio::test]
async fn a_replica_of_the_first_schema_takes_the_assistants_tables() {
    assert_eq!(SCHEMA_VERSION, 2);
    let dir = tempfile::tempdir().expect("tempdir");
    let path = dir.path().join("db");
    let mut first = prism_schema().expect("schema");
    first.version = 1;
    first.tables.retain(|t| !t.name.starts_with("assistant_"));
    let opened = rocky_sdk::Configuration::new(&path, &first)
        .expect("config")
        .create()
        .await
        .expect("create the first schema");
    opened.database.close().await.expect("close");

    // The first open publishes the new schema (an issuer, as every Prism
    // device is) and writes to its tables at once.
    let client = PrismClient::open_or_create(path.display().to_string(), None)
        .await
        .expect("open with the assistant's tables");
    client
        .save_assistant_conversation(
            row("c-1", "hi", 1),
            0,
            "",
            vec![entry("e0", "{}")],
            strings(&["m"]),
        )
        .await
        .expect("save");
    let saved = client.assistant_conversation("c-1").await.expect("read");
    client.close().await.expect("close");
    assert_eq!(saved.history, strings(&["m"]));
}
