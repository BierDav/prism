//! `GET /rocky/{node}`: Rocky over WebSockets behind the HTTPS proxy.
//!
//! Every worker advertises its own path (Rocky pins the node it dials). The
//! worker that receives a connection admits it when the path names itself,
//! and otherwise forwards the unwrapped byte stream to the named worker's
//! plain rocky-tcp port inside the cluster.

use crate::http::AppState;
use axum::{
    body::Body,
    extract::{Path, Request, State},
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
};
use hyper_util::rt::TokioIo;
use std::{sync::Arc, time::Duration};
use tokio::{io::AsyncWriteExt, net::TcpStream};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);
const CLOSE_TIMEOUT: Duration = Duration::from_secs(15);

type Failure = Box<dyn std::error::Error + Send + Sync>;
pub type Forward = Arc<dyn Fn(&str) -> Option<String> + Send + Sync>;

#[derive(Clone)]
pub struct RockyRoute {
    pub node: String,
    pub attachment: rocky_tcp::Attachment,
    /// Maps another worker's node name to its in-cluster `host:port`.
    pub forward: Forward,
}

pub fn valid_node(node: &str) -> bool {
    !node.is_empty()
        && node.len() <= 63
        && !node.starts_with('-')
        && !node.ends_with('-')
        && node
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
}

fn is_websocket_upgrade(headers: &HeaderMap) -> bool {
    let has = |name: header::HeaderName, token: &str| {
        headers
            .get_all(name)
            .iter()
            .filter_map(|v| v.to_str().ok())
            .flat_map(|v| v.split(','))
            .any(|v| v.trim().eq_ignore_ascii_case(token))
    };
    has(header::UPGRADE, "websocket")
        && has(header::CONNECTION, "upgrade")
        && headers
            .get(header::SEC_WEBSOCKET_VERSION)
            .is_some_and(|v| v == "13")
}

