# Design

## Context

M3 state (see the archived change `2026-11-03-setup-m3-durability` and `m3/replication.md`):
- One jar, two roles, Spring Boot 4.1 / Java 21. The coordinator (public subnet) routes writes to each shard's fixed
  leader and reads to R = 2 of its 3 replicas (`QuorumReader`, hedged, highest log version wins). Replicas are in
  private subnets with no internet; only the S3 gateway endpoint.
- The version of a record is its log position (`LogEntry.version`, ADR 0010). The log is one JSON line per entry with
  a CRC32 prefix (`ReplicationLog`, ADR 0009). Leaders know each follower's confirmed position
  (`/replication/status`).
- Logging: plain Logback text through Lombok `@Slf4j`, written to the journal and the serial console. Nothing ties
  log lines from different nodes to one request. No Actuator, no metrics.
- Outgoing HTTP uses `RestClientBuilder.withTimeout` (a plain `RestClient` on the JDK client), not Boot's
  auto-configured builder, so Boot's automatic client instrumentation and tracing propagation would not apply.
  `QuorumReader` asks replicas on virtual threads, so thread-local context (MDC) is not carried over by itself.
- Infrastructure: 7 `t3.micro` nodes (8 with the YCSB client), lab limit 9; **no new instances**. Every node's boot
  script is in the launch template. **Changing the launch template replaces all 7 nodes**: old and new nodes do not
  fit in 9 instances, and a replaced node loses its `replication.log` (README, "Before pushing a template change").
  Instance tags, parameters and separate resources (alarms, dashboards, security group rules) can be changed without
  replacing nodes.
- The pipeline and the README find events in the nodes' logs by text (`Running as`, `Registered replica`, `downloaded`).

## Goals / Non-Goals

**Goals:**
- Every requirement in `specs/observability` can be shown on AWS: dashboard panels, an alert firing and clearing for
  a stopped follower, one trace ID across the coordinator and the replicas it called.
- Low metric cardinality: no per-key or per-table tags, so cost and memory stay small on `t3.micro`.
- LWW lives in one comparison used everywhere copies are compared, unit-tested on its own.
- Every significant choice is made in an ADR; this design lists the options and implements what the ADRs decide.
- No launch-template change, if the chosen monitoring stack allows it.

**Non-Goals:**
- Distributed tracing with spans and a trace backend (OpenTelemetry collector, Jaeger, X-Ray). Trace IDs in logs are
  enough for the brief.
- Storing the trace ID in the replication log, so that followers' log lines carry it.
- Central log shipping and search, unless ADR 0013's backend gives it with no extra node.
- Health and build-version endpoints (M5).
- Read repair, anti-entropy, keeping vector-clock siblings, an alternative conflict strategy (optional in the brief).
- Automatic recovery actions triggered by alerts, incident tickets, backups, health reports (optional in the brief).
- An MCP tool for UPDATE, table registration or any admin endpoint.

## Mandatory ADRs

### ADR 0012: which metrics are collected and why
The ADR picks the list and gives the reason for each (which dashboard panel, alert or SLO uses it). A metric with no
consumer in the brief's dashboard, alerts or SLOs is left out. It also says which metrics are SLIs (the ones an SLO in
ADR 0014 is stated on) and why the others are not. Candidates:

| Group | Metric (Micrometer name, tags) | Brief item it serves |
|---|---|---|
| Requests, every node | `http.server.requests` (method, uri template, status) | latency p50 / p95 / p99, throughput |
| Errors, every node | `margosha.errors` (operation, code) counted in `ApiExceptionHandler` | error rate; tells 404 (normal) from 503 (bad) |
| Replication, leader | `margosha.replication.lag.seconds` (follower) | replication lag panel and alert |
| Cluster, coordinator | `margosha.cluster.replicas` (shard, role) | cluster health panel |
| Distribution | `margosha.ring.share` (shard), `margosha.shard.requests` (shard, operation), `margosha.replica.records` | shard distribution panel |

Questions and alternatives the ADR answers:
- *Scope:* only the RED metrics (rate, errors, duration) versus RED plus the replication and cluster metrics the
  dashboard and lag alert need.
- *Percentiles:* computed in the app (exact per node, cannot be combined across nodes) versus histogram buckets (can be
  combined, more series). This depends on ADR 0013: CloudWatch takes precomputed values, Prometheus-style backends
  compute percentiles from buckets.
