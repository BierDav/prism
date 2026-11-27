//! The leader checks each configured fork's manifest and upstream on GitHub.
//! Fork Kit still owns the merge, its credentials and the PR lifecycle.

use crate::{leader::Clock, t3::Secrets};
use regex::Regex;
use reqwest::{Client, Method, StatusCode, Url};
use rocky_prism::{PrismClient, sha256_hex};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use serde_json::json;
use std::{cmp::Ordering, time::Duration};

const POLL: Duration = Duration::from_secs(15 * 60);
const RETRY_SECONDS: i64 = 2 * 60 * 60;
const WORKFLOW: &str = "sync-forks.yml";

pub(crate) async fn lead(client: &PrismClient, secrets: &Secrets, now: &Clock) {
    let Ok(controller) = std::env::var("PRISM_FORK_KIT_CONTROLLER") else {
        return;
    };
    let forks = std::env::var("PRISM_FORK_KIT_FORKS").unwrap_or_default();
    let mut forks: Vec<_> = forks
        .split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .collect();
    forks.sort_unstable();
    forks.dedup();
    if forks.is_empty() {
        return;
    }
    let Ok((owner, _)) = repo_parts(&controller) else {
        eprintln!("prism-worker: PRISM_FORK_KIT_CONTROLLER must be a GitHub owner/repo");
        return;
    };
    if forks.iter().any(|name| !repo_part(name)) {
        eprintln!("prism-worker: PRISM_FORK_KIT_FORKS must be comma-separated repository names");
        return;
    }
    let mut ticker = tokio::time::interval(POLL);
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    let http = Client::builder()
        .timeout(Duration::from_secs(20))
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .expect("Fork Kit HTTP client");
    let api = Url::parse("https://api.github.com/").expect("GitHub API URL");
    loop {
        ticker.tick().await;
        // Read the mounted file again so rotating the token needs no restart.
        let Some(token) = secrets("PRISM_FORK_KIT_TOKEN") else {
            eprintln!("prism-worker: Fork Kit polling is waiting for PRISM_FORK_KIT_TOKEN");
            continue;
        };
        let github = GitHub {
            http: &http,
            api: &api,
            token: &token,
        };
        futures_util::future::join_all(forks.iter().map(|fork| {
            let github = &github;
            let controller = &controller;
            async move {
                let repo = format!("{owner}/{fork}");
                if let Err(err) = check(client, github, controller, &repo, now()).await {
                    eprintln!("prism-worker: Fork Kit check of {repo} failed: {err}");
                }
            }
        }))
        .await;
    }
}

#[derive(Deserialize)]
struct Manifest {
    upstream: Upstream,
    fork: Fork,
}

#[derive(Deserialize)]
struct Upstream {
    url: String,
    track: String,
}

#[derive(Deserialize)]
struct Fork {
    branch: String,
    #[serde(default = "default_sync_branch")]
    sync_branch: String,
}

fn default_sync_branch() -> String {
    "fork-kit/sync".into()
}

#[derive(Deserialize)]
struct GitObject {
    sha: String,
    #[serde(rename = "type")]
    kind: String,
}

#[derive(Deserialize)]
struct GitRef {
    #[serde(rename = "ref")]
    name: String,
    object: GitObject,
}

#[derive(Deserialize, Serialize)]
struct Dispatch {
    fingerprint: String,
    at: i64,
    run: u64,
}

#[derive(Deserialize)]
struct Run {
    status: String,
    conclusion: Option<String>,
}

impl Run {
    fn failed(&self) -> bool {
        self.status == "completed"
            && matches!(
                self.conclusion.as_deref(),
                Some(
                    "failure"
                        | "timed_out"
                        | "cancelled"
                        | "startup_failure"
                        | "action_required"
                        | "stale"
                )
            )
    }
}

