# Rollout: Prism's Rocky history seals and compacts

What this changes and why is in [worker.md, "Sealing"](worker.md#sealing).
This is the one-time rollout on the k3s cluster. Every step is in place: the
existing database, replicas and enrollments stay.

## Before

- The workers run an image from this change (`deploy/kustomization.yaml`
  pinned by the worker workflow after merge). The new image alone changes
  nothing: the workers come up with a coordinator link
  (`prism-worker: seals are coordinated through https://rocky-relay.quickme.at`)
  and keep running uncoordinated until the Job runs.
- The public coordinator answers. From any host with the alpha.13
  `rocky-voter` (the voters' release; the pinned Rocky is protocol-identical):

  ```sh
  curl -fSLo rocky-bundle.json https://rocky.quickme.at/bundle.json
  cmp rocky-bundle.json deploy/rocky-coordinator-bundle.json   # must match
  rocky-voter key tenant.key   # once; any throwaway tenant
  rocky-voter check rocky-bundle.json https://rocky-relay.quickme.at tenant.key
  ```

  If the bundle differs, the service was re-certified: update
  `deploy/rocky-coordinator-bundle.json` **before** the Job runs (the
  database names the bundle forever once coordinated).

- Each worker reports itself untrusted (`trusted: false`), uncoordinated
  in its settings (no `finalized`) and with a large unsealed history
  (`operationsSinceSeal`, `retainedBytes`):

  ```sh
  w=prism-worker.monitoring.svc.cluster.local.:8080
  kubectl -n monitoring exec prism-worker-0 -- /prism-worker seal \
    http://prism-worker-0.$w http://prism-worker-1.$w http://prism-worker-2.$w
  ```

## Run the Job

1. The root, from fleet's sops, into a short-lived Secret (the Prism root
   text contains the root key phrase; either form works):

   ```sh
   kubectl -n monitoring create secret generic prism-rocky-root \
     --from-file=root=<(sops -d --extract '["root"]' sops/prism-rocky.yaml)
   ```

2. Set the Job's image to the digest the workers run, then apply it:

   ```sh
   digest=$(kubectl -n monitoring get sts prism-worker \
     -o jsonpath='{.spec.template.spec.containers[0].image}' | sed 's/.*@//')
   sed "s/sha256:REPLACE_WITH_WORKER_DIGEST/$digest/" deploy/jobs/rocky-coordinate.yaml \
     | kubectl apply -f -
   kubectl -n monitoring wait --for=condition=complete --timeout=60m job/prism-rocky-coordinate
   kubectl -n monitoring logs job/prism-rocky-coordinate
   ```

   The log lists each worker's trusted identity, then the coordinator's
   receipt (`tenant`, `revision: 1`, `expires`) and the first worker's seal
   status. Note `expires` (about a year out) for the renewal.

3. Remove the root:

   ```sh
   kubectl -n monitoring delete secret prism-rocky-root
   kubectl -n monitoring delete job prism-rocky-coordinate
   ```

## Check

Within a few minutes (one 30 s seal tick, then the coordinator's
acknowledgement and sync), `prism-worker seal` (above) shows on every worker
`trusted: true`, `coordination: "service"`, a `finalized` and a `compacted`
seal, `operationsSinceSeal` under a few hundred and no `sealSkipped`.
`retainedBytes` falls within a minute of each final seal and then stays
flat. It settles at about the size of the live data (each seal's checkpoint
of every table) plus the operations since the last seal, not at zero. The Mac and the
phone receive the seals with no update, but compact their own replica only
once they run an app build with this `rocky-prism` (its compaction loop);
rebuild and install them to shrink the 1.6 GB Mac replica.

The first seal covers the whole unsealed history (about 88k operations), so
the first finality and compaction can take a while on the slow replicas.

## If the Job fails

- `this device's identity is not in its registry yet`: the worker could not
  renew its credentials from another worker (none reachable). Fix the
  workers' links and run the Job again; every step is idempotent.
- `coordinator control: ... Backpressure`: the public service paces
  registrations; run the Job again later.
- `coordinator control: ... Denied`: the tenant's quota or the bundle is
  wrong; check `rocky-voter check` above.

## Renewal (yearly) and new workers

Run the same Job with `--revision 2` (then 3, ...) before `expires`, or
after a worker is added or re-provisioned (the control lists every trusted
identity, so list every worker URL). A new worker must also be trusted (the
Job's first step does it).

## What it can't recover from

The database names this coordinator's genesis forever. If the public service
is re-certified or upgraded past coordinated finality (Rocky upstream
`27dfd00ce`, after alpha.16), seals stop becoming final and history grows
again. The way out then is a fresh Prism database with every device
re-enrolled, or a Rocky upgrade that seals without a coordinator (also a
fresh database). Keep the public coordinator on its current genesis and
alpha.13 while Prism depends on it.
