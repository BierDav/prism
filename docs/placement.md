# Placement: profiles that Prism schedules

A **placement profile** is an ordered list of models (accounts), each with its
reasoning level and an on/off switch. A T3 thread started on
`Profiles/<id>` in any model picker, or tagged `placement:<id>`, runs on the
account the profile ranks first and moves on when that account runs low.

Prism is the **placement provider**. Its Rocky database holds the
profiles and the policy, the owner edits them on the phone (Phone tab ›
Profiles), and every T3 host syncs with Prism: it reports what it has and
runs, and Prism answers with live instructions. Prism sees every host's
running threads, so the load it counts per account is the whole fleet's,
not one host's.

The T3 side is the BierDav/t3code fork (`placement-profiles`): its
`placementSource` setting points at Prism, and its placement worker turns
the instructions into thread starts and moves.

## Words

- **entry**: one model of a profile: `instanceId` (the T3 provider
  instance, e.g. `claudeAgent`), `model` (e.g.
  `claude-david/claude-opus-5-5`), `reasoning` (a level like `high`, or `""`
  for the model's default), `enabled`, and optionally
  `sessionShareOfWeekly` (the weekly percent one full 5-hour session window
  costs on this account; turns on the weekly rule).
- **profile entry**: an entry with `instanceId` `placement` and `model` set
  to another profile's id. At that place the profile uses the other
  profile's enabled models, in their order (a model already listed earlier
  counts once). A disabled profile entry adds nothing. It carries no
  `reasoning` or `sessionShareOfWeekly` (each model keeps its own). Saving
  refuses a profile entry for a missing profile or one that would include
  the profile itself; deleting a profile another one names answers `409`.
  T3 never sees profile entries: the sync response lists the models they
  stand for. `GET /v1/placement` shows them as one row labelled with the
  profile, `driver` `placement`, its state and reason taken from that
  profile's best model.
- **account key**: what load and burn are counted by. It is the `model` slug
  (the gateway routes `<account>/<model>` to one account, whichever host
  asks), not the instance.
- **selection**: T3's `ModelSelection`:
  `{"instanceId": "claudeAgent", "model": "claude-david/claude-opus-5-5", "options": [{"id": "effort", "value": "high"}]}`.
  `options` is left out for the default reasoning.

## Prism's tables

```sql
CREATE TABLE placement_profiles (
  id        TEXT PRIMARY KEY,        -- ^[a-z0-9][a-z0-9-]*$, at most 40
  label     TEXT NOT NULL,
  position  INTEGER NOT NULL
);
CREATE TABLE placement_entries (
  profile_id  TEXT NOT NULL REFERENCES placement_profiles(id) ON DELETE CASCADE,
  position    INTEGER NOT NULL,
  instance_id TEXT NOT NULL,
  model       TEXT NOT NULL,
  reasoning   TEXT NOT NULL DEFAULT '',
  enabled     BOOLEAN NOT NULL DEFAULT TRUE,
  session_share_of_weekly DOUBLE PRECISION,   -- NULL: no weekly rule
  PRIMARY KEY (profile_id, position)
);
-- what each host last reported (its models without their usage readings,
-- and its running threads; idle ones are not stored), for the fleet-wide
-- load; a report older than 3 minutes no longer counts
CREATE TABLE placement_reports (
  machine     TEXT PRIMARY KEY,
  reported_at BIGINT NOT NULL,
  body        JSONB NOT NULL
);
-- the moves Prism asked for, so it asks once per thread and the phone can list them
CREATE TABLE placement_moves (
  id          TEXT PRIMARY KEY,        -- "<machine>:<threadId>:<to model>:<at>"
  machine     TEXT NOT NULL,
  thread_id   TEXT NOT NULL,
  from_sel    JSONB NOT NULL,
  to_sel      JSONB NOT NULL,
  reason      TEXT NOT NULL,
  at          BIGINT NOT NULL,
  state       TEXT NOT NULL            -- asked | continued | failed | dropped
);
```

### Usage readings

Usage is not stored with the reports. Readings change every few minutes,
and every stored change replicates to every worker, phone and Mac and stays
in Rocky's history, which Prism can't compact yet. Instead:

- The workers read every gateway account's windows from ClickHouse
  (`ai_usage.account_limits`, which the AI gateway's collector fills every
  few minutes) at most once a minute (`usage_url`, `worker/src/usage.rs`).
  The session window is `five_hour` (Codex: `primary_window`), the weekly
  window `seven_day` (Codex: `secondary_window`).
- A model `<account>/<model>` ranks with its account's windows when
  ClickHouse read them in the last 20 minutes. Any other model (pooled
  names like `agy-pro`, `jetbrains`, `gh`), and any account ClickHouse
  doesn't have fresh, ranks with the host's reading from the sync request.
- Burn-rate samples live in each worker's memory (45 minutes of them),
  not in Rocky.
- The apps rank from the `placement_usage` setting: the readings and burn
  rates the workers ranked with last, in whole percents. A worker rewrites
  it when it changed, at most every 5 minutes unless a window ran out or
  came back.

### Temporary account limits

An account can be held back for a while: placement counts its session
window as full at `sessionPercent` used and its weekly window at
`weeklyPercent`, until `until` (or until removed). "Stop claude-alex with
30% of the session and 10% of the week left" is:

```sh
prism account-limit claude-alex --session 70 --weekly 90 --until 2026-11-28T08:00:00Z
prism account-limit claude-alex --clear
```

(in a prism-worker pod, like the other admin commands; the apps' API has
`PUT`/`DELETE /v1/placement/account-limits/{account}` with the same JSON).
Ranking scales the account's readings, burn rate and weekly share by
100 / limit, so the switch point, the prediction and the weekly slack all
count against the limit: at 70% used the entry is `exhausted` and its
threads move, and at about 66% (95% of 70) they are compacted and moved
before it. The decision shows the real readings, and its reason names the
limit. Limits are `policy` rows `account_limit:<account>`, listed in the
policy's `accountLimits`; saving the policy doesn't change them.

The policy, the alert-fix profile and the text-generation profile are
settings (`placement_policy`, `alert_fix_profile`,
`text_generation_profile`) in Prism's existing settings store. Nothing is
built in: a new database has no profiles, roles or actions until the owner
creates them in the apps. With no alert-fix profile, alert fixes launch
without one and T3 places them with its own default.

The view lists every model a host reports under every profile, the ones a
profile doesn't name appended as disabled. Saving drops disabled entries at
the end that carry no reasoning or share, so an editor that saves the list
it shows doesn't store them.

Policy (as in the fork's `PlacementPolicy`):

```json
{"switchAtPercent": 95, "weeklySlackSessions": 3, "activeHours": {"start": 8, "end": 23}, "roleProfiles": {"review": "review"}}
```

`activeHours` is optional, in Europe/Vienna. `weeklyMoveSlackSessions`
(optional, default 1.5) is the second weekly threshold:

- weekly slack at most `weeklySlackSessions` (3): the entry is `promoted`,
  preferred for new threads;
- at most `weeklyMoveSlackSessions` (1.5): running threads of the profile on
  entries with more slack also move onto it, one per entry per sync (each
  host's next report counts the moved thread there, so the entry turns
  `full` before it overfills). Idle threads stay where they are.

A thread on an `exhausted` entry waits for the reset on that account instead
of moving when every spent window resets within 30 minutes and no other entry
of the profile is at most `weeklyMoveSlackSessions`: the provider keeps its
prompt cache until then. Prism lists no move for it; T3 continues it on the
same account once the limit resets.

A running thread is interrupted and compacted before it runs into a limit,
while its account can still run the compaction. The entry is
`over_threshold` when:

- its session (5-hour) window is at or past `switchAtPercent`, or
- at the current pace, its session or weekly window runs out within
  10 minutes. The pace is the session burn rate per thread (samples of the
  last 45 minutes) times the entry's running threads. The weekly rate is
  that times `sessionShareOfWeekly` / 100. Without a known pace, only the
  session switch point applies.

An entry is `full` when its running threads plus one more would pass the
switch point before the session window resets, at the current pace. The
prediction looks at most one session window (5 hours) ahead: some providers
(gh, make, jetbrains) report a monthly quota as their session window.

Two cases run into the limit instead:

- The entry's weekly allowance still has to be spent (weekly slack at most
  `weeklyMoveSlackSessions`). The thread keeps going until the entry is
  `exhausted`. It then moves without compaction (T3 skips `/compact` on a
  spent account), or waits when the limit resets within 30 minutes. So that
  this move hands over a short context rather than the whole conversation, a
  running thread is compacted in place at the point it would otherwise have
  moved: Prism lists a move whose `to` is its `from`, and T3 interrupts it,
  runs `/compact` on the same account and continues there. Prism asks this
  once per thread per session window.
