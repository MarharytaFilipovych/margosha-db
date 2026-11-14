# ADR 0014: SLO values

## Context

We need targets for the store, and alert limits that follow from them, for three things: availability (the share of
client requests that don't fail with a 5xx), how fast the coordinator answers reads and writes, and replication lag.

For speed we use p99: the time within which 99 of 100 requests finish. The numbers come from the M3 benchmark. There
the store handled about 1,015 requests per second, reads had a p99 of about 15 ms (18.5 ms in the slowest run), and
updates about 28 ms (34 ms in the slowest run). A deploy restarts all 7 nodes at once, so the store is down for about
1–2 minutes. Normally a follower receives each write within a few milliseconds.

## Alternatives considered

### Availability

- **97%, deploys included:** a deploy fits in it, but real faults could fail 3 in 100 requests unnoticed. Rejected.
- **99.9%, deploys not counted:** a stopped leader fails its shard's writes for a few seconds, which already uses it
  up. Rejected.
- **99.5%, deploys not counted:** strict enough to catch real faults. Deploys are left out until M5 makes them
  rolling.

### Latency

- **Just above the benchmark (20 ms for reads, 40 ms for writes):** the slowest benchmark run was already close to
  this, so a normal busy minute would trigger an alert. Rejected.
- **About twice the benchmark (30 ms for reads, 60 ms for writes):** catches smaller slowdowns, but more load than in
  the benchmark could trigger an alert. Rejected.
- **About three times the benchmark (50 ms for reads, 100 ms for writes):** normal spikes stay below it, but a stuck
  replica or a CPU that has run out of credits goes above it.
- **One limit for all operations:** writes take about twice as long as reads, so one number would be too loose for
  reads or too strict for writes. Rejected.

### Replication lag

- **Alert above 5 s:** a short Java garbage-collection pause or a slow disk on `t3.micro` could trigger an alert.
  Rejected.
- **Alert above 60 s:** a stopped follower would go unnoticed for over a minute. Rejected.
- **Alert above 10 s:** normal lag is milliseconds, so 10 s means a follower is stopped or stuck.

### Node down during deploys

- **Alert after 3 minutes:** a restart doesn't trigger an alert, but a real failure is noticed only after 3–4
  minutes. Rejected.
- **Alert after 1 minute, also during deploys:** every deploy sends 7 node-down e-mails. Rejected.
- **Alert after 1 minute, muted during deploys:** the pipeline turns the e-mails off while the nodes restart.

## Decision

The store should answer **99.5%** of client requests without a 5xx in each hour, not counting deploys. A 404 or a 409
counts as a success, because it is a correct answer.

The coordinator's p99 should stay at or below **50 ms for reads** (read, exists) and **100 ms for writes** (create,
update, delete). The latency alert fires when p99 stays above this for **5 minutes**.

Each follower's lag should be at most **5 s** in 99% of minutes. The lag alert fires when it stays above **10 s for
1 minute**.

A replica counts as down after **1 minute** in which the coordinator could not reach it. The coordinator counts as
down after **2 minutes** without its metrics, so that values arriving late don't trigger it
([ADR 0013](0013-monitoring-stack.md)). So that deploys don't trigger alerts, the pipeline
turns the alert e-mails off before a deploy and back on after it, also when the deploy fails.

## Consequences

- Real faults trigger an alert within about 1–2 minutes (replica down, lag), about 3 minutes (coordinator down) or 5
  minutes (slow answers).
- Deploys don't trigger alerts, but a node that doesn't come back after a deploy is noticed only once e-mails are on
  again.
- While e-mails are off, the dashboard still shows the alarms; only the e-mails stop.
- The limits leave room for more load than in the benchmark, so a slowdown of up to about three times isn't reported.
- An outage shorter than 1 minute, or lag that stays under 10 s, gets no alert; it shows only on the dashboard.
- Not counting deploys hides their cost. This must be measured again once deploys are rolling.
