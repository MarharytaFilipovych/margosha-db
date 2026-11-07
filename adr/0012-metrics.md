# ADR 0012: Which metrics we collect and why

## Context

The brief asks for latency (p50, p95, p99), throughput and error rate for the coordinator and the replicas; a
dashboard with cluster health, replication lag and shard distribution; and alerts for a node that is down, slow
answers (high p99) and lag. Metrics go to CloudWatch, which charges for every metric every hour, and each node works
out its own percentiles. Replicas have no internet, so the coordinator collects their metrics and sends them on.

## Alternatives considered

### How much to collect

- **Only requests, errors and latency:** cheapest, but no lag, health or distribution, which the brief asks for.
  Rejected.
- **Everything Micrometer offers (JVM, every URL, …):** hundreds of metrics, most of them never looked at. Rejected.
- **Only what a panel, an alert or an SLO uses:** every metric below is used somewhere.

### Replica latency

- **One latency per replica:** cheapest, but a slow write is hidden by many fast reads. Rejected.
- **One latency per operation on each replica:** followers serve only reads and exists checks. Shows which operation
  on which replica is slow.

### Replication lag in seconds

- **The follower reports its own lag:** a stopped follower reports nothing, exactly when we need the alert. Rejected.
- **The leader uses a time stored in the log entry:** only works if record versions are clock times, which they may
  not be. Rejected.
- **The leader remembers when it added each entry:** lag is the time since it added the oldest entry the follower
  hasn't confirmed yet, and 0 when the follower has everything.

### How the coordinator gets replica metrics

- **Prometheus format:** a standard, but one more library and text to parse. Rejected.
- **The coordinator times its own calls:** includes network time, and misses the leader's work for followers.
  Rejected.
- **A small JSON endpoint on each replica** with exactly the values below, read once a minute.

## Decision

We collect only metrics that a panel, an alert or an SLO uses, and nothing per table or per key. For every latency
we send the number of requests per minute (throughput) and p50, p95 and p99.

On the coordinator we track how long each operation takes (create, read, exists, update, delete). This feeds the
latency and throughput panels, the slow-answer alert and the latency SLO. We also count its errors by operation and
error code, for the error panel and the availability SLO. A 404 or 409 is a normal answer; only a 5xx is a failure.

On each replica we track how long each of its operations takes and count its errors by code, so the dashboard shows
which replica is slow or failing. The coordinator reads these from the replica's JSON endpoint, and also records
whether each replica is up (1) or down (0) for the node-down alert and the health panel.

Each leader reports how many seconds each of its followers is behind, for the lag panel, alert and SLO. The
coordinator also reports how many replicas are registered per shard and role, for the health panel, and the number of
records per replica and requests per shard, for the distribution panel.

The SLIs, the numbers our targets are set on, are: the share of client requests without a 5xx, the coordinator's p99
for reads and for writes, and replication lag. The coordinator sees every client request, so its numbers are what a
client gets. Replica numbers help find the cause, but are not SLIs: a slow replica doesn't hurt clients while the
other replicas answer in time.

## Consequences

- A slow operation can be traced to one replica.
- Percentiles from different nodes can't be added up, so the dashboard shows them per node.
- After a leader restarts, it doesn't know when older entries were added and uses the restart time, so lag looks
  smaller than it is until the follower catches up.
- No JVM or machine metrics: memory or CPU trouble shows only as slower answers. AWS's free EC2 CPU graph (every 5
  minutes) is still there for that.
- Every extra metric costs money every hour, so a new one must be used somewhere too.
