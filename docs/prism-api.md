# Prism API

Prism is the owner's companion app for david-cluster-1: an Android app and a
macOS menu-bar app (`android/` and `macos/` in this repository). This
document is the contract between the apps and **Prism's data**: the paths,
the JSON and the errors the apps' shared client (`shared/`'s `PrismClient`)
speaks.

Since phase 6 of the move to Rocky there is no server behind it. Every
device holds a full replica of Prism's Rocky database (`rocky-prism`,
docs/worker.md), and `rocky-prism-ffi`'s `Replica.call` answers each request
of this contract on the device, from that replica
(`rocky-prism-ffi/src/api.rs`):

- **Reads** (`GET`) come from the replica's tables, which the worker leading
  the cluster keeps current: alerts from Alertmanager and `prism run`, the
  agents it started and their latest steps, the questions and approvals
  waiting in every T3 it reaches, machines, metrics, projects.
- **Actions** (`POST`, and `PUT` of the model settings) write a request row
  into the replica (`alert_actions`, `agent_requests`, `agent_messages`,
  `question_answers`, `approval_answers`, `settings`). The leader claims it,
  runs it (asks T3, checks a signature, ends an alert) and records how it
  ended; `call` waits for that outcome to sync back and answers with it, as
  the server did. While no worker is reachable it answers **`202`** with
  `"pending": true` after about 20 s, and the request stays queued: the
  leader runs it once the device syncs again. A retry with the same
  `requestId` waits for the same request. When the device can't reach the
  workers at all (the app passes their URL, `prismUrl`), it runs the
  request in T3 itself and answers as the server would; if it can't either,
  it answers `202` at once with the reason in `detail`
  (docs/direct-fix.md).
- **Placement** edits (`PUT`/`DELETE /v1/placement/…`) are written straight
  into the replica, as the server wrote them into its database.

Below, "the server" means the worker leading the cluster: it collects
alerts and questions, starts and watches agents and pushes, and the device
reads its results from the replica.

Change this contract only together with both apps. New fields may appear in any
response at any time, so ignore fields you don't know. Removing or changing a
field needs a new version (`/v2`).

## At a glance

| What | Where |
| --- | --- |
| API | On the device: `PrismReplica.client(device)` (`shared/`), no network call per request |
| Sync | The replica dials the workers, `wss://prism.quickme.at/rocky/<pod>` (docs/worker.md) |
| Push | ntfy topic `prism-events` on `https://ntfy.quickme.at`, read with your existing relay token; it wakes the app |
| Identity | The device's Rocky provisioning (`prism-worker provision <device>`), set by fleet |
| Updates (Android) | `GET https://prism.quickme.at/v1/android/release(.apk)` from the workers, no token |
| Phone metrics | `POST https://prism.quickme.at/v1/telemetry/write`, Prometheus remote write with the phone's telemetry basic auth |
| Open a thread on the Mac | `t3code://threads/<environmentId>/<threadId>` |
| Open a thread on the phone | `t3code://threads/<environmentId>/<threadId>` in T3 Code's app, else `https://<host>.netbird.cloud:3774/<environmentId>/<threadId>` |

## Identity and enrollment

There are no tokens and no sign-in. A device joins Prism with its **Rocky
provisioning**: `prism-worker provision <device>` mints a
`prism-device-v1:…` string for the device name (the Mac's fleet host name,
or `david-pixel` for the phone), which installs the device's own Rocky
identity on the replica's first open. With it go the workers' contacts
(`prism-worker peer`), which the replica dials. Every device holds the whole
database; there are no permissions inside it.

| Device | Provisioning | Peers | Where from |
| --- | --- | --- | --- |
| Phone | setting `rocky_provisioning` | setting `rocky_peers` (one per line) | `prism-phone-enroll` over USB (`--settings-only` to rotate) |
| Mac | `rocky.provisioningFile` in the app's config (a file only the owner reads) | `rocky.peers` | fleet's Prism config (`--config FILE`) |

Each worker lists every device in its `PEERS` with the placeholder route
`127.0.0.1:9` (the devices dial; nobody dials them).

The device name is also who requested an action (`requestedBy`), and the
name the workers check an approval's signature under: each device's
hardware key (the Mac's Secure Enclave key, the phone's StrongBox key) is in
the workers' config (`devices.<name>.key`, selfhosted-platform
`manifests/alerting/prism-config.json`). `prism pubkey` prints a Mac's; the
phone shows its key on the Approvals tab, and `prism-phone-enroll` prints it.

