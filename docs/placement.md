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
-- what each host last reported (models, readings, threads), for the
-- fleet-wide load; a report older than 3 minutes no longer counts
CREATE TABLE placement_reports (
  machine     TEXT PRIMARY KEY,
  reported_at BIGINT NOT NULL,
  body        JSONB NOT NULL
);
-- session readings per account key, for the burn rate
CREATE TABLE placement_samples (
  account     TEXT NOT NULL,
  at          BIGINT NOT NULL,
  used        DOUBLE PRECISION NOT NULL,
  running     INTEGER NOT NULL,
  PRIMARY KEY (account, at)
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

The policy, the alert-fix profile and the text-generation profile are
settings (`placement_policy`, `alert_fix_profile`,
`text_generation_profile`) in Prism's existing settings store. The first start
seeds Coding, Review and Minor from fleet's `agents/profiles/full.nix` as of
2026-11-21 when `placement_profiles` is empty.

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

Two cases run into the limit instead:

- The entry's weekly allowance still has to be spent (weekly slack at most
  `weeklyMoveSlackSessions`). The thread keeps going until the entry is
  `exhausted`. It then moves without compaction (T3 skips `/compact` on a
  spent account), or waits when the limit resets within 30 minutes.
- The window resets within 30 minutes. The thread hits the limit and waits
  for the reset on the same account (above), keeping its prompt cache.

The weekly window has no fixed switch point. Only its pace or 100% stops it.

## T3 ⇄ Prism: `POST /v1/placement/sync`

On the machine event API (`https://prism-events.quickme.at`, the host's
machine token as `Authorization: Bearer`, as `prism report` uses; T3 reads
it from `placementSource.tokenFile`). The T3 server calls it every 30
seconds, and right away when a profile thread starts or a run ends and its
last answer is older than 30 seconds. A replica that is not the leader
answers too: placement is a pure function of the replicated rows.

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
  `placementMoveTarget` decides for each reported thread (running or not).
  Prism lists a move until the host reports the thread on `to` or
  acknowledges it; it never asks for a second move of the same thread
  within 2 minutes.

- `actions.textGeneration`: the profile T3's text generation (thread
  titles, branch names, commit messages and other generated text) runs in,
  or absent for none. T3 sets its `textGenerationModelSelection` setting to
  that profile's `next` whenever it differs and `next` is not null, so the
  text-generation code itself is unchanged and keeps working while Prism is
  away. With no profile set, T3 leaves the setting alone.

T3 acknowledges a move in its next request with
`"moves": [{"id": "…", "state": "continued" | "failed", "detail": "…"}]`.

## The phone's API

With the device token, as the rest of `/v1`.

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
