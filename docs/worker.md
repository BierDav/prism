# Prism worker: Prism on Rocky

The **prism worker** (`worker/`, Rust) is Prism's server. It replaced the
Go server and its Postgres database in phase 7. Every Prism device (the workers, the phone and the Macs)
holds a full replica of one Rocky database and syncs it peer to peer with
`rocky-tcp` over NetBird. There is no central database. The workers alone
make placement decisions and talk to the outside world.

Postgres's data was dropped at the cutover: there was no migration. The
workers started empty, and the owner sets profiles up again on the phone.

## Decisions

| Question | Decision |
| --- | --- |
| Who decides placement | The workers. Phones and Macs only edit settings and answer. |
| Scaling | Workers scale horizontally: any number, each a full replica. |
| Permissions | None inside the database. Every enrolled device can read and write everything. |
| Outside HTTP | `prism.quickme.at`, served by every worker behind one Service. Alertmanager and T3 keep using it. |
| Notifications | ntfy stays as the wake-up path to the phone (see below). |
| Enrollment | Automatic. Fleet mints every device's Rocky provisioning; no QR codes or pairing codes. |
| Data from Postgres | Dropped. |
| Frontends | Android stays Compose, and the Mac app stays SwiftUI. Both use the KMP `shared/` module for logic only, with no Compose Multiplatform. The Mac app moves into this KMP project. |
| Transport | `rocky-tcp` over WebSockets through `prism.quickme.at`; plain TCP between workers in the cluster. Rocky encrypts and authenticates above both. |

## Pieces

```
cli/               Rust: the `prism` command (packages.<system>.prism; also /prism in the worker image)
worker/            Rust: the prism worker (HTTP + Rocky peer + placement engine)
rocky-prism/       Rust: the schema and the client API, shared by the worker and the apps
rocky-prism-ffi/   Rust: rocky-prism's UniFFI surface for the apps (cdylib for JNA, staticlib for the Mac)
uniffi-bindgen/    Rust: gobley's bindgen, generating the Kotlin Multiplatform bindings
                   (Android and the JVM through JNA, macOS through Kotlin/Native's cinterop)
shared/            KMP logic: view models and state on top of rocky-prism, no UI
android/           Compose UI
macos/             SwiftUI UI, consuming shared/ as an XCFramework
```

`rocky-prism` owns the Rocky `Schema` and typed reads and writes, so the
worker and the apps can't disagree on tables. The apps reach it through
`shared/`'s `PrismReplica` (`shared/README.md`, "The Rocky replica"): gobley's
bindgen without its Gradle plugins, because this project builds without
Gradle. The apps never speak HTTP to
Prism for their data; they read and write their local replica.

## Data model

All tables are replayed Rocky tables. Ids are Rocky ids unless noted.

- `profiles(slug, label, position)`: `slug` matches `^[a-z0-9][a-z0-9-]*$`
  and is what T3 sees as `Profiles/<slug>`.
- `profile_entries(profile, position, instance_id, model, reasoning, enabled, session_share_of_weekly)`
- `actions(action, profile)`: `alert-fix`, `text-generation`, and one row per
  role from `docs/placement.md`.
- `policy(key, value)`: the placement policy knobs.
- `hosts(name, seen_at, models_json)`: what each T3 host last reported.
- `threads(host, thread_id, profile, model, state, updated_at)`: running
  placed threads, which the load per account is counted from.
- `instructions(host, thread_id, kind, selection_json, reason, created_at, done_at)`:
  moves and interrupts the workers decided and T3 has yet to apply.
