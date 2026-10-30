# Design

## Context

M2 state (see the archived change `2026-10-30-setup-m2-sharding` and `m2/`):
- One jar, two roles. The coordinator talks to shards through one storage interface (create / read / exists / update /
  delete); in sharded mode a routing implementation sends each call to the one shard that owns the key, over the same
  REST API clients use. A shard keeps each table in an in-memory map.
- Shards register with the coordinator on start and every 10 s; the coordinator's shard list, ring and table
  definitions are in memory.
- `margosha-app`: 1 coordinator (public subnet) and 2 shards (private A and B), all `t3.micro` with 8 GiB gp3, built
  from one launch template whose boot script reads the node's role and settings from its **instance tags** on every
  boot and downloads the jar from S3. Shards accept API traffic only from the coordinator's security group.
- The pipeline reboots the nodes after uploading the jar and waits until each started it and the coordinator logged
  both shard registrations.
- Constraints: Learner Lab, at most 9 running instances, `t3.micro` has 1 GiB RAM (`-Xmx512m`), \$50 for the course,
  shards have no internet (S3 only, through the gateway endpoint).

Scope set in the proposal: leader-based replication, no failover in M3. N / W / R are decided in
[ADR 0007](../../../adr/0007-n-w-r-values.md) (N = 3, W = 2, R = 2, W and R configurable).

## Goals / Non-Goals

**Goals:**
- Keep the coordinator's client API unchanged: clients and the YCSB binding need no change.
- One storage engine for every replica (leader and followers use the same log and recovery code).
- Every mandatory M3 ADR maps to one part of the code that implements it.
- The behaviour in the specs can be tested in-process (leader + 2 followers in one JVM), as M2's sharding was.

**Non-Goals:**
- Leader election or promotion, fencing, log truncation after a leader change.
- Log compaction, snapshots, or removing deleted records from the log: the log grows for the whole course (sized under Risks).
- Replicating the coordinator, or moving table definitions off its disk.
- Merkle trees / anti-entropy (optional in the brief).
- Read-your-writes guarantees beyond what R + W > N gives; reading from a single replica.

## Mandatory ADRs

### ADR 0007: values of N / W / R (decided)
N = 3, W = 2, R = 2. W and R are settings (`margosha.replication.write-quorum`, `.read-quorum`, used by the leader and
the coordinator); N (`margosha.replication.factor`) is fixed at 3 by the number of replica resources in the stack and
is a setting only for the start-up check. Every node refuses to start when W or R is outside 1..N or R + W ≤ N. In the
stack, W and R are parameters (`WriteQuorum`, `ReadQuorum`) passed as instance tags, so changing them is a stack update
plus the pipeline's reboot, with no instance replaced.

### Decided in ADRs 0008–0011
These are written before the code that depends on them. Below is a summary of each decision and the alternatives it
considered; the ADR is the source.

**ADR 0008: replica synchronisation mechanism.** Decided in
[ADR 0008](../../../adr/0008-replica-synchronisation.md): the leader copies writes through its log; followers pull the
entries after their position with long-polling; a restarted follower resumes from the last entry in its own log. The
questions and alternatives it answers:
- *Who copies a write to the replicas*: the coordinator sends each write to all N replicas and counts their answers
  (Dynamo style; replicas could receive two writes in different orders, and a replica that was down misses writes unless
  something keeps them for it), or the leader copies it to its followers through its log and answers the coordinator
  only after W replicas have it (one order set by the leader; the brief's "leader event-sources them with some log
  structure" and "followers consume the log").
- *Who starts the transfer*: the leader pushes each entry to every follower (it knows at once when they have it, but
  needs per-follower retry queues and a separate catch-up path after a restart), or each follower pulls the entries
  after its position (one loop for live replication and catch-up; it learns of new entries only when it asks).
- *How a puller waits*: short polling at a fixed interval (wasted requests, or added delay on every write), long-polling
  (the leader holds the request until an entry arrives), or a streaming connection (fast, but a second protocol next to
  the REST API of ADR 0004).
- *Catch-up after a restart*: a full copy from the leader, a snapshot plus the rest of the log, or only the entries after
  the follower's stored position (the brief asks for "only the relevant log range").

**ADR 0011: write acknowledgement.** Decided in [ADR 0011](../../../adr/0011-write-acknowledgement.md): a follower's
next request's position is its confirmation; the leader answers after W − 1 confirmations (semi-sync) or 503 after
about 1 s, shorter than the coordinator's 2 s shard timeout. The questions and alternatives it answers:
- *How a follower confirms*: a separate acknowledgement request, or the position in its next fetch (a follower only asks
  for "after X" once X is stored).
- *When the leader answers the client*: right away (async, W = 1), after W − 1 confirmations (semi-sync, follows from
  ADR 0007), or after all followers (fully sync).
- *Timeout*: after it the write fails with 503 and its outcome is unknown; the cost on the write path is measured in
  the benchmark.

**ADR 0009: log structure.** Decided in [ADR 0009](../../../adr/0009-log-structure.md): an append-only file on each
replica's disk, one JSON line per entry holding the write's result and a CRC32, stored = after fsync, never trimmed.
The questions and alternatives it answers:
- *Where the log lives*: a broker (Kafka, RabbitMQ: another instance, at the 9-instance limit while benchmarking, and
  1 GiB of RAM), objects on S3 (survives losing the leader; tens of ms and a request charge per write), or an
  append-only file on each replica's disk.
- *Entry content*: the client's request (followers would repeat the state checks) or the result of the write (the full
  new value, so applying it needs no earlier state).
