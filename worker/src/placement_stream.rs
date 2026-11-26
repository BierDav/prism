//! `GET /v1/placement/stream`: placement sync over a WebSocket, as
//! JSON-RPC 2.0 (subprotocol `jsonrpc-2.0`), so a T3 host hears Prism's
//! answer the moment it changes instead of at its next 30-second sync
//! (`docs/placement.md`, "T3 ⇄ Prism").
//!
//! - The host calls `placement.sync` with the body of
//!   `POST /v1/placement/sync`; the result is that endpoint's answer.
//! - Prism keeps the host's last report (without its move
//!   acknowledgements) and ranks it again every [`PUSH_CHECK_EVERY`] while
//!   it is at most [`REPORT_FRESH_FOR`] old. When the answer differs from
//!   the last one the host got (`checkedAt` aside), Prism sends it as the
//!   notification `placement.update`: a changed profile, policy or action,
//!   a new move, or a ranking that moved with the accounts' usage.
//! - Prism pings every [`PING_EVERY`] and closes a connection it has heard
//!   nothing from (not even a pong) for [`IDLE_TIMEOUT`].
//!
//! The host authenticates the upgrade with its machine token
//! (`Authorization: Bearer`), as for the POST.

use crate::http::{AppState, MAX_SYNC_BODY_BYTES, require_machine_token, run_placement_sync};
use axum::{
    extract::{
        State,
        ws::{Message, WebSocket, WebSocketUpgrade, rejection::WebSocketUpgradeRejection},
    },
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
};
use futures_util::{SinkExt, StreamExt};
use rocky_prism::{PlacementSyncRequest, PlacementSyncResponse};
use serde_json::{Value, json};
use std::time::{Duration, Instant};

/// The WebSocket subprotocol this endpoint speaks.
pub const SUBPROTOCOL: &str = "jsonrpc-2.0";
/// How often a connection ranks its host's last report again.
pub const PUSH_CHECK_EVERY: Duration = Duration::from_secs(5);
/// A report older than this is not ranked again: the host is gone or stuck,
/// and its threads no longer count (`PLACEMENT_FRESH_REPORT_SECS`).
const REPORT_FRESH_FOR: Duration = Duration::from_secs(3 * 60);
const PING_EVERY: Duration = Duration::from_secs(20);
const IDLE_TIMEOUT: Duration = Duration::from_secs(90);

pub const METHOD_SYNC: &str = "placement.sync";
pub const METHOD_UPDATE: &str = "placement.update";

const PARSE_ERROR: i64 = -32700;
const INVALID_REQUEST: i64 = -32600;
const METHOD_NOT_FOUND: i64 = -32601;
const INVALID_PARAMS: i64 = -32602;
/// The sync failed on Prism's side (as the POST's 503): try again.
const SYNC_FAILED: i64 = -32000;

pub async fn placement_stream(
    State(state): State<AppState>,
    headers: HeaderMap,
    upgrade: Result<WebSocketUpgrade, WebSocketUpgradeRejection>,
) -> Response {
    let machine = match require_machine_token(&state, &headers) {
        Ok(machine) => machine,
        Err(resp) => return resp,
    };
    let Ok(upgrade) = upgrade else {
        return (StatusCode::UPGRADE_REQUIRED, "WebSocket upgrade required").into_response();
    };
    upgrade
        .protocols([SUBPROTOCOL])
        .max_message_size(MAX_SYNC_BODY_BYTES)
        .on_upgrade(move |socket| serve(state, machine, socket))
}

/// What one connection remembers between messages.
#[derive(Default)]
struct Stream {
    /// The host's last report, without its acknowledgements, and when it came.
    report: Option<(PlacementSyncRequest, Instant)>,
    /// The last answer the host got, without `checkedAt`.
    sent: Option<Value>,
}

