# T3 directly from a device when the workers are unreachable

The workers are how Prism normally acts: a device writes a request row, and
the leading worker runs it in T3 (docs/worker.md). When a device can't reach
the workers, it runs its own request in T3 over NetBird instead, using its
own T3 sessions and the leader's own code (`worker/src/direct.rs`). The phone
reaches the workers over `prism.quickme.at` and needs no NetBird handshake
while they answer. It uses NetBird only for the fallback.

## Pairing: automatic

Nobody pairs by hand. Every fleet T3 host (`dev-host` and `prism-client`)
runs fleet's hourly `prism-t3-client-session` timer. The timer registers the
workers' own approval session (`prism register-t3`) and, with
`prism register-t3-devices http://<host>.netbird.cloud:3773`, each Prism
device's sessions:

| Kind | What it is | Used for |
| --- | --- | --- |
| `client` | `t3 auth session issue`, orchestration read and operate, 30 days | approvals (T3's client API) |
| `mcp` | full-access MCP session, 30 days, from T3's MCP OAuth with a pairing code the host mints with a five-minute `access:write` session | Fix, Do…, follow-ups, stop, question answers |

The devices are `devices` in the workers' config (david-pixel, david-mb,
david-mm). `GET /v1/t3/device-sessions` tells the host when each
device's sessions with it expire. The host mints only the missing ones and
those with under 15 days left, so two sessions per device and kind exist at
most, and a device always holds one that outlives a two-week outage.
`PUT /v1/t3/device-session` checks the session against the host's T3 (its
environment and, for `client`, its permissions; for `mcp`, an MCP call)
and stores it in the replicated `settings` table as
`t3.device.<device>.<machine>.<kind>`. Each device reads its own from its
replica. T3's access settings list them as "Prism on <device>". Revoking one
there is safe: the host mints a new one within the hour.

The leader also publishes what targets and prompts need from its config
(`t3.config`: the machines, the cluster's workspace, the instructions and
the devices' public keys), without the workers' T3 URLs and credentials.

## When a device runs a request itself

`Replica.call` (`rocky-prism-ffi/src/api.rs`) checks the workers' health
first (`GET https://prism.quickme.at/v1/health`, remembered for 30 s when
up, 10 s when down). A worker answers it at once, without reading its
replica: `503` only while its replica is incomplete (as `/ready`), `200`
however busy the database is, since a busy leader still runs the request:

- **Reachable:** the request goes to the workers, as before. Approvals,
  question answers and stops, which T3 takes only once anyway (an approval
  carries the same `commandId` on both routes), also run on the device when
  the workers haven't finished them after 8 s.
- **Unreachable:** the device claims its own request row as
  `device:<name>` and runs it right away with the leader's code: the same
  target, prompt, placement profile and full access for a Fix; T3's client
  API for an approval, with the same checks the leader makes (still
  pending, unchanged, signed for an accept). No worker reclaims a device's
  claim, so nothing runs twice once the workers are back; the finished row,
  the agent and the closed approval sync to them then.

A device with no sessions yet, or none for the target machine, leaves the
request to the workers and answers `202` with the reason in `detail`. So does
an agent request for the project `auto`: the leader picks it with its text
generation, which the published config leaves out.
A Fix, Do… or follow-up sent while the workers answered is never repeated on
the device: the workers may already have started it.

New alerts and approvals still reach the devices through the workers, who
watch every T3 for approvals with their own sessions and push them.
Dismiss and Resolve are replica rows either way.
