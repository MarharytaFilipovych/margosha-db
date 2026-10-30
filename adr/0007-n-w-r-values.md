# ADR 0007: Values of N / W / R

## Context

We need to decide on:
1. **N (replication factor):** how many replicas is enough for our database.
2. **W:** how many replicas have to confirm the successful write before returning the acknowledgement back to a user.
3. **R:** how many replicas have to be read from before returning the final result back to the user.

## Alternatives considered

### Replication factor (N)

- **1 replica:** it's not enough for the data persistence. On the failure, that will definitely occur, taking into
  account common network failures, all the data residing within this single replica will be lost and the server will
  be down completely. Rejected.
- **2 replicas:** it's better than 1 for sure, but there is still a chance that both replicas could be down. Rejected.
- **3 replicas:** there is still a chance that all the 3 will be down, but it is tiny.
- **More than 3 replicas:** it's great for our network defense, but that is expensive as well.

### Writes (W)

- **1:** the writes will be very fast, but if the replica that was written into goes down before sending this new data
  to other replicas, the data will be lost forever.
- **2:** writes are slightly slower, because we have to receive an acknowledgement from one more replica before
  replying back, but there is less chance that the data will be lost.
- **Quorum:** that is most of the replicas: total / 2 + 1. That is a good balance between persistence and speed.
  With 3 replicas the quorum is 2 (3 / 2 + 1 = 2), so these two options are the same here. With more replicas they
  differ: with 5 replicas the quorum is 3, so "2" would no longer be a majority.
- **All replicas:** that matters for the systems that require high data persistence (banking), but the writes become
  too slow.

### Reads (R)

- **1:** the reads are definitely fast, but there is a great chance that the data we get is stale (different from the
  other replicas).
- **2:** the reads are a bit slower, but the chance is greater that we will get consistent data.
- **Quorum:** this is a good balance between speed and consistency. As with writes, with 3 replicas the quorum is 2;
  with 5 replicas it would be 3.
- **All replicas:** that also matters for such systems as banking, but the reads will be very slow.

## Decision

3 replicas, quorum reads and writes: **N = 3, W = 2, R = 2**.

**Configurable:** W and R are settings, so other values can be tried (for example R = 1 to show a stale read). A node
refuses to start when R + W is not greater than N. N stays 3, because it is the number of replica instances the
stack creates. W = 2 and R = 2 are the default values.

**Why quorum reads see the latest write (R + W > N):** a write is confirmed by W = 2 of the 3 replicas, and a read
asks R = 2 of them. Since 2 + 2 = 4 > 3, the replicas a read asks and the replicas that confirmed the last write always
overlap in at least one replica, and that replica has the newest version. The read returns the answer with the highest
version, so it returns the latest confirmed write. With R + W ≤ N (for example W = 1, R = 2) the two groups may not
overlap, and a read can miss the latest write.

## Consequences

- There is a small chance that all replicas will be down at once, so the system will almost always be available.
- With one replica of a shard down, reads and writes still work: 2 replicas are left for both quorums.
- With two replicas of a shard down, that shard can neither read nor write: a quorum of 2 can't be reached.
- With the leader of a shard down, writes to that shard fail, because only the leader takes writes and M3 has no
  failover; reads still work from the two followers.
- Quorum reads and writes provide a good balance between speed and consistency.
- **Latency cost:** a write waits for the leader's own disk write plus the faster of the two followers; the slower
  follower doesn't hold it up. So a write is slower than with W = 1 by about one round trip between replicas, but has a
  shorter tail than W = 3, which always waits for the slowest replica. A read asks 2 replicas in parallel and waits for
  the slower of the two answers, so it is a bit slower than R = 1. The real numbers will come from the M3 benchmark.
- **CP or AP:** when the network splits, the system keeps consistency and gives up availability (CP). Example: the
  leader of shard A is cut off from both of its followers. Writes to shard A fail with 503 instead of being confirmed
  by the leader alone, and reads still work from the two followers and return every confirmed write. If a read cannot
  reach 2 replicas of a shard, it fails instead of returning a possibly stale answer. The system never behaves as AP:
  a setting that would allow stale reads (R + W ≤ N, for example R = 1 with W = 2) is refused at start.
- 3 replicas will not exceed the allowed budget.
