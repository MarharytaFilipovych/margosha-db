# ADR 0011: Write acknowledgement

## Context

A write must count as done only when W = 2 replicas have stored it (ADR 0007). We need to decide how a follower
confirms an entry and when the leader answers.

## Alternatives considered

### How a follower confirms

- **A separate confirmation request:** one more request per entry. Rejected.
- **The position in its next request:** asking for "entries after 57" means 57 is stored. No extra request (Kafka does
  the same).

### When the leader answers

- **Right away (async, W = 1):** a confirmed write is lost if the leader dies first. Rejected.
- **After all followers (fully sync):** one slow follower slows every write. Rejected.
- **After W − 1 = 1 follower (semi-sync):** matches ADR 0007.

## Decision

Followers **confirm with their next request's position**, and the leader answers after **1 follower confirmation**.
If none comes within about 1 s, the leader answers 503: the entry stays in its log, so the write's outcome is unknown
and it may appear later. The leader's timeout is shorter than the coordinator's, so the leader's answer reaches the
client.

## Consequences

- One follower down doesn't slow writes; both down means 503 with an unknown outcome.
- Every write costs one leader–follower round trip; the benchmark measures it.
- The leader knows each follower's position, which shows replication lag.
