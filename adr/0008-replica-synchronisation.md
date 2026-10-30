# ADR 0008: Replica synchronisation mechanism

## Context

Each shard has a leader and 2 followers; only the leader takes writes. We need to decide who copies a write to the
replicas, how the followers get new entries, and how a restarted follower catches up. When a write counts as done is
ADR 0011.

## Alternatives considered

### Who copies a write

- **The coordinator sends it to all 3 replicas:** nothing sets one order, so replicas can get two writes in different
  orders, and a replica that was down misses writes. Rejected.
- **The leader copies it through its log:** one order for all replicas.

### Who starts the transfer

- **Push (the leader sends to each follower):** needs a retry queue per follower and a separate catch-up path.
  Rejected.
- **Pull (each follower asks for the entries after its position):** one loop for both normal replication and
  catch-up.

### How a follower waits

- **Short polling:** either many empty requests or a delay on every write. Rejected.
- **Streaming (WebSocket, gRPC):** a second protocol next to REST. Rejected.
- **Long-polling:** the leader holds the request until a new entry arrives, so it is fast with no wasted requests.

### Catch-up after a restart

- **Full copy or snapshot:** slow as data grows, or out of scope for M3. Rejected.
- **Only the entries after the follower's last position:** what the brief asks ("resync only the relevant log range").

## Decision

The **leader** copies writes through its log, and followers **pull** with **long-polling**. A restarted follower
rebuilds from its own log and asks for the entries after its last one, the same request as normal replication. The
coordinator only routes writes to the leader and makes the quorum reads; it doesn't copy data.

## Consequences

- One mechanism for replication and for catch-up.
- The leader holds at most 2 waiting requests (one per follower).
- While the leader is down, followers keep asking and continue when it is back (no failover in M3).
