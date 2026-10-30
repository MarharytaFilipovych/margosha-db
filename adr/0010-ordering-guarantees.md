# ADR 0010: Ordering guarantees

## Context

All replicas of a shard must end up with the same records, and a quorum read must be able to tell which answer is
newer. We need to decide on:
1. **Who sets the order** of writes.
2. **How versions are assigned.**
3. **How deletes are recorded.**
4. **What the order covers:** a key, a shard, or the whole store.
5. **What a client can rely on**, and what not.

## Alternatives considered

### Who sets the order

- **Writes handled concurrently, order worked out later:** faster in theory, but needs conflict handling (M4). Rejected.
- **The leader, one write at a time per shard:** the leader checks, records and applies each write under a lock, so
  the log order is the order the writes happened. Waiting for the followers is done outside the lock, so writes still
  overlap there.

### Versions

- **Timestamps:** the clocks of different machines differ, so a later write can get a slightly different timestamp. Rejected.
- **A counter per key:** works, but a follower can't use it as its position in the log. Rejected.
- **A counter per shard (1, 2, 3…):** the entry's position in the log is its version. 

### Deletes

- **Just remove the record:** one replica would say "not found" and another the old value, and a read can't tell which is newer. Rejected.
- **Keep a delete marker with its own version (a tombstone):** "deleted at version 90" is newer than "value at
  version 50", so the read returns "not found".

### What the order covers

- **The whole store:** needs one counter shared by both shards, which would be a bottleneck. Rejected.
- **One shard:** every replica of a shard applies the same writes in the same order. A key never moves to another
  shard in M3, so this covers every key.

## Decision

The **leader sets the order**, one write at a time per shard. The **version is the entry's position in the shard's
log**. A **delete is a tombstone** with its own version. The order is **per shard**.

**Guaranteed:**
- Every replica of a shard applies the same writes in the same order.
- A read returns the latest confirmed write (R + W > N).
- Writes to one key are seen in the order they were confirmed.

**Not guaranteed:**
- An order between keys of different shards.
- A write that failed with 503 ("outcome unknown") may still appear later, when a follower catches up.

## Consequences

- Followers apply entries without any checks or conflict handling.
- A read picks the newest answer simply by comparing two numbers.
- Writes to one shard are handled one at a time on the leader; the benchmark shows if that limits throughput.
- Tombstones stay in the log forever.
- Clients must treat a 503 on a write as "maybe written" and read the record to check.
