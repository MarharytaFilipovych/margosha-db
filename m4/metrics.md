# Metrics: what the store measures

This page lists every metric the nodes collect, its tags, what it shows and why it is there. 

Decisions behind it:
* [ADR 0012](../adr/0012-metrics.md) (which metrics, and only those a panel, an alert or an SLO uses)
* [ADR 0013](../adr/0013-monitoring-stack.md) (CloudWatch, with the coordinator sending the replicas' metrics on)

## How the metrics get to CloudWatch

- Every node keeps its metrics in memory, with Micrometer. Only metrics whose name starts with `margosha.` are kept.
  JVM, HTTP-server and other built-in metrics are dropped.
- Every metric has the node's tags: `role` (`coordinator`, `leader` or `follower`), and on a replica also `shard`
  (`shard-a`) and `replica` (`shard-a-1`). A metric's own tag of the same name wins over these.
- Each replica shows its metrics as JSON on `GET /metrics`. Replicas have no internet, so the coordinator reads that
  endpoint on every replica once a minute and sends the values on with its own.
- The coordinator sends everything to CloudWatch once a minute, in the namespace `MargoshaDB`. This is on only when
  it has a region, which on AWS comes from `/etc/margosha/stack.env`. On a laptop and in tests nothing is sent.
- Tags become CloudWatch dimensions, and CloudWatch adds a suffix to the name: `.value` for a gauge, `.count` for a
  counter. For a latency only the count (throughput) and the percentiles are sent. The sum, average and maximum are
  dropped, since nothing uses them.

A replica's `GET /metrics` answer example:

```json
{
  "metrics":[
    {"name":"margosha.requests","type":"COUNTER","tags":{"operation":"read","role":"leader","shard":"shard-a","replica":"shard-a-1"},"value":20.0},
    {"name":"margosha.requests.percentile","type":"GAUGE","tags":{"operation":"read","phi":"0.99","role":"leader","shard":"shard-a","replica":"shard-a-1"},"value":3.1,"unit":"milliseconds"},
    {"name":"margosha.replication.lag","type":"GAUGE","tags":{"follower":"shard-a-2","role":"leader","shard":"shard-a","replica":"shard-a-1"},"value":0.0,"unit":"seconds"},
    {"name":"margosha.replica.records","type":"GAUGE","tags":{"role":"leader","shard":"shard-a","replica":"shard-a-1"},"value":20.0}
  ]
}
```

The metric names that reached CloudWatch:
```bash
aws cloudwatch list-metrics --namespace MargoshaDB --query "Metrics[].MetricName" --output text
```

## The metrics

### `margosha.requests`: how long requests take

- **Kind:** timer, on the coordinator and on every replica.
- **Tags:** `operation` = `create`, `read`, `exists`, `update` or `delete`. Followers only have `read` and `exists`.
- **Shows:** how many requests of each operation the node handled, and how long they took at p50, p95 and p99 over the last minute in milliseconds.
- **In CloudWatch:** `margosha.requests.count` (throughput) and `margosha.requests.percentile.value` with the dimension `phi` = `0.5`, `0.95` or `0.99`.
- **Why:** the latency and throughput panels, the high-p99 alert and the latency SLO. The coordinator's p99 for reads
  and for writes are SLIs, because the coordinator sees every client request. The replicas' numbers show which
  replica is slow, but are not SLIs: one slow replica doesn't hurt clients while the others answer in time. Each
  operation has its own latency, so a slow write isn't hidden by many fast reads.

### `margosha.errors`: requests answered with an error

- **Kind:** counter, on the coordinator and on every replica.
- **Tags:** `operation` and `code`, the error code of the answer (`RECORD_NOT_FOUND`,`SHARD_UNAVAILABLE`, `INTERNAL_ERROR`, …).
- **Shows:** how many requests of each operation ended with each error code. A counter appears only after its first error.
- **In CloudWatch:** `margosha.errors.count`.
- **Why:** the error-rate panel and the availability SLO. A not-found or already-exists answer is normal, so only
  the 5xx codes (`INTERNAL_ERROR`, `SHARD_UNAVAILABLE`) count as failures. The share of the coordinator's requests
  without them is the availability SLI.

### `margosha.replication.lag`: how far a follower is behind

- **Kind:** gauge, in seconds, on each leader.
- **Tags:** `follower`, the follower's ID (`shard-a-2`).
- **Shows:** the time since the leader added the oldest log entry that this follower hasn't confirmed yet. 0 when the
  follower has everything. A stopped follower's lag keeps growing, because the leader measures it, not the follower.
- **In CloudWatch:** `margosha.replication.lag.value`.
- **Why:** the lag panel, the lag alert and the lag SLO (lag is an SLI).

### `margosha.replica.up`: is a replica reachable

- **Kind:** gauge, 1 or 0, on the coordinator.
- **Tags:** `role` (`leader` or `follower`), `shard`, `replica`.
- **Shows:** 1 when the coordinator could read the replica's metrics on its last try, 0 when it couldn't.
- **In CloudWatch:** `margosha.replica.up.value`.
- **Why:** the node-down alert for each replica and the health panel.

### `margosha.cluster.replicas`: replicas per shard

- **Kind:** gauge, on the coordinator.
- **Tags:** `shard`, and `role` = `leader` or `follower`.
- **Shows:** how many replicas of each role are registered with the coordinator. Usually it's 1 leader and 2 followers per shard.
- **In CloudWatch:** `margosha.cluster.replicas.value`.
- **Why:** the health panel.

### `margosha.shard.requests`: requests per shard

- **Kind:** counter, on the coordinator.
- **Tags:** `shard`.
- **Shows:** how many client requests the coordinator sent to each shard.
- **In CloudWatch:** `margosha.shard.requests.count`.
- **Why:** shows whether the ring spreads the load evenly.

### `margosha.replica.records`: records per replica

- **Kind:** gauge, on every replica.
- **Tags:** only the node's own (`role`, `shard`, `replica`).
- **Shows:** how many records the replica holds. Deleted records are not counted. The replicas of one shard should
  show the same number once the followers have caught up.
- **In CloudWatch:** `margosha.replica.records.value`.
- **Why:** shows whether the data is spread evenly over the shards.

## The dashboard

The CloudWatch dashboard `margosha-app` is the `MonitoringDashboard` resource in `cfn/margosha-app.yaml`, so the
pipeline creates and updates it with the stack. Nothing is edited by hand in the console. The stack output
`DashboardUrl` is its link. Panels, all per minute:

- **Cluster health:** `margosha.replica.up` for each replica (1 up, 0 down), and `margosha.cluster.replicas`
  (leaders and followers registered per shard). When the coordinator is down, every panel stops getting data.
- **Latency:** p50, p95 and p99 of `margosha.requests`, one panel each, for the coordinator (one line per operation)
  and for the replicas (one line per replica and operation).
- **Throughput:** the count of `margosha.requests`, for the coordinator and for each replica.
- **Errors:** `margosha.errors` by code, for the coordinator and for each replica, plus the share of 5xx answers
  (`INTERNAL_ERROR`, `SHARD_UNAVAILABLE`) in all requests. That share appears only after a first 5xx.
- **Replication lag:** `margosha.replication.lag` for each follower.
- **Shard distribution:** `margosha.shard.requests` per shard and `margosha.replica.records` per replica.

Every panel is a CloudWatch `SEARCH` on the metric's name and its dimensions, so a replica added later shows up
without editing the dashboard.

## The alerts

The alarms are also in `cfn/margosha-app.yaml`. Each one e-mails the SNS topic
`margosha-app-alerts` when it fires and when it is OK again. The address comes from the GitHub variable
`MARGOSHA_ALERT_EMAIL` (stack parameter `AlertEmail`) and must be confirmed once from the e-mail AWS sends.

- **Node down (7 alarms).** The coordinator: `margosha.cluster.replicas` (shard-a, leader) sent no data for 1 minute.
  The coordinator sends it every minute, so missing data counts as down. Each replica: `margosha.replica.up` below 1
  for 1 minute. Here missing data does not count, because it means the coordinator is down, which its own alarm
  reports.
- **High latency (5 alarms).** The coordinator's p99 per operation above 50 ms (read, exists) or 100 ms (create,
  update, delete) in each of 5 minutes in a row.