- *How lag in seconds is measured:* the leader computes it for each follower (now − time of the oldest entry the
  follower has not confirmed; needs the write time from ADR 0016 in the entry), versus the follower reports its own
  lag (a stopped follower reports nothing, which is when lag matters), versus lag in entries only (the brief asks for
  seconds).
- *How metrics are exposed:* Spring Boot Actuator with Micrometer on the API port (the security groups already limit
  who reaches it) versus a separate management port (needs new security group rules), versus a push-only registry with
  no endpoint.
- The expected number of series.

### ADR 0013: monitoring stack (no new instances)
The question: where metrics are collected and kept, where the dashboard and alerts live, and how the private replicas'
metrics leave the VPC. Constraints: no new instance; replicas have no internet; the coordinator has internet;
`t3.micro` has 1 GiB (the JVM has `-Xmx512m`); a launch-template change replaces every node; secrets do not go in the
repository. Options:

| Option | How replicas' metrics get out | Dashboard and alerts as code | Node down when the node is dead | Costs and risks |
|---|---|---|---|---|
| **CloudWatch** (Micrometer CloudWatch registry in the jar) | Each node pushes itself, the replicas through a CloudWatch interface VPC endpoint in the private subnets (VPC stack change), or the coordinator reads and relays them | `AWS::CloudWatch::Dashboard`, `AWS::CloudWatch::Alarm`, SNS topic with e-mail, all in CloudFormation and deployed by the pipeline | Alarm on missing data per node (`TreatMissingData: breaching`) | No launch-template change. Must check that `LabRole` allows `PutMetricData`, alarms, dashboards and SNS. Percentiles per node only. Custom metrics are billed per metric-hour, so cardinality matters. Interface endpoint about $0.01/h per AZ |
| **Prometheus + Alertmanager + Grafana on the coordinator** | Coordinator scrapes the replicas (it can reach 8080) | Prometheus rules and Grafana provisioning files in the repository | `up == 0` per target. The coordinator itself needs an outside dead-man's switch | Needs installing in the boot script (launch-template change, nodes replaced) or out-of-band (SSM). Memory tight next to the JVM on 1 GiB. A bigger coordinator type replaces it too |
| **Prometheus agent on the coordinator → Grafana Cloud (free tier)** | Coordinator scrapes and remote-writes | Grafana dashboard JSON and alert rules in the repository, applied through Grafana's API | `absent()` / `up == 0` in Grafana Cloud, also for the coordinator (its data stops) | Agent install as above. API token kept outside the repository (GitHub secret → NoEcho parameter or SSM). External account |
| **Datadog agent on the coordinator** (student pack) | OpenMetrics check scraping all nodes | Dashboard and monitor JSON in the repository, applied through Datadog's API | Agent "no data" monitors | Agent install as above. API key as a secret. Sentry covers errors only, not node-down or lag, so at most a complement |

The ADR must state: the option chosen, how each of the 7 nodes is seen as down (coordinator included), where the
notifications go, which secret it needs and where that secret lives, whether it changes the launch template (and if
so, the migration: terminate first, accept or back up the data), where the dashboard and alert definitions live in
the repository, and its cost per lab hour.

### ADR 0014: SLO values
Based on the M3 benchmark (1,015 ops/s; READ p99 14.6 ms, UPDATE p99 28.3 ms; writes bounded by the 1 s ack
timeout) and the M3 failure checks. The ADR sets, with a reason for each:
- **Availability:** share of client requests that are not 5xx over a window (for example 99.5 % over 1 h; 404 and 409
  count as successes).
- **Latency:** READ / EXISTS p99 and write p99 targets with headroom over the benchmark (for example 50 ms and 100 ms),
  and the alert threshold and duration (for example p99 above the target for 5 min).
- **Replication lag:** target and alert threshold in seconds (for example alert at lag > 10 s for 1 min). It must be
  well above the normal lag (about one long-poll round trip) and well below the time that makes a follower useless
  for reads.
- **Node down:** how long a node may not report before the alert fires (for example 1 min), compared with a reboot
  (about 1–2 min, so a deploy may page; the ADR says whether that is acceptable or silenced during deploys). This also
  answers how long failure detection takes.
