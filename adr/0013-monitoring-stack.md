# ADR 0013: Monitoring stack

## Context

M4 needs metrics from all 7 nodes, a dashboard, and alerts (node down, slow answers, replication lag). Limits:
- No new instance: the lab allows 9, and we use 7 (8 with YCSB).
- Replicas have no internet, so only the coordinator can reach AWS services.
- `t3.micro` has 1 GiB of memory, and Java already takes 512 MB.
- Changing the launch template replaces all 7 nodes and loses their logs (M3).
- The whole course has $50, and anything AWS bills while the lab is stopped costs money every day (M1).

Both lab roles may use everything the options below need ([permissions.md](../m4/checks/permissions.md)).

## Alternatives considered

### Where monitoring runs

- **Prometheus and Grafana on the coordinator:** free, but tight in 1 GiB, needs an install (all nodes replaced), and
  stops with the coordinator, so nothing would tell us the coordinator is down. Rejected.
- **An agent on the coordinator sending to Grafana Cloud or Datadog:** the same install, plus an outside account and
  its key. Rejected.
- **CloudWatch:** a library in our jar sends the metrics, nothing to install. The dashboard, alarms and e-mail are
  part of the CloudFormation stack. It runs outside our nodes, so it notices when the coordinator stops.

### How replica metrics get out

- **A CloudWatch VPC endpoint:** billed every hour it exists, also between lab sessions. M1 already rejected endpoints
  for this. Rejected.
- **The endpoint only during lab sessions:** almost free, but a manual on and off step every session, and one
  forgotten "off" keeps billing. Rejected.
- **The coordinator sends them on:** it can already reach the replicas on port 8080. Free, but more code, and replica
  metrics stop while the coordinator is down.

## Decision

We use **CloudWatch, and the coordinator sends the replicas' metrics on.** Every node uses the Micrometer CloudWatch
library, set up in the jar. Once a minute the coordinator reads each replica's metrics and sends them, marked with the
replica's ID.

When the coordinator stops, its metrics stop too, and its alarm treats "no data" as "down". For each replica, the
coordinator sends an `up` value of 1 or 0, and that alarm fires on 0. For this alarm "no data" does not count, so
when the coordinator is down we get one alarm, not seven.

The check on AWS ([coordinator_down.md](../m4/checks/coordinator_down.md)) showed that "no data" alone is slow: while
CloudWatch still finds a value from the last few minutes, it uses that value, so the alarm fired only after 7.5
minutes. Options:
- **Accept the delay:** nothing to change, but a stopped coordinator is noticed only after 7–8 minutes. Rejected.
- **Count each minute without a value as 0:** CloudWatch's `FILL(m, 0)` turns an empty minute into 0, so the alarm
  sees the gap at once. A minute whose values arrive late would also be 0, so the alarm waits for 2 such minutes.
We count each minute without a value as 0, and the alarm fires after 2 minutes of zeros.

Alarms send e-mails through SNS. The e-mail address comes from a GitHub variable, so it isn't in the repository, and
it is confirmed once from the inbox. No secret is needed, because the nodes use `LabRole`. The dashboard, alarms and
e-mail topic are in `cfn/margosha-app.yaml`. **The launch template doesn't change and no instance is added.**

## Consequences

- Nothing to install, no node replaced, no data lost.
- The number of metrics is the main cost.
- While the coordinator is down, replica metrics and alarms stop, and only the coordinator's alarm fires. Accepted:
  without the coordinator, clients can't use the store anyway.
- A stopped replica is noticed within 1 minute plus the alarm's wait time. A stopped coordinator is noticed after
  about 3 minutes: 2 minutes without values, plus the time until the next check.
- Each node works out its own percentiles, so the slow-answer alarm watches the coordinator, which sees every client
  request.