- The window resets within 30 minutes. The thread hits the limit and waits
  for the reset on the same account (above), keeping its prompt cache.

The weekly window has no fixed switch point. Only its pace or 100% stops it.

## Starting the next weekly window

A subscription's weekly window starts with the account's first request
after the last one ended (Codex's do; Claude's reset on a fixed weekly
schedule). An account left unused after its reset starts its next week
late, and that week's reset comes as much later. So the leader sends each
account a very small prompt ("Reply with OK.", about 150 tokens) once its
weekly window has ended, through the AI gateway the worker already uses for
text generation (`text_generation.url`; the gateway routes `<account>/<model>`
to that account).

- An account counts when an enabled profile entry names it (the part of
  `model` before `/`) and ClickHouse has a weekly reading for it from the
  last 20 minutes (pooled names are no single account and get none).
  Switching every entry of an account off stops its prompts.
- The window has ended when the reading shows no weekly reset in the
  future. The first prompt goes out a minute after the reset; Prism tries
  again every 10 minutes while the readings show no new window, at most 3
  times per ended window.
- Prism keeps, per account, the last reset it saw, when it last prompted
  and how often (`policy` rows `weekly_activation:<account>`), and counts a
  prompt as sent before sending it, so a lost prompt is never sent twice
  at once.

## Decision log

Prism keeps no history of its rankings, so the workers write them to
ClickHouse, `observability.placement_decisions` (selfhosted-platform's
`manifests/cliproxy-analytics/schema.sql`, 180 days), when `placement_log_url`
is set (`worker/src/placement_log.rs`):

- `ranking`: a host's ranking of a profile whenever its pick (`next`) or
  any entry's state changes, with every entry's state, reason, readings,
  running threads and prediction as JSON in `ranked`;
- `move` and `compact`: each move or in-place compaction Prism asks for, once;
- `sync_failed`: a failed sync, at most every 10 minutes per host.

Why did the default profile pick claude-1 at 08:36?

```sql
SELECT at, machine, next, ranked FROM observability.placement_decisions
WHERE kind = 'ranking' AND profile = 'default'
  AND at BETWEEN '2026-11-23 08:00:00' AND '2026-11-23 08:40:00'
ORDER BY at
```

## T3 ⇄ Prism: `POST /v1/placement/sync`

On the machine event API (`https://prism-events.quickme.at`, the host's
machine token as `Authorization: Bearer`, as `prism report` uses; T3 reads
it from `placementSource.tokenFile`). The T3 server calls it every 30
seconds, and right away when a profile thread starts or a run ends and its
last answer is older than 30 seconds. A host connected to the stream
(below) syncs over it instead, at once on every change. A replica that is
not the leader answers too: placement is a pure function of the replicated
rows.

Request:

```json
{
  "v": 1,
  "environmentId": "env-…",
  "models": [
    {
      "instanceId": "claudeAgent",
      "model": "claude-david/claude-opus-5-5",
      "label": "Opus 5.5 · claude-david",
      "driver": "claudeAgent",
      "provider": "Claude",
      "reasoning": {"optionId": "effort", "levels": ["low", "medium", "high", "max"], "default": "high"},
      "blocked": null,
      "session": {"usedPercent": 41.5, "resetsAt": "2026-11-21T19:00:00Z"},
      "weekly": {"usedPercent": 62, "resetsAt": "2026-11-24T08:00:00Z"}
    }
  ],
  "threads": [
    {
      "threadId": "thr-…",
      "title": "Fix brew-update on david-mb",
      "profile": "coding",
      "selection": {"instanceId": "claudeAgent", "model": "claude-david/claude-opus-5-5"},
      "running": true
    }
  ]
}
```