- `alerts(key, fingerprint, episode, payload, name, check_name, severity, machine, summary, state, received_at, resolved_at, missing_since)`:
  one row per episode. `key` is derived from the label fingerprint (the Go
  server's) and Alertmanager's `startsAt`; `state` is `open`, `resolved` or
  `gone` (`dismissed` on older episodes). Only the alert's condition ends it. Any worker may insert the same episode, so reads keep
  the row with the smallest Rocky id per key, and updates name the key.
- `alert_numbers(key, number, at)`: the owner's `#42`. Only the leader numbers
  episodes; if two leaders in a handover give one number twice, the earlier
  row keeps it and the leader numbers the other key again.
- `alert_actions(request_id, alert, kind, instruction, requested_by, requested_at, claimed_by, claimed_at, done_at, thread_id, link, error)`:
  fix (the only kind; an older app's dismiss or resolve ends with an error
  saying why). A device writes the request (the same
  `request_id` again is the same request), and the leader claims, runs and
  finishes it. A fix records the launched thread; a failure records the text
  the owner sees.
- `pushes(key, at)`: the ntfy wake-ups the leader posted, kept 8 days.
- `t3_sessions(machine, url, token, label, updated_at)`: paired T3 MCP sessions
  (from `prism pair`, `POST /v1/t3/pair`).
- `t3_client_sessions(machine, url, token, environment_id, updated_at)`: T3
  client API sessions registered by T3 hosts via `PUT /v1/t3/client-session`
  for scanning and answering command approvals.
- `approvals(id, machine, environment_id, thread_id, request_id, payload_json, created_at, updated_at)`:
  pending T3 command approvals collected by the leader from `t3_client_sessions`.
- `approval_answers(request_id, approval_id, message, signature, requested_by, requested_at, claimed_by, claimed_at, done_at, error)`:
  owner answers to an approval. `message` is the base64 of the approval as
  the app shows it with the chosen `decision`; an accept needs `signature`,
  the SSHSIG (namespace `approval@prism.quickme.at`, `ecdsa-sha2-nistp256`
  from the apps, or `ssh-ed25519`) of that message, made by the key
  configured for `requested_by` in `devices` and embedding exactly that key.
  The leader checks that the approval is still open and unchanged, rereads
  the thread and dispatches `runtime-request.respond` over T3's WebSocket RPC
  only while T3 still asks exactly that; otherwise the approval closes.
- `questions(id, machine, environment_id, thread_id, request_id, payload_json, created_at, updated_at)`:
  pending T3 agent questions collected by the leader across all reachable T3s.
- `question_answers(request_id, question_id, answers_json, requested_by, requested_at, claimed_by, claimed_at, done_at, error)`:
  owner answers to a pending question. The leader calls `t3_pending_request`
  (`respond`) and posts a `question.closed` push.
- `agents(id, alert_id, machine, host, project_id, project_name, thread_id, environment_id, title, prompt, link, state, created_at, updated_at)`:
  Prism-started agents (both alert fixes and ad-hoc assistant runs).
- `agent_requests(request_id, prompt, machine, project_id, profile, requested_by, requested_at, claimed_by, claimed_at, done_at, agent_id, thread_id, link, error)`:
  device requests to start a new agent. When `machine` or `project_id` is empty
  and `text_generation` is configured, the leader picks them automatically
  (magic picks).
- `agent_messages(request_id, agent_id, kind, text, requested_by, requested_at, claimed_by, claimed_at, done_at, error)`:
  `kind` `message` (the default): a follow-up sent into an existing agent
  thread via `t3_thread_send`; `kind` `stop`: the agent's run is stopped with
  `t3_thread_interrupt` (`stop: true`), as Go's `StopAgent` did. An agent
  that is not `active` is left alone.
- `agent_activity(agent_id, state, steps_json, updated_at)`: latest steps and
  run state of each agent, refreshed by the leader's agent watcher (which also
  posts `agent.finished` when an agent completes).
- `machines(name, metrics_json, updated_at)`: per-host metrics snapshot from
  ClickHouse PromQL (`metrics_url`, `metrics_user`, `PRISM_METRICS_PASSWORD_FILE`).
- `projects(machine, project_id, name, path, updated_at)`: T3 projects
  discovered across reachable machines.
