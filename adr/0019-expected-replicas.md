# ADR 0019: Replicas the coordinator expects

## Context

[ADR 0013](0013-monitoring-stack.md) reports a replica as down when the coordinator sends `margosha.replica.up` = 0
for it. "No data" doesn't count, so that a stopped coordinator gives one alarm, not seven.

The AI review ([ai_critique.md](../m4/ai_critique.md), finding 1) found a gap. The coordinator keeps registrations
only in memory, and it creates a replica's `up` value only after that replica registers. After the coordinator
restarts, a replica that never comes back (its instance didn't start, the jar crashed, its disk is full) never
registers. So there is no `up` value for it, and its alarm stays OK. This happens after every deploy and every new lab
session, because both restart all 7 nodes. The pipeline notices it during a deploy, because it waits for 6
registrations, but nothing notices it after a lab session starts.

## Alternatives considered

- **Write the limit down and keep it.** Nothing to change, but the node-down alert misses
  a replica that is down after a restart. Rejected.
- **An alarm on the number of registered replicas per shard** (`margosha.cluster.replicas`, leader below 1,
  followers below 2). Only template changes. But this value exists only after the first replica of a shard
  registers, so a shard with no replica at all still sends no data. Rejected.
- **Count "no data" as down for the replica alarms.** Catches it, but a stopped coordinator then fires 7 alarms
  instead of 1, which ADR 0013 rejected.
- **The coordinator knows which replicas to expect.** A list of shards in its settings. For each shard it expects
  `<shard>-1` as the leader and `<shard>-2` … `<shard>-N` as followers, the IDs the stack gives them. It creates their
  `up` values when it starts. A replica that hasn't registered after a grace time gets 0.

## Decision

The coordinator expects the replicas of the shards in `margosha.metrics.expected-shards`, set to `shard-a,shard-b`.
An expected replica has **no value for 3 minutes** after the coordinator starts, so that the replicas have time to
register after a normal restart. After that it has **0** until it answers, so its existing node-down alarm fires. A
registered replica works as before.

## Consequences

- A replica that never registers after the coordinator restarts is reported about 4 minutes after the coordinator
  starts: 3 minutes of grace, plus up to 1 minute until the next read.
- A stopped coordinator still gives one alarm, not seven.
- The list of shards is in the coordinator's settings, so a new shard (M5) must be added there.
- The IDs must follow the stack's pattern. A replica with another ID is still read when it registers, but isn't
  expected.
- Only the jar changes. No template change and no node replaced.