- `models`: every model of every enabled provider instance on the host
  (never the Profiles provider itself). `blocked` is why it cannot run now
  (`"its provider is signed out"`, …) or `null`. `session` and `weekly` are
  the worst window of that kind that limits the model, as the fork's
  `placementWindows` reads it (the usage hub's account first), or absent.
  Prism ranks with them only for models ClickHouse has no fresh reading
  for, and never stores them (see "Usage readings").
  `reasoning` is absent for a model without a reasoning option. `driver` is
  the instance's driver kind (`claudeAgent`, `codex`, `antigravity`,
  `opencode`, …) and `provider` its display name ("Antigravity · pooled");
  older hosts leave both out. `hidden: true` marks a model hidden in the T3
  picker. Android honors the cluster host's preference (or hides a model
  when all reporting hosts hide it). It does not change profile routing.
- `threads`: every thread tagged `placement:<id>` that is not archived,
  with its current selection; `running` while a run is in progress.

Response:

```json
{
  "v": 1,
  "checkedAt": "2026-11-21T18:20:00Z",
  "policy": {"switchAtPercent": 95, "weeklySlackSessions": 3, "roleProfiles": {"review": "review"}},
  "profiles": [
    {
      "id": "coding",
      "label": "Coding",
      "entries": [
        {"selection": {"instanceId": "claudeAgent", "model": "claude-david/claude-opus-5-5", "options": [{"id": "effort", "value": "high"}]}, "enabled": true, "sessionShareOfWeekly": 13.7}
      ],
      "next": {"instanceId": "claudeAgent", "model": "claude-david/claude-opus-5-5", "options": [{"id": "effort", "value": "high"}]},
      "ranked": [
        {"selection": {…}, "state": "ok", "reason": "Session window at 42%.", "session": {…}, "weekly": {…}, "runningThreads": 2, "predictedSessionPercent": 77, "weeklySlackSessions": 6.4}
      ]
    }
  ],
  "actions": {"textGeneration": "minor"},
  "moves": [
    {"id": "david-mb:thr-…:claude-1/claude-opus-5-5:1763749200", "threadId": "thr-…", "from": {…}, "to": {…}, "reason": "The session limit is at 96%, at or past the 95% switch point."}
  ]
}
```

- `profiles` lists every profile in order, with `entries` as stored
  (reasoning already turned into `options`, using the `optionId` this host
  reported for that model, else the one another host reported), so T3 can
  keep them as its `placementProfiles` setting and still pick in profile
  order while Prism does not answer. `ranked` and `next` are the fork's
  `PlacementProfileDecision`, states `ok | promoted | full | over_threshold |
  exhausted | disabled | unavailable`, ranked exactly as the fork's
  `rankPlacementProfile`: the host's own `blocked` makes an entry
  `unavailable`; a model the host did not report at all is `unavailable`
  ("its provider instance is not configured"). `runningThreads` and the burn
  rate are counted over every host's last report.
- `moves`: threads of this host that should move now, as the fork's
  `placementMoveTarget` decides for each reported thread (running or not),
  plus the in-place compactions above (`to` equal to `from`).
  Prism lists a move until the host reports the thread on `to` or
  acknowledges it; it never asks for a second move of the same thread
  within 2 minutes. Each running thread a sync moves counts on its target
  before the next thread's target is chosen, so evacuating an account
  spreads its threads instead of filling the first runnable entry.

- `actions.textGeneration`: the profile T3's text generation (thread
  titles, branch names, commit messages and other generated text) runs in,
  or absent for none. T3 sets its `textGenerationModelSelection` setting to
  that profile's `next` whenever it differs and `next` is not null, so the
  text-generation code itself is unchanged and keeps working while Prism is
  away. With no profile set, T3 leaves the setting alone.

T3 acknowledges a move in its next request with
`"moves": [{"id": "…", "state": "continued" | "failed", "detail": "…"}]`.

### The stream: `GET /v1/placement/stream`