Alternatives: thresholds taken directly from the benchmark p99 (would page under normal load spikes) versus targets
with headroom; one latency SLO for all operations versus separate read and write targets (writes are about twice as
slow).

### ADR 0015: what an agent may do through MCP, and how it is authorised
The ADR decides:
- **Allowed:** the four tools (EXISTS, CREATE, READ, DELETE), possibly limited to tables in an allow-list. Whether DELETE
  is allowed without further limits, with the justification. Tool annotations: READ and EXISTS `readOnlyHint`, DELETE
  `destructiveHint`, so clients that ask before risky calls ask before a delete.
- **Refused:** UPDATE, table registration, replica registration, replication and status endpoints, anything not in the
  four tools. No bulk or scan operations exist, so an agent cannot list or dump a table.
- **Form and transport:** a separate module and jar that reuses the `api` module's `CoordinatorClient` and models,
  versus endpoints inside the coordinator. Stdio on the operator's machine (the client starts the server, no new
  listening port; the server calls the coordinator over the API port open to `OperatorCidr`) versus streamable HTTP
  (remote clients such as n8n can connect, but it is a new public endpoint that needs its own authentication, per the
  MCP authorisation spec OAuth 2.1). Which SDK (the official MCP Java SDK, `io.modelcontextprotocol.sdk`, or Spring AI's
  MCP server).
- **Authorisation:** (a) the MCP server is the only check, and the network (`OperatorCidr`) protects the coordinator.
  Simple, but anyone at the operator's IP can call the coordinator directly. (b) An agent API key that the MCP server
  sends and the coordinator checks, limiting requests carrying it to the four operations and allowed tables. This is
  enforced at the store, so it holds even if the MCP server is bypassed, but it adds code to the coordinator. (c)
  OAuth for HTTP transport, too heavy for this project. Where the key lives: an environment variable in the MCP
  client's config on the operator's machine; on the coordinator, a stack parameter (NoEcho) or SSM, never the repository.
- **Prompt injection in stored data:** a READ can return text written by anyone with write access. The ADR says what
  limits the damage (the tools available, the human approval clients ask for destructive calls, no update tool) and
  what is accepted as a limit.

### ADR 0016: record versioning for LWW
Options: **vector clocks** (detect concurrent writes, but need siblings or a merge rule, and with one leader per shard
there are no concurrent writers to detect, while the brief asks for LWW anyway); **wall-clock timestamps** (simple,
but a clock that goes back after a restart can make a newer write lose); **a counter (Lamport)** (monotonic, but a
leader that lost its log restarts low and its new writes would lose); **hybrid logical clock**
(`max(wall clock, last timestamp + 1)`, close to wall time and never going back). The ADR also fixes who assigns the
version and when, its resolution, the tie-break between equal versions, and how M3 log entries without one compare.

### ADR 0017: structured logging and trace ID propagation
The ADR decides:
- **Header and format:** an own `X-Trace-Id` header (accepted from the client if it matches a pattern, for example
  `[A-Za-z0-9-]{1,64}`, otherwise generated) versus W3C `traceparent`.
- **Propagation:** an own servlet filter (MDC, response header) and a `ClientHttpRequestInterceptor` added in
  `RestClientBuilder`, with the MDC captured and restored for `QuorumReader`'s virtual threads, versus Micrometer
  Tracing (only propagates through Boot's own `RestClient.Builder`, not ours; brings span machinery and needs a trace
  backend to be useful) versus OpenTelemetry.
- **Log format:** Spring Boot structured console logging (`logging.structured.format.console`, ECS or Logstash), which
  includes MDC values, versus a Logback JSON encoder; the fixed fields (`role`, `shard`, `replica`).

## Decisions

These implement what the ADRs decide; the alternatives are in the ADRs.

### 1. Metrics
Implemented as ADRs 0012 and 0013 decide. Custom meters are registered where the data already is: errors in
`ApiExceptionHandler`, lag in `ReplicationLeader`, record counts in `ReplicaStorage`, cluster gauges in
`ShardRegistryService` and the ring. Common tags on every meter: `role`, `shard`, `replica` (set from the existing
properties). Only the metrics endpoint the registry needs is exposed, if any.

### 2. Structured logs and trace IDs
Implemented as ADR 0017 decides, on both roles. The coordinator sets the trace ID for a client request; replicas take
it from the coordinator's request. Messages keep their text, so the pipeline's and README's substring searches still
match.