async fn check(
    client: &PrismClient,
    github: &GitHub<'_>,
    controller: &str,
    fork: &str,
    now: i64,
) -> Result<(), String> {
    let key = format!(
        "fork-kit.dispatch:{}",
        format!("{controller}:{fork}").to_ascii_lowercase()
    );
    let prior = client
        .get_setting(&key)
        .await
        .map_err(|err| err.to_string())?;
    let prior: Option<Dispatch> = prior
        .as_deref()
        .map(serde_json::from_str)
        .transpose()
        .map_err(|err| format!("invalid stored dispatch: {err}"))?;

    let mut failed = false;
    if let Some(prior) = &prior
        && let Some(run) = github.run(controller, prior.run).await?
    {
        if run.status != "completed" {
            // Let Fork Kit finish this run before replacing the one we monitor.
            return Ok(());
        }
        if run.failed() {
            failed = true;
            let link = format!("https://github.com/{controller}/actions/runs/{}", prior.run);
            client
                .record_fork_sync_result(
                    fork,
                    Some((&link, run.conclusion.as_deref().unwrap_or("failure"))),
                    now,
                )
                .await
                .map_err(|err| err.to_string())?;
        } else if run.conclusion.as_deref() == Some("success") {
            client
                .record_fork_sync_result(fork, None, now)
                .await
                .map_err(|err| err.to_string())?;
        }
    }

    let raw = github.manifest(fork).await?;
    let manifest: Manifest =
        toml::from_str(&raw).map_err(|err| format!("invalid .fork-kit.toml: {err}"))?;
    let upstream = github_repo(&manifest.upstream.url)?;
    let upstream_ref = github
        .upstream_ref(&upstream, &manifest.upstream.track)
        .await?;
    let sha = github.commit(&upstream, upstream_ref.object).await?;
    let fingerprint = sha256_hex(&format!("{raw}\0{}\0{sha}", upstream_ref.name));
    if prior.as_ref().is_some_and(|prior| {
        prior.fingerprint == fingerprint && now.saturating_sub(prior.at) < RETRY_SECONDS
    }) {
        return Ok(());
    }

    // Even without an upstream change, an outstanding sync PR may need
    // another pass (a hold removed, checks retried, or the fork advanced).
    // An idle fork should not spend any Actions minutes.
    if !failed
        && github.merged(fork, &manifest.fork.branch, &sha).await?
        && !github.open_sync_pr(fork, &manifest.fork).await?
    {
        return Ok(());
    }

    let run = github.dispatch(controller, fork).await?;
    let value = serde_json::to_string(&Dispatch {
        fingerprint,
        at: now,
        run,
    })
    .map_err(|err| err.to_string())?;
    client
        .set_setting(&key, &value, now)
        .await
        .map_err(|err| err.to_string())?;
    eprintln!(
        "prism-worker: dispatched Fork Kit for {fork} at {} ({sha}), run {run}",
        upstream_ref.name
    );
    Ok(())
}

struct GitHub<'a> {
    http: &'a Client,
    api: &'a Url,
    token: &'a str,
}

