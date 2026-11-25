# Proposal: Forks — Prism babysits the custom forks production runs

Status: proposal, revised after review (2026-11-24). The Prism side (registry,
scanner, alerts, repair agents, Forks section) is not implemented.

**What changed since (2026-11-25).** `fork-kit.md` was adopted, so the
per-strategy parts below collapse into one: see its section "What it means for
Prism's Forks proposal". Section 1 describes the forks *before* that; today:

- All five forks use [fork-kit](https://github.com/BierDav/fork-kit) v0.4.0:
  one sync PR from `fork-kit/sync` into the fork branch (`patched`, `docker`,
  `placement-profiles`), merged by auto-merge when its checks pass. The sync
  runs every two hours from `BierDav/fork-kit`'s `sync-forks.yml` with the one
  App key; each fork keeps only the guard workflow and holds no key.
- **seaweedfs-backup** moved out of selfhosted-platform into the GitHub fork
  `BierDav/seaweedfs` (`patched`, following SeaweedFS releases); it publishes
  `ghcr.io/bierdav/seaweedfs-backup`.
- **CLIProxyAPI** deploys itself: its release dispatches selfhosted-platform's
  `pin-cliproxy-image.yaml`, which pins the new digest after checking it was
  built from `patched`. The side-branch build is gone.
- **t3code** keeps `placement-profiles` merged with the nightlies through the
  sync PR; `Fork nightly` builds that branch as it is and runs again.
- **Holds exist now**: the label `fork-kit:hold` fails the required check
  `fork-kit/hold`, so that PR cannot merge; for CLIProxy, the file
  `manifests/cliproxy/image-hold` in selfhosted-platform stops new pins. Open
  point 3 is therefore answered: holds are recorded, not incidents.

## 1. What production runs from a fork

Checked against `origin` and the GitHub API on 2026-11-24.

| Fork | Patches live on | Follows | Strategy | Artifact → deploy pin | Watched by today | State |
|---|---|---|---|---|---|---|
| `BierDav/palpo` | `patched` | `palpo-im/palpo` main | **merge** upstream into the full history every 6 h, FF push; base in `.custom-upstream-revision` | `ghcr.io/bierdav/palpo` → `matrix/score.yaml` (Renovate, automerge) | `fork-build-check` | healthy; prod `0.6.0-custom.4` |
| `BierDav/mautrix-teams` | `docker` | `YourSandwich/mautrix-teams` stable releases | **replay**: rebuild `docker` from the new tag, cherry-pick fork commits, `push --force-with-lease` | `ghcr.io/bierdav/mautrix-teams` → `matrix/score.yaml` (Renovate, automerge) | `fork-build-check`; conflict → GitHub issue | healthy; prod `33.0.0-custom.1`; build files only, no source patches |
| `BierDav/CLIProxyAPI` | `patched` (7 ahead: two source patches — Antigravity `functionCall` IDs, Claude OAuth refresh journal — plus CI) | `router-for-me/CLIProxyAPI` main | **merge**, as palpo | `ghcr.io/bierdav/cliproxyapi` → `selfhosted-platform/manifests/cliproxy/workloads.yaml` (by hand); CLIProxy plugins build as `.so` against it | `fork-build-check` | **red**: `TestConvertOpenAIResponsesRequestToAntigravity_InterruptedMessageBeforeRealOutputPreservesPairingAndOrder` fails after the upstream merge (00:03) and again after a fork-only push (13:21). Prod runs `8.0.80-custom.2`, released today from side branch `claude-refresh-journal`; `8.1.0-custom.1` and `8.1.1-custom.1` exist but are not deployed |
| `BierDav/t3code` | feature branch `placement-profiles` (CI on default branch `prism-nightly`) | `pingdotgg/t3code` nightly tags | **merge-at-build**: `fork-nightly.yml` merges the feature branch into each nightly; nothing merged is stored | release `fork-v<ver>` → `fleet/agents/t3-nightly.nix` (`bump-t3-nightly.sh` CronJob) | `BotStale` (bump script fails after 6 h lag) | **stalled**: `Fork nightly` disabled by hand on 2026-11-22, one run queued since 2026-11-21; fleet on `…20261122.5111`, upstream at `…20261124.5191` |
| `selfhosted-platform/images/seaweedfs-backup` | `.patch` files + `live-updates.go` in-tree | SeaweedFS, pinned 4.53 (source `4ab66fb…` and runtime image, which must move together) | **patch-files** | Gitea image → `manifests/backup/seaweedfs-backup.yaml` (by hand) | nothing; the Renovate rule meant to keep it in step with the cluster's SeaweedFS no longer matches the file | static; skew with the cluster chart goes unnoticed |

Not production forks: `typst/packages`, `Metrolist`, `YouMod`. Gitea has no forks or mirrors; fleet has no Nix patch overlays.

What this shows:

1. **Five forks, four strategies, four failure signals**: job summary, GitHub issue, stale CronJob, nothing.
2. **Alerts carry no fork context.** "custom-release.yml failed" does not say which upstream range, which patch, or whether the fork or upstream broke it.
3. **Repair is manual**, guided by per-fork prose (`palpo/docs/custom-fork.md`, `selfhosted-platform/docs/palpo-upstream-sync.md`).
4. **Holds are invisible.** CLIProxyAPI is deliberately on a side-branch build and t3code was paused by hand, but nothing records that, so every signal looks like a failure.
5. **Nothing enforces safety.** No prod branch has protection or rulesets, and T3 agents run `full-access` with the owner's `gh` login (`worker/src/t3.rs:808`).

## 2. Goal

One native Prism feature, **Forks**. For every fork it knows what the fork is and why it exists. It sees where each upstream change has got to on its way into prod, says precisely what stopped it, and starts a repair agent that knows the fork. It does this without per-fork code in Prism, and safety is enforced by GitHub, not by a prompt.

## 3. Design

### 3.1 Registry in Prism, description in the fork

**Policy lives in Prism** (a Rocky table edited from the apps), so a repair agent cannot change its own rules:

`forks(id, name, repo, manifest_path, strategy, patch_ref, prod_ref, t3_project, autonomy, hold_until, hold_reason, added_at)`

- `patch_ref`: where the patches live and where a repair lands: `patched`, `docker`, `placement-profiles`, or `main` with path `images/seaweedfs-backup` for seaweedfs.
- `autonomy`: `observe` | `one-tap` (v1 default) | `auto-pr` (v2).
- `hold_until` / `hold_reason`: an intentional hold, set from the app (e.g. "CLIProxyAPI on claude-refresh-journal until the journal patch lands"). While held, Lag and Stalled are informational only.

**The description lives in the fork**, at `manifest_path` (default `.prism/fork.toml`; for seaweedfs `images/seaweedfs-backup/fork.toml`). It holds no policy, only facts and the patch ledger:

```toml
upstream = { repo = "github:palpo-im/palpo", track = "branch:main" }  # or "release", "tag:<regex>", "chart:<name>" (seaweedfs)
base_file = ".custom-upstream-revision"
sync   = ".github/workflows/sync-upstream.yml"
gates  = ".github/workflows/custom-release.yml"
artifact = { oci = "ghcr.io/bierdav/palpo" }     # upstream base read from the image label / release body
deploy = [{ repo = "gitea:david-cluster-1/matrix", path = "score.yaml", bumper = "renovate" }]
runbook = "docs/custom-fork.md"

[[patch]]
id = "room-tags"
what = "Room tags at /user/{user_id}/rooms/{room_id}/tags with flat PUT bodies"
paths = ["crates/server/src/..."]
upstream = "none"            # or an issue/PR URL: the agent's "drop when" hint
```

The ledger replaces the prose list in each fork's docs with something both Prism and an agent can read. v1 needs no per-patch tests and no shared CI action. Prism classifies from workflow conclusions, job names and the conflict lists the sync jobs already write.

### 3.2 What the scanner tracks

A `forks.rs` scanner runs on the leader on its own cadence (every 5–10 min, using ETag requests), not every 30 s `tick_scans`. For each fork it derives:

- **upstream**: the newest upstream ref (branch head / release / nightly tag / cluster chart version for patch-files);
- **fork**: the newest fork commit on `patch_ref` and its upstream base;
- **gates**: the conclusion of the latest gate run on it, and whether the last change before it came from upstream or from the fork;
- **artifact**: the newest artifact and **its** upstream base, read from the image label or release, not from the branch, because prod may be built from a side branch;
- **prod**: the pinned artifact in each `deploy` file;
- **lag**: how long upstream has been ahead of what prod runs.

### 3.3 What can go wrong, and what Prism does

| Condition | How it's recognised | Action |
|---|---|---|
| **Held** | `hold_until` in the future | Show only |
| **Stalled**: workflow disabled, a run queued > 1 h, no run in 2× its period | workflow state, run age | Alert (warning). One-tap Re-enable / Run now. No agent |
| **Infra**: cancelled, runner/registry/token errors | conclusion + log signature | One automatic rerun, then treated as Gate red |
| **Upstream red**: upstream's own CI failing on that SHA | upstream check runs | Wait; alert only if it persists |
| **Conflict**: sync could not merge or replay | sync job failed + its conflict list | Alert + **Repair** |
| **Gate red after upstream change** (a semantic conflict) | gate red, last change from upstream | Alert + **Repair** |
| **Gate red after fork change** (a fork regression) | gate red, last change from the fork | Alert to the owner; no agent (it is their own change) |
| **Owner already on it**: an open branch/PR or a live T3 thread on the fork | GitHub + Prism `agents` | Suppress the repair offer and link the work instead |
| **Deploy lag**: artifact ahead of the pin for > N h | deploy file vs. artifact | Alert pointing at the Renovate PR / bump CronJob |
| **Skew** (patch-files): cluster chart moved past the patch base | chart version vs. `base` | Alert + Repair (rebase the patches) |

**One incident per `(fork, stage)`.** A newer upstream ref updates the open incident rather than opening another, so a fork that stays red for days is one incident, not one per sync. It closes when that stage passes. Incidents are read like alerts: the smallest Rocky id per key wins.

**Alerts go through Alertmanager.** The scanner POSTs them as `fork-build-check` does today, labelled with fork, stage, upstream range and failing test. They come back through the existing webhook, become ordinary episodes in the Alerts tab, and are not ended by mistake by `reconcile()`. `fork-build-check` shrinks to a thin, independent watchdog that only checks that Prism's fork scanner is alive. It is not removed, so fork watching doesn't die with Prism.

### 3.4 Repair agents

**v1: one tap.** The alert's "Repair" (or Forks › Repair) writes a `fork_requests` row (`kind = repair`), claimed by a worker like `alert_actions`. Only workers run these, because devices have no GitHub credentials. It launches through the existing `t3_thread_launch` path in the `fork-repair` placement action (falling back to alert-fix). The worktree is based on `patch_ref`, not `{remote}/HEAD`, so `t3.rs`'s `workspace_strategy` gains a base ref.

**The prompt is built from structured data**: fork name, strategy, `patch_ref`, upstream range as SHAs, conflicting paths, failing test names, run URL, the patch ledger (the patches touching those paths marked), and the runbook path. **Upstream-authored text** (commit messages, log excerpts) **is passed only as fenced, labelled untrusted data**, never as raw commit bodies.

**What a repair produces depends on the strategy:**

| Strategy | Repair produces |
|---|---|
| merge | PR from `prism/repair/<stage>-<sha7>` into `patch_ref` with the integration commit; never rewrites history |
| replay | fixes to the patch commits on a repair branch. The sync workflow replays them; the agent never pushes `docker` itself |
| merge-at-build | PR into `placement-profiles`, proven by a `fork-test-v*` build (`fork-nightly.yml`'s `feature_ref` input) |
| patch-files | PR editing the `.patch` files and the base pin, both pins moved together |

**Safety is enforced, not requested:**

- **Rulesets** on every `patch_ref`: PR required, required checks, no force-push, no deletion. Only the sync workflow's bot may bypass, because replay needs to force-push.
- **Repair credentials**: a GitHub App installed on the fork repos, whose installation token may push only `prism/repair/*` and open PRs. Repair agents run in a T3 session without the owner's `gh` login.
- **Workflow and build files**: any repair diff touching `.github/`, the manifest or Dockerfiles needs the owner, whatever the autonomy. The same goes for an upstream merge that changes workflows (generalising CLIProxyAPI's existing rule).
- **Gates on PRs**: palpo's and CLIProxyAPI's `custom-release.yml` get a `pull_request` trigger that tests and builds but does not publish.

**Merging is the owner's decision.** Merging into `patch_ref` releases, and for palpo and mautrix-teams Renovate then automerges into prod. So "Merge" goes through Approvals as a **new approval source**; today's approvals are T3 runtime requests only. The signed message contains the repo, PR, head SHA, base SHA and target branch, and the dialog says "this deploys to production". The worker merges with GitHub's `sha` parameter, so any push after the approval voids it.

**v2: automatic** (`autonomy = auto-pr`), only once the rulesets, the App token and the PR gates are in place. The leader writes the same `fork_requests` row itself (`requested_by = prism:auto`, `request_id = sha256(incident key + attempt)`), so automatic and one-tap launches share claiming and T3's launch-key dedup across leader handovers. Attempts are append-only rows. Bounds: 2 attempts per incident, a global cap on concurrent and daily repair agents, and a kill switch in settings. This reverses worker.md's "Fixes start only on a request" for forks only, deliberately and opt-in per fork.

### 3.5 In the apps

A **Forks** section in `shared/` + Android + macOS. Per fork: prod version and its upstream base; lag; the five scanner facts as chips; hold (set/clear); the patch ledger; the open incident with its alert, agent (Open in T3) and PR; actions: Run sync, Rerun gates, Repair, Re-enable, Hold, Merge (approval).

### 3.6 Credentials the worker needs (none exist today)

- A GitHub App (read, plus actions:write, pull-requests:write on the fork repos) for the scanner and merges, and its restricted installation tokens for repair agents.
- A read-only Gitea token for the deploy files in `matrix` and `selfhosted-platform`.

## 4. Rollout

1. **See** (v1a): `forks` registry, manifests for the five forks, the scanner, Stalled / Conflict / Gate red / Lag / Held / Skew as Alertmanager alerts with fork context, Forks section read-only. Fix the dead seaweedfs Renovate rule. `fork-build-check` becomes the watchdog.
2. **One-tap repair** (v1b): `fork_requests`, `fork-repair` placement action, strategy-aware repair outputs, branch rulesets, GitHub App with repair-branch-only tokens, PR gates, Merge through Approvals.
3. **Automatic repair** (v2): `auto-pr` per fork, with caps, kill switch and append-only attempts.
4. **Later**: patch-obsolete detection (upstream now does what a patch did → PR dropping it), agent-drafted upstream PRs, a shared `prism-fork-result.json` action if log classification proves too weak.

## 5. Decisions for the owner

1. **Start with v1 (see + one-tap repair) and decide on automatic repair after living with it?** (proposed: yes)
2. **Branch rulesets and a GitHub App on the four GitHub forks**: OK to add? They are the precondition for any agent pushing.
3. **Holds right now**: record CLIProxyAPI (prod on `claude-refresh-journal`) and t3code (nightly disabled) as held, or treat them as incidents?
4. **seaweedfs-backup**: include it in v1, or once the GitHub forks work?
