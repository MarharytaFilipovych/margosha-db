# Margosha DB

A "light" version of a serverless key-value store like DynamoDB.

- **Decisions:** [adr/](adr/)
- **OpenSpec changes:** M1 [openspec/changes/archive/2026-10-24-setup-m1-infrastructure/](openspec/changes/archive/2026-10-24-setup-m1-infrastructure/), M2 [openspec/changes/archive/2026-10-30-setup-m2-sharding/](openspec/changes/archive/2026-10-30-setup-m2-sharding/), M3 [openspec/changes/archive/2026-11-03-setup-m3-durability/](openspec/changes/archive/2026-11-03-setup-m3-durability/), M4 [openspec/changes/setup-m4-operations/](openspec/changes/setup-m4-operations/)
- **IaC templates (AWS CloudFormation):** [cfn/](cfn/)
- **Application (Java 21, Maven):** [app/](app/)

---

## Milestones

Each milestone has its own page: what it contains, how to bring it up, how to verify it, and what changed since the
milestone before.

- **[Milestone 1: Infrastructure setup](M1.md).** The AWS foundation: what the lab's role may do, a read-only role
  described in IaC, the network stack `margosha-vpc` (a VPC with public and private subnets), service quotas and the
  budget, and a test that the stack can be created again from the template.
- **[Milestone 2: Sharded key-value store](M2.md).** A coordinator and two shards on EC2. The coordinator places each
  record on a shard with a consistent hashing ring, and records are kept in memory. A GitHub Actions pipeline builds,
  tests and deploys every push, and YCSB measured the first benchmark.
- **[Milestone 3: Replicated, durable store](M3.md).** Each shard becomes 3 replicas: a leader and two followers.
  Writes are stored on 2 replicas (W = 2) and reads ask 2 (R = 2). Every replica keeps a log on its disk, so the data
  survives reboots, deploys and the end of a lab session. Deploys are versioned and can be rolled back.
- **[Milestone 4: Operations](M4.md).** Monitoring, tracing, conflict resolution and agent access. Metrics from all 7
  nodes go to a CloudWatch dashboard, and 16 alarms send an email when a node is down, answers are slow, or a follower
  falls behind. Every request carries a trace ID across the nodes, every write gets a version (Last-Write-Wins), and an
  AI agent can use the store through an MCP server with four record operations.