impl GitHub<'_> {
    fn request(
        &self,
        method: Method,
        repo: &str,
        path: &[&str],
    ) -> Result<reqwest::RequestBuilder, String> {
        let (owner, name) = repo_parts(repo)?;
        let mut url = self.api.clone();
        url.path_segments_mut()
            .expect("GitHub API base")
            .pop_if_empty()
            .extend(["repos", owner, name])
            .extend(path);
        Ok(self
            .http
            .request(method, url)
            .header("User-Agent", "prism-worker")
            .header("X-GitHub-Api-Version", "2026-03-10")
            .bearer_auth(self.token))
    }

    async fn get<T: DeserializeOwned>(&self, repo: &str, path: &[&str]) -> Result<T, String> {
        response(self.request(Method::GET, repo, path)?)
            .await?
            .json()
            .await
            .map_err(|err| err.to_string())
    }

    async fn manifest(&self, fork: &str) -> Result<String, String> {
        response(
            self.request(Method::GET, fork, &["contents", ".fork-kit.toml"])?
                .header("Accept", "application/vnd.github.raw+json"),
        )
        .await?
        .text()
        .await
        .map_err(|err| err.to_string())
    }

    async fn git_ref(&self, repo: &str, kind: &str, name: &str) -> Result<GitRef, String> {
        if !safe_ref(name) {
            return Err(format!("unsupported Git ref {name:?}"));
        }
        let mut path = vec!["git", "ref", kind];
        path.extend(name.split('/'));
        self.get(repo, &path).await
    }

    async fn upstream_ref(&self, repo: &str, track: &str) -> Result<GitRef, String> {
        if let Some(branch) = track.strip_prefix("branch:") {
            return self.git_ref(repo, "heads", branch).await;
        }
        if track == "release" {
            #[derive(Deserialize)]
            struct Release {
                tag_name: String,
            }
            let release: Release = self.get(repo, &["releases", "latest"]).await?;
            return self.git_ref(repo, "tags", &release.tag_name).await;
        }
        if let Some(pattern) = track.strip_prefix("tag:") {
            let pattern = Regex::new(&format!(r"\A(?:{pattern})\z"))
                .map_err(|err| format!("unsupported upstream tag pattern: {err}"))?;
            let tags: Vec<GitRef> = self.get(repo, &["git", "matching-refs", "tags"]).await?;
            return latest_tag(tags, &pattern)
                .ok_or_else(|| format!("no upstream tag matches {track:?}"));
        }
        Err(format!("unsupported Fork Kit tracking rule {track:?}"))
    }

    async fn commit(&self, repo: &str, mut object: GitObject) -> Result<String, String> {
        #[derive(Deserialize)]
        struct Tag {
            object: GitObject,
        }
        for _ in 0..8 {
            if object.sha.len() != 40 || !object.sha.bytes().all(|b| b.is_ascii_hexdigit()) {
                return Err("GitHub returned an invalid Git object ID".into());
            }
            match object.kind.as_str() {
                "commit" => return Ok(object.sha),
                "tag" => {
                    let tag: Tag = self.get(repo, &["git", "tags", &object.sha]).await?;
                    object = tag.object;
                }
                _ => return Err("tracked Git ref does not point at a commit".into()),
            }
        }
        Err("too many nested annotated tags".into())
    }

    async fn merged(&self, fork: &str, branch: &str, sha: &str) -> Result<bool, String> {
        #[derive(Deserialize)]
        struct Comparison {
            behind_by: u64,
        }
        if !safe_ref(branch) {
            return Err(format!("unsupported fork branch {branch:?}"));
        }
        let compare = format!("{sha}...{branch}");
        let resp = self
            .request(Method::GET, fork, &["compare", &compare])?
            .query(&[("per_page", "1")])
            .send()
            .await
            .map_err(|err| err.to_string())?;
        if matches!(
            resp.status(),
            StatusCode::NOT_FOUND | StatusCode::UNPROCESSABLE_ENTITY
        ) {
            // GitHub cannot always see upstream objects in the fork's network.
            // Fork Kit fetches both sides directly and can make the decision.
            return Ok(false);
        }
        Ok(resp
            .error_for_status()
            .map_err(|err| err.to_string())?
            .json::<Comparison>()
            .await
            .map_err(|err| err.to_string())?
            .behind_by
            == 0)
    }

    async fn open_sync_pr(&self, repo: &str, fork: &Fork) -> Result<bool, String> {
        let (owner, _) = repo_parts(repo)?;
        let head = format!("{owner}:{}", fork.sync_branch);
        let prs: Vec<serde_json::Value> =
            response(self.request(Method::GET, repo, &["pulls"])?.query(&[
                ("state", "open"),
                ("head", &head),
                ("base", &fork.branch),
                ("per_page", "1"),
            ]))
            .await?
            .json()
            .await
            .map_err(|err| err.to_string())?;
        Ok(!prs.is_empty())
    }

    async fn run(&self, controller: &str, id: u64) -> Result<Option<Run>, String> {
        let resp = self
            .request(
                Method::GET,
                controller,
                &["actions", "runs", &id.to_string()],
            )?
            .send()
            .await
            .map_err(|err| err.to_string())?;
        if resp.status() == StatusCode::NOT_FOUND {
            // A run can be deleted; the next dispatch can still be tracked.
            return Ok(None);
        }
        resp.error_for_status()
            .map_err(|err| err.to_string())?
            .json()
            .await
            .map(Some)
            .map_err(|err| err.to_string())
    }

    async fn dispatch(&self, controller: &str, fork: &str) -> Result<u64, String> {
        #[derive(Deserialize)]
        struct Accepted {
            workflow_run_id: u64,
        }
        let accepted: Accepted = response(
            self.request(
                Method::POST,
                controller,
                &["actions", "workflows", WORKFLOW, "dispatches"],
            )?
            .json(&json!({ "ref": "main", "inputs": { "repository": fork } })),
        )
        .await?
        .json()
        .await
        .map_err(|err| err.to_string())?;
        Ok(accepted.workflow_run_id)
    }
}

async fn response(request: reqwest::RequestBuilder) -> Result<reqwest::Response, String> {
    request
        .send()
        .await
        .and_then(reqwest::Response::error_for_status)
        .map_err(|err| err.to_string())
}

fn repo_parts(repo: &str) -> Result<(&str, &str), String> {
    repo.split_once('/')
        .filter(|(owner, name)| repo_part(owner) && repo_part(name))
        .ok_or_else(|| "expected a GitHub owner/repo".into())
}

fn repo_part(part: &str) -> bool {
    !matches!(part, "" | "." | "..")
        && part
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
}

