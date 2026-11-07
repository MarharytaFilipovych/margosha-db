# Proposal

## Why

Milestone 4 of the capstone is about running the store: seeing what it does, noticing when something breaks, and
recovering. In M3 the only way to learn that a replica is down or a follower is behind is to SSH in and call
`/replication/status` by hand, and logs from the coordinator and the replicas cannot be tied to one request. M4 adds
metrics, structured logs with trace IDs, a dashboard and alerts; gives every record a version and a Last-Write-Wins
(LWW) rule for choosing between replica copies; and lets AI agents use the store through an MCP server with clear
limits.

Scope of this change (only what the M4 brief lists):
- **No leader failover.** The brief does not ask for it. Leaders stay fixed by configuration, as in M3. "Responding to
  node failures" here means: the failure is visible on the dashboard, an alert fires, and the alert clears when the
  node recovers by itself (M3 already rebuilds replicas from their logs).
- **No new EC2 instances.** The Learner Lab allows 9, and the stack already uses 7 (8 with the YCSB client). The
  monitoring backend must fit the existing nodes or a managed service.
- **Optional parts of the brief are left out:** the alternative conflict-resolution strategy and the operational
  automation section.

## Decisions required before implementation (ADRs)

These are not decided in this proposal. Each is recorded in its own ADR, and the design lists the alternatives to
consider. Tasks that depend on them come after the ADRs.

Mandatory in the brief:
- **Which metrics are collected and why** (ADR 0012): latency p50 / p95 / p99, throughput and error rate for the
  coordinator and the replicas, plus the replication and cluster metrics the dashboard and alerts need; which of them
  are SLIs; how replication lag is measured; how the metrics are exposed.
- **SLO values** (ADR 0014): latency, error rate and replication lag targets, and the alert thresholds derived from
  them. Based on the M3 benchmark (READ p99 14.6 ms, UPDATE p99 28.3 ms).
- **What an agent may and may not do through MCP, and how it is authorised** (ADR 0015): the allowed tools and tables,
  what is refused, how the MCP server is built and run (transport), how its requests are authorised, and what happens
  when stored data contains a prompt injection.

Not mandatory, but the brief leaves a choice open:
- **Monitoring stack** (ADR 0013): where metrics are collected and stored, and where the dashboard and alerts live,
  without new instances (for example CloudWatch, Prometheus on an existing node, or a SaaS such as Datadog or Grafana
  Cloud).
- **Record versioning for LWW** (ADR 0016): timestamps or vector clocks (the brief offers both), how the version is
  assigned, and how ties are broken.
- **Structured logging and trace ID propagation** (ADR 0017): the log format, the trace ID header and its format, and
  how the ID is passed between nodes.

## What Changes

- **Metrics:** every node exposes request latency (p50 / p95 / p99), throughput and error rate per operation.
  Leaders expose each follower's replication lag in seconds. The coordinator exposes cluster health and shard
  distribution metrics. The exact list is ADR 0012's; they are collected by the backend chosen in ADR 0013.
- **Structured logs with trace IDs:** every node writes structured log lines. The coordinator gives each client
  request a trace ID (or keeps the one the client sent), returns it in a response header, and passes it to the replicas
  it calls for that request (ADR 0017).
- **Dashboard:** cluster health (nodes up, replicas per shard), latency / throughput / errors, replication lag, and
  shard distribution. Defined as code in the repository.
- **Alerts:** node down, p99 latency above the threshold, replication lag above N seconds (values from ADR 0014).
  Defined as code.
- **Record versioning and LWW:** every write gets a version as ADR 0016 decides, stored with the write and returned by
  replica reads. Quorum reads choose the copy that wins under LWW instead of the highest log position.
- **MCP server:** exposes EXISTS, CREATE, READ and DELETE as MCP tools with JSON input schemas and errors mapped from
  the store's error codes. Its limits, form and authorisation follow ADR 0015. It is checked with an MCP client
  (Claude) and shown at the defense.
- **Infrastructure:** changes only what the monitoring stack needs (ADR 0013), for example a metrics endpoint
  reachable by the collector, or alarms and a dashboard in the template. Replicas stay reachable only from inside the
  stack. No instance is added.

## Capabilities

### New Capabilities

- `observability`: the metrics each node exposes, structured logs and trace ID propagation, the dashboard, and the
  alerts with their thresholds.
- `conflict-resolution`: record versioning and the Last-Write-Wins rule used where replica copies of a record are
  compared.
- `mcp-agent-access`: the MCP server: its tools, their schemas and errors, what an agent is allowed and refused, and
  how its requests are authorised.

### Modified Capabilities

- `shard-replication`: log entries carry the write's version from ADR 0016; quorum reads return the LWW winner
  instead of the highest log version.
- `kv-api`: the API specification documents the trace ID header, the write's version in replica reads, and the
  metrics endpoint that is part of the public port.
- `app-infrastructure`: the monitoring stack fits within the existing instances, and only the access it needs is
  opened.

## Impact

- **Code (`app/`):** metrics in `node` (library and registry from ADRs 0012 and 0013); a request filter and a client
  interceptor for trace IDs; structured logging (ADR 0017); the write's version in `LogEntry`, `ReplicationEntry`,
  `VersionedRecord` and replica reads; an LWW comparison in `QuorumReader`; the MCP server as ADR 0015 decides.
- **API:** the client API is unchanged except for an optional request header and a response header for the trace
  ID. The internal replica API adds the write's version to log entries and replica reads. `openapi.yaml` is updated.
- **Infrastructure and pipeline:** depends on ADR 0013. At most security group rules, monitoring resources
  (dashboard, alarms, notification topic) or configuration on existing nodes. No new instances. A launch-template
  change replaces the nodes, so the change-set preview from the README is run first.
- **Dependencies:** a metrics library and registry for the chosen backend, and an MCP SDK (chosen in the ADRs; the
  versions are fixed when they are added).
- **Documentation:** ADRs 0012–0017, `m4/` notes (metrics, tracing a request, LWW, MCP with a client transcript),
  README M4 section, M4 AI critique, demo.
- **Not in this change:** leader failover or election, vector-clock siblings, read repair or anti-entropy, health and
  build-version endpoints (M5), the trace ID inside the replication log, central log shipping (unless ADR 0013 makes
  it free), alert runbooks, the optional alternative conflict strategy and operational automation, moving data when
  the ring changes (M5), rolling deploys (M5).
