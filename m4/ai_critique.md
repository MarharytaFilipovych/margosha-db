# AI design review — Margosha DB, Milestone 4

Nov 15, 2026

**Reviewed:** `openspec/changes/setup-m4-operations/` (proposal, design, specs, tasks), ADR 0012–0018, `M4.md`,
`cfn/margosha-app.yaml` (dashboard, alarms, boot script), `.github/workflows/pipeline.yml`, `app/node`
(`RequestMetrics`, `ReplicaMetricsRelay`, `TraceIdFilter`, `LastWriteWins`, `AgentKeyFilter`), `app/mcp-server`,
`m4/metrics.md`, `m4/lww.md`, `m4/tracing/`, `m4/mcp/`, `m4/checks/`

## 1. A replica that never registers is never reported as down (high)

The node-down alarm of a replica watches `margosha.replica.up`, and the coordinator makes that gauge only for replicas
in its registry (`ReplicaMetricsRelay.readAll` walks `shardRegistryService.replicas()`). The registry lives in memory,
so after the coordinator restarts it is empty until each replica registers again. A replica that does not come back
(its instance failed to start, the jar crashed on boot, its disk is full) never registers. The coordinator never
creates its `up` gauge, CloudWatch gets no data, and the alarm has `TreatMissingData: notBreaching`, so it stays `OK`.
The same holds for its lag alarm: the leader has no position for a follower it never saw.

This is not rare here. Every deploy restarts all 7 nodes, and so does every new lab session (the lab stops and starts
the instances). The pipeline catches it during a deploy, because it waits for 6 registrations. A lab session start
goes around the pipeline, and then nothing catches it. ADR 0014 says "a node that doesn't come back after a deploy is
noticed only once e-mails are on again". In fact it is never noticed by an alarm. The only sign is
`margosha.cluster.replicas` showing 2 instead of 3 on the dashboard, and no alarm watches that number for this.

Fix: let the coordinator know the replicas it expects (the 6 IDs, from a property or the stack) and create their `up`
gauges at start with the value 0, so a replica that never shows up is "down", not "no data". A cheaper fix is one alarm
per shard on `margosha.cluster.replicas` (leader below 1, followers below 2).

> **Response: fixed.** The coordinator expects `<shard>-1` … `<shard>-3` for each shard in
> `margosha.metrics.expected-shards`. An expected replica has no `up` value for 3 minutes after the coordinator starts,
> and 0 after that until it answers, so its node-down alarm fires ([ADR 0019](../adr/0019-expected-replicas.md),
> `ReplicaMetricsRelayTest`). Checked on AWS: the alarm fired about 4 minutes after the coordinator started
> ([expected_replicas.md](checks/expected_replicas.md)).

## 2. ADR 0018 moved the read limit to fit a slowdown nobody explained (medium–high)

In M3, under about 1,000 requests per second, reads had a p99 of about 15 ms. In M4, with almost no load, a normal
read takes 27–30 ms on the coordinator, while the replicas need 2–4 ms (`traces.md`). The trace shows two gaps of
about 10 ms that it "doesn't show why": the slower call, and the time after the last answer. ADR 0018 then raised the
read limit from 50 ms to 100 ms, so that this normal state does not trigger the alarm. That is the limit following the
data, not the data being explained.

There is a cause that fits M4 in particular. M4 added one log line per request and one per replica call, on every
node, at `INFO`. The boot script sends the service's output to `journal+console`, so every line also goes to the serial
console, which is slow. If writing to it blocks, the request thread waits, and the coordinator writes three lines for
each read, two of them right after the answers arrive. This is a guess that the review can't prove from the documents,
but it is cheap to test, and other causes are also possible (idle CPU, cold connections at low load). M4 also did not
run YCSB again, so nobody knows whether the extra logging lowered throughput. M5 compares with the M2 baseline, and
a slower M4 would show up there with no explanation.

