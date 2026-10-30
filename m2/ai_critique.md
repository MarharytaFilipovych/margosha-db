# AI design review — Margosha DB, Milestone 2

Oct 30, 2026

**Reviewed:** `openspec/changes/setup-m2-sharding/` (design, specs, tasks), ADR 0004–0006, `cfn/margosha-app.yaml`,
`cfn/margosha-vpc-stack.yaml`, `.github/workflows/pipeline.yml`, `app/` (ring, node, api, ycsb-binding),
`m2/routing.md`, `m2/app_infrastructure.md`, `m2/deploy_pipeline.md`, `m2/end_to_end.md`, `m2/benchmark/procedure.md`,
`m2/benchmark/results.md`

**Prompt given to the AI:** "Critique the architecture of my Milestone 2: the coordinator and shards, routing and
shard registration, the application stack, the delivery pipeline and the benchmark. Find weaknesses, contradictions
and risky assumptions, and say how serious each one is."

## 1. A coordinator restart leaves the store with no shards (high)

The shard list lives only in the coordinator's memory (design decision 6), and a shard registers exactly once: after the
first accepted `POST /shards`, `CoordinatorRegistrationService` shuts its retry thread down. Design decision 6 assumes
"on a deploy and at the start of a lab session all nodes restart". That holds for those two cases only. If the
coordinator alone restarts (its JVM crashes or runs out of heap, and systemd's `Restart=on-failure` brings it back, or
someone reboots just that instance), it comes back with an empty ring and every request answers `503 SHARD_UNAVAILABLE`
until the shards are restarted by hand. The troubleshooting table in `app_infrastructure.md` already lists this symptom
("reboot all three nodes"), so the gap is known, but it is a manual recovery for a failure that systemd makes automatic.
Fix: let shards re-register periodically (a heartbeat every few seconds; `register` is already idempotent), or let the
coordinator rebuild the list on start from the instance tags the stack already sets (`MargoshaRole`, `MargoshaShardId`).

