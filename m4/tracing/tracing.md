# Tracing: following one request through the store

This page describes how M4 follows one client request from the coordinator to the replicas it calls, and how to see
it as a timeline with durations. Decision behind it: [ADR 0017](../../adr/0017-logging-and-trace-ids.md) (own
`X-Trace-Id` header, Spring Boot JSON logs, logs stay on each node, one helper command).

## The trace ID

Every request gets a trace ID in the `X-Trace-Id` header:

- A client may send its own: 1 to 64 letters, digits or dashes (for example `my-test-1`). It is kept.
- Without one, or with any other value, the node makes a new one: 32 hex characters
  (`3f1c9a2e4b7d4e1a9c3f2a8b6d0e5f71`).
- Every response carries it, errors included, so the client can always see which ID its request got.
- The coordinator sends it on to every replica it calls for the request, quorum reads included, and each replica keeps
  it. One ID therefore finds the request in the logs of every node it reached.

Followers copying a write from their leader don't log the ID: they get the entry from the leader's log, not from
the request.

Send a request with your own trace ID and see it in the answer:

Windows (PowerShell):
```powershell
$r = Invoke-WebRequest -Method Post -Uri "$API/tables/users/records" -ContentType 'application/json' -Headers @{ 'X-Trace-Id' = 'my-test-1' } -Body '{"user_id":"42","name":"Anna"}'
$r.Headers['X-Trace-Id']
```

macOS:
```bash
curl -s -i -X POST "$API/tables/users/records" -H 'Content-Type: application/json' -H 'X-Trace-Id: my-test-1' -d '{"user_id":"42","name":"Anna"}' | grep -i x-trace-id
```

## The log format

Every node writes one JSON object per line (Spring Boot's built-in `logstash` format):

```json
{"@timestamp":"2026-11-08T19:30:12.917Z","message":"POST /tables/users/records 201 in 51 ms","logger_name":"com.margosha.node.common.filter.TraceIdFilter","thread_name":"http-nio-8080-exec-3","level":"INFO","traceId":"my-test-1","method":"POST","path":"/tables/users/records","status":201,"durationMs":51,"role":"coordinator"}
```

Fields on every line:

- `@timestamp` (UTC on the nodes), `level`, `logger_name`, `thread_name`, `message`.
- `role`: `coordinator`, `leader` or `follower`. A replica also has `shard` (`shard-a`) and `replica` (`shard-a-1`).
- `traceId`: on every line written while the node handles a request.

Two kinds of lines build the timeline:

- **A node finished a request** (every node): `method`, `path`, `status`, `durationMs`. Message:
  `POST /tables/users/records 201 in 51 ms`.
- **The coordinator's call to one replica ended**: `toReplica`, `method`, `path`, `status` (or `error` when the
  replica didn't answer), `durationMs`. Message: `Call to shard-b-1: POST /tables/users/records/42 201 in 45 ms`.

Each step is logged once, when it ends, with its duration, so its start is the line's time minus `durationMs`.
Requests the nodes send each other all the time (followers polling the log, replicas re-registering every 10 s) log
these lines at DEBUG, which is not shown, so they don't fill the console between client requests. Expected client
errors (404, 409, 400) are one WARN line without a stack trace.

## The timeline command

The helper reads the console output of every running node from your laptop (`aws ec2 get-console-output`, no SSH),
keeps the lines with the trace ID and prints them in order of start time:

Windows (PowerShell):
```powershell
.\m4\tracing\commands\trace.ps1 -TraceId my-test-1
```

macOS, needs `jq` (`brew install jq`):
```bash
./m4/tracing/commands/trace.sh my-test-1
```

Columns: start time (UTC), milliseconds since the first step, the step's duration, its status, the node, and the step.
A call from the coordinator to a replica is indented with `->`.

A write (from the in-process test with 2 shards × 3 replicas, `TraceAcrossNodesTest`; the test's table name
`users-<random>` is shortened to `users`):

```
START (UTC)     +MS     MS  STATUS  NODE         STEP
19:30:12.866      0     51  201     coordinator  POST /tables/users/records
19:30:12.870      4     45  201     coordinator    -> call shard-b-1: POST /tables/users/records/42
19:30:12.874      8     40  201     shard-b-1    POST /tables/users/records/42
```

The coordinator's request took 51 ms; 4 ms of it were spent before calling the leader (finding the table and the
shard), the call to the leader took 45 ms, and the leader's own handling 40 ms, which includes waiting for a
follower to confirm the write (W = 2). The 5 ms between the call and the leader's handling is the network and HTTP.

A read (R = 2):

