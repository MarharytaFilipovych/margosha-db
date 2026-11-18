# ADR 0021: Availability alarm

## Context

[ADR 0014](0014-slo-values.md) sets three SLOs: availability (99.5% of client requests without a 5xx in each hour),
latency and replication lag. Latency and lag have alarms. Availability is only a line on the dashboard.

The AI review ([ai_critique.md](../m4/ai_critique.md), finding 4) showed what that misses: a leader that answers 503
to every write (disk full, followers that can't confirm) keeps every alarm OK. The node is up, the lag may be 0, and
reads are fast.

## Alternatives considered

### When the alarm fires

- **No alarm, the dashboard only.** Nothing to add, but the failure above stays unnoticed. Rejected.
- **The 5xx share above 0.5% over 1 hour.** Exactly the SLO, but a total outage is reported up to an hour late.
  Rejected.
- **A count of 5xx answers, for example 10 in 5 minutes.** Simple, but under heavy load 10 is nothing, and at low
  load it may never be reached. Rejected.
- **The 5xx share above 5% over 5 minutes, with at least 20 requests.** 5% for 5 minutes uses most of the hour's
  budget of 0.5%. The minimum keeps one failed request at low load from setting it off.

### Which metrics the alarm uses

- **The existing error counters by operation and code.** No code change. But a CloudWatch alarm can't use `SEARCH`,
  so it must name each metric, and at most 10. The 5xx codes for 5 operations are 10 metrics, plus 5 request counts,
  so 15. Rejected.
- **A new counter of 5xx answers, all operations together** (`margosha.requests.failed`). One small counter in the
  code, and the alarm needs only 6 metrics: the counter and the 5 request counts.

## Decision

Every node counts its record requests answered with a 5xx in **`margosha.requests.failed`**, with no operation or code
tag. The alarm **`margosha-app-availability`** fires when, in 5 minutes, the coordinator had at least **20** record
requests and more than **5%** of them were answered with a 5xx. It emails the same topic as the other alarms, and the
pipeline mutes it during deploys like the others.

## Consequences

- A store that fails many requests is reported within about 5 minutes, also when every node is up.
- Below 20 requests in 5 minutes no failure is reported, even if all of them failed. That shows only on the
  dashboard.
- The share is counted inside the coordinator, so it doesn't see the time when the coordinator itself is down. The
  coordinator-down alarm reports that.
- One more metric and one more alarm, about $0.40 per month together.
- A template change, but only a new alarm resource. No node replaced.