> **Response: fixed.** Shards now repeat their registration every 10 s (`margosha.shard.register-refresh-interval`)
> instead of stopping after the first success; a failed repeat goes back to the normal retry with backoff. On the
> coordinator, a repeat from the same shard at the same address changes nothing (it keeps the existing connection,
> so the repeats don't create new HTTP clients); a new address still replaces the old one. New tests:
> `ShardRegistrationTest.restartedCoordinatorGetsItsShardsBackWithoutRestartingThem` (stop the coordinator, start a new
> one on the same port, the running shard is back on its ring and serves a write) and
> `ShardRegistryServiceTest.registeringKnownShardAgainAtTheSameAddressKeepsItsConnection`. The `shard-routing` spec has a
> new scenario, *Coordinator restarts*, and design decision 6 describes the repeat. I chose the heartbeat over reading the
> instance tags because it needs no AWS access from the coordinator and also works on a laptop and in the tests.

## 2. Anyone who can reach the coordinator can take over a shard's keys (high)

`POST /shards` is served on the same port as the client API, without authentication. The coordinator security group
opens that port to the whole VPC and to `OperatorCidr`. `ShardRegistryService.register` accepts any ID and **replaces**
an existing one ("Updated shard …"), so one request such as
`{"shardId":"shard-a","address":"http://attacker:8080"}` sends about half of all reads and writes to another machine.
No check ties a shard ID to an instance. In M2 the operator CIDR is one IP and the VPC holds only your instances, so the
exposure is small today, but it grows with every client added to the VPC (the bench client already can do it). Fix:
serve `/shards` on a second port that only the shard security group can reach, or require a shared secret delivered
through the stack; and refuse to change the address of a registered shard unless the old one stops answering.

## 3. Lost data looks like missing data (medium–high)

Several normal events silently lose records: a shard that restarts (crash, OOM, reboot) registers again with an empty
store; a shard added later takes over keys that stay on the old owner (the "ring changes do not move data" scenario);
every deploy reboots all nodes. In each case reads answer `404 RECORD_NOT_FOUND`, the same answer as for a key that never
existed, and the coordinator logs only "Updated shard". A client cannot tell "you never wrote this" from "the store lost
it". Durability is planned for M3, but the API could already be honest: e.g. a per-shard start time or generation number
returned by registration, and a distinct error (or a log warning) when a shard re-registers with a different generation.

## 4. The deploy check passes even if the new jar can't start (medium)

The pipeline's last step waits for `sha256 <hash>` on each node's console. That line is printed by `margosha-prepare`
after the download and **before** Java starts, so a jar that fails at startup (bad configuration, a Spring context
error, a port clash) still gives a green deploy. Nothing checks that the coordinator answers or that both shards
registered. Fix: after the hash, wait for `Running as` on each node and two `Registered shard` lines on the coordinator
(the console already has them), or call the API from the runner once access allows it. Related: when only a template
changes, the jar hash is the same as before (the build is reproducible), so the step matches the previous boot's line and
proves nothing (`deploy_pipeline.md` notes this).

> **Response: fixed.** The check was meant to prove "the nodes run the new jar", and the download line was the closest
> log line; it doesn't prove the jar started. The last pipeline step ("Wait for the nodes to start the new jar") now
> needs, for each node, the new jar's hash **and** a `Running as …` line logged after the deploy began, and then
> `Registered shard shard-a` and `shard-b` on the coordinator, also after the deploy began. Spring writes a UTC time on
> each line, so lines from earlier boots are ignored; this also fixes the template-only case, where the hash alone
> matched the previous boot. If that doesn't happen within 12 minutes, the step fails with an error and the node's last
> log lines. The matching was tested on sample console logs from these runs (an old boot with the same hash, a download
> with no start, a startup crash, another jar, shard-b only, and the passing cases). As before, most startup errors
> already fail the `check` job, whose integration tests start the same application in-process; this step catches what
> only happens on the instances, such as the boot script's arguments.

## 5. Every push to `main` wipes the database (medium)

A deploy reboots all three nodes at once (design decision 9), so each application commit means a full outage plus loss
of all records, and a template change that touches the launch template replaces all instances. This is accepted for M2,
but the delivery design itself doesn't carry over to M3: once records are durable, "reboot everything" is still an
outage. Worth planning now: restart shards one at a time and the coordinator last, waiting for registration in
between; this also needs finding 1 fixed first.

## 6. The single coordinator is both the bottleneck and the single point of failure (medium)

The benchmark shows it: coordinator up to 63% CPU while the shards stayed at 11–14% (`results.md`). Every request is
parsed, re-serialised and sent again, so latency includes two HTTP hops and throughput is limited by one `t3.micro`.
Adding shards would mostly not help. The design accepts this for M2, but the conclusion matters for M5 (rebalancing):
measuring "more shards" with this coordinator would show little gain and could be misread as a sharding problem.
Options: client-side routing (the binding fetches the ring and calls shards directly, which needs shards reachable from
clients), several stateless coordinators sharing the ring, or at least a cheaper internal protocol for the second hop.

## 7. The benchmark is a single run, warm-up included (medium)

`results.md` reports one run. Its own throughput-over-time table shows the first 30 s at about half of the steady
state (JIT warm-up after the reboot that empties the store), and the overall 1,531 ops/s mixes both. With one run there is
no idea of the variation between runs, and M3/M5 will be compared to this number. A 10% difference later could be noise.
Fix for the procedure: a fixed warm-up (e.g. a short run phase whose output is discarded), then three measured runs,
reporting the median and the spread. Also worth writing down: the trial changed workload A's records, because each
UPDATE leaves only one field (see finding 8), so later runs on the same data read smaller records.

## 8. UPDATE semantics make workload A lighter than it looks (low–medium)

YCSB's update sends one field; Margosha's UPDATE replaces the whole record, so after the run about half of the touched
records hold one 100-byte field instead of ten. Reads of those records return a tenth of the data, and the shards hold
less memory than the load phase created. The docs say this "doesn't change the latency or throughput", but it does,
slightly in the store's favour, and it makes the numbers less comparable with other databases' workload A results.
Either add a partial update (PATCH, merging attributes) or state the effect more carefully in `results.md`.

## 9. A write that times out may still have happened (low–medium)

The coordinator answers `503` when a shard doesn't reply within `margosha.shard.timeout` (2 s), but the shard may have
applied the write after the timeout. A client that retries a CREATE then gets `409 RECORD_ALREADY_EXISTS` for its own
write; a retried UPDATE is harmless; a DELETE retry gets `404`. The `shard-routing` spec says such an operation "fails
with a service-unavailable error", which a client will read as "nothing happened"; for writes that is not guaranteed.
Fix: document `503` as "unknown outcome" for writes, or make CREATE retries idempotent.

## 10. Releases are a single mutable file (low)

Nodes always download `releases/margosha.jar`, and the bucket has no versioning. Whatever is in the bucket runs on the
next boot, including the lab's automatic start at the beginning of a session; there is no record of which version ran
before, and a rollback means finding and re-uploading an old jar by hand. Fix: upload to `releases/<commit>/margosha.jar`
and keep the version to run in an instance tag or a stack parameter, which the boot script reads.

## 11. Smaller points (low)

- Shard B is in another AZ than the coordinator, so half of all requests pay cross-AZ latency and traffic, while the
  layout still does not survive the loss of `us-east-1a` (M1 critique, finding 1, still applies). The benchmark didn't
  report latency per shard, so the cost is unknown.
- How many records a shard holds before running out of heap was never measured; `-Xmx512m` and 100,000 records were
  safe, but nothing stops a client from filling a shard, and an `OutOfMemoryError` ends in finding 3's silent loss.
- The pipeline uses the lab's session credentials (`LabRole`, very broad). Anyone who can change the workflow on `main`
  can use them. In the lab this is accepted; outside it, an OIDC role limited to the two stacks and the bucket would be
  the normal setup.
- New T3 instances in standard mode start with 0 CPU credits, which invalidated the first trial run. The procedure now
  checks the client's credits, but a `t3.small` client that runs out mid-run would still limit the next benchmark.