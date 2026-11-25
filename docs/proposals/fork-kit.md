# Proposal: one way to keep a fork — `fork-kit`

Status: proposal (2026-11-24). Companion to `forks.md`: if this is adopted, every
fork uses the same layout and the same workflows, and Prism's Forks feature
needs only one strategy.

**Built:** the merge-through-a-sync-PR design is published as
[BierDav/fork-kit](https://github.com/BierDav/fork-kit) v0.1.0 (Apache-2.0).
Its README is the reference; this document records how we got there.

## Review outcome (2026-11-24): the storage decision below is withdrawn

A delegate review, checked against the repos, showed the patch queue fits only
the small forks:

- **palpo**: its patches register its own regression tests inside upstream's
  `quality-control.yml` and `complement.yml` (+9 lines). Those run on PRs into
  `patched` today; with no upstream source on `fork` they would silently stop
  gating releases. Its ~7 logical changes also overlap in the files upstream is
  actively rewriting (`sync_v5.rs`, `device_lists.rs`), so a queue would be
  churn on every sync.
- **t3code**: `placement-profiles` contains 5 merge commits (nightly tags,
  another PR). Exported as a series, its first patch already fails to apply;
  only one squashed diff applies.
- Clean `git am -3` is not "safe" (CLIProxyAPI is red today after a clean
  merge), reverse-apply misdetects upstreamed patches, and Electron's export
  isn't deterministic enough to avoid churn.

**Revised proposal: one strategy, merge through a sync PR.** Every GitHub
fork keeps the full source on its protected branch and takes upstream by
merging, never rebasing. This is palpo's current scheme, which already
replaced a failed replay:

- `sync` merges the new upstream ref into branch `fork-kit/sync` and opens or
  updates **the sync PR**. Updating it is another merge, never a force-push.
  The bot touches the branch only while every commit on it is its own;
  once a human or an agent has pushed, it only comments with the newer ref.
- Upstream's own CI runs on the PR, so palpo's tests registered in it keep
  gating, alongside the fork's `test` and per-architecture `verify-image`
  hooks.
- Auto-merge only when the merge had **no conflicts** and every required check
  is green on the head SHA, with branches required to be up to date.
  Conflicts, red checks or upstream workflow changes need the owner.
- The sync bot is a GitHub App token (`contents`, `pull-requests`, `workflows`).
  With it, its PRs and merges trigger CI, and it can merge upstream's
  `.github/` changes. `GITHUB_TOKEN` can do neither.
- The **patch ledger** lives in `fork.toml` (`[[patch]]` with paths, tests,
  upstream link). Prism maps conflicting paths and failing tests to patches
  from it; an agent audits the ledger against `git diff base..fork` on each
  sync PR.
- Per fork: **mautrix-teams** switches from replay + force-push to merging
  release tags. **t3code** persists what `fork-nightly` computes today
  (feature branch + nightly merge) as its sync PR. **seaweedfs** stays as
  in-tree patch files over a pinned release, the one declared exception (two
  patches; a repo for them isn't worth it).

What stays from the sections below, adapted to merge:

- `fork.toml` as the manifest, with `version` derived in `sync` rather than
  stored, a pluggable `publish` (`oci` | `github-release`) and extra `[[pin]]`s.
- `fork-result.json`, the reusable workflow with fixed job names, and the one
  sync PR as the only place a repair happens.
- Rulesets: no force-push, no bypass. Repair agents limited by a ruleset ref
  allowlist (tokens can't be scoped per branch).
- Supply-chain hardening:
  - pin fork-kit and its actions by SHA;
  - explicit secrets instead of `secrets: inherit`;
  - `test`/`build` with read-only tokens, uploading an OCI artifact;
  - a source-free `publish` job is the only one that holds `packages: write`;
  - no cache writes from `sync` or PR runs.
- The `-fork.N` tags need no Renovate change: under semver they sort above
  `-custom.N` with the same `X.Y.Z`.
- Pilot: CLIProxyAPI first.

The original patch-queue design follows for the record.

## Decision (withdrawn): patches are files, the patched source is derived

Every fork keeps its patches as a **patch queue**: numbered `git format-patch`
files on a protected branch, applied to a pinned upstream commit. The patched
source tree is never stored; it is built from `upstream@base + patches`
whenever it is needed, whether for CI, a release or someone working on it.

Why this over the strategies in use today:

| | merge (palpo, CLIProxyAPI) | rebase stack (mautrix-teams) | **patch queue** (seaweedfs today) |
|---|---|---|---|
| What the patch set is | the diff `base..patched`, mixed with merge resolutions; palpo still carries `4a6ad714` in its history though upstream replaced it | one commit per patch | one file per patch |
| Force-push | never | every sync | never |
| Branch protection / PR flow | yes | needs a bypass | yes |
| Which patch broke on a new upstream | can't tell | the commit that fails | the file that fails |
| Patch made obsolete by upstream | invisible | empty cherry-pick | applies in reverse → drop it |
| CI files separate from source patches | no (palpo's mixed commits broke replay) | path filters | yes: they aren't patches |
| Review of a patch change | normal diff | `range-diff` | patch-of-patch, so `fork-kit` comments with the range-diff |

It's the approach Debian (quilt), nixpkgs and most distribution packagers use
for downstream patches over a moving upstream. The one cost is that working on
a patch means checking out a materialized tree and exporting back; `fork-kit`
does both in one command each.

## Prior art (searched 2026-11-24)

Nothing found does the whole job: a patch queue, scheduled refresh onto
upstream, one sync PR, a report per patch, and agent repair. The parts exist:

| What | Exists as | Use it? |
|---|---|---|
| Patch files ⇄ commits (materialize / export) | **Electron**'s `script/git-import-patches` / `git-export-patches` with `patches/<target>/` + an ordered `.patches` index (MIT, used daily on Chromium-sized patch sets); **git-pile** (`genbranch` / `genpatches`, orphan pile branch, LGPL-2.1, 17 stars, last push 2025-06); StGit, TopGit, quilt, guilt | **Yes**: adopt Electron's format and vendor its two scripts rather than write our own; git-pile is the same idea but barely maintained |
| Rebasing distro patches onto a new release | `rebase-helper` + Packit `pull_from_upstream` (Fedora) | No: RPM/dist-git specific; same pattern (release monitoring → PR) |
| Keeping a fork current by PR | `wei/pull` (7k stars), GitHub's own fork sync, `actions-sync-upstream` | No: merge or rebase of whole branches, stop at the first conflict; nothing per patch |
| Agent conflict repair | Cohere's `vllm-skills` (`rebase-assistant`, `auto-rebase`: baseline tests before the rebase, loop until tests pass, escalate to a human); `sorafujitani/t3code` PR #25 (scheduled T3 agent; `prepare`/`verify`/`publish` script; skill rules: read the fork's commits, keep both sides, keep fork behaviour when upstream deliberately changed it, stop when intent can't be inferred) | **Borrow**: the baseline-first rule and the resolution rules go into fork-kit's repair skill and Prism's repair prompt |
| Fork drift dashboards | ForkMonitor (planned features only), branch-watch, ad-hoc "fork is N commits behind" issue bots | No: ahead/behind counts only; Prism's view is per-stage and per-patch |

Two lessons taken from these:

- **`GITHUB_TOKEN` cannot push upstream's workflow-file changes.** The t3code PR works around this by pushing from a laptop. With a patch queue the `fork` branch never contains upstream's `.github/`, so the problem disappears.
- **Establish a green baseline before refreshing** (Cohere). `sync` first runs `test` on the current base. If that is already red, the incident is "fork red", not "upstream broke us".

## Layout of every fork

The fork stays a GitHub fork (so upstream PRs still work), but its default
branch, `fork`, holds no upstream source:

```
.fork/
  fork.toml                    # upstream repo + what to track, base SHA, base version
  series                       # patch order
  patches/
    0001-sync-room-tags.patch  # git format-patch, one logical change each
    0002-sync-invite-state.patch
  overlay/                     # downstream-only files copied over the tree: Dockerfile.custom, tests/custom_*.py
.github/workflows/fork.yml     # ~10 lines calling fork-kit's reusable workflow
```

Each patch ends with trailers that **are** the patch ledger Prism shows:

```
Patch-Name: sync-room-tags
Why: Element sends flat tag bodies; upstream rejects them
Upstream: https://github.com/palpo-im/palpo/issues/NNN   # or "none"
Test: tests/custom_idle_sync.py::room_tags                # optional
```

`fork.toml`:

```toml
upstream = "github:palpo-im/palpo"
track    = "branch:main"        # or "release", "tag:<regex>"
base     = "813034394c7a49db05ddb1c42e99a2d47de24ea7"
version  = "0.6.0"              # for artifact tags: <version>-fork.<n>

[build]                          # passed to the reusable workflow
test      = "cargo test -p palpo-server database_ && python3 tests/custom_idle_sync.py"
image     = "ghcr.io/bierdav/palpo"
dockerfile = "Dockerfile.custom"
platforms = ["linux/amd64", "linux/arm64"]
```

## `BierDav/fork-kit`

A public repo, released as `v1`, with three parts.

**1. The `fork-kit` CLI** (one Python script, no dependencies), for CI, the owner and agents alike. `materialize` and `export` wrap Electron's vendored `git-import-patches` / `git-export-patches` (MIT, notice kept), and the patch directory uses Electron's `.patches` index as `series`. fork-kit's own code is the `rebase` classification, `check` and `report`:

| Command | What it does |
|---|---|
| `fork-kit materialize [dir]` | upstream at `base`, `git am` the series (one commit per patch, trailers kept), overlay copied: a normal repo to build, test or edit in |
| `fork-kit export` | from a materialized tree: rewrite `.fork/patches` and `series` from `base..HEAD`, deterministic (no dates or hashes), so an unchanged patch produces no diff |
| `fork-kit rebase <ref>` | apply the series to a new upstream ref with `git am -3`. Per patch: `clean`, `refreshed` (only context moved), `conflict` (with paths) or `upstreamed` (reverse-applies on the new base) |
| `fork-kit check` | lint: every patch has the trailers, series matches the files, the overlay doesn't collide with upstream files |
| `fork-kit report` | write `fork-result.json`: stage, base, target ref, per-patch outcome, failing tests |

**2. Composite actions** (`fork-kit/setup`, `fork-kit/materialize`, `fork-kit/report`). Gitea Actions can use them too, by URL.

**3. A reusable workflow** (`.github/workflows/fork.yml`, `workflow_call`) with fixed job names, so Prism reads every fork the same way:

| Job | Runs on | Does |
|---|---|---|
| `sync` | schedule (6 h) + dispatch | look up the newest upstream ref; `fork-kit rebase`; push the result to branch `fork-kit/sync` and open or update **the sync PR**: base bump, refreshed patches, `upstreamed` patches removed, the per-patch table in the description. On a conflict the PR is a draft labelled `conflict`, with the patches that did apply and the conflict list |
| `test` | every PR and push to `fork` | `materialize`, then `build.test` |
| `build` | every PR and push to `fork` | `materialize`, build the image for all platforms, no push on PRs |
| `publish` | push to `fork` | push `<version>-fork.<n>` with labels `fork.base`, `fork.series` (hash of the patch files), release notes from the patch trailers |
| `report` | always | upload `fork-result.json` |

A fork's own workflow is just:

```yaml
name: fork
on:
  schedule: [{cron: "17 */6 * * *"}]
  workflow_dispatch:
  pull_request: {branches: [fork]}
  push: {branches: [fork]}
jobs:
  fork:
    uses: BierDav/fork-kit/.github/workflows/fork.yml@v1
    secrets: inherit
```

## How changes reach production

- **New upstream, everything applies**: the sync PR changes only `base` and context lines. `test` and `build` run on it. If they're green and fork-kit marks the PR `refresh-only`, the sync bot merges it, `publish` releases, and Renovate / the bump CronJobs deploy as today.
- **New upstream, conflict or red tests**: the sync PR stays open. That PR is the **one place a repair happens**: the owner or a Prism repair agent runs `fork-kit materialize`, fixes the failing patch, `fork-kit export`s and pushes to `fork-kit/sync`. Merging needs the owner (Prism's approval). No other branch or PR is involved, whatever the fork.
- **A patch was upstreamed**: the sync PR drops it and says so; the owner confirms by merging.
- **A new or changed patch**: an ordinary PR to `fork` (materialize → commit → export). Same gates.

Branch protection on `fork`: PR required, `test` and `build` required, no force-push, no bypass. The only automatic merge is the sync bot's on `refresh-only` PRs. Repair agents get a token that can push only `fork-kit/*` branches. Nothing in this setup ever rewrites history.

## What it means for Prism's Forks proposal

`forks.md` gets simpler:

- **One strategy.** The strategy column, the per-strategy repair table and `patch_ref` go away. The repair target is always the open sync PR.
- **The patch ledger is the patch trailers**, not a separate list in the manifest.
- **`fork.toml` is the manifest** Prism reads; the policy (autonomy, hold) stays in Prism.
- **Classification** reads `fork-result.json` and the fixed job names; Prism needs no log parsing.
- **Patch-obsolete detection** comes free from `upstreamed`.
- **No force-push** anywhere, so branch protection needs no bypass.

## Migration, per fork

| Fork | Work |
|---|---|
| CLIProxyAPI | two patches (Antigravity functionCall IDs, Claude OAuth refresh journal), cut from the current diff; first migration, the smallest |
| mautrix-teams | no patches; overlay = `Dockerfile.custom`; track `release` |
| palpo | split the ~2,700-line diff (33 files) into about 7 logical patches, the ones `docs/custom-fork.md` lists. An agent can do it; done when `materialize` gives a tree identical to today's `patched` |
| seaweedfs-backup | already patches; move them from `selfhosted-platform/images/seaweedfs-backup` into a GitHub fork `BierDav/seaweedfs` (or keep them in-tree and call the composite actions from Gitea) |
| t3code | see open point 1 |

Each migration: build the patch queue, prove that `materialize` gives a tree byte-identical to what production runs from today, switch the workflows, protect `fork`, keep the old branch read-only for history.

## Open points

1. **t3code.** Its "patch" is a 5,000-line feature (`placement-profiles`) still under active development, which is awkward to develop as patch files. Proposed: same layout, but `fork.toml` says `source = "branch:placement-profiles"`. The `sync` job then *exports* the series from that branch instead of rebasing it, so you keep developing on the branch and the patch queue follows it. Once the feature settles, drop `source` and it's an ordinary patch queue.
2. **seaweedfs**: a GitHub fork (everything the same) or stay in-tree on Gitea (one exception, one fewer repo)?
3. **Automatic merge of `refresh-only` sync PRs**: allowed (proposed), or always ask?
4. **Tag scheme**: `<upstream-version>-fork.<n>` for all, replacing today's `-custom.<n>` (Renovate rules in `matrix` need one regex change).