```
START (UTC)     +MS     MS  STATUS  NODE         STEP
19:30:15.898      0     29  200     coordinator  GET /tables/users/records/42
19:30:15.903      5     19  200     coordinator    -> call shard-b-2: GET /tables/users/records/42
19:30:15.903      5      1  200     shard-b-1    GET /tables/users/records/42
19:30:15.903      5      2  200     coordinator    -> call shard-b-1: GET /tables/users/records/42
19:30:15.907      9     14  200     shard-b-2    GET /tables/users/records/42
```

Both replicas were asked at the same time (parallel quorum read); shard-b-1 answered in 1 ms, shard-b-2 in 14 ms,
and the coordinator answered after both.

Both scripts can also read saved console output instead of AWS, one file per node named `<node>.log`
(`-FromDir <folder>` in PowerShell, a second argument in the shell script). That is how the examples above were made
from the test's output.

## Limits

- **Durations are exact, start times between nodes are close.** A duration is measured on one node. Start times on
  different nodes come from different clocks (kept in sync by NTP), so they can be a few milliseconds apart.
  Within a step, a call always lasts at least as long as the replica's own handling, and the coordinator's request
  at least as long as its calls.
- **Only recent requests.** `get-console-output` returns about the last 64 KB of a node's console, which is a few
  minutes when the store is quiet but well under a second during a benchmark. Older lines are in each node's journal
  (`$PEM`, `$COORD_PUB` and the replicas' private IPs as in M3's [How to verify it, step 1](../../M3.md#how-to-verify-it); a replica is reached through
  the coordinator):

  Windows (PowerShell):
  ```powershell
  ssh -i $PEM "ec2-user@$COORD_PUB" "journalctl -u margosha --since '10 min ago' | grep my-test-1"
  ssh -i $PEM -o ProxyCommand="ssh -i $PEM -W %h:%p ec2-user@$COORD_PUB" ec2-user@10.0.1.15 "journalctl -u margosha --since '10 min ago' | grep my-test-1"
  ```

  macOS (Terminal):
  ```bash
  ssh -i "$PEM" "ec2-user@$COORD_PUB" "journalctl -u margosha --since '10 min ago' | grep my-test-1"
  ssh -i "$PEM" -o ProxyCommand="ssh -i $PEM -W %h:%p ec2-user@$COORD_PUB" ec2-user@10.0.1.15 "journalctl -u margosha --since '10 min ago' | grep my-test-1"
  ```
- **Under heavy load the journal may drop lines.** By default journald keeps at most 10,000 lines per 30 seconds for
  one service and drops the rest ("Suppressed N messages"). During a benchmark each node writes more than that.
- **The log files keep everything.** Every node also writes the same JSON lines to
  `/var/lib/margosha/logs/margosha.log`. A file is closed at 20 MB and compressed, and up to 200 MB are kept per node
  ([ADR 0020](../../adr/0020-log-file.md)). The helper reads them over SSH with `-Files` (Windows) or
  `--files` (macOS), the replicas through the coordinator. Use it for older requests and for requests made under
  load:

  Windows (PowerShell):
  ```powershell
  .\m4\tracing\commands\trace.ps1 -TraceId my-test-1 -Files -Pem $PEM
  ```

  macOS (Terminal):
  ```bash
  ./m4/tracing/commands/trace.sh my-test-1 --files "$PEM"
  ```

  On Windows the path in `$PEM` must have no spaces, because Windows PowerShell can't pass it inside the SSH jump
  option.
- **The console output can lag a little** behind the node's log, so if a fresh request is missing, run the helper
  again after a few seconds.
- **Write path only to the leader.** A trace shows the coordinator, the leader and, for reads, the replicas that
  were asked; followers copying the write are not part of it.

## Where the code is

| Class | Runs on | Job |
|---|---|---|
| `api.util.TraceIds` | all | The header name, the valid-ID rule, new IDs |
| `common.filter.TraceIdFilter` | every node | Takes or makes the ID, puts it in the log context and the response, logs the end of the request |
| `coordinator.interceptor.ReplicaCallInterceptor` | coordinator | Sends the ID to the replica, logs the end of each call |
| `coordinator.replica.QuorumReader` | coordinator | Copies the log context into the parallel read tasks |
| `application*.properties` | every node | JSON logs on the console and in `logs/margosha.log`, `role` / `shard` / `replica` fields |

| Case | Test |
|---|---|
| Generated, kept and replaced IDs; the header on errors; the end line with its duration | `CoordinatorControllerTest` |
| A write traced on the coordinator and the leader, a read on the replicas asked; durations nest | `TraceAcrossNodesTest` |
| A log line is JSON with `role`, `shard`, `replica`, and is also written to the log file in the data folder | `StructuredLogTest` |
| Expected errors are one WARN line without a stack trace | `ApiExceptionHandlerTest` |