The workers still mint `prism_` device tokens (`prism device-token
<device>`) for their own HTTP routes (docs/worker.md, "HTTP on
prism.quickme.at"); the apps don't use them.

## Conventions

- JSON in and out, `Content-Type: application/json`.
- Times are Unix seconds (integers). `0` or a missing field means none.
- Errors return a non-2xx status with `{"error": "<text to show the owner>", "code": "<code>"}`.
  The codes are `invalid` (400), `unauthorized` (401), `not_found` (404),
  `conflict` (409, e.g. resolving an alert that is already resolved),
  `unavailable` (503, e.g. the target machine is offline or T3 does not
  answer; `error` says which). The leader's error for a request it ran
  arrives as text; `call` gives it the server's status (`not_found`,
  `conflict`, `unavailable`, else `invalid`). The
  `error` text is written for the owner and safe to show verbatim.
- Actions that start agents take some seconds (the leader asks the target's T3
  first). An action answers `202` with `"pending": true` when the leader's
  outcome did not sync back in time (above); a pending agent start has an
  empty agent `id`.
- Every action takes an optional `requestId` (any string, at most 200
  bytes). The leader performs an action only once per `requestId` and a
  retry waits for the same request. Send a fresh UUID per user tap and reuse
  it when you retry. An approval answer has none: the same signed answer is
  one request.

## Alerts

An **alert** is one episode of something that fires. It comes from one of two
places:

- `kind: "alert"`: a cluster alert from a vmalert rule or a CronJob, through
  Alertmanager.
- `kind: "machine_event"`: a failed check a machine reported with
  `prism run <check> -- …`, an alert the server keeps itself (`HostCheckFailed`).
  The next successful run of the same check resolves it.

### The Alert object

```json
{
  "id": 42,
  "kind": "machine_event",
  "name": "brew-update",
  "alertname": "HostCheckFailed",
  "category": "Machines",
  "brief": "Nightly Homebrew update",
  "severity": "warning",
  "host": "david-mb",
  "check": "brew-update",
  "summary": "brew-update failed on david-mb (exit code 1)",
  "description": "",
  "command": "/path/to/brew-update",
  "output": "…the last 4,000 characters of output…",
  "labels": {"alertname": "HostCheckFailed", "machine": "david-mb", "check": "brew-update", "severity": "warning", "source": "prism"},
  "state": "open",
  "startsAt": 1763470000,
  "createdAt": 1763470012,
  "updatedAt": 1763470012,
  "endedAt": 0,
  "agents": [ { "…": "Agent objects, newest first" } ]
}
```

