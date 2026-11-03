# Replication: how a write reaches every replica, and how a read finds the newest one

This page describes how M3 keeps several copies of every record.
Decisions behind it: [ADR 0007](../adr/0007-n-w-r-values.md) (N = 3, W = 2, R = 2),
[ADR 0008](../adr/0008-replica-synchronisation.md) (leader copies through its log, followers pull with long-polling),
[ADR 0009](../adr/0009-log-structure.md) (append-only JSON lines with a checksum, stored = after fsync),
[ADR 0010](../adr/0010-ordering-guarantees.md) (version = log position, deletes are tombstones),
[ADR 0011](../adr/0011-write-acknowledgement.md) (follower confirms with its next position, semi-sync).
Routing a key to its shard is unchanged since M2 ([routing](../m2/routing.md)).

## Replica sets

Each shard is a replica set of N = 3 replicas: one leader and two followers. Roles are fixed (no failover in M3).

```
                         coordinator (1 for the cluster)
                    routes by the ring, quorum reads, writes to leaders
                   /                                      \
        shard-a                                          shard-b
  leader shard-a-1 ──log──▶ shard-a-2, shard-a-3   leader shard-b-1 ──log──▶ shard-b-2, shard-b-3
```

A replica is started with its shard, its own ID, its role and its data folder:

```
java -jar margosha.jar shard --margosha.shard.id=shard-a --margosha.replica.id=shard-a-1 \
    --margosha.replica.role=leader --margosha.data-dir=/var/margosha --margosha.coordinator.url=http://<coordinator>:8080
java -jar margosha.jar shard --margosha.shard.id=shard-a --margosha.replica.id=shard-a-2 \
    --margosha.replica.role=follower --margosha.replica.leader-url=http://<leader>:8080 \
    --margosha.data-dir=/var/margosha --margosha.coordinator.url=http://<coordinator>:8080
```

Every replica registers with the coordinator (`POST /shards` with `shardId`, `replicaId`, `role`, `address`) when it
starts and every 10 s after that. A shard joins the ring when its first replica registers. A second, different
leader for a shard is rejected (400) and the registered one is kept. So is a replica ID beyond the shard's N
registered ones (400): with a fourth replica, R = 2 of 4 and W = 2 of 4 would no longer overlap. A registered replica
can still move to a new address.

N, W and R are settings (`margosha.replication.factor`, `.write-quorum`, `.read-quorum`). Every node, coordinator or
replica, refuses to start unless W and R are between 1 and N and R + W > N.

## The write path (W = 2)

Example: `PUT /tables/users/records/42 {name: Bob}`, key 42 owned by shard-a.