Fix: before accepting 100 ms, test once. Run the same slow read loop with the per-request log line turned off (a
logging level in the jar's properties, deployed by the pipeline, no node replaced) and compare the coordinator's p50.
If the logging is the cause, use an asynchronous appender or write the JSON log to a file instead of the console. Then
run one short YCSB workload and compare it with M3.

## 3. Trace lines are lost exactly when there is load (medium–high)

Trace IDs are only useful if the lines are still there when someone looks. Two limits make this unlikely under load:

- The helper reads `aws ec2 get-console-output`, which keeps only about the last 64 KB per node (ADR 0017 says this).
  A JSON log line is a few hundred bytes, so at benchmark load 64 KB holds well under a second of requests.
- The fallback is `journalctl` over SSH. But journald limits each service by default to 10,000 lines per 30 seconds
  and drops the rest with "Suppressed N messages". At 1,000 requests per second the coordinator writes about 3,000
  lines per second, so most lines would be dropped. Whether the journal survives a reboot on this AMI was not checked
  either.

So the checks in `traces.md` work, because they ran with one client and a few requests. During a real incident, the
moment you need a trace, its lines are probably gone. Control question 8 asks what is missing for a real incident, and
this is the honest answer: there is no place where logs are kept. Fix: write the JSON log to a rotated file on each
node (Spring Boot `logging.file.*` in the jar, no template change) and let the helper read it over SSH. Or log the
per-request line only for slow or failed requests, and for requests that carry a client trace ID. At least write the
limit down in `tracing.md`.

> **Response: fixed.** Every node also writes its JSON log to `logs/margosha.log` in its data folder, rotated at
> 20 MB, up to 200 MB. The trace helper reads these files over SSH with `-Files` / `--files` ([ADR 0020](../adr/0020-log-file.md),
> `StructuredLogTest`, `tracing.md`). Checked on AWS: after about 3 MB of new lines the console had lost a request,
> the files still had it ([log_file.md](checks/log_file.md)).

## 4. Two of the three SLOs are not measured or alerted (medium)

ADR 0014 sets three SLOs, but only latency has an alarm based on the SLO. Availability (99.5% of requests without a
5xx in each hour) has a dashboard line, which "appears only after a first 5xx", and no alarm. A leader that answers
`503` to every write (disk full, followers that can't confirm) keeps all 16 alarms `OK`: the node is up, lag may be 0,
and the read p99 is fine. The lag SLO ("at most 5 s in 99% of minutes") is not computed anywhere. The lag alarm checks
something else (above 10 s for 1 minute).

The availability SLI is also counted inside the coordinator, so the requests it never sees are not counted: when the
coordinator is down or restarting, clients get a refused connection, and the SLI shows nothing. Deploys are left out
on purpose, but every deploy and every lab session start is a 1–2 minute outage of the whole store.

Fix: one alarm on the same expression the dashboard already has (5xx share above 0.5% over 1 hour, or a faster
"burn rate" version over 5 minutes), with `FILL(..., 0)` so it works before the first error. Write in ADR 0014 that
the SLI is seen from inside the coordinator and doesn't count a coordinator outage, which only the coordinator-down
alarm reports.

> **Response: partly fixed.** A new counter `margosha.requests.failed` and an availability alarm: more than 5% of at
> least 20 requests in 5 minutes answered with a 5xx ([ADR 0021](../adr/0021-availability-alarm.md)). ADR 0021 also
> says that the SLI doesn't see a coordinator outage. Not changed: the 5 s lag target has no alarm of its own. The
> 10 s lag alarm is looser on purpose, so that short pauses don't trigger it (ADR 0014). Checked on AWS: with shard A's
> leader stopped, 32 of 60 requests failed and the alarm fired at 53.33%
> ([availability_alarm.md](checks/availability_alarm.md)).

## 5. The agent key limits a request, it doesn't let it in (medium)

`AgentKeyFilter` checks requests that carry `X-Agent-Key`. A request without the header passes with no check at all.
So the key does not authorise the agent. It is a label the agent chooses to wear. Anything that can run a command,
including Claude Code itself with `curl`, can drop the header and use every endpoint: update, register a table,
`POST /shards`. ADR 0015 accepts this ("the API only accepts the operator's IP"), but then the claim in `mcp.md` that
"even a changed or badly set up MCP server can't do more than the four operations" is only true for a server that
still sends the key.

There are smaller gaps in the same area:

- ADR 0015 says "the user must approve every delete". `mcp.md` says that in auto mode Claude doesn't always ask, and
  `mcp_session.md` shows a delete without a question. The ADR and the documents say different things. The
  `destructiveHint` is a hint to the client, not a check in the store.
- The allowed tables are kept twice: `MCP_ALLOWED_TABLES` on the laptop and `margosha.agent.allowed-tables` in the
  coordinator's jar. Adding a table means changing both, and the second needs a deploy.
- The key lives in plain text in the Claude settings file on the laptop, and there is no way to change it without a
  deploy, because the coordinator reads SSM only at start.

Fix: if the key is meant to protect the store, the next step is "every client sends a key" (ADR 0015's rejected
option), with one key for YCSB and the operator and one for agents. Until then, write in ADR 0015 that the key limits
an honest MCP server and protects nothing against an agent with a shell, and correct the sentence about approving every
delete.

## 6. LWW is built for conflicts that this store can't have (medium–low)

Every write gets a vector clock and an HLC, and `LastWriteWins` compares vectors, then HLCs, then log positions, then
writer IDs. But each shard has one leader that orders all writes, so every vector has one counter, one copy always
dominates, and the HLC and tie-break branches never run outside unit tests. `concurrent_writes.md` shows exactly this:
100 updates from two clients end as one chain, `{"shard-a-1": 101}`. That check proves the leader's log order (which
M3 already had), not that LWW resolves a conflict.

This is not wrong, and ADR 0016 says the vector will matter in M5 when keys move between shards. But it means the
store carries two clocks where one counter would order the same writes today, and the parts that would decide a real
conflict were never seen working on AWS. In the defence the question "show me a conflict that LWW resolved" has no
answer from the running system. Fix: either say this plainly in `lww.md` (LWW is ready for M5, today the leader
already prevents conflicts), or add one test on real nodes that creates two concurrent copies (for example, writes
given to two replicas directly through a test-only path) and shows which wins. Also write down what is lost: with LWW
one of the two concurrent writes disappears with no error to the client that sent it.

## 7. The monotonic-reads problem from M3 is still open (low–medium)

The M3 review (finding 4) showed that a read can return a write that no follower has yet, and the next read can go
back to the older value. The response was "left for M4". M4 changed the read path (LWW now chooses the winner), but
the leader still applies a write to its records before a follower confirms it, and reads still see it. LWW does not
change this: the leader's unconfirmed copy has the higher version, so it wins whenever the leader is asked. Fix: either
do the fix proposed in M3 (the leader answers reads only up to the version W replicas have), or move the note from
"left for M4" to M5 in `m3/replication.md` and the M4 documents, so the plan is honest.

## 8. Smaller points (low)

- **Cost is not written down.** The design asked ADR 0013 for "its cost per lab hour", and it says only "the number
  of metrics is the main cost". There are roughly 120–140 custom metrics (5 operations × 4 values on the coordinator
  and the leaders, 2 × 4 on each follower, plus up, lag, records and error counters). At $0.30 per metric per month,
  billed per hour, that is about $0.05 per hour while the store runs. The 16 alarms cost about $1.60 per month, also
  while the lab is stopped. Small, but it belongs in the ADR, because M1 counted every cost that runs between sessions.
- **p99 at low load is the slowest one or two requests.** At 4–5 reads per second, a minute has about 250 reads, so
  its p99 is decided by 2–3 requests. That is part of why the 50 ms limit almost fired. A minimum number of requests
  before the alarm counts the minute (metric math with the count) would help more than a higher limit.
- **Different units for the same metric.** The coordinator sends latencies in milliseconds and the replicas in seconds
  (`metrics.md`). The dashboard handles it, but an alarm on a replica's latency written with the coordinator's numbers
  would be off by 1,000 times.
- **Lag looks smaller after a leader restart.** The leader uses its restart time for older entries (ADR 0012), so a
  follower that was already behind can stay under the 10 s alarm for a while. Accepted in the ADR, worth showing at the
  defence.
- **A client chooses its own trace ID.** Two clients that send the same ID (for example `readme-read-$RUN` run twice in
  the same second) get mixed timelines. The coordinator could append a short random part to a client's ID.
- **Replica metrics depend on the coordinator.** When the coordinator is down, there are no replica metrics and no
  replica alarms (accepted in ADR 0013). The alarms then can't tell "coordinator down" from "coordinator down and a
  replica down too".