- *Format*: text (readable with `tail` at the defense) or binary (smaller, faster to parse); how a write cut off by a
  crash is detected on start (a checksum per entry, or none).
- *When an entry counts as stored*: after the operating system has it, or after it is forced to disk (fsync), which
  sets what "W replicas stored it" means and costs latency on `t3.micro` + gp3.
- *Growth*: the log grows forever (compaction and snapshots are out of scope), or old entries are trimmed.
- Points for the ADR: how long rebuilding from the log takes on start, and what a damaged entry in the middle means.

**ADR 0010: ordering guarantees.** Decided in [ADR 0010](../../../adr/0010-ordering-guarantees.md): the leader
handles writes one at a time per shard, the version is the entry's log position, deletes are tombstones, the order is
per shard. The questions and alternatives it answers:
- *Who sets the order*: the leader alone, writes handled one at a time per shard (one lock), or concurrently with order
  decided afterwards.
- *Versions*: a per-shard sequence number (the log position), a per-key counter, or timestamps (clock skew between
  nodes; M4 is where timestamps and vector clocks come in).
- *Deletes*: removed from the follower's maps (a quorum read can't tell a deleted record from a replica that missed the
  delete), or kept as a versioned delete marker (a tombstone).
- *Scope of the guarantee*: one order per shard, per key, or across the whole store (needs one sequencer for all
  shards). Keys never move between shards in M3.
