# ADR 0016: Record versioning for Last-Write-Wins

## Context

When replicas hold different copies of a record, one copy must win under Last-Write-Wins (LWW). For that every record
needs a version. Each shard has one leader that handles all writes one after another, and today the version is the
position in its log. The nodes' clocks are kept right by NTP, which corrects them in jumps, so the time can go back.

## Alternatives considered

### Version

- **Clock time only:** simple, used by DynamoDB global tables and Cassandra. But NTP can move the clock back, and a
  write from a node whose clock is behind then loses without anyone noticing. Rejected.
- **Lamport counter only:** never goes back, but can't tell "this write came after that one" from "these two writes
  happened at the same time", so a real conflict looks like a normal newer write. Rejected.
- **Hybrid logical clock (HLC) only:** close to real time and never goes back, but, like Lamport, can't see writes
  that happened at the same time. Rejected.
- **Vector clock:** one counter for each leader that wrote the record. Comparing two vectors shows whether one copy
  is newer or the two were written at the same time (concurrent).

### What happens to concurrent copies

- **Keep both and let the client merge them:** as in Dynamo. Every read needs a merge, and a deleted record can come
  back. Rejected: the store resolves conflicts with LWW.
- **The later clock time wins:** a write made after an NTP correction can lose to an older one. Rejected.
- **The higher Lamport counter wins:** the counter has nothing to do with real time, so "last" doesn't mean latest.
  Rejected.
- **The later HLC wins:** an HLC is a pair: `l`, the larger of the clock time and the last `l`, and a counter `c` that
  grows when `l` didn't. Close to real time and never goes back.

## Decision

**Every write, deletes included, gets a vector clock and an HLC, both given by the shard's leader.** The leader takes
the record's current vector and adds 1 to its own counter, and in the same step gives the write an HLC. When the
leader starts, it reads the last `l` and `c` from its log, so its HLC never goes back after a restart.

To compare two copies of a record, we first compare their vectors. If one is greater or equal in every counter, that
copy is newer and wins. If the two were written at the same time, the copy with the later HLC wins. If the HLCs are
also equal, the higher log position wins, and then the higher replica ID, so the same copy wins every time. If the
winning copy is a delete, the record does not exist.

Every log entry must have a version, so logs written before M4 are not kept: the replicas start with empty data
folders when M4 is first deployed. A replica that never received a record has no version for it, and any copy wins.

## Consequences

- With one fixed leader per shard, every vector has one counter and copies are never concurrent today. The vector
  clock matters once more than one node writes a record, for example after a leader change.
- Concurrent writes are noticed, but only one is kept; the other is lost without notice, as LWW does.
- The vector gets one more counter for every leader that ever wrote the record; with fixed leaders it stays at one.
- A leader whose clock runs far ahead makes its writes win until real time catches up.
- Every log entry and replica answer gets bigger by the vector and the HLC (two numbers).
- The code stays simple, with no special case for old entries, but the data stored before M4 is lost on the upgrade.