| Field | Meaning |
| --- | --- |
| `id` | The alert's number, the owner's `#42`. Unique per episode, never reused. |
| `kind` | `alert` or `machine_event`. |
| `name` | What to show as the title: the check for a machine event, otherwise the alert name. |
| `alertname` | The `alertname` label. |
| `category` | One of `Machines`, `Cluster nodes`, `etcd and DNS`, `Workloads`, `Storage`, `Databases`, `Backups`, `Public websites`, `Monitoring`, `CI and updates`, `Other`. Group by it in this order. |
| `brief` | A short description of what this alert means, from the server's catalog. It may be empty. |
| `severity` | `critical`, `warning` or `info` (other values are possible from custom rules; treat them as `warning`). |
| `host` | The machine it concerns (the `machine` label), or `""` for the cluster. |
| `check` | The machine check's name, or `""`. |
| `summary`, `description` | From the alert's annotations. Untrusted text: show it as plain text, never as markup or links. |
| `command`, `output` | For machine events: the command and the tail of its output. Show them in monospace. |
| `labels` | Every label. |
| `state` | `open`, `resolved` (it recovered), `dismissed` (the owner dismissed this episode) or `gone` (Alertmanager no longer had it; cluster alerts only). |
| `startsAt` | When the episode started (Alertmanager's `startsAt`, or the first failed run of a machine event), never later than `createdAt`. |
| `createdAt` | When the server first saw this episode. |
| `updatedAt` | When the alert last changed. |
| `endedAt` | When it left `open`, or `0` while it is open. |
| `agents` | The agents started for this alert. |

### List

`GET /v1/alerts?state=open|recent|all` returns `{"alerts": [Alert…]}`, newest
first:

- `open` (the default): every open alert.
- `recent`: every open alert, plus those that ended in the last 7 days.
- `all`: everything the server still has (it keeps the latest episode of each
  alert).

`GET /v1/alerts/{id}` returns one Alert, or `404`.

### Actions on an alert

All of them take `POST` with an optional JSON body.

| Request | Body | What happens | Answer |
| --- | --- | --- | --- |
| `POST /v1/alerts/{id}/fix` | `{"instruction": "…"?, "requestId": "…"?}` | Starts an agent on the alert's machine (or the cluster). Without `instruction` it investigates the alert and fixes it where appropriate. With one, it follows the instruction. | `201 {"agent": Agent}` |
| `POST /v1/alerts/{id}/dismiss` | `{"requestId": "…"?}` | Ends the current episode. A cluster alert stays quiet until its next episode. A machine event is resolved and fires again on the next failed run. | `200 {"alert": Alert}` |
| `POST /v1/alerts/{id}/resolve` | `{"requestId": "…"?}` | Machine events only: resolves the check until it fails again. For a cluster alert it answers `409`; use dismiss. | `200 {"alert": Alert}` |

An action on an alert that is no longer open answers `409`, except `fix` with an
instruction, which is allowed on any alert the server still has.

## Agents

### Start one

`GET /v1/agent-options?target=<machine>` lists the available `models` and
`projects` for the T3 environment that will run the agent. Omit `target`
or use `cluster` for the cluster host. The response includes the `machine`
name, the configured `defaultModel`, and `defaultProject` (its ID, or null
if the configured project is missing). Each project has an `id` and a
`name`, and its `icon` from T3 or null (`{"kind": "emoji", "emoji"}`, or
`{"kind": "monogram" | "lucide", "text" | "name", "color"}` with a Tailwind
colour name; without one, show the name's monogram); the empty ID means a
scratch folder. A project with a git remote has the ID `repo:<canonical key>`, one without
`host:<machine>/<its T3 ID>`; a repository's ID
(`repo:gitea.quickme.at/owner/name`) is the same on every machine. The list
also has the repositories of every other T3 Prism reaches, marked
`"clone": true`: starting an agent in one clones it from its remote next to
the machine's own project (or most of its repositories) and registers it in
that T3 first. SSH targets list only their workspace. SSH targets keep the runner's
configured workspace. `profiles` lists the placement profiles of that T3
(`id` and `label`, empty when it has none); a launch in one sends the model
`{"instanceId": "placement", "model": "<profile id>"}`, and T3 picks the
account.

`GET /v1/agent-options?target=auto` answers for the machine Prism would
pick now, named in `picked` (see `"target": "auto"` below).

`GET /v1/projects` lists every project on every machine Prism reaches, the
same whichever machine an agent will run on: `{"projects": [{"id", "name",
"icon", "machines", "repository"}]}`, sorted by name, the scratch folder (ID
`""`) last. A project with a git remote (`"repository": true`, ID
`repo:<canonical key>`) is listed once with the machines that have it, and is
cloned on any other machine an agent starts in it. A folder without one has
the ID `host:<machine>/<its T3 ID>` and works on that machine only. Two
projects of one name are told apart by the repository's owner
(`owner/name`) or the folder's machine (`name · machine`).

`POST /v1/agents`:

```json
{"task": "do", "target": "david-mb", "request": "update Xcode's command line tools", "requestId": "…"}
```

| Field | Meaning |
| --- | --- |
| `task` | `fix` (investigate and fix a problem), `do` (anything), `alert` (implement, test and deploy monitoring), `deploy` (research, plan with the owner, then deploy a service) or `retire` (retire a service with its grace period). |
| `target` | A machine name or alias from `GET /v1/machines`, or `"cluster"` / omitted for the cluster, or `"auto"`: a folder's own machine, else among the machines with T3 that answer, the strongest tier (cores × GiB of memory, by powers of two) with one under 60 % CPU and 85 % memory, and in it the least used one. `alert`, `deploy` and `retire` work on the cluster only. `"via/machine"` picks the machine whose T3 link to use, or the SSH runner. |
| `request` | What to do, at most 10,000 characters. Required. |
| `model` | Optional `{instanceId, model}` from the target's options, or a placement profile as `{"instanceId": "placement", "model": "<profile id>"}`. Used only for this launch, with the model's default reasoning. When omitted, use the saved model and reasoning. |
| `projectId` | Optional project ID from the target's options, or `"auto"`: the server's text generation model (`text_generation` in its config: an OpenAI-compatible `url` and a `model`, with the key PRISM_TEXT_GENERATION_KEY) reads the request and picks one of `GET /v1/projects`, or a scratch folder for none. An empty string starts in scratch; omitting it uses the configured workspace. Picking another project on the cluster host targets the machine rather than the cluster. |

It answers `201 {"agent": Agent}`. A target that is offline or has no route
answers `503` with an `error` that says so. No agent is started.

### The Agent object

```json
{
  "id": "mcp:launch:9e47…",
  "task": "investigate",
  "title": "Investigate: #42 HostCheckFailed",
  "alertId": 42,
  "target": "david-mb",
  "state": "running",
  "runStatus": "running",
  "questionPending": false,
  "lastMessage": "",
  "createdAt": 1763470100,
  "updatedAt": 1763470160,
  "requestedBy": "david-pixel",
  "t3": {
    "environmentId": "65bb4709-a12d-4923-9b69-89f7f77f0fc9",
    "threadId": "mcp:launch:9e47…",
    "host": "david-mb",
    "link": "https://app.t3.codes/threads/…"
  }
}
```

| Field | Meaning |
| --- | --- |
| `id` | The T3 thread ID. It is also the agent's ID in this API (percent-encode it in paths). |
| `task` | `fix`, `do`, `alert`, `deploy`, `retire`, `investigate` (fix without instruction on an alert) or `instruct` (fix with instruction on an alert). |
| `title` | The T3 thread's title. |
| `alertId` | The alert it works on, or `0`. |
| `target` | `cluster` or the machine it works on. |
| `state` | `running` (a turn is in progress), `waiting` (it asks the owner a question, see `questionPending`), `finished` (the last turn completed), `failed` (the last turn failed or was interrupted) or `stale` (no activity for three days; the server stopped watching it). |
| `runStatus` | T3's thread status as last seen, for display only. |
| `questionPending` | The agent waits for an answer. Answer it in the app ("Questions" below) or open the thread to answer it in T3. |
| `lastMessage` | The final message of the last finished turn: Markdown, untrusted, at most 4,000 characters (it ends with `…` if cut). Render it with links disabled or shown as plain text. |
| `createdAt`, `updatedAt` | Unix times. |
| `requestedBy` | The device that started it, or `""` for one started before Prism. |
| `t3.environmentId` | The T3 environment the thread lives in. |
| `t3.threadId` | The thread. |
| `t3.host` | The fleet machine that runs the thread, which is not always `target`: an agent for a machine without T3 runs on its SSH runner. |
| `t3.link` | T3's own link as it returned it, for reference only. Build your own link (below). |

### Follow its activity

`GET /v1/agents/{id}/activity` reads the agent's thread from its T3 now and
answers:

```json
{
  "agent": Agent,
  "status": "running",
  "items": [
    {"id": "…", "position": 12, "runId": "…", "type": "command_execution",
     "status": "completed", "title": "du -sh /var/cache", "text": "4.1G\t/var/cache", "truncated": false}
  ]
}
```

| Field | Meaning |
| --- | --- |
| `agent` | The Agent. While T3 says a turn runs, its `state` is `running` or `waiting` even before the server's watcher notices. |
| `status` | T3's thread status now. |
| `items` | The latest steps, oldest first, at most 40. |
| `items[].type` | T3's item type: `user_message`, `assistant_message`, `command_execution`, `file_change`, a tool call (`mcp_tool_call`, `dynamic_tool_call`, `web_search`, …), `plan` and others. Show unknown types plainly. Reasoning is left out. |
| `items[].status` | `running`, `completed` or `failed`. |
| `items[].title`, `items[].text` | Untrusted. Messages are Markdown, at most 4,000 characters; every other step's text is cut to 300. `truncated` says the text was cut. |

The prompt Prism launched the agent with is not shown: the thread's first
message is the owner's request instead, or left out when the agent had none
(an alert investigated without instruction). Poll it every few seconds while
the agent runs. A T3 that does not answer gives `503`.

### Send a follow-up

`POST /v1/agents/{id}/messages` with `{"text": "…", "requestId": "…"}` sends
the owner's message into the agent's thread: it starts a new turn when the
agent is idle, or steers the running one. `text` is required, at most 10,000
characters. It answers `200 {"agent": Agent}` with the agent `running`; the
server follows the turn and pushes `agent.finished` when it ends, as for the
first. A T3 that does not answer gives `503` and nothing is sent.

### Stop it

`POST /v1/agents/{id}/stop` with `{"requestId": "…"}` interrupts the agent's
run through the T3 that launched it (`t3_thread_interrupt`, `stop: true`;
an `agent_messages` row of `kind` `stop`). It answers `200 {"ok": true}`,
also when the agent is not running (then nothing is sent), `404` for an
unknown agent and `503` when its T3 does not answer. The watcher records
the outcome when T3 reports it.

### Open the thread

The apps answer an agent's questions and send follow-ups through the server.
For anything else they open the thread in T3:

- **macOS:** `t3code://threads/<environmentId>/<threadId>`, with each part
  percent-encoded (`:` becomes `%3A`). This opens the T3 desktop app.
- **Phone:** the same `t3code://threads/<environmentId>/<threadId>` link, in
  T3 Code's Android app (`com.t3tools.t3code`). Without that app, T3's web app
  on the machine that runs the thread, over NetBird:
  `https://<host>.netbird.cloud:3774/<environmentId>/<threadId>`, each part
  percent-encoded. `<host>` is `t3.host`. (`GET /v1/machines` gives
  `t3WebOrigin` per machine.)

### List

`GET /v1/agents?state=active|recent|all` returns `{"agents": [Agent…]}`, newest
first:

- `active` (the default): `running` and `waiting`.
- `recent`: also the agents that finished in the last 7 days.
- `all`: also `stale` ones (at most 200).

`GET /v1/agents/{id}` returns one Agent.

## Questions

The server collects every pending T3 user question (an agent asking the owner
with its ask-user-question tool) from **every thread** on every T3 it reaches:
the machines it calls directly and every machine their T3 links reach, not only
the agents Prism started. It scans them about every 30 seconds. A thread that a
machine reaches both directly and through a link is counted once. Permission
approvals are handled separately through `GET /v1/approvals` and
`POST /v1/approvals/{id}/answer`. Prism forwards the chosen decision to
T3’s client API. A device signature binds the approval to the selected scope.
Fleet registers a short-lived T3 client session using `prism register-t3`;
this client session is separate from the MCP session used for agent actions.

### List

`GET /v1/questions` returns the open questions, oldest first:

```json
{
  "questions": [
    {
      "id": "c0b6…",
      "threadId": "mcp:launch:9e47…",
      "environmentId": "65bb4709-a12d-4923-9b69-89f7f77f0fc9",
      "host": "david-mb",
      "title": "Clean up Homebrew",
      "createdAt": 1763470000,
      "agentId": "",
      "questions": [
        {
          "id": "colour",
          "header": "Colour",
          "question": "Which colour should the test use?",
          "multiSelect": false,
          "options": [{"label": "Red", "description": "Warm"}, {"label": "Blue", "description": "Cold"}]
        }
      ]
    }
  ]
}
```

| Field | Meaning |
| --- | --- |
| `id` | T3's request ID. It may contain `:` or `/`; percent-encode it in paths. |
| `threadId`, `environmentId`, `host` | Where the thread is, as in the Agent's `t3` ("Open the thread"). `host` is the machine that runs the thread. |
| `title` | The thread's title. |
| `createdAt` | When the server first saw the question. |
| `agentId` | The thread ID when Prism started this agent (`GET /v1/agents/{id}`), else `""`. |
| `questions` | One request may ask several questions; answer all of them at once. `options` may be empty (free text only). `multiSelect` allows several options. |

The list is read from the server's database, so it is fast. It lags T3 by up to
half a minute: a question answered in T3 meanwhile answers `404` (below).

### Answer

`POST /v1/questions/{id}/answer`:

```json
{"answers": {"colour": "Blue", "parts": ["A", "B"], "note": "free text"}, "requestId": "…"}
```

`answers` maps each question's `id` to:

- an option's `label`, or free text, as a string;
- for a `multiSelect` question, a list of option labels (or free text as a
  string).

The server sends it to the thread's T3 (`t3_pending_request` `respond`),
naming options as T3 expects. Free text goes to the agent as written (trimmed).
It answers:

- `200 {"ok": true}`: answered. The question is closed and `question.closed` is pushed.
- `400 invalid`: a question is not answered, an answer is not a string or a
  list of strings, a list names something that is not an option, or the
  question takes options only. `error` says which.
- `404 not_found`: the question is no longer pending (answered in T3 or on
  another device, or the run ended). It is closed; reload the list.
- `503 unavailable`: the thread's T3 does not answer now. The question stays
  open; try again.

## Machines

`GET /v1/machines` returns:

```json
{
  "cluster": {"name": "cluster", "runsOn": "david-server", "workspace": "/home/…/selfhosted-platform"},
  "machines": [
    {
      "name": "david-mb",
      "aliases": ["mb", "macbook"],
      "description": "",
      "t3": true,
      "route": "direct",
      "via": "",
      "reachable": true,
      "state": "reachable",
      "detail": "",
      "sshRunner": true,
      "openAlerts": [42],
      "t3WebOrigin": "https://david-mb.netbird.cloud:3774"
    }
  ]
}
```

| Field | Meaning |
| --- | --- |
| `t3` | The machine runs T3. Otherwise its agents run on an SSH runner. |
| `route` | How the server reaches it: `direct` (it calls the machine's T3), `link` (through `via`'s T3 link), `ssh` (no T3; agents run on `via`, the SSH runner), `none` (no route: not paired, or no runner answers) or `reports` (it only reports events). |
| `reachable` | An agent can start there now. |
| `state` | T3's word for it: `reachable`, `not reachable`, `not paired`, a link status, … Show it as is. |
| `detail` | e.g. `session expires in 3 days`; may be empty. |
| `openAlerts` | IDs of its open machine events. |
| `t3WebOrigin` | Where its T3 web app is over NetBird, or `""` without T3. |

From the replica, a machine has the fields the leader records in its
`machines` row (`name`, `aliases`, `description`, `t3`, `reachable`,
`t3WebOrigin`) and `openAlerts`; `cluster`, `route`, `via`, `state`,
`detail` and `sshRunner` are absent (the apps default them). The leader
refreshes the rows every 30 s; the call itself is local and cheap.

The worker serves the same view at `GET /v1/machines` to the admin token only
(`prism machines` in a worker pod prints it as text); the apps read machines
from their Rocky replica.

### Metrics

`GET /v1/machines/metrics` returns the performance of every host that
reported metrics in the last week: the fleet hosts and the cluster nodes,
from node-exporter through the cluster's metrics store (ClickHouse's
`observability.metrics`, read with PromQL). Names are the hosts' names
(`instance`), the same as in `GET /v1/machines` for machines Prism knows.

```json
{
  "time": 1763470000,
  "hosts": [
    {
      "name": "david-mb",
      "online": true,
      "lastSeen": 1763469990,
      "os": "Darwin 27.2.0",
      "arch": "arm64",
      "bootTime": 1762946714,
      "cpu": 0.64,
      "cores": 18,
      "load": [17.62, 26.13, 31.66],
      "memory": {"used": 31756042240, "total": 51539607552},
      "swap": {"used": 767492096, "total": 2147483648},
      "disk": {"mount": "/System/Volumes/Data", "used": 638755143680, "total": 994610155520},
      "filesystems": [{"mount": "/System/Volumes/Data", "used": 638755143680, "total": 994610155520}],
      "network": {"receive": 325822.09, "transmit": 967289.99},
      "battery": {"level": 0.59, "charging": true},
      "history": {"start": 1763462760, "step": 120, "cpu": [0.21, 0.2, null, …], "memory": [0.61, …]}
    }
  ]
}
```

| Field | Meaning |
| --- | --- |
| `time` | When the server read the metrics. |
| `hosts` | Sorted by name. |
| `online` | The host reported within the last 3 minutes. An offline host keeps `name`, `lastSeen`, `bootTime` and `history`; its current values are `null` or empty. |
| `lastSeen` | The host's own clock at its last report. |
| `os`, `arch` | uname's system and release (`Linux 7.3.4`), and the machine (`x86_64`, `arm64`). |
| `bootTime` | When it booted; uptime is `time - bootTime`. |
| `cpu` | The busy share of all cores over the last 5 minutes, 0–1, or `null`. |
| `cores` | Logical CPUs. |
| `load` | Load averages over 1, 5 and 15 minutes, or `[]`. |
| `memory` | Bytes in use and installed. Linux: what `MemAvailable` does not cover. macOS: Activity Monitor's "Memory Used" (app memory, wired and compressed). |
| `swap` | Bytes of swap in use and configured, or `null` without swap. |
| `disk` | The main filesystem (`/`, or the data volume on macOS), or `null`. |
| `filesystems` | Every filesystem of at least 1 GiB, `disk` first. APFS volumes that share a container are listed once. |
| `network` | Bytes per second received and sent over the last 5 minutes, all interfaces except loopback, containers and VPN tunnels. |
| `battery` | A laptop's charge (0–1) and whether it charges, or `null`. |
| `history` | CPU and memory use (0–1) over the last two hours, one value per `step` seconds from `start`; `null` where the host reported nothing. |

From the replica, `hosts` are the metrics the leader stored in each
`machines` row and `time` is when it last stored them; a host without
metrics is left out.

## Model and reasoning

`GET /v1/settings/model` returns:

```json
{
  "model": {"instanceId": "codex", "provider": "codex", "model": "codex-david/gpt-6.1-sol"},
  "reasoning": {"current": "xhigh", "default": "medium", "levels": ["low", "medium", "high", "xhigh"]},
  "models": [{"instanceId": "codex", "provider": "codex", "model": "codex-david/gpt-6.1-sol"}, …]
}
```

`reasoning` is `null` when the model has no reasoning level. `current` is `""`
when it uses the default. The list is the placement view's `models` (what
the T3 hosts reported in the last day), and `model` is the `model` setting,
else the first of them.

