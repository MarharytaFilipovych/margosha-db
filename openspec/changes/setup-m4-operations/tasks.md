# Tasks

## 1. Decisions (before the code that depends on them)

- [x] 1.1 Check with the AWS CLI, using the lab credentials, what the monitoring options need from `LabRole`: CloudWatch `PutMetricData` / `PutMetricAlarm` / `PutDashboard`, SNS topic + e-mail subscription, a CloudWatch interface VPC endpoint, SSM parameters. Write the commands and answers in `../../../m4/checks`; verify every option in design.md "ADR 0013" is marked allowed or denied
- [x] 1.2 ADR 0013: monitoring stack in `adr/` (option chosen from design.md "ADR 0013", how each of the 7 nodes is seen as down including the coordinator, where notifications go, the secret it needs and where it lives, whether it changes the launch template and the migration if so, where the dashboard and alert definitions live, cost per lab hour); verify it uses the result of 1.1 and states that no instance is added
- [x] 1.3 ADR 0012: metrics collected and why in `adr/` (each metric with its tags and the panel, alert or SLO that uses it, which are SLIs and why the others are not, percentiles vs histograms for the chosen backend, how lag in seconds is measured, how metrics are exposed); verify every metric has a consumer in the brief's dashboard, alerts or SLOs and the list covers p50 / p95 / p99, throughput and error rate for the coordinator and the replicas
- [x] 1.4 ADR 0014: SLO values in `adr/` (availability, read and write p99 targets, replication lag target, node-down duration and so the detection time, and each alert's threshold and duration, with the M3 benchmark numbers as the basis and the alternatives from design.md); verify each alert in `specs/observability` has a threshold and a duration in it, and each SLO names its SLI from ADR 0012
- [x] 1.5 ADR 0015: MCP agent boundaries in `adr/` (allowed tools and tables, whether DELETE is allowed and why, what is refused, module and SDK, transport, how requests are authorised and where the credential lives, tool annotations, prompt injection in stored data, accepted limits); verify it answers "what may an agent do, what may it not, and how is it authorised", lists every refusal in `specs/mcp-agent-access`, and says what happens when an agent reads a prompt injection
- [x] 1.6 ADR 0016: record versioning for LWW in `adr/` (vector clocks vs timestamps vs counter vs hybrid logical clock, who assigns it and when, resolution, tie-break, behaviour when a clock goes back or is far ahead, how M3 entries without a version compare); verify it covers every scenario in `specs/conflict-resolution` and states what was given up
- [x] 1.7 ADR 0017: structured logging and trace ID propagation in `adr/` (header and format, propagation mechanism including virtual threads, log format and fixed fields; alternatives in design.md "ADR 0017"); verify it names where the trace ID is set, how it reaches the replicas, and how an invalid client value is handled

## 2. Record versioning and LWW (after ADR 0016)

- [x] 2.1 Add the write's version to `LogEntry` and the log line, assigned by the leader inside the write lock as ADR 0016 decides, kept across a rebuild; verify `ReplicationLogTest` / `ReplicaStorageTest` cases for a version kept across a rebuild, plus the cases ADR 0016 names
- [x] 2.2 Carry the version through `ReplicationEntry`, `ReplicaRecord`, `ReplicaExists`, `VersionedRecord` and `ReplicaAnswer`; verify the model serialization round-trip tests and the shard controller tests show the version, and a follower's entry equals the leader's
- [x] 2.3 Add the `LastWriteWins` comparator and use it in `QuorumReader` instead of the highest log version; verify unit tests for a later version, a winning delete, equal versions (same winner in both orders), and a replica that never received the record
- [x] 2.4 In-process test for `specs/conflict-resolution`: two clients updating one key concurrently (every replica converges to the same winner); verify it passes with `./mvnw verify`
- [x] 2.5 Write `m4/lww.md`: what the version is, who assigns it, where LWW is applied, and why the store has no concurrent writers today; verify its examples match the tests
- [x] 2.6 Make the replicas start with empty data folders on the first M4 deploy, as ADR 0016 decides (M3 logs have no versions and are not read); verify on AWS that every replica starts and registers after the deploy

## 3. Structured logs and trace IDs (after ADR 0017)

- [x] 3.1 Switch every role to the structured log format from ADR 0017 with `role`, `shard`, `replica` fields; verify a test that a log line parses with those fields and the message
- [x] 3.2 Trace ID filter on both roles as ADR 0017 decides (accept a valid client value, otherwise generate one; MDC; response header, errors included; one line with method, path, status and `durationMs` when the request ends); verify `CoordinatorControllerTest` cases for generated, kept, and invalid IDs, an error response with the header, and the end-of-request line with its duration
- [x] 3.3 Propagate the trace ID to the replicas the coordinator calls, including `QuorumReader`'s virtual threads, and log one line per replica call with the replica, status and `durationMs`; verify an in-process test (2 shards × 3 replicas, log capture) that one write's trace ID appears on the coordinator and the leader, one read's on the replicas that answered, and that each call's duration is at least the replica's own and at most the coordinator's request duration
- [x] 3.4 Check every log search in `.github/workflows/pipeline.yml` and the README (`Running as`, `Registered replica`, `downloaded`, …) against the new log lines and fix the ones that break; verify the pipeline's wait step passes on a test deploy
- [x] 3.5 Update `openapi.yaml` with the trace ID header on every operation and the version in replica reads and log entries; verify `OpenApiSpecTest` passes and the YCSB round-trip test passes unchanged (client API unchanged)
- [x] 3.6 Write `m4/tracing.md`: the header, the log format, and the helper command (Windows and macOS) that collects one trace ID's lines from every running node's console output and prints them as a timeline (start time, node, step, status, duration); verify one run of it shows a write's coordinator request, call to the leader and the leader's handling in order with durations

## 4. Metrics (after ADRs 0012 and 0013, and group 2 if lag uses the write's time)

- [x] 4.1 Add the metrics library and registry from ADRs 0012 and 0013; expose only what the registry needs; common tags `role` / `shard` / `replica`; request latencies as ADR 0012 decides; verify a test that READ and UPDATE latencies are recorded after requests and that no other actuator endpoint answers
- [x] 4.2 Error counter by operation and code in `ApiExceptionHandler`; verify a test that a not-found READ increments only the not-found count
- [x] 4.3 Replication lag in seconds per follower as ADR 0012 decides; verify an in-process test that a stopped follower's lag grows and returns to 0 after it catches up
- [x] 4.4 Cluster health and shard distribution metrics from ADR 0012; verify a test with 2 shards × 3 replicas showing 1 leader + 2 followers per shard and a distribution value per shard
- [x] 4.5 If ADR 0013 needs it, the way replica metrics reach the backend (relay through the coordinator, or VPC endpoint configuration); verify on a local multi-node run (or on AWS) that all 7 nodes' metrics arrive
- [x] 4.6 Write `m4/metrics.md`: each metric, its tags, what it shows, and the ADR 0012 reason; verify it lists the same metrics the code registers

## 5. MCP server (after ADR 0015)

- [x] 5.1 Add the MCP server in the form and with the SDK and transport ADR 0015 decides (a Spring AI version that supports the project's Spring Boot 4.1), configuration from environment variables, no banner and logging away from the protocol stream; verify `./mvnw verify` builds it and, with stdio, a test shows stdout carries only protocol messages
- [x] 5.2 Implement `exists_record`, `read_record`, `create_record`, `delete_record` with input schemas, descriptions and tool annotations; map `ApiException` to tool errors with code and message, and "outcome unknown" for 503 on writes; verify tests against an in-process coordinator for the tool list, create→read, delete→exists, not found, already exists, unavailable, and schema violations
- [x] 5.3 Implement the limits and the authorisation from ADR 0015 (in the MCP server and, if the ADR decides so, in the coordinator); verify tests for a table not allowed, missing and wrong credentials (nothing created), and no log line containing the credential
- [x] 5.4 Write `m4/mcp.md`: what the server offers, how to configure Claude (Desktop or Code) for it, the credential setup, and the ADR 0015 boundaries; verify the documented config starts the server from a clean checkout

## 6. Dashboard and alerts (after ADRs 0013 and 0014, and group 4)

- [x] 6.1 Define the dashboard as code (cluster health, latency p50 / p95 / p99, throughput, error rate for coordinator and replicas, replication lag, shard distribution) where ADR 0013 decides; verify it is created from the repository with no manual edits and every panel shows data from a local or AWS run
- [x] 6.2 Define the alerts as code (node down for each of the 7 nodes, p99 latency, replication lag) with ADR 0014's thresholds and durations, and the notification target; verify the definitions validate (`cfn-lint` or the backend's own check) and list all three alert kinds
- [x] 6.3 Add any AWS resources and access the monitoring stack needs to the templates (no new instance, replicas unreachable from outside, secrets outside the repository) and to the pipeline; preview the change set and verify no instance shows `Replacement: True` or `Conditional`

## 7. Checks on AWS (after groups 3–6)

- [x] 7.1 Deploy through the pipeline; verify 7 instances (8 with `BenchClient=true`), all 6 replicas registered, metrics from all 7 nodes in the backend, the dashboard showing data, and a replica's metrics endpoint unreachable from outside the VPC
- [x] 7.2 Follower down and back: stop one follower while writing for longer than the lag threshold; verify the dashboard shows it down with growing lag, the node-down and lag alerts fire and notify, and both resolve by themselves after it is started and catches up. Write the commands, timings and screenshots into `../../../m4/checks`
- [x] 7.3 Coordinator down: stop the coordinator; verify its node-down alert fires (the monitoring does not depend on it to report its own absence, or ADR 0013's documented limit is shown)
- [x] 7.4 High latency: drive p99 above the threshold (for example YCSB with more threads, or a lowered threshold for the test); verify the high-latency alert fires and clears, and record how it was triggered
- [ ] 7.5 Tracing: write and read a record and follow its trace ID through the coordinator and the replicas it called using `m4/tracing.md`; verify the lines and add them to `../../../m4/checks`
- [ ] 7.6 LWW on AWS: write the same key from two clients at once and read it from every replica; verify all replicas hold the same winner, and add the output to `../../../m4/checks`
- [ ] 7.7 MCP with a client: connect Claude to the MCP server against the deployed store, use all four tools, try a refused action (table not allowed or missing credential) and ask the agent to update a record (no tool); verify the session transcript is saved in `m4/mcp_session.md`

## 8. End-to-end checks

- [ ] 8.1 Fresh deploy from the pipeline into an account where only `margosha-vpc` exists; verify all 7 nodes come up, every replica registers, metrics, dashboard and alerts exist, and the store works, with no manual console steps beyond those ADR 0013 documents
- [ ] 8.2 Run `openspec validate setup-m4-operations --strict`; verify it passes

## 9. Deliverables (apply to every milestone)

- [ ] 9.1 README: M4 section with how to bring it up (including monitoring and the MCP server), how to verify it, and what changed since M3; verify the commands run as written in a new lab session
- [ ] 9.2 Demo, including the MCP session with a client
- [ ] 9.3 Transcript of the AI critique of the architecture in `m4/ai_critique.md`

## Workflow follow-up

- Archive the change after the milestone is reviewed.
- After archiving, fill in the `Purpose` of the new main specs if any is left as TBD, and fix links to `openspec/changes/setup-m4-operations/` that point at the old path.
