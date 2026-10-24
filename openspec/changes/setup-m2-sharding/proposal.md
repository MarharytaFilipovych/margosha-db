## Why

Milestone 2 of the Cloud Architectures capstone turns the M1 network into a working key-value store that spreads its data across several machines (horizontal sharding). A single node can only hold as much data and serve as many requests as one machine allows; splitting the keys across shards with a consistent hashing ring lets the store grow by adding shards, while moving as few keys as possible when a shard joins or leaves.

M2 runs in the AWS Academy Learner Lab (`LabRole` / `LabInstanceProfile`, \$50 for the whole course, max 9 running instances). The M1 budget and quota review ([quotas_and_budget.md](../../../m1/quotas_and_budget.md)) already fixed the shape of the deployment: one `t3.micro` coordinator in the public subnet, two `t3.micro` shards in the private subnets with no internet access, an S3 gateway endpoint, a `t3.small` YCSB client only while benchmarking, and GitHub Actions for CI/CD. The YCSB numbers from M2 are the baseline that M3 and M5 are compared against, so they must be recorded and kept.

## What Changes

- Add an API specification for registering a table definition and doing CRUD over table records. A table has a compound primary key: a partition key and an optional sort key.
- Add the **coordinator** node (Java 21). It first serves the API in single-node mode, storing records itself in memory. Operations: register a table; on records EXISTS, CREATE, READ, UPDATE and DELETE.
- Add the **shard** node (same Java application, different role). Each shard keeps its records in memory.
- Add shard registration: an API operation on the coordinator that each shard calls when it starts.
- Shard writes and reads: the coordinator hashes the record's key onto a consistent hashing ring with virtual nodes and forwards the request to the shard that owns it.
- Add the S3 gateway endpoint to the network stack, so the shards can install packages and download the application without internet access.
- Add IaC (CloudFormation) for the application: an artifacts bucket, the coordinator and shard instances with their security groups, and the YCSB client, which is created only while benchmarking.
- Add a CI/CD pipeline (GitHub Actions) that builds and tests the application on every push and, on `main`, deploys infrastructure changes through CloudFormation and application changes through the artifacts bucket.
- Benchmark the sharded store with YCSB and keep the numbers in the repository as the M2 baseline.

## Decisions required before implementation (ADRs)

- API protocol and format
- Hash function and number of virtual nodes
- Partition key strategy

These are not decided in this proposal. Each is recorded in its own ADR, and the design lists the alternatives to consider. Tasks that depend on them come after the ADRs.

## Capabilities

### New Capabilities

- `kv-api`: the API contract: the API specification document, table definitions with a compound primary key, and the record operations (EXISTS, CREATE, READ, UPDATE, DELETE) with their results and errors.
- `shard-routing`: how the coordinator places data: single-node mode, shard registration, the consistent hashing ring with virtual nodes, and routing of writes and reads to shards.
- `shard-storage`: how a node keeps records: in memory.
- `app-infrastructure`: the application's IaC stack: artifacts bucket, coordinator and shard instances, security groups, and the YCSB client.
- `delivery-pipeline`: the CI/CD pipeline that builds, tests and deploys infrastructure and application updates.
- `benchmarking`: the YCSB benchmark of the sharded store and the recorded baseline numbers.

### Modified Capabilities

- `network`: adds an S3 gateway endpoint on the private route table, so private subnets can reach S3 (package repositories and the artifacts bucket) without internet access.

## Impact

- New Java 21 application in the repository (coordinator, shard, YCSB binding), built with Maven into a single jar.
- New API specification file in the repository (format set by the API protocol ADR).
- `cfn/margosha-vpc-stack.yaml` gets the S3 gateway endpoint. New template `cfn/margosha-app.yaml` for the application. This keeps the two-stack layout of [ADR 0002](../../../adr/0002-stack-layout.md).
- New GitHub Actions workflow. It uses the Learner Lab session credentials stored as repository secrets, which expire every session and must be refreshed before a deploy.
- Records are kept in memory, so they are lost when a node restarts (a deploy, or the Learner Lab stopping the instances). Durability is the topic of M3.
- AWS cost: as estimated in M1 (about \$0.036 per hour while the coordinator and shards run, YCSB client only while benchmarking). The instances run only during work sessions.
- New documentation: three ADRs, M2 docs (API, how routing works, benchmark results), README section for M2 (how to bring it up, how to verify it, what changed since M1).