The same sync over a WebSocket, so a host hears a new move or a changed
profile within seconds instead of at its next 30-second sync
(`worker/src/placement_stream.rs`). The protocol is JSON-RPC 2.0, one
message per text frame, subprotocol `jsonrpc-2.0`; the upgrade carries the
machine token as `Authorization: Bearer`, as the POST does (`401` without
it).

- The host calls `placement.sync` with the request above as `params`; the
  `result` is the response above.

  ```json
  {"jsonrpc": "2.0", "id": 7, "method": "placement.sync", "params": {"v": 1, "environmentId": "env-…", "models": […], "threads": […]}}
  {"jsonrpc": "2.0", "id": 7, "result": {"v": 1, "checkedAt": "…", "profiles": […], "moves": […]}}
  ```

- Prism keeps the host's last report (without its acknowledgements) and
  ranks it again every 5 seconds while it is at most 3 minutes old. When
  the answer differs from the last one the host got (`checkedAt` aside),
  Prism sends it as the notification `placement.update` (no `id`): a
  changed profile, policy or action, a new move, or a ranking that moved
  with the accounts' usage.

  ```json
  {"jsonrpc": "2.0", "method": "placement.update", "params": {"v": 1, "checkedAt": "…", "profiles": […], "moves": […]}}
  ```

- Errors are JSON-RPC's: `-32700` (not JSON), `-32600` (not a request, or
  a batch, which Prism doesn't take), `-32601` (another method), `-32602`
  (params that are no sync request) and `-32000` (the sync failed on
  Prism's side, the POST's `503`: try again).
- Prism pings every 20 seconds and closes a connection it heard nothing
  from, not even a pong, for 90 seconds.

T3 keeps one stream open and syncs over it, and over the POST while it is
down. Over the stream it reports every change to its profile threads (a
run starting or ending, a thread changing account) and every
acknowledgement on its next 5-second tick instead of waiting out the 30
seconds, and still sends a full report every 30 seconds. It carries out a
pushed move the moment it arrives.

### How T3 moves a running thread

T3 moves a live run through the thread's queue, so nothing waits for its
next sync: it queues `/compact` on the account the thread leaves (left out
when that account has no usage left), then the continuation on the new
account, both ahead of any message already queued, and only then
interrupts the run. The thread runs the compaction as soon as the
interrupt ends the run, and the continuation after it, and T3 acknowledges
the move as `continued` right away. An in-place compaction (`to` equal to
`from`) works the same, on one account. If the run fails on its usage
limit before the interrupt lands, the thread stops its queue while that
provider is limited; T3 then takes the queued compaction and continuation
out of it and starts the continuation on the new account by itself.

## The phone's API

From the device's replica, as the rest of `/v1`.

`GET /v1/placement` returns:

```json
{
  "profiles": [
    {"id": "coding", "label": "Coding", "entries": [
      {"instanceId": "claudeAgent", "model": "claude-david/claude-opus-5-5", "label": "Opus 5.5 · claude-david", "driver": "claudeAgent", "provider": "Claude", "reasoning": "high", "enabled": true, "sessionShareOfWeekly": 13.7,
       "state": "ok", "reason": "Session window at 42%.", "runningThreads": 2}
    ]}
  ],
  "models": [
    {"instanceId": "claudeAgent", "model": "claude-david/claude-opus-5-5", "label": "Opus 5.5 · claude-david", "driver": "claudeAgent", "provider": "Claude",
     "reasoning": {"levels": ["low", "medium", "high", "max"], "default": "high"}, "machines": ["david-mb", "david-server"]}
  ],
  "alertFixProfile": "coding",
  "actions": {"alertFix": "coding", "textGeneration": "minor"},
  "roles": ["implementation", "research", "review", "design", "test", "general"],
  "policy": {"switchAtPercent": 95, "weeklySlackSessions": 3, "roleProfiles": {"review": "review"}},
  "moves": [{"machine": "david-mb", "threadId": "thr-…", "from": "claude-david/claude-opus-5-5", "to": "claude-1/claude-opus-5-5", "reason": "…", "at": 1763749200, "state": "continued"}]
}
```