impl Stream {
    /// Remembers `resp` as sent; false when the host already has it.
    fn note_sent(&mut self, resp: &PlacementSyncResponse) -> bool {
        let mut value = serde_json::to_value(resp).unwrap_or(Value::Null);
        if let Value::Object(map) = &mut value {
            map.remove("checkedAt");
        }
        if self.sent.as_ref() == Some(&value) {
            return false;
        }
        self.sent = Some(value);
        true
    }
}

async fn serve(state: AppState, machine: String, socket: WebSocket) {
    let (mut tx, mut rx) = socket.split();
    let mut stream = Stream::default();
    let mut push = tokio::time::interval(state.placement_push_every);
    push.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut ping = tokio::time::interval(PING_EVERY);
    ping.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let mut heard = Instant::now();
    loop {
        let outgoing = tokio::select! {
            incoming = rx.next() => {
                let Some(Ok(incoming)) = incoming else { break };
                heard = Instant::now();
                match incoming {
                    Message::Text(text) => {
                        handle(&state, &machine, &mut stream, text.as_str()).await
                    }
                    Message::Binary(_) => Some(error(
                        Value::Null,
                        INVALID_REQUEST,
                        "Send JSON-RPC as text frames.",
                    )),
                    Message::Close(_) => break,
                    // axum answers pings itself; a pong only shows the host is there.
                    Message::Ping(_) | Message::Pong(_) => None,
                }
            }
            _ = push.tick() => {
                match &stream.report {
                    Some((req, at)) if at.elapsed() <= REPORT_FRESH_FOR => {
                        let resp = run_placement_sync(&state, &machine, req.clone(), 0).await;
                        resp.filter(|resp| stream.note_sent(resp)).map(|resp| {
                            json!({"jsonrpc": "2.0", "method": METHOD_UPDATE, "params": resp})
                        })
                    }
                    _ => None,
                }
            }
            _ = ping.tick() => {
                if heard.elapsed() > IDLE_TIMEOUT {
                    break;
                }
                if tx.send(Message::Ping(Default::default())).await.is_err() {
                    break;
                }
                None
            }
        };
        if let Some(outgoing) = outgoing
            && tx
                .send(Message::Text(outgoing.to_string().into()))
                .await
                .is_err()
        {
            break;
        }
    }
    let _ = tx.close().await;
}

/// Answers one text frame; None for a notification, which gets no answer.
async fn handle(state: &AppState, machine: &str, stream: &mut Stream, text: &str) -> Option<Value> {
    let Ok(message) = serde_json::from_str::<Value>(text) else {
        return Some(error(Value::Null, PARSE_ERROR, "Parse error."));
    };
    let Value::Object(message) = message else {
        return Some(error(
            Value::Null,
            INVALID_REQUEST,
            "Send one request per frame; batches are not supported.",
        ));
    };
    let id = message.get("id").cloned();
    let valid_id = matches!(
        id,
        None | Some(Value::Null | Value::Number(_) | Value::String(_))
    );
    let method = message.get("method").and_then(Value::as_str);
    let (Some(method), true, Some("2.0")) = (
        method,
        valid_id,
        message.get("jsonrpc").and_then(Value::as_str),
    ) else {
        let id = if valid_id { id } else { None };
        return Some(error(
            id.unwrap_or(Value::Null),
            INVALID_REQUEST,
            "Invalid Request.",
        ));
    };
    let reply = |result: Result<Value, (i64, &str)>| {
        let id = id.clone()?;
        Some(match result {
            Ok(result) => json!({"jsonrpc": "2.0", "id": id, "result": result}),
            Err((code, text)) => error(id, code, text),
        })
    };
    if method != METHOD_SYNC {
        return reply(Err((METHOD_NOT_FOUND, "Method not found.")));
    }
    let params = message.get("params").cloned().unwrap_or(Value::Null);
    let Ok(req) = serde_json::from_value::<PlacementSyncRequest>(params) else {
        return reply(Err((
            INVALID_PARAMS,
            "Invalid params: expected a placement sync request.",
        )));
    };
    let report = PlacementSyncRequest {
        moves: Vec::new(),
        ..req.clone()
    };
    let Some(resp) = run_placement_sync(state, machine, req, text.len()).await else {
        // The host now reports over the POST, maybe to another worker: ranking the
        // report kept here again would write it back over the newer one.
        stream.report = None;
        return reply(Err((SYNC_FAILED, "Placement sync failed; try again.")));
    };
    stream.report = Some((report, Instant::now()));
    stream.note_sent(&resp);
    reply(Ok(serde_json::to_value(&resp).unwrap_or(Value::Null)))
}