- `PUT /v1/settings/model` `{"instanceId": "codex", "model": "codex-david/gpt-6.1-sol"}`
  stores the model (the replica's `settings`, key `model`) that agent
  launches omitting `model` start with; alert fixes start in the alert-fix
  placement profile. It must be one of `models`. It returns the same object
  as the GET.
- `PUT /v1/settings/reasoning` `{"level": "high"}` (or `"default"`) stores
  the level for the current model (key `reasoning:<instanceId>/<model>`).
  Each model keeps its own. The worker does not pass it to T3 yet. It returns
  the same object as the GET.

`GET /v1/agent-options?target=` comes from the same data: the placement
models reported on that machine (all of them when none is), the placement
profiles, and the machine's projects from the replica's `projects` plus the
scratch folder.

## Placement profiles

See [`docs/placement.md`](placement.md) for the full contract, ranking rules and
the T3 host sync endpoint (`POST /v1/placement/sync` on the machine event API).

- `GET /v1/placement` returns `profiles` (with live ranking from the cluster
  machine), `models` (union reported in the last day), `alertFixProfile`,
  `policy` and the last 20 `moves`.
- `PUT /v1/placement/profiles/{id}` creates or replaces a profile.
- `DELETE /v1/placement/profiles/{id}` removes a profile (`409` for the
  alert-fix profile).
- `PUT /v1/placement/order` `{"ids": […]}` orders the profiles.
- `PUT /v1/placement/alert-fix-profile` `{"profile": "coding"}` sets the
  profile alert fixes start on.
- `PUT /v1/placement/policy` updates the placement policy.

Each mutation returns the same object as `GET /v1/placement` and publishes
`placement.changed`.

## Push

### Channel

Push goes through the owner's relay: ntfy at `https://ntfy.quickme.at`, topic
**`prism-events`**.

- The server publishes there as its own ntfy user (`prism-server`), which may
  only write `prism-events`.
- Each app reads it **with its relay token** (`relay_token` on the phone,
  `relayTokenFile` on the Macs), which may only read `prism-events`.
- Keep one stream open: `GET /prism-events/json`.
- After a reconnect, catch up with `?since=<id of the last message you saw>`
  (ntfy keeps messages for a week), or `since=1h` on first start. Each message
  carries a `key`. Drop one whose `key` you have handled.

Why ntfy and not FCM: the relay is self-hosted, authenticated and reachable
without NetBird. The
Android app is distributed outside Google Play (it updates itself from this
server, "The Android app" below), and FCM would add
a Google project, a server credential and Google as a third party seeing every
alert, and gain nothing. The apps already use ntfy's catch-up. Push is only a
notification: the API stays the source of truth, so a lost push is repaired by
the next list call.

### Messages

The ntfy message's `message` field is a JSON string:

```json
{"v": 1, "type": "alert.firing", "key": "alert.firing:42", "time": 1763470012, "title": "#42 brew-update · david-mb", "body": "brew-update failed on david-mb (exit code 1)", "severity": "warning", "alertId": 42}
```

The server also sets ntfy's `title`, `priority` and `tags`, so a stock ntfy
client shows something useful. Priority: 5 for a critical `alert.firing`, 4 for
a warning one and for `agent.question`, 1 for `alert.ended`,
`agent.started`, `app.release`, `app.settings`, `placement.changed` and `question.closed`, 3 for everything else. The apps build their
own notification from the JSON.

| `type` | When | Extra fields | Suggested handling |
| --- | --- | --- | --- |
| `alert.firing` | A new alert episode starts | `alertId`, `severity`, `host` | Notify. Critical is high priority. Tap opens the alert. |
| `alert.ended` | An alert left `open` | `alertId`, `state` (`resolved`/`dismissed`/`gone`) | Remove its notification and refresh. Don't notify. |
| `agent.started` | An agent started (from any device) | `agentId`, `alertId` | Refresh. Don't notify. |
| `agent.question` | A T3 thread asks the owner, whether Prism started it or not | `questionId`, `agentId`, `alertId`, `host`, `environmentId`, `threadId` | Notify. Tap opens the question (`GET /v1/questions`) or the thread in T3. For a thread Prism did not start, `agentId` is the thread ID and `title` is "Agent asks: <thread title>". |
| `question.closed` | A question was answered (in the app or in T3) or is no longer pending | `questionId`, `host`, `environmentId`, `threadId` | Remove the notification whose `agent.question` had this `questionId` and refresh the questions. Don't notify. |
| `agent.finished` | A turn ended | `agentId`, `alertId`, `state` (`finished`/`failed`), `host`, `environmentId`, `threadId` | Notify quietly. `body` is the start of `lastMessage`. Tap opens the thread. |
| `app.settings` | A phone's settings changed | `device` | The phone with that name applies its settings. Don't notify. No values in the push. |
| `notice` | Something the owner should do (e.g. a T3 session expires soon) | — | Notify quietly. |
| `app.release` | A new version of an Android package is published | `package`, `versionCode` | The app with that package checks for its update. Don't notify. |
| `placement.changed` | Placement profiles or policy changed | — | Reload the Profiles page if it is open. Don't notify. |

`title` and `body` are plain text, at most 200 and 1,000 characters. For
`agent.question` and `agent.finished` the message carries what the app needs to
open the thread without asking the API. An `agent.question`'s `body` lists the
questions, one per line.

## The Android app

The phone's Prism is its own Device Owner (`android/src/.../Dpc.kt`;
enrollment: `docs/android-enrollment.md`). It updates itself from the
workers (`prism_url`, `https://prism.quickme.at`), and its settings from its
replica, so no MDM is involved. The release download and the metrics push
are the only HTTP calls the phone makes to Prism.

