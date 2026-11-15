# ADR 0018: Read latency limit at low load

## Context

[ADR 0014](0014-slo-values.md) set the read limit to **50 ms** for the coordinator's p99. The alert goes off when p99
stays above that for 5 minutes. The 50 ms was based on the M3 benchmark, where the store handled about 1,000 requests
a second and reads had a p99 of about 15 ms. Before M4, nobody had seen this metric on AWS.

The checks showed how long reads take when the store is quiet:

- With about 4–5 reads a second from one client, the coordinator's read p99 was 33–75 ms per minute. It was above
  50 ms in 3 of 5 minutes. The p50 was 11–23 ms and the p95 21–31 ms ([high_latency.md](../m4/checks/high_latency.md)).
- A normal read takes about 28 ms on the coordinator, but only 2–4 ms on the replicas. The first read after a quiet
  period can take more than 100 ms ([traces.md](../m4/tracing/traces.md)).
- All nodes had plenty of CPU credits, and nothing was broken.

So with light, steady traffic, the read alert almost goes off even though nothing is wrong. It didn't go off only
because 2 of the 5 minutes were below 50 ms. Writes don't have this problem: their limit is 100 ms, and at low load
their p99 was about 20 ms after the first minute.

## Alternatives considered

- **Keep 50 ms and just write down the risk.** Nothing changes. But light traffic could set off a read alert with
  nothing wrong, and alerts that go off for no reason teach people to ignore them.
- **Raise the read limit to 75 ms.** This would catch a replica that is slow but still answers: reads then wait for
  the 50 ms hedge, ask the third replica, and take about 80 ms. But the slowest normal minute was already 75.2 ms, with
  nothing wrong. So there is no room to spare, and false alerts are still possible. Rejected.
- **Raise the read limit to 100 ms, like writes.** Every minute seen at low load (75 ms at most) stays below it. A
  replica that stops answering is still noticed, because its own node-down alert goes off within about 1–2 minutes.
  A real slowdown is reported only when reads get about 3–4 times slower than normal at low load.
- **Watch p95 instead of p99, and keep 50 ms.** At low load p95 was 21–31 ms, so a few slow reads a minute don't
  change it. But ADR 0012 and ADR 0014 use p99 to measure speed, so both would have to change. And if only 1 to 5 in
  100 reads were slow, nobody would be told.
- **Make reads faster.** The trace shows that one of the 2 parallel calls waits about 10 ms longer, and the coordinator
  needs another 10 ms after the answers. Finding and fixing this is performance work that M4 doesn't ask for, and the
  limit would still have to allow for the first read after a quiet period. Rejected for M4.

## Decision

We **raise the read limit to 100 ms**, the same as for writes. The coordinator's p99 for reads (read, exists) should
stay at or below 100 ms, and the alert goes off when it stays above that for 5 minutes. This replaces the 50 ms in
ADR 0014.

## Consequences

- At low load, normal reads stay below the limit, so a read alert means reads really got slow.
- A replica that is slow but still answers (reads of about 80 ms through the hedge) doesn't set off this alert. It can
  still be seen on the dashboard, in the replica latency panels.
- Reads and writes have the same limit, so the alarms are easier to explain.
- The limit is based on one short run at low load. If normal minutes get close to 100 ms, it has to be looked at
  again.
- The alarms `margosha-app-read-p99` and `margosha-app-exists-p99` in `cfn/margosha-app.yaml` change. No instance is
  replaced.