1. **Coordinator** (`ShardRoutingService`): checks the table and the key (as in M2), finds the owner on the ring,
   and sends the write to that shard's **leader** only. No leader registered → `503 Shard shard-a has no registered
   leader`.
2. **Leader** (`LeaderRecordStorage` → `ReplicaStorage`), one write at a time per shard:
   checks the state (`404` if the record doesn't exist), appends the result to its log with the next version (say 57),
   forces it to disk (fsync), and applies it in memory.
3. **Followers** are waiting in a long-poll (`GET /replication/log?after=56&follower=shard-a-2`). The new entry wakes
   them: they get entry 57, store it in their own log (fsync), apply it, and ask again with `after=57`.
4. **Confirmation**: that next request is the follower's confirmation of 57. As soon as W − 1 = 1 follower has
   confirmed, the leader answers `200`, and the coordinator answers the client.
5. **No confirmation in time** (1 s, `margosha.replication.ack-timeout`): the leader answers
   `503 Write 57 was stored by the leader but not confirmed by 1 follower(s) in time; its outcome is unknown`.
   The entry stays in the leader's log and reaches the followers when one is back. The coordinator waits 2 s
   (`margosha.shard.timeout`), longer than the leader, so the client gets the leader's own answer.

A follower asked for a write answers `421 NOT_LEADER`.

## The log and versions

Each replica keeps an append-only file `replication.log` in its data folder; one line per write:

```
3a1f9c02 {"version":56,"table":"users","partitionKey":"42","attributes":{"name":"Ann"}}
9b07e4d1 {"version":57,"table":"users","partitionKey":"42","attributes":{"name":"Bob"}}
51c2aa30 {"version":58,"table":"users","partitionKey":"7","deleted":true}
```

- The version of an entry is its line number in the log, set by the leader. Every replica of a shard has the same
  entries in the same order.
- A delete is kept as a **tombstone** (`"deleted":true`) with its own version, so "deleted at 58" beats "Ann at 56".
- The 8 hex digits are a CRC32 of the JSON. On start a replica reads its whole log and rebuilds its records in memory:
  a bad last line (cut off by a crash, never confirmed) is dropped; a bad line in the middle stops the replica.
- Next to the log, `replication.log.id` holds the **log ID**: a random ID the leader creates for a new log, and that a
  follower copies when its own log is empty. Two logs with the same ID are the same history, so version 57 means the
  same write in both. A log with no entries has no ID (an ID file left next to an empty log is deleted on start).

## Followers and resync

A follower knows its position from the last entry in its own log. After a restart it rebuilds from its log and asks
for the entries after that position, with the same request as normal replication, so it receives only what it
missed. While the leader can't be reached, a follower retries (0.5 s, doubling up to 5 s) and never gives up.

Every fetch names the follower's shard and replica ID (`?shard=shard-a&follower=shard-a-2`). The leader serves only its
own shard, and only the replica IDs `<shard>-1` to `<shard>-N` other than its own (the names the stack gives, so
`shard-a-2` and `shard-a-3`). Anything else gets `400` and is not counted as a confirmation, so neither a follower
pointed at the wrong leader nor a request with a made-up follower name can confirm a write.

A follower with entries sends its log ID with every fetch (`&logId=…`). The leader refuses the fetch with
`409 LOG_MISMATCH`, copies nothing and doesn't count it as a confirmation when:
- the follower's log ID is not the leader's, or
- the follower's position is past the leader's last entry.

Both mean the two logs are different histories. Copying on top of them would give two different writes the same
version number, and a quorum read compares those numbers.

**A follower that starts with an empty log registers with the coordinator only once it has caught up.** It first asks
its leader for its last version (`GET /replication/status`), then registers when it has stored that many entries.
Until then the coordinator doesn't ask it for reads. Without this, a follower whose data folder was emptied would be
asked for reads while it still lacks writes it had confirmed before, and a read that asks it and a follower that is
behind could return an older value as the latest. A follower that starts with entries registers at once, even while
its leader is down: it still has every entry it confirmed.

## When a log is lost

| What was lost | What happens | What to do |
|---|---|---|
| A follower's data folder | It takes the leader's log ID, copies the whole log again, then registers | Nothing |
| The leader's data folder (followers still have the data) | The leader starts a new log with a new ID. It refuses its followers (`LOG_MISMATCH`, an `ERROR` line on both sides), so every write answers `503`. Reads still return the followers' data | Stop the leader, copy a follower's `replication.log` and `replication.log.id` into the leader's data folder, start it again |
| Every replica's data folder | A new, empty shard | Nothing to recover (the benchmark's reset does this on purpose) |

Logs written before log IDs existed have no ID file. On the first start with this version the leader creates an ID,
and each follower takes it on its first fetch, if its position is not past the leader's last entry.

## Quorum reads (R = 2)

Example: `GET /tables/users/records/42`.

1. The coordinator (`QuorumReader`) takes the shard's replicas starting at the shard's round-robin position: each read
   starts one replica further, so with R = 2 each of the 3 replicas is asked in 2 of every 3 reads.
2. It asks the first R = 2 in parallel. A replica answers with its version: `{attributes, version: 57}`, or
   `404` with the version it holds (`58` for a delete, `0` if it never received the key).
3. If one fails, or has not answered after **50 ms** (`margosha.shard.read-hedge-delay`), the next replica not asked
   yet is asked too, and the first R answers are used. So a replica that is down or slow delays a read by about
   50 ms, not by the 2 s shard timeout. (Before this, a stopped follower made two of every three reads of its shard
   take 2.2 s: [one-follower-down.md](aws_checks/one-follower-down.md).)
4. A replica that failed to answer is asked **last** for the next 5 s (`margosha.shard.skip-after-failure`), so reads
   don't keep running into it. Then one read tries it again in its normal place: if it answers, it is back in the
   rotation; if not, it is skipped for another 5 s.
5. The answer with the highest version wins. A delete with the highest version means "not found".
6. Fewer than R answers → `503 Only 1 of the 2 replicas a read needs answered for shard shard-a`.

Why this finds the latest write: a confirmed write is on W = 2 of 3 replicas, a read asks 2 of 3, and 2 + 2 > 3, so
at least one replica asked has it.

EXISTS works the same way and returns `true` only when the newest answer is a record.

### What a read does not guarantee: monotonic reads

A read always returns the latest **confirmed** write, but it can also return a write that is **not confirmed yet**.
The leader applies a write to its records in memory before it waits for a follower, and it answers reads from those
records. So:

1. A client updates key 42 to version 57. The leader has stored it; neither follower has it yet.
2. A read asks the leader and `shard-a-2`: the leader's 57 is the newest answer, so the read returns version 57.
3. The next read asks `shard-a-2` and `shard-a-3` (round-robin): both still have 56, so it returns version 56.

The second read went back in time. A client can also read a write whose `503` ("outcome unknown") it is still waiting
for. In normal operation this lasts a few milliseconds, until a follower confirms. It lasts longer only when the
followers can't copy from the leader but the coordinator can still reach them (for example a follower in its 5 s
retry pause). The entry is never taken back (there is no failover or log truncation in M3), so the newer value does
not disappear; it only shows up on some reads before the others.

Not fixed in M3 ([AI critique](ai_critique.md), finding 4). The fix would be for the leader to answer reads only up to
the newest version W replicas have stored (it knows that from `followerPositions`), keeping the previous value of a
key until its new version is confirmed. That changes the write path that was benchmarked, so it is left for M4, where
consistency models are the topic.

## What happens when replicas are down

| Down in a shard | Writes | Reads |
|---|---|---|
| nothing | work | work |
| one follower | work (the other follower confirms) | work (the third replica is asked instead, after at most 50 ms) |
| both followers | `503`, outcome unknown, after the 1 s ack timeout | `503`: only the leader answers |
| the leader | `503 Replica shard-a-1 of shard shard-a did not answer` | work, from the two followers |
| two replicas incl. the leader | `503` | `503` |

The other shard is never affected, and a request is never sent to another shard instead.

## Status of a replica

`GET /replication/status` on any replica:

```json
{"shardId":"shard-a","replicaId":"shard-a-1","role":"leader","lastVersion":57,
 "followerPositions":{"shard-a-2":57,"shard-a-3":56},"readsServed":4021,"entriesReceived":0}