- `settings(key, value)`: `model` (the apps' default launch model, `{"instanceId", "provider", "model"}`,
  which `agent_requests` without a model start with), `reasoning:<instanceId>/<model>`
  (its reasoning level, stored only), `cluster_machine` (the leader's
  `cluster_machine`, which the apps' placement view and model list rank by)
  `android.settings.<device>` (a phone's published settings, which the
  phone reads from its replica as `GET /v1/android/settings`),
  `t3.device.<device>.<machine>.<client|mcp>` (a device's own T3 sessions,
  which the T3 hosts register) and `t3.config` (what the devices run their
  own requests with; both in docs/direct-fix.md).
- `android_releases(package, version_code, version_name, sha256, size, uploaded_at, uploaded_by, stored_on)`:
  the published Android release per package. The APK (up to 200 MiB) stays
  off Rocky so it does not bloat phone and Mac replicas: the worker that
  takes the upload streams it to `<PRISM_WORKER_DATA>/android/<package>/<version_code>.apk`
  and records itself in `stored_on`. Another worker asked for it fetches it
  once from `http://<stored_on>.<PRISM_WORKER_PEER_DOMAIN>:<HTTP port>`,
  checks version, size and SHA-256 against the row and keeps the copy. Each
  volume keeps one version per package. The leader pushes `app.release` once
  per release.

Request rows are written once per `request_id`, claimed like `alert_actions`
(unclaimed, stale after 5 minutes, or already the worker's) and finished only
by their holder. A device that can't reach the workers claims its own request
as `device:<name>` and runs it in T3 itself (docs/direct-fix.md); no worker
reclaims such a claim. The leader forgets finished requests and closed approvals and
questions after a week.

A device asks for anything with a side effect (start an agent, send a follow-up,
answer a question or approval, fix an alert) by **writing a
request row**. It never calls a worker directly. Requests become `claimed_by` a
worker, then `done_at`.

### App screen data sources (phases 5 & 6)

| App screen / action | Reads | Writes |
| --- | --- | --- |
| Alerts list & detail | `alerts`, `alert_numbers`, `alert_actions`, `agents` | `alert_actions` (`fix`) |
| Approvals | `approvals`, `approval_answers` | `approval_answers` (signed with device SSH key) |
| Questions | `questions`, `question_answers` | `question_answers` |
| Agents / Assistant | `agents`, `agent_activity`, `agent_requests`, `agent_messages`, `machines`, `projects` | `agent_requests`, `agent_messages` |
| Machines & metrics | `machines`, `hosts` | — |
| Placement & profiles | `profiles`, `profile_entries`, `actions`, `policy`, `hosts`, `threads` | `profiles`, `profile_entries`, `actions`, `policy` |
| Settings | `settings` | `settings` |
| Android self-update | `GET /v1/android/release`, `GET /v1/android/release.apk` (HTTP, before sync) | — |

The apps reach all of it through the HTTP API's paths and JSON
(docs/prism-api.md), answered on the device by `rocky-prism-ffi`'s
`Replica.call` (`rocky-prism-ffi/src/api.rs`): reads map the rows to the
API's objects, and an action writes its request row and waits up to 20 s for
the leader's `done_at` to sync back (`RequestOutcome`), answering `202
pending` otherwise. Stopping an agent is an `agent_messages` row of `kind`
`stop`. An approval answer's `request_id` is
`approval:<sha256(device:approval:message:signature)>`, so the same signed
answer is one request.

## Rocky's own status

Every worker logs Rocky's status for its replica once a minute when it
changed, and at least hourly (`worker/src/rocky_status.rs`): Rocky's alerts
(a due seal it skipped and why, an unreachable coordinator, a stalled
finality pass), what blocks reads, the coordination mode, and the size of
the replica's files:

```
prism-worker: rocky status: alerts [...], blockers [...], coordination "not_configured"; replica 912 MiB
```

Rocky reports these only through its status, and a replica that never
sealed went unnoticed until its history passed a gigabyte and transactions
timed out.

## Workers scaling out

Placement ranking is a pure function of the replicated rows, so any worker
can answer T3's `POST /v1/placement/sync`. It writes the host's report and
answers with the instructions it derives.

Work that must happen once runs only on the leader: numbering alerts,
claiming requests, launching fix threads, posting to ntfy and reconciling
with Alertmanager (deciding moves joins it later). The leader is a
Kubernetes `coordination.k8s.io/v1` `Lease`, the same pattern as the Go
elector but without Postgres: renewed every 3 s, 15 s long, and the leader
stops leading 10 s after its last successful renewal by its own clock, so it
has stopped before anyone else can take it. A worker shutting down releases
the lease at once. Outside a cluster (no service-account token) the worker
always leads, so local runs and tests need no API server.

Only a worker whose replica can do that work campaigns (`worker/src/health.rs`).
Every 5 s it reads `alert_numbers` and `alerts`. When Rocky refuses them as
incomplete (the replica is still importing, or its node's author log forked),
it releases the lease at once, never takes it, and answers 503 on `/ready`,
the readiness probe, so Alertmanager's webhooks reach another worker. It
counts as ready again after three good reads in a row. A busy database
(Preempted, Timeout) changes nothing. Until its first verdict a worker takes
a free lease only once it has been left for another 15 s past its expiry, so
a worker known to be ready gets it first. `/health` stays the liveness probe
and always answers 200.

The worker reaches the API server with its pod's service-account token and
CA from `/var/run/secrets/kubernetes.io/serviceaccount`, and needs `get`, `create` and
`update` on `leases` in its namespace.

The leader claims a request by setting `claimed_by` and `claimed_at`, and
acts only when the claim is its own. A request a former leader claimed but
never finished is claimed again after 5 minutes. Side effects are
idempotent per request id: a fix launch carries
`clientRequestId = prism:sha256(prism:<investigate|instruct>:<device>:sha256(request id))`,
so T3 returns the thread a former leader already launched.

On its own, when an alert fires, the Go server did nothing but notify, and
so does the worker: the leader numbers the episode and posts its
`alert.firing` push; when it ends, `alert.ended`. Fixes start only on a
request.

## HTTP on prism.quickme.at

The worker keeps the paths that outside callers use:

- `POST /alerts/cluster`: Alertmanager, handled as the Go server did (400 for
  an invalid payload, which Alertmanager does not retry; 503 for a storage
  failure, which it does; 413 without a body or over 2 MiB). The Go server
  served it unauthenticated on a cluster-internal port. The worker serves it
  on its one HTTP port, which `prism.quickme.at` routes in full, so the
  cluster sets `PRISM_ALERTS_TOKEN(_FILE)` (the `prism-alerts` Sealed
  Secret), which the webhook carries as a bearer token (Alertmanager's
  `http_config.authorization.credentials_file`).
- `POST /v1/placement/sync`: T3 hosts (machine token)
- `POST /v1/events`, `GET /v1/ping`: `prism run` host check events and its
  token check (machine token; 202 and plain-text errors, as in Go)
- `PUT /v1/t3/client-session`: T3 hosts registering their client API session
  for approvals (machine token)
- `GET /v1/t3/device-sessions`, `PUT /v1/t3/device-session`: T3 hosts
  pairing every device with their T3 (`prism register-t3-devices`, machine
  token; docs/direct-fix.md)
- `GET /v1/android/release`, `GET /v1/android/release.apk`: APK updates. The Go
  server wanted a device token; Rocky provisioning has none, so these are
  public. The phone installs only an APK of its own package signed with its
  own certificate (Updater.kt, and Android itself), so a public download
  discloses the app but cannot change what runs.
- `PUT /v1/android/release`: publish a new APK (machine token in
  `android_publishers`; Content-Length of at most 200 MiB, `sha256` required,
  a zip, a higher `versionCode`)
- `PUT /v1/android/settings`: a publisher (`android/publish.py`) sets a
  phone's settings (machine token in `android_publishers`); `GET
  /v1/android/settings` is the phone's own, with its `prism_` device token.
  Both answer as Go did. They live in the `settings` table under
  `android.settings.<device>`, and the leader pushes app.settings once per
  change.
- `POST /v1/telemetry/write`: the phone's metrics (Prometheus remote write,
  at most 2 MiB), forwarded unchanged to `telemetry_url` in the config with
  basic auth `telemetry_user` (default `prism_phone`) and
  `PRISM_TELEMETRY_PASSWORD(_FILE)`. The phone sends the same credential,
  which the worker compares with its copy; no database read, so a push
  never waits on Rocky's storage. It answers with the upstream's status
  (`502` when the upstream refuses the worker's credential or is
  unreachable, `504` after 10 s, `503` until configured).
- `GET /health`, `GET /v1/health` (`503` only while the replica is
  incomplete; it reads nothing, so a busy database never delays it)

The owner's admin routes, what `prism pair`, `machines`, `device-token` and
`device-revoke` call. The Go server ran these commands in its pod against
PostgreSQL; the worker serves them to the admin token, `admin:<hex
HMAC-SHA256(event key, "prism-admin-v1")>`, which `prism` derives from
`PRISM_EVENT_KEY(_FILE)`. So they run where the key is, in a worker pod
(`kubectl exec prism-worker-0 -- /prism machines`), against the worker at
`PRISM_URL` (default `http://127.0.0.1:8080`):

- `POST /v1/t3/pair` `{machine, link, access?}` → `{paired, answer}`: Go's
  pairing (`pairing.rs`): T3's MCP OAuth flow with the pairing code, stored
  as a `t3_sessions` row. Refusals are 200 with `paired: false` and the
  owner's text. `prism` never sends a code twice.
- `GET /v1/machines` → Go's machines view (`machines.rs`), which `prism
  machines` prints as Go did.
- `POST|DELETE /v1/devices/{device}/token` → `{device, token}` or `{device,
  revoked}`: a `prism_` device token, of which only the SHA-256 is kept
  (`settings` row `device.token.<device>`). Tokens the Go server minted are
  in PostgreSQL and don't carry over: mint new ones.
- `PUT|DELETE /v1/placement/account-limits/{account}` → `{account, limit}`:
  an account's temporary placement limit (`prism account-limit`,
  `docs/placement.md`).

Every other `/v1/*` route the phone and Mac use today becomes a read or a
request row.

## Which binary serves what

| | Rust |
| --- | --- |
| `prism run`, `report`, `ping`, `token`, `register-t3`, `register-t3-devices`, the Mac app's subcommands | `prism` (`cli/`), packages.<system>.prism |
| `prism pair`, `machines`, `device-token`, `device-revoke`, `account-limit` | `prism` in a worker pod, over the admin routes above |
| The sealing rollout (`coordinate`) | `prism-worker coordinate`, the one-shot Job in `deploy/jobs/` |
| The server | `prism-worker` (`worker/`), the image's entrypoint |

## ntfy

Keep it, but only as a **wake-up**. Android stops background sockets, so a
phone with a full replica still needs a push to learn that something
happened. Rocky sync can't wake it. The leader posts a short ntfy message:
the same JSON the apps read today (docs/prism-api.md, "Push"), with the
alert's title, so today's apps keep working. The phone wakes up, syncs over
Rocky, and shows the notification from its replica. Without ntfy, alerts
arrive only when the app happens to be running.

The server and topic are `push.url` and `push.topic` in `PRISM_CONFIG`
(default `https://ntfy.quickme.at`, `prism-events`), and the token is
`PRISM_PUSH_TOKEN(_FILE)`, read on every post. Without a token, or for an
event over an hour old, the push is recorded unposted, as in Go.

## Enrollment from fleet

Every device (the workers, `david-pixel`, the Macs) is enrolled the same way,
with Rocky's instant (0-RTT) provisioning. Fleet creates the database once and
mints one provisioning per device. A device opens from its provisioning on
its first start, without contacting anyone, and reuses its data directory
after that. There are no QR codes and no pairing.

Every device gets Rocky's `Issuer` profile: it can read and write every
table and renew other devices' credentials. That matches "no permissions
inside the database". It also keeps credentials alive without the root.
Rocky credentials last 30 days, and any running issuer (in practice a worker)
renews the others.

### What fleet stores and renders

| What | Where | Secret |
| --- | --- | --- |
| The root (`prism-root-v1:...`, about 320 KB) | sops, once | yes |
| One provisioning per device (`prism-device-v1:...`, about 64 KB) | sops, one per device | yes |
| One peer contact per worker and route (`rocky://peer/...`, under 1 KB) | plain config | no |
| One dial-only contact per phone and Mac (`prism-worker peer -`) | the workers' peers | no |

The root is the creator device's data directory and root key, packed into one
string. It never syncs, and fleet only reads it: each `provision` opens a
scratch copy. The root key inside is needed only for what the database's
publisher alone may sign, such as revoking a device.

Provisionings are random, so mint each one once and keep it in sops; don't
mint a new one on every deploy. A device that already has a data directory
ignores its provisioning. To re-enroll a device, wipe its data directory and
give it a new provisioning.

The root is too large for an environment variable (Linux caps a single
string at 128 KiB), so pass it as a file. Provisionings fit either way.

### Commands

```sh
# Once, on a dev host: create the database. fleet keeps the root in
# sops/prism-rocky.yaml (key root, admins only).
prism-worker init prism-root            # never overwrites; mode 0600

# Per device (the name is a DNS label, e.g. prism-worker-0, david-pixel, david-mb).
# fleet keeps the workers' and the phone's in sops/prism-rocky.yaml (key: the
# device), each Mac's in sops/prism/<host>.yaml (key rocky-provisioning).
PRISM_ROOT_FILE=<(sops -d --extract '["root"]' sops/prism-rocky.yaml) \
  prism-worker provision prism-worker-0 > prism-worker-0.provisioning

# Per worker: its contacts (not secret), one for each audience.
prism-worker peer wss://prism.quickme.at/rocky/prism-worker-0 \
  < prism-worker-0.provisioning      # phones and Macs (fleet's traits/prism/rocky-peers.nix)
prism-worker peer prism-worker-0.prism-worker.monitoring.svc.cluster.local.:7411 \
  < prism-worker-0.provisioning      # workers (PEERS in the prism-worker Sealed Secret)

# Per phone and Mac: a contact the workers know it by (it can't be dialed).
prism-worker peer - < david-pixel.provisioning
```

`provision` prints the provisioning on stdout and the device's node key on
stderr. `peer` reads a provisioning on stdin and prints the contact.

### What each device gets

- **Workers**, as a Kubernetes Secret per pod mounted at
  `PRISM_WORKER_PROVISIONING_FILE` (or `PRISM_WORKER_PROVISIONING`). Every
  worker gets the same `PRISM_WORKER_PEERS` (or `PRISM_WORKER_PEERS_FILE`): the
  in-cluster contacts of all workers and the dial-only contacts of every
  phone and Mac, separated by whitespace. A worker skips its own contact.
  Rocky syncs only with peers it knows, so a device missing from this list
  can dial the workers but its rows never reach them (and theirs never reach
  it); adding a device means updating this list and restarting the workers. The data directory (`PRISM_WORKER_DATA`) must persist (a
  StatefulSet volume). A worker that loses it must get a new provisioning
  (a new node): enrolling the same node again on an empty volume restarts
  its Rocky author log from the start, which can fork its own old log, so
  that its operations never apply anywhere. Revoke the old node afterwards.
- **The phone**: the settings `rocky_provisioning` and `rocky_peers` (the
  workers' `wss://` contacts, one per line), written over USB by
  `prism-phone-enroll` (docs/android-enrollment.md).
- **The Macs**: `rocky: {"provisioningFile", "peers", "clusterMachine"?}` in
  the app's config (`--config FILE`, from fleet's activation); the file
  holds the provisioning and only the owner reads it.

The apps open through the same `rocky-prism` call as the worker:
`PrismClient::open_provisioned(directory, provisioning, network)`, then
`add_peers(contacts)` on every start. `open_provisioned` turns on Rocky's
automatic sync when a network is attached.

Without a provisioning the worker creates a database of its own, as before
(local runs and tests). Setting peers without a provisioning is an error,
because such a database can't sync with anyone.

### Peer routes: a config value, not part of the provisioning

A Rocky provisioning carries routes, but only the routes of the device that
minted it: the root, which never runs. Rocky also pins the node a route
leads to, and holds one route per transport kind (rocky-tcp is `tcp` for
both `wss://` and `host:port`). So a provisioning can't name the workers.
Peers are therefore a separate, public config value: one contact per worker,
`wss://` for phones and Macs and in-cluster for workers. A contact is fixed by
the worker's provisioning and address. Scaling out means provisioning the new
pod and adding its contact to the lists.

### Not done yet

- Seeding: an enrolled device seeds nothing, because each device would seed
  its own copy of the default profiles and every copy would sync. The owner
  sets up profiles on the phone, or the leader (phase 2) seeds them once.
- Revoking a device needs the root key and a running device. There is no
  command for it yet.
- A rocky-tcp node needs a transport that accepts connections. A dial-only
  attachment (`Options::dialer()`) is refused as a node's network, so the
  apps need a listening or external attachment.

## Sealing

Rocky prunes synced history only behind a *final seal*. Until this was set
up no Prism device ever sealed, so every replica kept every operation since
the database was created. Each Rocky transaction locks the whole replica, so
transactions got slower as history grew, until they timed out. A replica
refuses writes once it holds `RETAINED_HISTORY_BYTES` (512 MiB,
`rocky-prism/src/client.rs`). Three things make seals happen:

1. **Trusted workers.** Only devices whose identity the root trusts seal.
   Provisioned devices are issuers, not trusted, and Rocky skips them without
   an alert. The workers are trusted; the Mac and the phone are not.
2. **A coordinator.** In a database whose owner-signed settings name a
   coordinator, a seal is final once that coordinator acknowledged it.
   Without one, trusted devices refuse to seal (`coordinator_required`).
   Prism uses the public Rocky coordination service at rocky.quickme.at
   (three voters in the cluster's `rocky-coordinator` namespace, reached over
   iroh through `rocky-relay.quickme.at`). The database is its *domain*,
   registered with a coordination grant the root signs. The domain's control
   lists the trusted workers' identities and expires after at most 366 days.
3. **Routine seals and compaction.** A trusted, coordinated worker seals
   the followed tip every 128 replayed transactions (checked every 30 s).
   Every replica, the Mac and the phone included, receives the seal and its
   finality by sync. Rocky compacts on its own only up to the first final
   seal, so `rocky-prism` compacts every replica behind its latest final
   seal once a minute (`PrismClient::compact_history`). The apps need no
   coordinator. They need a build with this `rocky-prism` to compact
   their own replica.

The coordinator only orders metadata (which seal ended which tip). Rows,
schemas and keys never leave the devices.

The workers reach the coordinator with `PRISM_ROCKY_COORDINATOR_BUNDLE_FILE`
(the trust bundle, pinned in `deploy/rocky-coordinator-bundle.json` and
mounted from the `prism-rocky-coordinator` ConfigMap). The rollout Job
(`deploy/jobs/rocky-coordinate.yaml`, `prism-worker coordinate`) does the
steps that need the root, which no worker keeps:

| Route (admin token, refused through the Ingress) | Does |
| --- | --- |
| `POST /v1/rocky/trust` `{root}` (the 24-word root phrase) | Records the worker in its registry (renewing its credentials if the registry doesn't list it yet) and trusts its identity. |
| `POST /v1/rocky/coordinate` `{root, trusted, revision}` | Registers (revision 1) or renews (2, 3, ...) the domain, trusting `trusted`, and names the coordinator in the database's settings. |
| `GET /v1/rocky/seal` | This replica's `SealStatus`: `trusted`, `coordination`, `operationsSinceSeal`, `finalized`, `compacted`, `retainedBytes`, `sealSkipped`. |

The domain's tenant key is derived from the root and the database's scope,
so a renewal needs only the root. Run the Job with the next revision before
the control expires (the Job's log prints `expires`), and after adding or
replacing a worker (the control lists every trusted identity).

A database names its coordinator for good: Rocky never lets it change or
drop it. That coordinator is the public service's current certified genesis.
If the service is re-certified (a new genesis, as on 2026-11-21 and
2026-11-23) or upgraded past the Rocky release that removed coordinated
finality (upstream `27dfd00ce`), seals stop becoming final again. Prism's
database then needs to be recreated. A voter store reset that keeps the
genesis only drops the registration: run the Job again with revision 1.
See `docs/rocky-sealing-rollout.md`.

## Network

Every device reaches the workers through `prism.quickme.at` over WebSockets
(`rocky-tcp`'s `websocket` feature), on 443 behind Traefik's TLS. Phones
and Macs only dial; no device needs NetBird for Prism.

Rocky pins the node it dials, so one load-balanced URL can't stand for every
worker. Each worker advertises its own path, `wss://prism.quickme.at/rocky/<pod>`.
The workers run as a StatefulSet with a headless Service. Any worker
receiving `/rocky/<pod>` either admits the connection (if it is that pod) or
forwards the unwrapped byte stream to `<pod>.<headless>:7411`, the pod's
plain `rocky-tcp` port inside the cluster. Workers sync with each other on
those internal ports. Scaling out needs no manifest change beyond `replicas`.

Traefik's idle timeout for this route must outlast Rocky's long-lived link
sessions.

The worker reads `PRISM_WORKER_NODE` (default `$HOSTNAME`, the pod name),
`PRISM_WORKER_PUBLIC_URL` (e.g. `wss://prism.quickme.at/rocky`; it advertises
`<url>/<node>`), `PRISM_WORKER_PEER_DOMAIN` (the headless Service's domain,
for forwarding) and `PRISM_WORKER_BIND` (the plain rocky-tcp port, 7411).
For enrollment it reads `PRISM_WORKER_PROVISIONING(_FILE)` and
`PRISM_WORKER_PEERS(_FILE)` (see above).

## Fix threads

A fix launches through T3's MCP endpoint (`t3_thread_launch`), as the Go
server does, in the alert-fix placement profile (the `actions` row), with
the Go server's prompt. The target is the alert's `machine` label, else the
cluster (`cluster_machine`, `t3_workspace`). The worker reaches the cluster machine (`T3_ACCESS_TOKEN(_FILE)`, or its
`t3_token`), machines with a configured `t3_token`, paired sessions stored in
`t3_sessions`, machines those link to, and machines without T3 over SSH from
`ssh_runners`. Launched fix threads are recorded in `agents` and watched by the
leader's agent watcher, which updates `agent_activity` and posts
`agent.finished` when the run completes.

## Environment

Beyond the network settings above:

| Variable | Use |
| --- | --- |
| `PRISM_CONFIG` | The config file (default `/config/config.json`; without it and without the file, defaults). The worker reads `push`, `cluster_machine`, `machines`, `ssh_runners`, `ssh_user`, `t3_url`, `t3_workspace`, `machine_instructions`, `alertmanager_url`, `reconcile_grace_seconds`, `reconcile_uptime_seconds`, `netbird_domain`, `t3_web_port`, `metrics_url`, `metrics_user`, `telemetry_url`, `telemetry_user`, `placement_log_url`, `usage_url`, `text_generation`, `android_publishers` and `devices`. |
| `PRISM_EVENT_KEY(_FILE)` | The key machine tokens and the admin token derive from. `prism` in the pod reads it too. |
| `T3_ACCESS_TOKEN(_FILE)` | Prism's session with the cluster machine's T3 (or the secret its `t3_token` names). |
| `PRISM_PUSH_TOKEN(_FILE)` | ntfy's publish token. |
| `PRISM_ALERTS_TOKEN(_FILE)` | Optional: the bearer token Alertmanager's webhook must carry. |
| `PRISM_METRICS_PASSWORD(_FILE)` | Password of `metrics_user` (ClickHouse's `telemetry_reader`), for host metrics (PromQL, `metrics_url`) and the accounts' usage (`usage_url`, which needs `SELECT` on `ai_usage.account_limits`). |
| `PRISM_TEXT_GENERATION_KEY(_FILE)` | API key for OpenAI-compatible text generation (magic picks). |
| `PRISM_TELEMETRY_PASSWORD(_FILE)` | The phone's telemetry password (`telemetry_user`, default `prism_phone`): what `POST /v1/telemetry/write` checks and sends on to `telemetry_url`, and what the workers write their placement decisions with (`placement_log_url`, `docs/placement.md`). |
| `PRISM_ROCKY_COORDINATOR_BUNDLE(_FILE)` | The trust bundle of the coordinator that makes this worker's seals final ("Sealing"). Unset: no coordinator, and the worker never seals. |
| `PRISM_ROCKY_COORDINATOR_ROOT` | The key the bundle must be signed by (default: the public service's, `bd3c36e8...6bf0`). |
| `PRISM_ROCKY_COORDINATOR_RELAY` | The iroh relay the coordinator's voters are reached through (default `https://rocky-relay.quickme.at`). |
| `PRISM_LEADER_LEASE` | The Lease's name (default `prism-worker-leader`). |
| `PRISM_LEADER_NAMESPACE` | Its namespace (default: the pod's own). |
| `KUBERNETES_SERVICE_HOST`, `KUBERNETES_SERVICE_PORT` | Set by Kubernetes; with the service-account token they make the worker campaign. |

## Phases

1. `rocky-prism` schema and client API. The worker opens the database, serves
   rocky-tcp, and handles `POST /v1/placement/sync` and the placement
   ranking ported from Go.
2. Alerts: the Alertmanager webhook, alert rows, the leader, request
   claiming, fix-thread launches, ntfy.
3. Approvals, questions, agents, activity and machines from T3's RPC.
4. Enrollment: `prism-worker provision` and the fleet wiring.
5. `shared/` on rocky-prism (UniFFI, gobley). Android reads and writes its replica.
6. The apps move off the Go server's HTTP API onto their replicas:
   `Replica.call` answers the API's paths on the device; the phone gets its
   provisioning from its settings, the Macs from their config. Only the APK
   download stays HTTP.
7. Cut over `prism.quickme.at` and remove the Go server and Postgres (done:
   a new root, every device provisioned again, the workers serve all of
   `prism.quickme.at` and `prism-events.quickme.at`).