**Publishers** are the machines listed in `android_publishers` in the
workers' config. They authenticate with their machine event token (`prism token
<machine>`, the one `prism run` uses), sent as a bearer token to this API.
fleet's `prism-android-publish` on david-mm is the publisher.

### Releases

Each Android package has its own channel, named by `?package=`
(`at.quickme.prism` when left out), so a debug build next to the
release app updates from its own.

- `GET /v1/android/release?package=P` (anyone, no token) returns the
  published version, or `404`:
  `{"package", "versionCode", "versionName", "sha256", "size", "uploadedAt", "uploadedBy"}`.
- `GET /v1/android/release.apk?package=P` (anyone, no token) returns the
  APK (`Content-Length`, `X-Version-Code`, `X-Content-SHA256`). The response
  is cut short if a newer version replaces it meanwhile.
- `PUT /v1/android/release?package=P&versionCode=N&versionName=V&sha256=H`
  (publisher only) takes the signed APK as the body, with a
  `Content-Length` of at most 200 MB. It returns `201` and the release, or
  `409` unless `versionCode` is higher than the published one, also if a
  newer upload finished first. It pushes `app.release`.

The phone checks the size, the SHA-256, the package, the version code and
that the APK is signed with its own key before it installs it.

### Settings

A phone's settings are the keys of `android/res/xml/app_restrictions.xml`.
`prism-phone-enroll` writes them over USB (`--settings-only` rotates them),
and fleet's publisher may change most of them later:

- `PUT /v1/android/settings` (publisher only, on the workers):
  `{"device": "david-pixel", "settings": {"relay_token": "…", "t3_url": null}}`.
  It replaces the device's settings and returns `{"device", "changed"}`. A
  string sets a key on the phone, `null` removes it (its default applies
  again), and a key left out stays as the phone has it. The workers keep them
  in the replicated `settings` table (`android.settings.<device>`), and the
  leader pushes `app.settings` (with `"device"`) once per change. They may
  set every key except how the phone reaches Prism and NetBird's enrollment
  (`prism_url`, `rocky_provisioning`, `rocky_peers`, `netbird_setup_key`,
  `netbird_management_url`).
- `GET /v1/android/settings` returns the calling device's own settings,
  `{"device", "settings", "updatedAt", "updatedBy"}`, or `404`. The phone
  asks its replica (`Replica.call`, for its `device_name`), so it needs no
  token; the workers also answer it over HTTP to a `prism_` device token.

The phone applies them when `app.settings` names it, when its replica's
`settings` change, every 6 hours and after a boot. Values are never logged.

### Metrics

- `POST /v1/telemetry/write` takes the phone's metrics as Prometheus remote
  write (snappy-compressed protobuf, at most 2 MiB; android/METRICS.md) and
  forwards the body with its `Content-Encoding`, `Content-Type` and
  `X-Prometheus-Remote-Write-Version` to `telemetry_url` in the workers'
  config. It needs basic auth: `telemetry_user` (`prism_phone`) and the
  phone's `telemetry_password`, which the worker compares with its own copy
  (`PRISM_TELEMETRY_PASSWORD`) and sends upstream; it reads no database.
  It answers with the upstream's status and an empty body: `401` for a
  wrong credential, `413` for a larger body, `502` when the upstream refuses
  the worker's credential or can't be reached, `504` after 10 s, and `503`
  while the worker has no `telemetry_url` or password.

## The assistant's conversations

The phone's assistant keeps every conversation in the replica, so it outlives
the app and reaches the other devices (the history screen,
`android/src/.../assistant/AssistHistory.kt`). These routes are only on the
device (`Replica.call`); the workers serve none of them. Times are Unix
seconds. A conversation is `{"id", "device", "title", "createdAt", "updatedAt"}`;
`device` is the one it was started on.

- `GET /v1/assistant/conversations?limit=N` returns
  `{"conversations": [...]}`, every device's, the most recently updated first
  (`limit` 1 to 500, default 500).
- `GET /v1/assistant/conversations/{id}` returns `{"conversation", "entries":
  [{"id", "body"}], "history": ["…"]}`, or `404`. `entries` are the
  transcript's entries in order and `history` the model's messages in order,
  each the app's own JSON as a string.
- `PUT /v1/assistant/conversations/{id}` stores the conversation as it is
  now: `{"title", "createdAt", "updatedAt", "entries", "history"}` in those
  shapes. It writes only the rows that changed (an entry by its ID; the
  history where it continues the stored one, dropping what it no longer
  has), so the same `PUT` again after a failure is safe, and returns
  `{"conversation"}`. The calling device started it if it is new. An ID is 1
  to 100 letters, digits or `-_.:`; each entry's and message's JSON is at
  most 16 KiB (`400` otherwise): the app cuts long texts and never stores
  image or file bytes, only their names.
- `DELETE /v1/assistant/conversations/{id}` deletes it with its rows and
  returns `{"ok": true}`.

## Health

`GET /v1/health` returns `200 {"ok": true}` from the device's replica.

## Sources and rollout

- The contract on the device: `rocky-prism-ffi/src/api.rs` (`Replica.call`),
  with `shared/`'s `PrismReplica.client` and `PrismClient`.
- The leader that runs the requests and keeps the tables: `worker/`
  (docs/worker.md).
- The workers: `worker/`, image `ghcr.io/bierdav/prism-worker`,
  StatefulSet `prism-worker` in namespace `monitoring`, from selfhosted-platform
  `apps/prism.yaml` (docs/worker.md).
- The `prism` command (`cli/`, Rust) is packages.<system>.prism in this
  flake, which fleet installs, and `/prism` in the worker image.
- Fleet machines report with `prism run` to
  `https://prism-events.quickme.at/v1/events` (public, per-machine tokens),
  which the workers serve. That event API is not part of this contract.
