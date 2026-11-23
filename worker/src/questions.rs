//! Pending T3 user questions scanner and answer dispatcher, ported from
//! the Go server's `questions.go`.

use crate::push::Pusher;
use crate::t3::T3Hub;
use rocky_prism::{Notification, PrismClient, QuestionRow};
use serde::Deserialize;
use serde_json::{Value, json};
use std::collections::BTreeSet;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ThreadListPage {
    #[serde(default)]
    threads: Vec<ThreadListEntry>,
    next_cursor: Option<i64>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ThreadListEntry {
    #[serde(default)]
    thread_id: String,
    #[serde(default)]
    title: String,
    #[serde(default)]
    link: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PendingListResp {
    #[serde(default)]
    request_ids: Vec<String>,
}

fn extract_env_from_link(link: &str) -> String {
    // e.g. https://.../threads/<env>/<thread> or t3code://threads/<env>/<thread>
    let Some(pos) = link.find("/threads/") else {
        return String::new();
    };
    let rest = &link[pos + "/threads/".len()..];
    rest.split('/').next().unwrap_or("").to_string()
}

/// Scans every reachable T3 (direct + linked) for pending user questions,
/// records new ones in Rocky, pushes `agent.question`, and closes disappeared ones.
pub async fn scan_questions(
    client: &PrismClient,
    t3: &T3Hub,
    pusher: &Pusher,
    now: i64,
) -> Result<(), String> {
    let direct = t3.direct_machines().await;
    let mut sources: Vec<(String, String, String)> = Vec::new(); // (client, via, host)
    for m in &direct {
        sources.push((m.clone(), String::new(), m.clone()));
    }
    let mut seen_envs = BTreeSet::new();
    for m in &direct {
        if let Ok(links) = t3.environment_links(m).await {
            for link in links {
                if link.environment_id.is_empty()
                    || !seen_envs.insert(link.environment_id.clone())
                    || (!link.status.is_empty() && link.status != "reachable")
                {
                    continue;
                }
                let host = t3.link_host(&link.label);
                sources.push((m.clone(), link.environment_id, host));
            }
        }
    }

    let mut seen_questions = BTreeSet::new();
    let mut covered_sources = BTreeSet::new();
    // Threads listed whose questions could not be read: theirs stay open.
    let mut unread_threads = BTreeSet::new();

    for (src_client, via, host) in sources {
        let mut cursor: Option<i64> = None;
        let mut threads = Vec::new();
        let mut ok = true;
        for _ in 0..20 {
            let mut args = json!({
                "allProjects": true,
                "statuses": ["waiting", "running"],
                "includeSubagents": true,
                "limit": 100,
            });
            if let Some(c) = cursor {
                args["cursor"] = json!(c);
            }
            match t3.call(&src_client, &via, "t3_thread_list", args).await {
                Ok(val) => {
                    let page: ThreadListPage =
                        serde_json::from_value(val).unwrap_or(ThreadListPage {
                            threads: Vec::new(),
                            next_cursor: None,
                        });
                    threads.extend(page.threads);
                    if page.next_cursor.is_none() || page.next_cursor == cursor {
                        break;
                    }
                    cursor = page.next_cursor;
                }
                Err(_) => {
                    ok = false;
                    break;
                }
            }
        }
        if !ok {
            continue;
        }
        covered_sources.insert(format!("{src_client}\x00{via}"));

        for t in threads {
            if t.thread_id.is_empty() {
                continue;
            }
            let env_id = {
                let e = extract_env_from_link(&t.link);
                if e.is_empty() { via.clone() } else { e }
            };
            let list_val = match t3
                .call(
                    &src_client,
                    &via,
                    "t3_pending_request",
                    json!({"action": "list", "threadId": t.thread_id}),
                )
                .await
            {
                Ok(v) => v,
                Err(_) => {
                    unread_threads.insert(t.thread_id.clone());
                    continue;
                }
            };
            let list: PendingListResp =
                serde_json::from_value(list_val).unwrap_or(PendingListResp {
                    request_ids: Vec::new(),
                });
            for req_id in list.request_ids {
                seen_questions.insert(req_id.clone());
                if client.has_open_question(&req_id).await.unwrap_or(false) {
                    continue;
                }
                let raw = match t3
                    .call(
                        &src_client,
                        &via,
                        "t3_pending_request",
                        json!({"action": "read", "threadId": t.thread_id, "requestId": req_id}),
                    )
                    .await
                {
                    Ok(v) => v,
                    Err(_) => continue,
                };
                let payload = serde_json::to_string(&raw).unwrap_or_default();
                let opened = client
                    .upsert_open_question(&QuestionRow {
                        question_id: req_id.clone(),
                        thread_id: t.thread_id.clone(),
                        environment_id: env_id.clone(),
                        host: host.clone(),
                        client: src_client.clone(),
                        via: via.clone(),
                        title: t.title.clone(),
                        payload,
                        state: "open".into(),
                        created_at: now,
                        updated_at: now,
                    })
                    .await
                    .map_err(|e| e.to_string())?;
                if opened {
                    let body = summarize_questions(&raw);
                    let n = Notification {
                        kind: "agent.question".into(),
                        key: format!("agent.question:{req_id}"),
                        time: now,
                        title: format!("Agent asks: {}", t.title),
                        body,
                        question_id: req_id,
                        agent_id: t.thread_id.clone(),
                        host: host.clone(),
                        environment_id: env_id.clone(),
                        thread_id: t.thread_id.clone(),
                        ..Notification::default()
                    };
                    let _ = pusher.deliver(client, &[n], now).await;
                }
            }
        }
    }

    // Close questions from covered sources that are no longer pending.
    let open = client
        .list_open_questions()
        .await
        .map_err(|e| e.to_string())?;
    for q in open {
        let src_key = format!("{}\x00{}", q.client, q.via);
        if covered_sources.contains(&src_key)
            && !seen_questions.contains(&q.question_id)
            && !unread_threads.contains(&q.thread_id)
        {
            client
                .close_question(&q.question_id, "closed", now)
                .await
                .map_err(|e| e.to_string())?;
            let n = Notification {
                kind: "question.closed".into(),
                key: format!("question.closed:{}", q.question_id),
                time: now,
                title: "Question closed".into(),
                question_id: q.question_id,
                host: q.host,
                environment_id: q.environment_id,
                thread_id: q.thread_id,
                ..Notification::default()
            };
            let _ = pusher.deliver(client, &[n], now).await;
        }
    }
    Ok(())
}

fn summarize_questions(raw: &Value) -> String {
    raw.get("questions")
        .and_then(Value::as_array)
        .map(|arr| {
            arr.iter()
                .filter_map(|q| q.get("question").and_then(Value::as_str))
                .collect::<Vec<_>>()
                .join("\n")
        })
        .unwrap_or_default()
}

/// Claims and dispatches pending `question_answers` rows.
pub async fn run_question_answers(
    client: &PrismClient,
    t3: &T3Hub,
    pusher: &Pusher,
    worker: &str,
    now: i64,
    claim_timeout: i64,
) -> Result<(), String> {
    let items = client
        .claimable_question_answers(now - claim_timeout)
        .await
        .map_err(|e| e.to_string())?;
    for item in items {
        if !client
            .claim_question_answer(&item.request_id, worker, now, now - claim_timeout)
            .await
            .map_err(|e| e.to_string())?
        {
            continue;
        }
        let err_msg = match process_question_answer(client, t3, pusher, &item, now).await {
            Ok(()) => String::new(),
            Err(e) => e,
        };
        client
            .finish_question_answer(&item.request_id, worker, now, &err_msg)
            .await
            .map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// T3's words for a request that is no longer pending (Go's `goneMessages`).
const GONE_MESSAGES: [&str; 3] = ["was not found", "already answered", "no longer pending"];

async fn close_question_with_push(
    client: &PrismClient,
    pusher: &Pusher,
    q: &QuestionRow,
    state: &str,
    now: i64,
) -> Result<(), String> {
    client
        .close_question(&q.question_id, state, now)
        .await
        .map_err(|e| e.to_string())?;
    let n = Notification {
        kind: "question.closed".into(),
        key: format!("question.closed:{}", q.question_id),
        time: now,
        title: "Question closed".into(),
        question_id: q.question_id.clone(),
        host: q.host.clone(),
        environment_id: q.environment_id.clone(),
        thread_id: q.thread_id.clone(),
        ..Notification::default()
    };
    let _ = pusher.deliver(client, &[n], now).await;
    Ok(())
}

/// Answers a pending question in T3, on the T3 the scanner found it on, as
/// Go's `AnswerQuestion` does: the app's answers become what T3 takes, a
/// question T3 no longer lists closes, and an unreachable T3 leaves it open.
/// The leader runs it, and a device does when the workers are unreachable.
pub async fn process_question_answer(
    client: &PrismClient,
    t3: &T3Hub,
    pusher: &Pusher,
    req: &rocky_prism::QuestionAnswerRow,
    now: i64,
) -> Result<(), String> {
    let Some(q) = client
        .get_open_question(&req.question_id)
        .await
        .map_err(|e| e.to_string())?
    else {
        return Err("Not found.".into());
    };
    let input: serde_json::Map<String, Value> = serde_json::from_str(&req.answers_json)
        .map_err(|_| "Answers are strings or lists of strings.".to_string())?;
    let request: PendingRequest = serde_json::from_str(&q.payload).unwrap_or_default();
    let answers = t3_answers(&request.questions, &input)?;
    let place = if !q.host.is_empty() {
        q.host.clone()
    } else if !q.client.is_empty() {
        q.client.clone()
    } else {
        "the cluster machine".into()
    };
    let unavailable = |err: &crate::t3::T3Error| match err {
        crate::t3::T3Error::User {
            message,
            unavailable: true,
        } => message.clone(),
        _ => format!("T3 on {place} does not answer. Try again, or answer in T3."),
    };
    let listed = t3
        .call(
            &q.client,
            &q.via,
            "t3_pending_request",
            json!({"action": "list", "threadId": q.thread_id}),
        )
        .await
        .map_err(|e| unavailable(&e))?;
    let listed: PendingListResp = serde_json::from_value(listed).unwrap_or(PendingListResp {
        request_ids: Vec::new(),
    });
    if !listed.request_ids.contains(&q.question_id) {
        close_question_with_push(client, pusher, &q, "closed", now).await?;
        return Err("Not found.".into());
    }
    if let Err(err) = t3
        .call(
            &q.client,
            &q.via,
            "t3_pending_request",
            json!({
                "action": "respond",
                "threadId": q.thread_id,
                "requestId": q.question_id,
                "answers": answers,
            }),
        )
        .await
    {
        if let crate::t3::T3Error::Tool(message) = &err
            && GONE_MESSAGES.iter().any(|m| message.contains(m))
        {
            close_question_with_push(client, pusher, &q, "closed", now).await?;
            return Err("Not found.".into());
        }
        return Err(unavailable(&err));
    }
    close_question_with_push(client, pusher, &q, "answered", now).await
}

#[derive(Default, Deserialize)]
struct PendingRequest {
    #[serde(default)]
    questions: Vec<PendingQuestion>,
}

#[derive(Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct PendingQuestion {
    #[serde(default)]
    id: String,
    #[serde(default)]
    header: String,
    #[serde(default)]
    multi_select: bool,
    #[serde(default)]
    options: Vec<PendingOption>,
    allow_custom_answer: Option<bool>,
    required: Option<bool>,
}

#[derive(Default, Deserialize)]
struct PendingOption {
    #[serde(default)]
    label: String,
    #[serde(default)]
    value: String,
}

/// Turns the app's answers into what T3 takes (Go's `t3Answers`): per
/// question ID a string (an option's value, which is its label unless the
/// option has its own value, or free text) or, for a multi-select question,
/// a list of option values.
fn t3_answers(
    questions: &[PendingQuestion],
    input: &serde_json::Map<String, Value>,
) -> Result<serde_json::Map<String, Value>, String> {
    let mut out = serde_json::Map::new();
    for q in questions {
        let name = if q.header.is_empty() {
            &q.id
        } else {
            &q.header
        };
        let option_value = |label: &str| -> Option<String> {
            q.options
                .iter()
                .find(|o| !o.value.is_empty() && o.value == label)
                .or_else(|| q.options.iter().find(|o| o.label.trim() == label.trim()))
                .map(|o| {
                    if o.value.is_empty() {
                        o.label.clone()
                    } else {
                        o.value.clone()
                    }
                })
        };
        let text = |s: &str| -> Result<String, String> {
            if let Some(v) = option_value(s) {
                return Ok(v);
            }
            let s = s.trim();
            if s.is_empty() {
                if q.required == Some(false) {
                    return Ok(String::new());
                }
                return Err(format!("Answer {name:?}."));
            }
            if q.allow_custom_answer == Some(false) {
                return Err(format!("{name:?} takes one of its options only."));
            }
            Ok(s.to_string())
        };
        let raw = input.get(&q.id).cloned().unwrap_or(Value::Null);
        let answer = match raw {
            Value::Null => Value::String(text("")?),
            Value::String(v) => Value::String(text(&v)?),
            Value::Array(items) => {
                let labels: Vec<String> = items
                    .into_iter()
                    .map(|i| match i {
                        Value::String(s) => Ok(s),
                        _ => Err("Answers are strings or lists of strings.".to_string()),
                    })
                    .collect::<Result<_, _>>()?;
                if !q.multi_select {
                    if labels.len() != 1 {
                        return Err(format!("{name:?} takes one answer."));
                    }
                    Value::String(text(&labels[0])?)
                } else {
                    let mut values: Vec<String> = Vec::new();
                    for label in &labels {
                        let Some(value) = option_value(label) else {
                            return Err(format!(
                                "{label:?} is not an option of {name:?}. Send free text as a string."
                            ));
                        };
                        if !values.contains(&value) {
                            values.push(value);
                        }
                    }
                    if values.is_empty() {
                        Value::String(text("")?)
                    } else {
                        json!(values)
                    }
                }
            }
            _ => return Err("Answers are strings or lists of strings.".into()),
        };
        out.insert(q.id.clone(), answer);
    }
    if let Some(unknown) = input
        .keys()
        .find(|k| !questions.iter().any(|q| &q.id == *k))
    {
        return Err(format!("The question has no part {unknown:?}."));
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::Config;
    use crate::testutil::{FakeT3, serve};
    use std::sync::Arc;

    const NOW: i64 = 1_800_000_000;

    fn payload() -> Value {
        json!({"requestId": "q-1", "questions": [
            {"id": "color", "header": "Color", "question": "Which color?",
             "options": [{"label": "Red"}, {"label": "Blue", "value": "blue-v"}],
             "allowCustomAnswer": false},
            {"id": "parts", "header": "Parts", "question": "Which parts?", "multiSelect": true,
             "options": [{"label": "A"}, {"label": "B"}]},
            {"id": "note", "header": "Note", "question": "Anything else?"}
        ]})
    }

    struct Rig {
        client: PrismClient,
        t3: FakeT3,
        hub: T3Hub,
        pusher: Pusher,
        _dir: tempfile::TempDir,
    }

    async fn rig() -> Rig {
        let dir = tempfile::tempdir().unwrap();
        let client = PrismClient::open_or_create(dir.path().display().to_string(), None)
            .await
            .unwrap();
        let t3 = FakeT3::default();
        t3.pending
            .lock()
            .unwrap()
            .insert("l1".into(), vec![("q-1".into(), payload())]);
        let url = serve(t3.router()).await;
        let cfg = Arc::new(
            Config::parse(
                &json!({"cluster_machine": "david-server",
                        "machines": {"david-server": {"t3_url": url}}})
                .to_string(),
            )
            .unwrap(),
        );
        let secrets: crate::t3::Secrets =
            Arc::new(|name: &str| (name == "T3_ACCESS_TOKEN").then(|| "t3-token".to_string()));
        let hub = T3Hub::new(cfg, Arc::clone(&secrets));
        let pusher = Pusher::new("http://127.0.0.1:9", "t", Arc::new(|_: &str| None));
        scan_questions(&client, &hub, &pusher, NOW).await.unwrap();
        Rig {
            client,
            t3,
            hub,
            pusher,
            _dir: dir,
        }
    }

    fn answer(answers: Value) -> rocky_prism::QuestionAnswerRow {
        rocky_prism::QuestionAnswerRow {
            request_id: "r".into(),
            question_id: "q-1".into(),
            answers_json: answers.to_string(),
            requested_by: "david-pixel".into(),
            requested_at: NOW,
            claimed_by: None,
            claimed_at: None,
            done_at: None,
            error: String::new(),
        }
    }

    #[tokio::test]
    async fn answers_are_mapped_to_option_values_and_invalid_ones_never_reach_t3() {
        let r = rig().await;
        assert!(r.client.get_open_question("q-1").await.unwrap().is_some());
        for bad in [
            json!({"color": "Red", "parts": ["A", "C"], "note": "x"}),
            json!({"color": "Green", "parts": ["A"], "note": "x"}),
            json!({"color": "Red", "parts": ["A"]}),
            json!({"color": "Red", "parts": ["A"], "note": "x", "extra": "y"}),
            json!({"color": 3, "parts": ["A"], "note": "x"}),
        ] {
            let err =
                process_question_answer(&r.client, &r.hub, &r.pusher, &answer(bad.clone()), NOW)
                    .await
                    .expect_err(&bad.to_string());
            assert!(!err.contains("does not answer"), "{bad}: {err}");
        }
        assert!(r.t3.responds.lock().unwrap().is_empty());

        process_question_answer(
            &r.client,
            &r.hub,
            &r.pusher,
            &answer(json!({"color": "Blue", "parts": ["B", "A", "B"], "note": "  free words  "})),
            NOW,
        )
        .await
        .expect("answer");
        let responds = r.t3.responds.lock().unwrap().clone();
        assert_eq!(responds.len(), 1);
        assert_eq!(
            responds[0]["answers"],
            json!({"color": "blue-v", "parts": ["B", "A"], "note": "free words"})
        );
        assert!(r.client.get_open_question("q-1").await.unwrap().is_none());
    }

    #[tokio::test]
    async fn a_question_t3_answered_meanwhile_closes() {
        let r = rig().await;
        *r.t3.respond_error.lock().unwrap() =
            Some("The user-input request was already answered.".into());
        let ok = json!({"color": "Red", "parts": ["A"], "note": "ok"});
        let err = process_question_answer(&r.client, &r.hub, &r.pusher, &answer(ok), NOW)
            .await
            .unwrap_err();
        assert_eq!(err, "Not found.");
        assert!(r.client.get_open_question("q-1").await.unwrap().is_none());
    }

    #[tokio::test]
    async fn a_question_whose_thread_could_not_be_read_stays_open() {
        let r = rig().await;
        r.t3.broken_threads.lock().unwrap().push("l1".into());
        scan_questions(&r.client, &r.hub, &r.pusher, NOW + 30)
            .await
            .unwrap();
        assert!(r.client.get_open_question("q-1").await.unwrap().is_some());
        // Readable again and no longer pending: now it closes.
        r.t3.broken_threads.lock().unwrap().clear();
        r.t3.pending.lock().unwrap().get_mut("l1").unwrap().clear();
        scan_questions(&r.client, &r.hub, &r.pusher, NOW + 60)
            .await
            .unwrap();
        assert!(r.client.get_open_question("q-1").await.unwrap().is_none());
    }
}