- **Replication lag (4 alarms).** A follower more than 10 s behind its leader for 1 minute.

During a deploy the pipeline turns the alarm e-mails off before it restarts the nodes, and on again at the end, also
when the deploy fails (step "Turn the alarm e-mails on again"). The alarms keep working meanwhile. 

## What is not collected

- No JVM, memory, CPU or HTTP-server metrics: nothing uses them, and every metric costs money each hour. Trouble there
  shows as slower answers. AWS's free EC2 CPU graph is still there.
- Nothing per table or per key.
- No sum, average or maximum latency, only the percentiles.

## Where the code is

| Class | Runs on | Job |
|---|---|---|
| `common.config.MetricsConfig` | every node | Keeps only `margosha.*` metrics. It makes the CloudWatch registry when a region is set |
| `common.config.CountAndPercentilesOnly` | coordinator | Drops the sum, average and maximum before sending |
| `common.metrics.RequestMetrics` | every node | `margosha.requests`, `margosha.errors`, `margosha.shard.requests` |
| `common.metrics.ReplicationLagMetrics` | leader | `margosha.replication.lag` |
| `common.metrics.ClusterMetrics` | coordinator, replicas | `margosha.cluster.replicas`, `margosha.replica.records`; the name of `margosha.replica.up` |
| `common.metrics.MetricsSnapshot` | replicas | Turns the replica's metrics into the `GET /metrics` answer |
| `coordinator.metrics.ReplicaMetricsRelay` | coordinator | Reads every replica's metrics once a minute: `margosha.replica.up` |
| `application*.properties` | every node | Namespace, send interval, relay interval, `role` / `shard` / `replica` tags |

| Case | Test |
|---|---|
| Read and update latencies are recorded; only `margosha.*` is kept; no actuator endpoint answers; a not-found read increments only the not-found count | `RequestMetricsTest` |
| A stopped follower's lag grows and returns to 0 after it catches up | `ReplicationLagTest` |
| 1 leader and 2 followers per shard, requests and records per shard (2 shards × 3 replicas) | `ClusterMetricsTest` |
| All 7 nodes' metrics are sent, no sum / average / maximum, a stopped replica goes to 0 | `ReplicaMetricsRelayTest` |
| A region in `stack.env` turns sending to CloudWatch on, no region keeps it off | `CloudWatchSwitchTest` |