fn github_repo(url: &str) -> Result<String, String> {
    let slug = url
        .strip_prefix("https://github.com/")
        .ok_or("upstream must be an https://github.com/ URL")?;
    let slug = slug.strip_suffix(".git").unwrap_or(slug);
    repo_parts(slug)?;
    Ok(slug.into())
}

fn safe_ref(name: &str) -> bool {
    name.split('/').all(repo_part)
}

fn latest_tag(tags: Vec<GitRef>, pattern: &Regex) -> Option<GitRef> {
    tags.into_iter()
        .filter(|tag| {
            tag.name
                .strip_prefix("refs/tags/")
                .is_some_and(|name| pattern.is_match(name))
        })
        .max_by(|a, b| natural_cmp(&a.name, &b.name))
}

// Fork Kit sorts each digit run as a number, including numbers too long to
// fit in an integer. Equal numeric keys sort in Git's ref order.
fn natural_cmp(a: &str, b: &str) -> Ordering {
    fn chunks(s: &str) -> impl Iterator<Item = &[u8]> {
        s.as_bytes()
            .chunk_by(|a, b| a.is_ascii_digit() == b.is_ascii_digit())
    }
    fn significant_digits(chunk: &[u8]) -> &[u8] {
        &chunk[chunk
            .iter()
            .position(|&byte| byte != b'0')
            .unwrap_or(chunk.len())..]
    }
    let order = chunks(a)
        .zip(chunks(b))
        .map(
            |(a, b)| match (a[0].is_ascii_digit(), b[0].is_ascii_digit()) {
                (true, true) => {
                    let a = significant_digits(a);
                    let b = significant_digits(b);
                    a.len().cmp(&b.len()).then(a.cmp(b))
                }
                (true, false) => Ordering::Less,
                (false, true) => Ordering::Greater,
                (false, false) => a.cmp(b),
            },
        )
        .find(|order| *order != Ordering::Equal);
    order.unwrap_or_else(|| chunks(a).count().cmp(&chunks(b).count()).then(a.cmp(b)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::serve;
    use axum::{
        Json, Router,
        extract::{Request, State},
        response::{IntoResponse, Response},
    };
    use std::sync::{Arc, Mutex};

    const FORK: &str = "BierDav/t3code";
    const CONTROLLER: &str = "BierDav/fork-kit";

    #[test]
    fn picks_the_latest_matching_nightly_by_version_and_build_number() {
        let tags = [
            "v0.1.13-nightly.20261127.20",
            "v0.1.14-nightly.20261127.9",
            "v0.1.14-nightly.20261127.100",
            "v0.1.14-nightly.20261127.99",
            "v0.2.0",
            "v0.2.0-preview.20261127.999",
        ]
        .into_iter()
        .map(|name| GitRef {
            name: format!("refs/tags/{name}"),
            object: GitObject {
                sha: String::new(),
                kind: "commit".into(),
            },
        })
        .collect();
        let pattern = Regex::new(r"\Av\d+\.\d+\.\d+-nightly\.\d{8}\.\d+\z").unwrap();
        assert_eq!(
            latest_tag(tags, &pattern).unwrap().name,
            "refs/tags/v0.1.14-nightly.20261127.100"
        );
    }

    #[derive(Default)]
    struct Fixture {
        behind: bool,
        dispatches: u64,
        refuse_dispatch: bool,
        conclusion: Option<&'static str>,
    }

    async fn mock(State(fixture): State<Arc<Mutex<Fixture>>>, request: Request) -> Response {
        let path = request.uri().path().to_owned();
        if request.method() == Method::POST {
            assert_eq!(
                path,
                "/repos/BierDav/fork-kit/actions/workflows/sync-forks.yml/dispatches"
            );
            assert_eq!(request.headers()["x-github-api-version"], "2026-03-10");
            let bytes = axum::body::to_bytes(request.into_body(), 4096)
                .await
                .unwrap();
            assert_eq!(
                serde_json::from_slice::<serde_json::Value>(&bytes).unwrap(),
                json!({"ref": "main", "inputs": {"repository": FORK}})
            );
            let mut fixture = fixture.lock().unwrap();
            if fixture.refuse_dispatch {
                return StatusCode::FORBIDDEN.into_response();
            }
            fixture.dispatches += 1;
            fixture.conclusion = None;
            return Json(json!({"workflow_run_id": fixture.dispatches})).into_response();
        }

        let fixture = fixture.lock().unwrap();
        let body = match path.as_str() {
            "/repos/BierDav/t3code/contents/.fork-kit.toml" => {
                assert_eq!(
                    request.headers()["accept"],
                    "application/vnd.github.raw+json"
                );
                return r#"
                    [upstream]
                    url = "https://github.com/pingdotgg/t3code.git"
                    track = 'tag:v\d+-nightly'
                    [fork]
                    branch = "placement-profiles"
                "#
                .into_response();
            }
            "/repos/pingdotgg/t3code/git/matching-refs/tags" => json!([
                {"ref": "refs/tags/v10-nightly", "object": {
                    "sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "type": "tag"
                }}
            ]),
            "/repos/pingdotgg/t3code/git/tags/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" => json!({
                "object": { "sha": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "type": "commit" }
            }),
            "/repos/BierDav/t3code/compare/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb...placement-profiles" =>
            {
                json!({"behind_by": u32::from(fixture.behind)})
            }
            "/repos/BierDav/t3code/pulls" => json!([]),
            "/repos/BierDav/fork-kit/actions/runs/1" | "/repos/BierDav/fork-kit/actions/runs/2" => {
                json!({
                    "status": if fixture.conclusion.is_some() { "completed" } else { "queued" },
                    "conclusion": fixture.conclusion
                })
            }
            _ => panic!("unexpected GitHub API call: {path}"),
        };
        Json(body).into_response()
    }

    #[tokio::test]
    async fn dispatches_when_needed_and_keeps_a_failed_run_open_until_success() {
        let dir = tempfile::tempdir().unwrap();
        let client = PrismClient::open_or_create(dir.path().display().to_string(), None)
            .await
            .unwrap();
        let fixture = Arc::new(Mutex::new(Fixture::default()));
        let url = serve(
            Router::new()
                .fallback(mock)
                .with_state(Arc::clone(&fixture)),
        )
        .await;
        let http = Client::new();
        let api = Url::parse(&url).unwrap();
        let github = GitHub {
            http: &http,
            api: &api,
            token: "test-token",
        };
        let now = 1_795_255_300;

        check(&client, &github, CONTROLLER, FORK, now)
            .await
            .unwrap();
        assert_eq!(fixture.lock().unwrap().dispatches, 0);

        {
            let mut state = fixture.lock().unwrap();
            state.behind = true;
            state.refuse_dispatch = true;
        }
        assert!(
            check(&client, &github, CONTROLLER, FORK, now + 1)
                .await
                .is_err()
        );
        fixture.lock().unwrap().refuse_dispatch = false;
        check(&client, &github, CONTROLLER, FORK, now + 2)
            .await
            .unwrap();
        assert_eq!(fixture.lock().unwrap().dispatches, 1);
        assert!(client.alert_rows().await.unwrap().is_empty());

        fixture.lock().unwrap().conclusion = Some("failure");
        check(&client, &github, CONTROLLER, FORK, now + 3)
            .await
            .unwrap();
        let alerts = client.alert_rows().await.unwrap();
        assert_eq!(alerts.len(), 1);
        assert!(alerts[0].is_open());
        assert!(
            alerts[0]
                .alert()
                .annotation("description")
                .contains("/actions/runs/1")
        );
        assert_eq!(fixture.lock().unwrap().dispatches, 1);

        // This alert is Prism's own; lack of an Alertmanager copy cannot end it.
        for time in [now + 10, now + 20] {
            client
                .reconcile_alerts(&Default::default(), true, 1, time)
                .await
                .unwrap();
        }
        let retry_at = now + 2 + RETRY_SECONDS;
        check(&client, &github, CONTROLLER, FORK, retry_at)
            .await
            .unwrap();
        assert_eq!(fixture.lock().unwrap().dispatches, 2);
        check(&client, &github, CONTROLLER, FORK, retry_at + 1)
            .await
            .unwrap();
        assert!(client.alert_rows().await.unwrap()[0].is_open());

        fixture.lock().unwrap().conclusion = Some("failure");
        check(&client, &github, CONTROLLER, FORK, retry_at + 2)
            .await
            .unwrap();
        let updated = client.alert_rows().await.unwrap();
        assert_eq!(updated.len(), 1);
        assert_eq!(updated[0].key, alerts[0].key);
        assert!(
            updated[0]
                .alert()
                .annotation("description")
                .contains("/actions/runs/2")
        );

        {
            let mut state = fixture.lock().unwrap();
            state.conclusion = Some("success");
            state.behind = false;
        }
        check(&client, &github, CONTROLLER, FORK, retry_at + 3)
            .await
            .unwrap();
        let resolved = client.alert_rows().await.unwrap();
        assert_eq!(resolved.len(), 1);
        assert!(!resolved[0].is_open());
        assert_eq!(fixture.lock().unwrap().dispatches, 2);
    }
}