pub async fn rocky_upgrade(
    State(state): State<AppState>,
    Path(node): Path<String>,
    mut req: Request,
) -> Response {
    // axum also routes HEAD here; a WebSocket handshake must be a GET.
    if req.method() != axum::http::Method::GET {
        return StatusCode::METHOD_NOT_ALLOWED.into_response();
    }
    let Some(route) = state.rocky.clone() else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let target = if node == route.node {
        None
    } else {
        match valid_node(&node).then(|| (route.forward)(&node)).flatten() {
            Some(address) => Some(address),
            None => return StatusCode::NOT_FOUND.into_response(),
        }
    };
    if !is_websocket_upgrade(req.headers()) {
        return (StatusCode::UPGRADE_REQUIRED, "WebSocket upgrade required").into_response();
    }
    let Some(key) = req.headers().get(header::SEC_WEBSOCKET_KEY).cloned() else {
        return (StatusCode::BAD_REQUEST, "missing Sec-WebSocket-Key").into_response();
    };
    let Some(on_upgrade) = req.extensions_mut().remove::<hyper::upgrade::OnUpgrade>() else {
        return (StatusCode::BAD_REQUEST, "connection cannot be upgraded").into_response();
    };
    let accept = rocky_tcp::websocket_accept_key(key.as_bytes());

    tokio::spawn(async move {
        let Ok(upgraded) = on_upgrade.await else {
            return;
        };
        let io = TokioIo::new(upgraded);
        let result = match target {
            None => route.attachment.admit_websocket(io).await,
            Some(address) => forward(io, &address).await,
        };
        if let Err(err) = result {
            eprintln!("prism-worker: rocky connection for {node} ended: {err}");
        }
    });

    let response = Response::builder()
        .status(StatusCode::SWITCHING_PROTOCOLS)
        .header(header::UPGRADE, "websocket")
        .header(header::CONNECTION, "upgrade")
        .header(header::SEC_WEBSOCKET_ACCEPT, accept)
        .body(Body::empty());
    match response {
        Ok(response) => response,
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

async fn forward(io: TokioIo<hyper::upgrade::Upgraded>, address: &str) -> Result<(), Failure> {
    let websocket = rocky_tcp::websocket_stream(io).await;
    let tcp = tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect(address)).await??;
    tcp.set_nodelay(true)?;
    let (mut ws_read, mut ws_write) = tokio::io::split(websocket);
    let (mut tcp_read, mut tcp_write) = tcp.into_split();
    // Each direction reports when its copy ends; from the first report on,
    // the shutdowns and the other direction get CLOSE_TIMEOUT in total.
    let (ended, mut first_end) = tokio::sync::mpsc::channel(2);
    let up_ended = ended.clone();
    let up = async move {
        let _ = up_ended.try_send(tokio::io::copy(&mut ws_read, &mut tcp_write).await);
        let _ = tcp_write.shutdown().await;
    };
    let down = async move {
        let _ = ended.try_send(tokio::io::copy(&mut tcp_read, &mut ws_write).await);
        let _ = ws_write.shutdown().await;
    };
    let both = async { tokio::join!(up, down) };
    tokio::pin!(both);
    let first = tokio::select! {
        biased;
        first = first_end.recv() => {
            let _ = tokio::time::timeout(CLOSE_TIMEOUT, &mut both).await;
            first
        }
        _ = &mut both => first_end.try_recv().ok(),
    };
    if let Some(copied) = first {
        copied?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::http::router;
    use rocky_core::{
        AppId, DomainId, MeshId, Scope,
        net::{Incoming, Transport},
    };
    use rocky_prism::PrismClient;
    use rocky_tcp::Options;
    use std::net::SocketAddr;

    fn scope() -> Scope {
        Scope {
            mesh: MeshId([1; 16]),
            app: AppId([2; 16]),
            domain: DomainId([3; 16]),
        }
    }

    fn loopback() -> Options {
        Options::new(SocketAddr::from(([127, 0, 0, 1], 0)))
    }

    async fn answer_once(port: Arc<dyn Transport>, reply: &'static [u8]) {
        if let Some(Incoming::Request(request)) = port.accept().await {
            let _ = request.respond(reply.to_vec()).await;
        }
    }

    #[tokio::test]
    async fn the_route_admits_its_own_node_and_forwards_the_others() {
        let dir = tempfile::tempdir().expect("tempdir");
        let client = Arc::new(
            PrismClient::open_or_create(dir.path().display().to_string(), None)
                .await
                .expect("open_or_create"),
        );
        let own = loopback().start().await.expect("own attachment");
        let other = loopback().start().await.expect("other attachment");
        let other_address = other.local_addr().expect("other address").to_string();
        let route = RockyRoute {
            node: "worker-0".into(),
            attachment: own.clone(),
            forward: Arc::new(move |node| (node == "worker-1").then(|| other_address.clone())),
        };
        let app = router(AppState::new(client, None).with_rocky(route));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0")
            .await
            .expect("bind");
        let http = listener.local_addr().expect("http address");
        tokio::spawn(async move {
            let _ = axum::serve(listener, app).await;
        });

        let own_port = own.transport();
        own_port.listen(Some(scope()), true).expect("listen own");
        let other_port = other.transport();
        other_port
            .listen(Some(scope()), true)
            .expect("listen other");
        tokio::spawn(answer_once(own_port, b"from worker-0"));
        tokio::spawn(answer_once(other_port, b"from worker-1"));

        let caller = Options::dialer().transport();
        let own_url = format!("ws://{http}/rocky/worker-0");
        let other_url = format!("ws://{http}/rocky/worker-1");
        assert_eq!(
            caller
                .request(&[own_url], scope(), b"hi")
                .await
                .expect("own"),
            b"from worker-0"
        );
        assert_eq!(
            caller
                .request(&[other_url], scope(), b"hi")
                .await
                .expect("forwarded"),
            b"from worker-1"
        );
        for unknown in ["worker-2", "Worker-1", "a.b"] {
            let url = format!("ws://{http}/rocky/{unknown}");
            assert!(
                caller.request(&[url], scope(), b"hi").await.is_err(),
                "{unknown}"
            );
        }
        own.close().await;
        other.close().await;
    }

    #[tokio::test]
    async fn only_get_reaches_the_upgrade() {
        use tower::ServiceExt;
        let dir = tempfile::tempdir().expect("tempdir");
        let client = Arc::new(
            PrismClient::open_or_create(dir.path().display().to_string(), None)
                .await
                .expect("open_or_create"),
        );
        let app = router(AppState::new(client, None));
        let head = axum::http::Request::builder()
            .method("HEAD")
            .uri("/rocky/worker-0")
            .body(Body::empty())
            .expect("request");
        let res = app.oneshot(head).await.expect("head");
        assert_eq!(res.status(), StatusCode::METHOD_NOT_ALLOWED);
    }

    #[test]
    fn node_names_are_dns_labels() {
        assert!(valid_node("prism-worker-0"));
        for bad in ["", "-a", "a-", "A", "a.b", "a/b", &"a".repeat(64)] {
            assert!(!valid_node(bad), "{bad}");
        }
    }
}
