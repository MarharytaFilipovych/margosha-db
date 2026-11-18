# Last-Write-Wins: which copy of a record wins

This page describes how M4 decides which copy of a record wins when replicas hold different copies.
Decision behind it: [ADR 0016](../adr/0016-record-versioning.md) (vector clock + HLC, given by the leader).

## What the version is

Every write (create, update and delete) gets a **record version** with three parts:

- **vector**: a vector clock, one counter for each leader that wrote the record, by replica ID.
- **hlc**: a hybrid logical clock `(l, c)`. `l` is the time in milliseconds, never lower than the last `l`; `c`
  counts writes that got the same `l`.
- **writer**: the replica ID of the leader that accepted the write.

The record version is separate from the log position, which works as in M3 and is now called `logVersion`. Every log entry must have a
record version, so M3 logs are not kept: the replicas start with empty data folders on the first M4 deploy.

A log line now looks like this (the checksum is cut short):

```
3f1c… {"logVersion":2,"table":"users","partitionKey":"42","attributes":{"name":"Bob"},
       "recordVersion":{"vector":{"shard-a-1":2},"hlc":{"l":1700000000000,"c":3},"writer":"shard-a-1"}}
```

## Who gives it

The shard's leader, inside its write lock, in the same step that appends the entry to its log:

- **vector**: the record's current vector with 1 added to the leader's own counter. A record's first write gets
  `{leader: 1}`.
- **hlc**: `l` = the larger of the clock and the last `l`. If `l` didn't change, `c` = last `c` + 1, otherwise
  `c` = 0. The HLC is shared by the whole log, not per record.

Example with the clock at 1000 ms, then 2000 ms (from `ReplicaStorageTest`):

```
create users/42   vector {shard-a-1: 1}   hlc (1000, 0)
clock → 2000
update users/42   vector {shard-a-1: 2}   hlc (2000, 0)
delete users/42   vector {shard-a-1: 3}   hlc (2000, 1)
```

If the clock goes back (NTP), the HLC does not: with the last HLC at (1000, 0) and the clock at 500, the next write
gets (1000, 1). When the leader starts, it reads the latest HLC from its log, so this holds across restarts too.

Followers copy the entry with its record version unchanged, so a follower's log entry equals the leader's.

## Where LWW is applied

In the coordinator's quorum read (`QuorumReader`): it asks R = 2 replicas and keeps the copy that wins under
`LastWriteWins`:

1. If one copy's vector is greater or equal in every counter (and not equal), that copy wins.
2. Otherwise (equal or concurrent vectors), the later HLC wins.
3. If the HLCs are equal too, the higher log position wins, then the higher writer replica ID.

A winning delete means the record does not exist (404 for READ, `false` for EXISTS).

Replicas send the record version with every READ and EXISTS answer, and in the 404 for a deleted record. A replica
that never received a write for the key answers with position 0 and no record version, and any copy wins over it.

Examples (from `LastWriteWinsTest`):

```
{a-1: 1}, (1000, 0)  vs  {a-1: 2}, (1000, 1)         → the second: its vector is greater
{a-1: 1}, (5000, 0)  vs  {a-1: 1, a-2: 1}, (1000, 0) → the second: its vector is greater, the HLC doesn't matter
{a-1: 1}, (1000, 0)  vs  {a-2: 1}, (1200, 0)         → the second: concurrent, later HLC
{a-1: 1}, (1000, 0)  vs  {a-2: 1}, (1000, 0)         → the second: concurrent, same HLC, same position, a-2 > a-1
never received (no version)  vs  {a-1: 1}, (1000, 0) → the second: any copy wins
```

## Why there are no concurrent writers today

Each shard has one fixed leader, and it takes writes one at a time inside its write lock. So every record's vector
has only the leader's counter, each write's vector is greater than the one before, and two copies are never
concurrent. When two clients update the same key at the same time, the leader puts their writes in an order, and the
last one in its log wins everywhere (`ConcurrentWritesTest`: two clients, 50 updates each; all 3 replicas end with
the leader's last write, with the leader's counter at 101: 1 create and 100 updates).

The vector clock and the concurrent case only matter once more than one node can write a record, for example after
a leader change or when M5 moves keys to a new shard (ADR 0016). Neither is part of this milestone. So the check on
AWS ([concurrent_writes.md](checks/concurrent_writes.md)) shows the leader's order, and the concurrent rules (steps 2
and 3 above) are checked only by the unit tests.

What LWW gives up: when two writes are concurrent, one of them is dropped, and the client that sent it gets no error.