```

On a leader, `followerPositions` shows how far each follower has confirmed (its replication lag). On a follower,
`entriesReceived` counts entries received since it started, which shows that a restart received only the missed range.

## Where the code is

| Class | Runs on | Job |
|---|---|---|
| `shard.replication.log.ReplicationLog` | replica | The log file: append (fsync), read after a position, check and replay on start |
| `shard.replication.storage.ReplicaStorage` | replica | Records in memory with versions, rebuilt from the log; one write at a time |
| `shard.replication.leader.LeaderRecordStorage` | leader | Write path: check, log, apply, then wait for W − 1 confirmations |
| `shard.replication.leader.ReplicationLeader` | leader | Long-poll for followers, their confirmed positions, the wait for confirmations |
| `shard.replication.follower.FollowerReplicator` | follower | The fetch → store → apply loop, resync, retries |
| `shard.replication.follower.FollowerRecordStorage` | follower | Reads from its copy; writes answer `421 NOT_LEADER` |
| `shard.service.ReplicaReadService` | replica | Versioned READ / EXISTS, read counter |
| `coordinator.service.ShardRegistryService`, `ReplicaSet` | coordinator | Registered replicas per shard, the leader, the round-robin read order |
| `coordinator.service.QuorumReader` | coordinator | Asks R replicas in parallel, replaces a failed one, returns the newest |
| `coordinator.storage.ShardRoutingService` | coordinator | Writes to the leader, reads through the quorum |
| `coordinator.storage.RemoteReplica` | coordinator | Calls one replica over HTTP |

## Tests that show each case

| Case | Test |
|---|---|
| Confirmed write is on the leader and a follower; one follower down; both down (503, kept in the log) | `ReplicaSetTest` |
| Followers get entries in log order; leader's recorded positions match theirs | `ReplicaSetTest` |
| Restarted follower receives exactly the 100 entries it missed | `ReplicaSetTest.restartedFollowerReceivesOnlyTheEntriesItMissed` |
| Each record written by its owner's leader and copied to that shard's followers only | `ReplicatedStoreTest` |
| Lagging follower: reads still return the newest version; a delete beats an old value | `ReplicatedStoreTest`, `QuorumReaderTest` |
| One follower down, two replicas down, leader down | `ReplicatedStoreTest`, `QuorumReaderTest` |
| 3,000 reads spread 1,800–2,200 per replica | `ReplicatedStoreTest`, `QuorumReaderTest` |
| Second leader rejected; replicas back after a coordinator restart | `ShardRegistryServiceTest`, `ShardRegistrationTest` |
| Table definitions survive a coordinator restart | `DurableTablesTest`, `TableRegistryServiceTest` |
| Log: crash in the middle of a write, damaged line, rebuild | `ReplicationLogTest`, `ReplicaStorageTest` |
| Log ID: created, kept on restart, dropped with an empty log, taken by an empty follower | `ReplicaStorageTest` |
| Leader that lost its log copies nothing over its followers' logs; follower ahead refused | `ReplicaSetTest` |
| Follower that lost its log copies everything again; an empty follower is ready only after catching up | `ReplicaSetTest` |
| Empty follower whose leader is down doesn't register | `ReplicatedStoreTest` |
| A replica ID beyond N is rejected; a registered one can still move | `ShardRegistryServiceTest` |
| A fetch from an unknown replica ID or from another shard's follower is refused and not a confirmation | `ReplicaSetTest` |
| A replica that never answers doesn't hold up reads; a failed replica is asked last, then tried again | `ReplicatedStoreTest`, `QuorumReaderTest`, `ShardRegistryServiceTest` |