`models` is the union of the models the hosts reported in the last day.
A profile's `entries` always list every one of them: the stored entries in
their order, then each model the profile does not hold yet, appended at the
bottom with `enabled: false` (and `reasoning: ""`), so the phone shows the
full selection and a new model never runs before it is switched on. Saving a
profile stores whatever list the phone sends. `driver` and `provider` come
from the hosts' reports; without one, `driver` is guessed from the instance
id (`claudeAgent`, `codex`, `agy-*` → `antigravity`, `opencode`) and
`provider` is the instance id.
`actions` names the profile for alert fixes (always set) and for text
generation (absent when none); `roles` are the `delegate_task` roles a
profile can be chosen for, with the current choice in
`policy.roleProfiles`.
`state`, `reason` and `runningThreads` are the live ranking as the cluster
machine would see it (absent when no host reported the model). `moves` is
the last 20.

- `PUT /v1/placement/profiles/{id}` `{"label": "Coding", "entries": [{"instanceId", "model", "reasoning", "enabled", "sessionShareOfWeekly"}]}`
  creates or replaces a profile; entries in order. A reasoning level must be
  one the model's `reasoning.levels` lists (or `""`).
- `DELETE /v1/placement/profiles/{id}` removes it (`409` for a profile an
  action or a role uses).
- `PUT /v1/placement/order` `{"ids": ["coding", "review", "minor"]}` orders the profiles.
- `PUT /v1/placement/alert-fix-profile` `{"profile": "coding"}` (kept; the
  same as `actions` with only `alertFix`).
- `PUT /v1/placement/actions` `{"alertFix": "coding", "textGeneration": "minor", "roleProfiles": {"review": "review"}}`:
  which profile each kind of work uses. Every key is optional and leaves
  the others alone; `textGeneration: ""` clears it (T3 keeps its last
  text-generation model), and a role mapped to `""` is removed (that role
  inherits its parent thread's profile). `roleProfiles` replaces the
  policy's `roleProfiles` as a whole. Each named profile must exist.
- `PUT /v1/placement/policy` with the policy object.

Each returns the same object as the GET and publishes `app.settings`-style
push `placement.changed` (priority 1, don't notify; the apps reload the
page if it is open).

Alert fixes, and launches that name no model, start in the alert-fix
profile: `t3_thread_launch` with `placementProfile: "<alertFixProfile>"`, as
a launch on `{"instanceId": "placement", "model": "<id>"}` does, after
checking that the machine's T3 lists the profile. T3 picks the profile's
account. Launches that name a model are unchanged. `/v1/settings/model`
stays for the Mac's composer.

### In the apps

Both apps edit the same things the same way (Android: Phone tab ›
Placement; macOS: the Profiles panel). The shared module (`shared/`'s
`Api.kt`) has the rules they share: `configuredEntries`,
`referenceCandidates`, `profileUsedBy` and `accounts`.

- A profile's page lists only the entries it holds (`configuredEntries`,
  hidden models left out but kept when saving), each with its switch, its
  reasoning level, reorder (Android: hold and drag) and remove (Android:
  hold without moving, or the entry's sheet). Add model offers the models
  it doesn't hold (not hidden ones) and the other profiles.
- A profile entry is `{"instanceId": "placement", "model": "<profile id>"}`
  ("Profile · Anthropic", with its own icon and no reasoning). The add menu
  leaves out the profile itself, the ones it holds, and every profile that
  uses it, directly or through others (that would be a cycle; the server
  refuses one with `400`). A profile another profile uses can't be deleted:
  the apps say which ones use it, as for actions and roles.
- Account limits (Android: Phone tab › Account limits; macOS: under the
  profiles) list `policy.accountLimits` with their end ("Ended …" once it
  passed), and add, edit and remove them with
  `PUT`/`DELETE /v1/placement/account-limits/{account}`. The owner enters
  the share to **keep** ("Keep 30% of the 5-hour window"), the apps send
  `sessionPercent: 70`. The accounts offered are the prefixes before `/` of
  `models` (`claude-alex` of `claude-alex/claude-opus-5-5`). Saving the
  policy never sends `accountLimits`.
