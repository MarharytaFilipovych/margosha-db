# Proposal

## Why

Milestone 3 of the capstone adds durability through replication. In M2 every record lives in one shard's memory: a
restart, a deploy or the end of a lab session loses it, and a stopped shard makes its keys unavailable (M2 AI critique,
findings 3 and 5). M3 gives each shard a replica set of 3 nodes, so every record is stored on several machines and on
disk, and reads are spread over all replicas instead of one node. The M2 YCSB numbers are the baseline this milestone is
measured against.

Scope of this change:
- **Leader-based replication** (the brief's suggestion). Per shard, one leader takes all writes; the followers copy
  them. No concurrent writers, so no conflicts (conflict resolution is M4).
- **No automatic failover in M3.** While a shard's leader is down, writes to that shard fail; reads keep working. Node
  failures are M4's topic.

## Decisions required before implementation (ADRs)

- **Values of N / W / R:** decided in [ADR 0007](../../../adr/0007-n-w-r-values.md): N = 3, W = 2, R = 2, with W and R
  configurable.
- **Replica synchronisation mechanism:** decided in [ADR 0008](../../../adr/0008-replica-synchronisation.md): the
  leader copies writes through its log, followers pull with long-polling, a restarted follower resumes after its last
  entry.
- **Write acknowledgement:** decided in [ADR 0011](../../../adr/0011-write-acknowledgement.md): followers confirm with
  their next position, the leader answers after W − 1 confirmations (semi-sync) or 503 after about 1 s. Split from the
  synchronisation ADR because it answers a different question (when a write counts as done vs how entries move).
- **Log structure:** decided in [ADR 0009](../../../adr/0009-log-structure.md): an append-only file on each replica's
  disk, one JSON line per entry with a checksum, stored = after fsync.
- **Ordering guarantees:** decided in [ADR 0010](../../../adr/0010-ordering-guarantees.md): the leader orders writes
  one at a time per shard, the version is the log position, deletes are tombstones. The brief lists log structure and ordering guarantees as one mandatory ADR;
  they are split because they answer different questions (what the log is vs what order a client can rely on).

Each is recorded in its own ADR, and the design summarises the alternatives. Tasks that depend on them come after the
ADRs.

## What Changes

- **Replica sets:** each of the 2 shards becomes N nodes: a leader and N − 1 followers. Replicas register with the
  coordinator with their shard ID, replica ID and role.
- **Replication log:** the leader records every write in an ordered log, and every write gives the record a new,
  higher version; deletes keep a version too. Where the log lives and its format: ADR 0009; how the order and versions
  are set: ADR 0010.
- **Followers consume the log:** they receive the leader's entries in order, keep their own copy on disk, and track
  their position in the log. After a restart a follower resyncs only the entries it missed. How entries get from the
  leader to the followers: ADR 0008; how followers confirm them: ADR 0011.
- **Write quorum:** the leader answers a write only after W replicas (itself included) have stored it. If that does not
  happen within a timeout, the write fails with a service-unavailable error.
- **Quorum reads:** the coordinator sends READ and EXISTS to R replicas of the owning shard, rotating which ones so the
  read load is even, and returns the result with the highest version.
- **Durable storage (BREAKING for the M2 behaviour "records are lost on restart"):** replicas keep their records across
  restarts and deploys by replaying their log. The coordinator also keeps table definitions on disk.
- **Infrastructure:** 6 replica instances (3 per shard, spread over both private subnets) instead of 2 shards;
  replicas of a shard can reach each other. The pipeline reboots and checks all 7 nodes.
- **Benchmark:** the same YCSB workload as M2 on the replicated store, compared against the M2 baseline, including why
  p99 changed. The procedure gets a discarded warm-up run and three measured runs (M2 AI critique, finding 7).

## Capabilities

### New Capabilities

- `shard-replication`: replica sets per shard, the leader's ordered log with versions, followers consuming it and
  resyncing after a restart, the write quorum W, quorum reads with R and the newest version, the N/W/R configuration
  and its R + W > N check, and what happens when replicas are down.

### Modified Capabilities

- `shard-storage`: records are kept across a node restart instead of being lost.
- `shard-routing`: registration is per replica (shard ID, replica ID, role); writes go to the shard's leader; reads go
  to R replicas of the shard; the unavailable cases change accordingly.
- `kv-api`: table definitions are kept across a coordinator restart.
- `app-infrastructure`: 3 replica nodes per shard instead of 1, their placement, replication traffic between replicas
  of a shard, and each follower learning its leader's address from the stack.
- `delivery-pipeline`: a deploy reboots and checks the coordinator and all replicas, and records survive it.
- `benchmarking`: the benchmark runs against the replicated store with a warm-up and three measured runs, and the
  results are compared against the M2 baseline.

## Impact

- **Code (`app/`):** durable replica storage built on the log (ADRs 0009, 0010), replication between leader and followers
  (ADRs 0008, 0011), coordinator changes (replica registry, leader routing, quorum reads, configuration check), durable table
  registry, tests with in-process replica sets.
- **API:** the public API to clients does not change. The internal API changes: replica registration carries a replica
  ID and role, replica reads return the record's version, and the replicas exchange log entries as ADR 0008 decides.
  `openapi.yaml` is updated.
- **Infrastructure:** `cfn/margosha-app.yaml` gets 4 more instances, new tags and a security group rule. The launch
  template's boot script changes, so the first deploy replaces all nodes. `cfn/margosha-vpc-stack.yaml` is unchanged.
- **Pipeline:** the deploy and its checks cover 7 nodes.
- **Learner Lab limits and budget:** 7 nodes run (8 with the YCSB client; the limit is 9). The nodes cost about twice
  M2's hourly rate (7 instead of 4 `t3.micro`), so they run only during work sessions.
- **Documentation:** ADRs 0007–0011 (N/W/R; replica synchronisation; log structure; ordering guarantees; write acknowledgement), M3 notes and
  benchmark results, README M3 section, M3 AI critique, demo.
- **Not in this change:** leader failover and leader election, conflict resolution and vector clocks (M4), Merkle-tree
  anti-entropy (optional in the brief), log compaction and snapshots, moving data when the ring changes (M5), rolling
  deploys (M5, zero downtime).
