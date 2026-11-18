# Spec Delta

## Purpose

Makes the running store observable: the metrics every node exposes, structured logs with trace IDs that follow a
request from the coordinator to the replicas it calls, the dashboard, and the alerts for failures and slow or lagging
nodes.

## ADDED Requirements

### Requirement: Request metrics on every node
The coordinator and every replica SHALL expose, per operation, the number of requests handled, the number that failed
(split by error code), and the latency distribution from which p50, p95 and p99 can be read. The metrics collected
SHALL be the ones chosen in the metrics ADR.

#### Scenario: Latency percentiles after requests
- **WHEN** clients make READ and UPDATE requests through the coordinator
- **THEN** the coordinator's metrics show, separately for READ and UPDATE, a request count that grew by the number of requests, and p50, p95 and p99 latencies

#### Scenario: Errors are counted by code
- **WHEN** a client reads a key that has no record
- **THEN** the coordinator's failed-request count for READ with the not-found code grows by one, and its other error counts do not change

#### Scenario: Replica metrics
- **WHEN** the coordinator sends requests to a replica
- **THEN** that replica's own metrics show the requests it handled, their latencies and their errors

### Requirement: Replication lag metric
The store SHALL expose, for each follower, its replication lag in seconds, measured as chosen in the metrics ADR, and
SHALL show 0 when the follower has every entry.

#### Scenario: Follower falls behind
- **WHEN** a follower is stopped and records are written to its shard for 30 seconds
- **THEN** that follower's lag reaches at least 30 seconds

#### Scenario: Follower caught up
- **WHEN** the follower is started again and has received all missed entries
- **THEN** its lag returns to 0 seconds

### Requirement: Cluster health and shard distribution metrics
The store SHALL expose the number of registered replicas per shard and role, and the shard distribution metrics chosen
in the metrics ADR.

#### Scenario: Shard distribution
- **WHEN** both shards have 3 registered replicas and clients write records with different keys
- **THEN** the metrics show 1 leader and 2 followers per shard, and the distribution metrics show a value for each shard

### Requirement: Node availability is detectable
The monitoring backend chosen in the monitoring stack ADR SHALL be able to tell, for each of the 7 nodes, whether it
is up, also when the node itself has stopped and cannot report anything.

#### Scenario: Stopped replica
- **WHEN** a replica's instance is stopped
- **THEN** within the time set in the SLO ADR the monitoring backend shows that node as down, while the other nodes stay up

#### Scenario: Replica that never registers after the coordinator starts
- **WHEN** the coordinator starts and an expected replica does not register within the time set in the expected-replicas ADR
- **THEN** the monitoring backend shows that replica as down, while it shows no value for it before that time

### Requirement: Structured logs
Every node SHALL write its log as structured lines in the format chosen in the logging ADR, with at least the time,
level, node role, replica ID (on replicas), logger, message, and the trace ID when the line belongs to a request.

#### Scenario: Log line format
- **WHEN** a replica handles a request and writes a log line about it
- **THEN** the line can be parsed in the chosen format and includes the time, level, role, replica ID, message and the request's trace ID

#### Scenario: Log lines kept in a file
- **WHEN** a node handles a request
- **THEN** the same structured line is also written to the node's log file in its data folder, which is rotated by size as the log-file ADR decides

### Requirement: Trace ID for every client request
The coordinator SHALL give every client request a trace ID, or keep the one the client sent in the trace ID header if
it is valid as the logging ADR defines, and SHALL return it in the same header of the response, errors included.

#### Scenario: Trace ID generated
- **WHEN** a client sends a request without a trace ID header
- **THEN** the response carries a new trace ID in the header, and the coordinator's log lines for that request carry the same ID

#### Scenario: Trace ID kept
- **WHEN** a client sends a valid trace ID in the header
- **THEN** the response and the coordinator's log lines for that request carry the client's trace ID

#### Scenario: Invalid trace ID replaced
- **WHEN** a client sends a trace ID header that is not valid
- **THEN** the coordinator ignores it and uses a new trace ID

### Requirement: Trace ID follows the request through the store
The coordinator SHALL pass the trace ID to every replica it calls for the request, and those replicas SHALL log their
handling of the request with it.

#### Scenario: Following a write
- **WHEN** a client creates a record
- **THEN** searching the logs of the coordinator and the shard's leader for the response's trace ID finds the request on the coordinator and the write on the leader

#### Scenario: Following a read
- **WHEN** a client reads a record with R = 2
- **THEN** the logs of the replicas that answered carry the response's trace ID

### Requirement: Timeline of a request
Every node SHALL write one log line when it finishes handling a request, and the coordinator one line when each call
to a replica ends, each with the trace ID, the step, its status and its duration in milliseconds. The repository SHALL
provide a command that collects the lines of one trace ID from all running nodes, from their console output or from
their log files, and prints them in order of start time with their durations.

#### Scenario: Timeline of a write
- **WHEN** a client creates a record and an operator runs the command with the response's trace ID
- **THEN** it prints the coordinator's request, its call to the leader and the leader's handling of the write, each with a start time and a duration, in order of start time

#### Scenario: Durations are consistent
- **WHEN** the coordinator calls a replica for a request
- **THEN** the duration of the call is at least the replica's own duration for that request, and the coordinator's request duration is at least the call's duration

### Requirement: Dashboard
A dashboard SHALL show cluster health (each node up or down, replicas per shard and role), latency p50 / p95 / p99,
throughput and error rate for the coordinator and the replicas, replication lag per follower, and shard distribution.
It SHALL be defined as code in the repository.

#### Scenario: Dashboard from the repository
- **WHEN** the dashboard is created from its definition in the repository on a freshly deployed stack
- **THEN** it shows all of these panels with data from the running store, with no manual panel editing

#### Scenario: Failure visible on the dashboard
- **WHEN** a follower is stopped while records are written
- **THEN** the dashboard shows that node as down and its replication lag growing

### Requirement: Alerts for critical events
Alerts SHALL fire for a node that is down, for p99 latency above its threshold, for replication lag above its
threshold in seconds, with the thresholds and durations set in the SLO ADR, and for a share of 5xx answers above
the threshold set in the availability-alarm ADR. Alerts SHALL be defined as code in the
repository and SHALL notify the operator.

#### Scenario: Node down alert
- **WHEN** a replica is stopped for longer than the node-down duration
- **THEN** the node-down alert fires for that replica and the operator is notified

#### Scenario: Coordinator down alert
- **WHEN** the coordinator is stopped for longer than its node-down duration
- **THEN** its node-down alert fires without any value from the coordinator, the replicas' node-down alerts do not
  fire, and the operator is notified

#### Scenario: Replication lag alert
- **WHEN** a follower is stopped while records are written to its shard for longer than the lag threshold
- **THEN** the replication-lag alert fires for that follower

#### Scenario: High latency alert
- **WHEN** the coordinator's p99 latency for an operation stays above the threshold for the alert's duration
- **THEN** the high-latency alert fires for that operation

#### Scenario: Availability alert
- **WHEN** the share of the coordinator's answers that are 5xx stays above the threshold for the alert's duration, with at least the minimum number of requests
- **THEN** the availability alert fires

#### Scenario: Failed answers are counted
- **WHEN** the coordinator answers a record request with a 5xx
- **THEN** its count of failed requests grows by one, while a 4xx answer leaves it unchanged

#### Scenario: Alert clears after recovery
- **WHEN** the stopped replica is started again and catches up with its leader
- **THEN** the node-down and replication-lag alerts for it are resolved without manual action
