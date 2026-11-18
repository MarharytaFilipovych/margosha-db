# ADR 0020: Keeping the logs in a file on each node

## Context

[ADR 0017](0017-logging-and-trace-ids.md) keeps the logs only where they were in M3: the journal and the serial
console of each node. The trace helper reads the console with `aws ec2 get-console-output`, and older lines are read
with `journalctl` over SSH.

The AI review ([ai_critique.md](../m4/ai_critique.md), finding 3) showed that both lose lines under load:

- The console output keeps only about the last 64 KB of a node. During a benchmark that is well under a second of
  requests.
- journald keeps by default at most 10,000 lines per 30 seconds for one service and drops the rest. During a
  benchmark the coordinator writes about 3,000 lines per second.

So the trace of a request is likely to be gone exactly when someone needs it.

## Alternatives considered

- **Write the limit down and keep it.** Nothing to change, but trace IDs don't work under load. Rejected.
- **Raise journald's limit.** Needs a change in the boot script, which is in the launch template, so all 7 nodes are
  replaced. Rejected.
- **CloudWatch Logs.** One search over all nodes, but it needs an agent on every node (launch template) and a paid VPC endpoint for the replicas, or the coordinator sending the replicas' logs on. Rejected.
- **The same JSON lines also in a file on each node.** Spring Boot writes it, set up in the jar's settings. Nothing is dropped, no template change. The helper reads the files over SSH.

## Decision

Every node also writes its JSON log lines to **`logs/margosha.log` in its data folder** (`/var/lib/margosha` on AWS).
A file is closed at **20 MB** and compressed, and at most **200 MB** are kept per node. The console and the journal
stay as they are, so the pipeline's searches still work.

The trace helper gets one more option, `-Files` (Windows) or `--files` (macOS). It then reads the files of every
running node over SSH, the replicas through the coordinator, instead of the console output.

## Consequences

- A trace can be found also under load and for older requests, as long as the node's 200 MB hold it.
- Reading the files needs SSH and the key pair. The console way without SSH stays for recent requests.
- Each line is written once more, to the disk. Up to 200 MB of disk per node.
- The files are in the data folder, so the benchmark's reset step deletes them too.
- Only the jar and the helper change. No template change and no node replaced.