### 3. Versions in the log and LWW in one comparator
- `LogEntry`, `VersionedRecord`, `ReplicationEntry`, `ReplicaRecord`, `ReplicaExists` and `ReplicaAnswer` get the
  write's version from ADR 0016. It is assigned inside `ReplicaStorage`'s write lock (where the log position is
  assigned) and kept in the log line.
- One `LastWriteWins` comparator (ADR 0016's order and tie-break) replaces
  `Comparator.comparingLong(ReplicaAnswer::version)` in `QuorumReader`. Followers still apply entries in log order.
- The client API stays the same (records do not show their version).

### 4. MCP server
Implemented as ADR 0015 decides. Tools:

| Tool | Input | Calls | Result |
|---|---|---|---|
| `exists_record` | `table`, `partitionKey`, `sortKey?` | `GET …/exists` | `{exists}` |
| `read_record` | `table`, `partitionKey`, `sortKey?` | `GET …/records/{pk}` | the record |
| `create_record` | `table`, `record` (string map, key attributes included, as in the API) | `POST …/records` | the created record |
| `delete_record` | `table`, `partitionKey`, `sortKey?` | `DELETE …/records/{pk}` | `{deleted: true}` |

- `ApiException` → tool error result (`isError: true`) with `code` and `message`. 503 on a write adds "outcome
  unknown". Schema violations are caught before any call.
- With stdio, logs go to **stderr**, because stdout carries the protocol.
- Tests: an in-process coordinator (existing test fixtures) plus an MCP client, covering every scenario in
  `specs/mcp-agent-access`.

### 5. Dashboard and alerts as code
Kept where ADR 0013 decides: CloudFormation resources in `cfn/` (CloudWatch), or JSON/YAML under `monitoring/`
applied by the pipeline (Grafana, Datadog).

## Risks / Trade-offs

- [The monitoring option needs a launch-template change, so all 7 nodes are replaced: 14 > 9 instances, and logs are
  lost] → ADR 0013 weighs this. Prefer an option configured from the jar, tags and separate resources. If the change
  is needed anyway, plan it as the README says (terminate first, accept or back up the data) and run the change-set
  preview before pushing.
- [`LabRole` may deny CloudWatch, SNS, or the VPC endpoint] → The first ADR 0013 task checks the permissions with
  the CLI before deciding, and the result goes in the ADR.
- [Structured logs break the pipeline's and README's text searches] → Check every `grep` / `Select-String` in
  `pipeline.yml` and the README against a new log line, and fix any that break.
- [Metric cardinality: cost in CloudWatch, memory on `t3.micro`] → No table or key tags, and `uri` uses templates.
  ADR 0012 lists only metrics that a panel, alert or SLO uses.
- [Node-down alerts fire on every deploy (reboots)] → ADR 0014 sets the duration with the reboot time in mind and
  says whether deploy-time alerts are accepted.
- [Clock jumps on a node] → ADR 0016 states how its versioning behaves when a clock goes back or is far ahead.
- [MCP server bypassed: an agent with shell access calls the coordinator directly] → ADR 0015 either enforces at the
  coordinator or states this as an accepted limit.
- [stdout pollution breaks stdio MCP] → Logging configured to stderr only, with a test that the server's stdout
  holds only protocol messages.

## Migration Plan

1. ADRs 0012–0017 first. ADR 0013 decides whether any later step touches the launch template.
2. Application changes (metrics, logs, trace IDs, versions, LWW) ship in one version. Replicas start with empty data
   folders on the first M4 deploy, as ADR 0016 decides (M3 logs are not kept). All nodes are rebooted together by the pipeline, so M3 and M4 replicas run side by side only for a
   few seconds.
3. Monitoring resources (stack, dashboard, alerts) deploy through the pipeline. Preview the change set first and
   confirm no instance shows `Replacement: True/Conditional`.
4. Rollback: revert the commit and deploy through the pipeline. Data written by M4 may not be readable by the M3 jar;
   it is not migrated back. Monitoring resources can be deleted without touching the nodes.
5. The MCP server runs where ADR 0015 decides (with stdio, on the operator's machine, not on AWS).

## Open Questions

- Exact versions of the metrics registry and the MCP SDK, fixed when they are added. They do not change the design.