fn error(id: Value, code: i64, message: &str) -> Value {
    json!({"jsonrpc": "2.0", "id": id, "error": {"code": code, "message": message}})
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth::machine_token;
    use crate::http::router;
    use crate::testutil::serve;
    use rocky_prism::{PrismClient, PutPlacementEntryInput, PutPlacementProfileRequest};
    use std::sync::Arc;
    use tokio_tungstenite::tungstenite::{self, client::IntoClientRequest};

    type Socket = tokio_tungstenite::WebSocketStream<
        tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
    >;

    const KEY: &[u8] = b"test-event-key-0123456789abcdef0123456789";

    async fn setup() -> (tempfile::TempDir, Arc<PrismClient>, String) {
        let dir = tempfile::tempdir().expect("tempdir");
        let client = Arc::new(
            PrismClient::open_or_create(dir.path().display().to_string(), None)
                .await
                .expect("open_or_create"),
        );
        let state = AppState::new(Arc::clone(&client), Some(KEY.to_vec()))
            .with_placement_push_every(Duration::from_millis(50));
        let base = serve(router(state)).await;
        (dir, client, base.replacen("http://", "ws://", 1))
    }

    async fn connect(base: &str, token: Option<&str>) -> Result<Socket, tungstenite::Error> {
        let mut req = format!("{base}/v1/placement/stream")
            .into_client_request()
            .expect("request");
        req.headers_mut()
            .insert("sec-websocket-protocol", SUBPROTOCOL.parse().unwrap());
        if let Some(token) = token {
            req.headers_mut()
                .insert("authorization", format!("Bearer {token}").parse().unwrap());
        }
        let (socket, resp) = tokio_tungstenite::connect_async(req).await?;
        assert_eq!(
            resp.headers().get("sec-websocket-protocol").unwrap(),
            SUBPROTOCOL
        );
        Ok(socket)
    }

    async fn call(socket: &mut Socket, frame: Value) {
        socket
            .send(tungstenite::Message::Text(frame.to_string().into()))
            .await
            .expect("send");
    }

    /// The next JSON-RPC message, skipping pings.
    async fn next(socket: &mut Socket) -> Value {
        loop {
            let frame = tokio::time::timeout(Duration::from_secs(10), socket.next())
                .await
                .expect("a message in time")
                .expect("open")
                .expect("frame");
            if let tungstenite::Message::Text(text) = frame {
                return serde_json::from_str(text.as_str()).expect("json");
            }
        }
    }

    fn report() -> Value {
        json!({
            "v": 1,
            "environmentId": "env-a",
            "models": [{
                "instanceId": "claudeAgent",
                "model": "claude-david/claude-opus-5-5",
                "label": "Opus 5.5 · claude-david",
                "blocked": null,
                "session": {"usedPercent": 42.0}
            }],
            "threads": []
        })
    }

    #[tokio::test]
    async fn refuses_a_connection_without_a_machine_token() {
        let (_dir, _client, base) = setup().await;
        match connect(&base, None).await {
            Err(tungstenite::Error::Http(resp)) => {
                assert_eq!(resp.status(), StatusCode::UNAUTHORIZED)
            }
            other => panic!("expected 401, got {other:?}"),
        }
        match connect(&base, Some("forged")).await {
            Err(tungstenite::Error::Http(resp)) => {
                assert_eq!(resp.status(), StatusCode::UNAUTHORIZED)
            }
            other => panic!("expected 401, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn answers_sync_and_pushes_a_changed_profile() {
        let (_dir, client, base) = setup().await;
        let token = machine_token(KEY, "david-mb").expect("token");
        let mut socket = connect(&base, Some(&token)).await.expect("connect");

        call(
            &mut socket,
            json!({"jsonrpc": "2.0", "id": 1, "method": METHOD_SYNC, "params": report()}),
        )
        .await;
        let answer = next(&mut socket).await;
        assert_eq!(answer["id"], 1);
        assert_eq!(answer["result"]["v"], 1);
        assert_eq!(answer["result"]["profiles"], json!([]));

        // The owner adds a profile; the host hears it without asking.
        client
            .put_placement_profile(
                "coding".into(),
                PutPlacementProfileRequest {
                    label: "Coding".into(),
                    entries: vec![PutPlacementEntryInput {
                        instance_id: "claudeAgent".into(),
                        model: "claude-david/claude-opus-5-5".into(),
                        reasoning: String::new(),
                        enabled: true,
                        session_share_of_weekly: None,
                    }],
                },
                chrono::Utc::now().timestamp(),
            )
            .await
            .expect("put profile");
        let update = next(&mut socket).await;
        assert_eq!(update["method"], METHOD_UPDATE);
        assert!(update.get("id").is_none(), "{update}");
        assert_eq!(update["params"]["profiles"][0]["id"], "coding");
        assert_eq!(
            update["params"]["profiles"][0]["next"]["model"],
            "claude-david/claude-opus-5-5"
        );

        // Nothing changed since: the next message is the answer to a new call.
        call(
            &mut socket,
            json!({"jsonrpc": "2.0", "id": "two", "method": METHOD_SYNC, "params": report()}),
        )
        .await;
        let answer = next(&mut socket).await;
        assert_eq!(answer["id"], "two", "{answer}");
        assert_eq!(answer["result"]["profiles"][0]["id"], "coding");
    }

    #[tokio::test]
    async fn answers_invalid_messages_with_json_rpc_errors() {
        let (_dir, _client, base) = setup().await;
        let token = machine_token(KEY, "david-mb").expect("token");
        let mut socket = connect(&base, Some(&token)).await.expect("connect");

        socket
            .send(tungstenite::Message::Text("{not json".into()))
            .await
            .expect("send");
        let reply = next(&mut socket).await;
        assert_eq!(reply["error"]["code"], PARSE_ERROR);
        assert_eq!(reply["id"], Value::Null);

        call(
            &mut socket,
            json!([{"jsonrpc": "2.0", "id": 1, "method": METHOD_SYNC}]),
        )
        .await;
        assert_eq!(next(&mut socket).await["error"]["code"], INVALID_REQUEST);

        call(&mut socket, json!({"id": 2, "method": METHOD_SYNC})).await;
        let reply = next(&mut socket).await;
        assert_eq!(reply["error"]["code"], INVALID_REQUEST);
        assert_eq!(reply["id"], 2);

        call(
            &mut socket,
            json!({"jsonrpc": "2.0", "id": 3, "method": "placement.nope"}),
        )
        .await;
        let reply = next(&mut socket).await;
        assert_eq!(reply["error"]["code"], METHOD_NOT_FOUND);
        assert_eq!(reply["id"], 3);

        call(
            &mut socket,
            json!({"jsonrpc": "2.0", "id": 4, "method": METHOD_SYNC, "params": {"models": 7}}),
        )
        .await;
        let reply = next(&mut socket).await;
        assert_eq!(reply["error"]["code"], INVALID_PARAMS);
        assert_eq!(reply["id"], 4);

        // A notification gets no answer; the sync after it does.
        call(
            &mut socket,
            json!({"jsonrpc": "2.0", "method": METHOD_SYNC, "params": report()}),
        )
        .await;
        call(
            &mut socket,
            json!({"jsonrpc": "2.0", "id": 5, "method": METHOD_SYNC, "params": report()}),
        )
        .await;
        assert_eq!(next(&mut socket).await["id"], 5);
    }
}