- *What a client sees*: the latest confirmed write (follows from ADR 0007's R + W > N), and what is not guaranteed:
  order across shards, and writes whose outcome was unknown (503) that appear later.
- Points for the ADR: what happens to entries that a failed write left behind, and whether a client can read a value
  older than one it read before.

The code keeps these behind small seams (a log interface, a replication client and server on the replica, a
configurable quorum), so each ADR decision is one implementation.

## Decisions

### 1. Topology: 2 shards × 3 replicas, fixed roles from the stack
| Shard | Leader | Followers |
|---|---|---|
| `shard-a` | `shard-a-1`, private A | `shard-a-2` private B, `shard-a-3` private A |
| `shard-b` | `shard-b-1`, private B | `shard-b-2` private A, `shard-b-3` private B |

Each shard has replicas in both AZs, and the two leaders are in different AZs, so losing one AZ leaves every shard with
at least one replica and one shard with its leader. Roles are instance tags (`MargoshaShardId`, `MargoshaReplicaId`,
`MargoshaReplicaRole`, and for followers `MargoshaLeaderIp = !GetAtt <leader>.PrivateIp`), read by the existing boot
script, as M2 does for the coordinator's IP. A private IP survives stop/start, so followers find their leader every
session.
- *Alternative*: followers ask the coordinator for their leader. Rejected: adds a dependency on the coordinator to
  replicate, and the stack already knows the answer.
- 7 nodes + the YCSB client = 8 running instances, under the limit of 9.

### 2. Replica storage: the log plus an in-memory index (shard-storage)
A replica keeps the latest version of each key in memory (table → key → version + attributes, or a deleted marker as
ADR 0010 decides), like M2's
maps plus a version, and keeps its log (ADR 0009) on disk. On start it rebuilds the maps from its log; its position in
the log is the last entry it holds. Reads are served from memory. Single-node mode keeps M2's in-memory storage.
- *Alternative*: an embedded database (SQLite, RocksDB). Rejected: a second copy of the data next to the log, a native
  library on 1 GiB nodes, and the log is needed anyway for replication.

### 3. Leader write path
The leader checks the request against its state (record exists / missing → 409 / 404, as in M2), records it in the log
with the next version, applies it, then waits as ADR 0011 decides until W replicas (itself included) have it, or the
timeout passes (503 `SHARD_UNAVAILABLE`, "outcome unknown"). How writes are ordered and versioned on the leader is
ADR 0010; the check, the log record and the apply must happen in that order, so the check sees every earlier write.

### 4. Coordinator: replica registry, writes to the leader, quorum reads (shard-routing, shard-replication)
- **Registration** carries `shardId`, `replicaId`, `role`, `address`. The ring stays keyed by shard ID (M2's hashing and
  vnodes are unchanged); each shard entry holds its leader and followers. A second, different leader for a shard is
  rejected (400). Repeats with the same details change nothing (M2 behaviour).
- **Writes** go to the shard's leader. No leader registered, or no answer within the write timeout → 503.
- **Reads:** the coordinator picks R replicas of the shard starting at a per-shard round-robin counter, so with R = 2
  each of the 3 replicas is asked in 2 of every 3 reads. It sends the requests in parallel; if one fails or times out
  it asks a remaining replica; it needs R answers or returns 503. It returns the answer with the highest version; a
  delete or "never written" with the highest version is "not found".
- Replica read answers carry the version (READ: attributes + version; not found / deleted: 404 with the version;
  EXISTS: `{exists, version}`). This is the internal API; the client API is unchanged.
- A follower answers a direct write with `421 Misdirected Request` and the new error code `NOT_LEADER` (internal: the
  coordinator never sends one).
- *Alternative*: send reads to all replicas and use the first R answers (lower tail latency, more read traffic).
  Possible later; the even distribution check is simpler with exactly R requests.

### 5. Durable table definitions (kv-api)
The coordinator appends each registered table to a file in its data directory, forced to disk, and loads it on start.
Without this, records would survive a restart but be unreachable (`TABLE_NOT_FOUND`) until the table is registered
again.
- *Alternative*: store table definitions in a system table on the shards. Rejected for M3: a bootstrap problem (the
  coordinator must read it before serving), for a few lines of data.

### 6. Infrastructure and pipeline
- `margosha-app.yaml`: 6 replica instances from the existing launch template (tags as in decision 1), parameters
  `WriteQuorum` / `ReadQuorum`, the shard security group allows the API port from itself (replication traffic between
  replicas), outputs for all 7 instance IDs. The boot script builds the replica's arguments from the new tags and creates
  the data directory. The launch template change replaces all M2 nodes on the first deploy (their data was in memory).
- Pipeline: reboot / start every node from the stack outputs (not three fixed variables), and wait until each runs the
  new jar and the coordinator has logged all 6 replica registrations after the deploy began.
- *Alternative*: separate launch templates for leader and follower. Rejected: the role is data (a tag), not a different
  machine.

### 7. Benchmark (benchmarking)
Same workload (A), record count, operation count and threads as M2, run on the replicated store with the M2 procedure
plus a discarded warm-up run and three measured runs (median reported). The comparison explains p99 with the write
path (W replicas must store each write, plus the log's disk writes as ADR 0009 decides) and the reads (each waits for
the slower of R replicas), while reads are now served by 6 nodes instead of 2. The M2 baseline is compared as recorded,
together with its steady-state numbers; it is not re-measured (that would mean deploying the M2 stack again).

## Risks / Trade-offs

- [The log is never compacted] → Size: the M3 benchmark writes ~100,000 records + 4 × 200,000 updates ≈ 1M entries,
  well under the 8 GiB disk unless entries are much larger than a YCSB record; rebuild time on start is measured on
  AWS. Compaction is a later milestone's topic.
- [Leader down = no writes for its shard] → Accepted (no failover). Reads keep working with R = 2; the demo and docs
  show it. M4 handles node failures.
- [A failed write may still appear later] → Documented as "outcome unknown" (spec), as the M2 AI critique (finding 9)
  asked.
- [Disk writes on `t3.micro` + gp3 may dominate write latency] → Measured in the benchmark; how often the log forces
  data to disk is part of ADR 0009.
- [7 nodes double the hourly cost] → Instances run only during work sessions; the YCSB client only while benchmarking.
- [Rebuilding a large log makes start-up slow, and the pipeline's wait step has a 12-minute limit] → Rebuild time is
  recorded on AWS; the limit is raised if needed.
- [All nodes rebooted at once in a deploy] → No data loss now, but a short outage; rolling deploys are part of M5's
  zero-downtime work.
- [Wrong role tags, e.g. two leaders for one shard] → The coordinator rejects a second leader (400) and logs it; the
  pipeline's registration check fails the deploy.

## Migration Plan

1. ADRs 0007–0011 first (all written).
2. Storage and replication with in-process tests; then coordinator changes; the full test suite passes.
3. Template and pipeline changes in the same commit as the code, so one deploy brings up the replicated store. The
   launch template change replaces the M2 instances (M2 data was in memory; nothing to migrate).
4. Verify on AWS: placement, registration, replication, follower resync, leader-down and follower-down behaviour,
   records surviving a deploy.
5. Benchmark, then the deliverables.

**Rollback:** revert the commit; the pipeline deploys the M2 template, which deletes the extra replicas and replaces
the nodes. Data stored by M3 is lost on rollback.
