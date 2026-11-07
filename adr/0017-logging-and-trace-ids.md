# ADR 0017: Structured logging and trace IDs

## Context

Every node writes plain-text logs, and nothing links the coordinator's lines to the replica lines for the same
request. We need structured logs, a trace ID that follows a request through the system, and a timeline that shows how
long each step took. The coordinator calls replicas through our own `RestClient` setup, and quorum reads run on
virtual threads, which don't take over the log context. Changing the boot script replaces all nodes.

## Alternatives considered

### Header

- **W3C `traceparent`:** a standard that tracing tools understand, but we have none, and its format is harder to read
  and create. Rejected.
- **Our own `X-Trace-Id`:** one value, easy to send with `curl`.

### How the ID gets to the replicas

- **OpenTelemetry Java agent:** automatic, but it needs a JVM flag in the boot script, so all nodes are replaced.
  Rejected.
- **Micrometer Tracing:** only works with Spring's own client setup, not ours. Rejected.
- **Our own filter and interceptor:** a few small classes in our code.

### Timeline and durations

- **A tracing tool (X-Ray, Jaeger):** shows a ready timeline, but needs an extra program or server on the nodes.
  Rejected.
- **One log line when a step starts and one when it ends:** twice as many lines, and the two must be matched up to
  get a duration. Rejected.
- **One log line when a step ends, with how long it took:** the start is the end time minus the duration.

### Log format

- **Plain text with the trace ID added:** not structured. Rejected.
- **A Logback JSON library:** one more dependency and XML setup. Rejected.
- **Spring Boot's built-in JSON logs:** one setting, and it already adds the log context.

### Where logs are kept

- **CloudWatch Logs:** one search over all nodes, but it needs either an agent (nodes replaced, a paid endpoint for
  replicas) or the coordinator sending replica logs on (much more code). Rejected.
- **On each node, as today, plus one helper command** that collects one trace from all nodes.

## Decision

The trace ID goes in our own **`X-Trace-Id`** header. The coordinator keeps the client's value if it is 1–64 letters,
digits or dashes, and otherwise makes a new one of 32 hex characters. Every response carries the ID, errors too.

A filter on every node takes the ID from the request (or makes one), adds it to the log context and the response,
and when the request is done logs one line with the method, path, status and `durationMs`. An interceptor in our
`RestClient` setup adds the ID to every call to a replica and, when the call is done, logs one line with the
replica, method, status and `durationMs`. The log context is copied into the parallel tasks of a quorum read.

Logs use Spring Boot's built-in JSON format, one line per event, with the time, level, logger, message, trace ID,
role, shard and replica ID. Messages keep their wording, so searches for their text still work.

A helper command run on the laptop reads every running node's console output (`aws ec2 get-console-output`, no SSH),
keeps the lines with the trace ID, and prints them as a timeline: start time, node, step, status and duration. Older
lines are read over SSH with `journalctl`.

## Consequences

- One command shows a request on every node it reached, in order, with how long each step took.
- Each duration is measured on one node, so it is exact; start times on different nodes can be a few milliseconds
  off, because their clocks differ.
- A call's duration minus the replica's own duration shows the time spent on the network.
- A follower that copies a write doesn't log the ID: it gets the entry from the leader, not from the request.
- No tracing library, nothing to install, no node replaced, no cost; one more log line per request and per call.
- The helper sees only about the last 64 KB of each node's log, so older requests need `journalctl`.
