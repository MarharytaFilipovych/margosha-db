# Design

## Context

See proposal.md - Why. What already exists and limits the approach:

- **Network** (`cfn/margosha-vpc-stack.yaml`): VPC `10.0.0.0/16`, public subnet in `us-east-1a`, private subnets A (`us-east-1a`) and B (`us-east-1b`) sharing one local-only route table. It exports `VpcId`, `PublicSubnetId`, `PrivateSubnetAId`, `PrivateSubnetBId`. It does not export the route table IDs.
- **Deployment shape and costs** are fixed by [quotas_and_budget.md](../../../m1/quotas_and_budget.md): `t3.micro` coordinator (public) and 2 shards (private), `t3.small` YCSB client only while benchmarking, standard CPU credits, 8 GiB gp3, `LabInstanceProfile`, no NAT Gateway, S3 gateway endpoint, GitHub Actions.
- **No internet in the private subnets.** Shards can reach only the VPC and, after this change, S3. They cannot reach Docker Hub, Maven Central, SSM or the CloudFormation API. They can install AL2023 packages (Corretto 21 is one) because the AL2023 repositories are in S3.
- **No IAM.** Instances use `LabInstanceProfile`; GitHub Actions uses the lab session credentials from repository secrets, which expire with every session.
- **`t3.micro` has 1 GiB RAM.** With the OS and the JVM's own overhead, about 512 MiB is left for the heap. Records are kept in memory, so this limits how many records a shard can hold.
- **The Learner Lab stops instances at the end of a session and starts them at the next one.** Disks and private IPs survive; public IPs change; anything kept only in memory is lost.
- The repo has no application code yet. Its `.gitignore` is already the standard Java one.

## Goals / Non-Goals

**Goals:**
- One Java code base that runs as coordinator (single-node or sharded) or as shard, built into one jar.
- Deterministic, testable routing: the ring is a plain in-process component with unit tests.
- A pipeline that deploys infrastructure updates and application updates.

**Non-Goals:**
- Replication and failover (M3). A stopped shard makes its keys unavailable.
- Durability: records are kept in memory and are lost when a node restarts. Durability is M3.
- Moving existing records when the ring changes (rebalancing). In M2 the shards are registered before data is loaded; see Risks.
- More than one coordinator, a load balancer, authentication or TLS.
- Operations the requirements do not ask for: scans, range queries, reading / updating / deleting tables, list / remove shards.

## Decisions

### Mandatory ADRs (open, decided in the ADRs)

These are written as ADRs before the code that depends on them. Below are the alternatives and the points that matter for this project, not a decision.

**1. API protocol and format.**
- *REST + JSON, described in OpenAPI 3*: testable with `curl` from the coordinator. JSON costs some CPU on `t3.micro`.
- *gRPC + Protocol Buffers*: binary and faster, the `.proto` file is the spec, and Java code is generated from it. Needs `grpcurl` for manual tests, and more dependencies in the jar.
- *GraphQL*: flexible queries, which this store does not need (all access is by primary key). Adds the most overhead per request.
- Points for the ADR: the YCSB binding is custom Java code in all cases, so YCSB does not favour any of them. The coordinator-to-shard calls use the same protocol (see decision 4), so the choice also sets internal overhead.

**2. Hash function and number of virtual nodes.**
- *Hash*: MurmurHash3 (32 or 128-bit) or xxHash64 are fast and spread keys well. MD5 / SHA-1 also spread well but are slower and their security is not needed. CRC32 and Java's `String.hashCode()` spread similar keys (like YCSB's `user123...`) poorly.
- *Virtual nodes per shard*: 1 gives a very uneven split with 2 shards. 64-256 keeps each shard within a few percent of its fair share; more vnodes make the ring larger but it stays small (a few thousand entries).
- Points for the ADR: the `shard-routing` spec requires a 40-60% split with 2 shards and at most 45% of keys moved when a third shard is added. A simulation written before the ring code (`m2/ring-simulation/`) measures this for the candidates and gives the numbers for the ADR; the ring tests (tasks section 4) confirm the chosen setting.

**3. Partition key strategy.**
- *Hash the partition key only*: all records with the same partition key are on one shard. A very popular partition key becomes a hot shard.
- *Hash the partition key and the sort key together*: spreads records more evenly, but one partition is split across shards.
- Points for the ADR: what YCSB uses as the key (the binding maps YCSB's key to the partition key, with no sort key), and how a hot key would show in the benchmark.

The code keeps these behind small seams (a hash function interface, a configurable vnode count, a routing-key function), so each ADR decision is configuration and one small class.

### 4. One jar, two roles, same protocol inside and outside
The application is a Maven multi-module project (Java 21, parent `pom.xml`, Maven Wrapper committed) with modules `ring`, `node` (coordinator + shard), `api` (the spec file and generated or hand-written models) and `ycsb-binding`. `node` builds one executable jar (`spring-boot-maven-plugin`) started as `java -jar margosha.jar coordinator|shard`. The coordinator talks to shards over the same protocol as clients.
- *Alternatives*: separate coordinator and shard artifacts (two things to build, publish and version); a different internal protocol (a second spec and client to maintain).
- *Server library*: Spring Boot (Spring Web) for the REST API from ADR 0004. It is the framework I know best, so the code is faster to build and easier to explain at the defense. The cost is memory: Spring Boot uses more of the 512 MiB heap than a small library, and the records are kept in the same heap (decision 5), so fewer records fit on a shard.
- *Alternatives*: Javalin (a small REST library, less memory, but new to me); the JDK built-in `HttpServer` (no dependencies, but routing, JSON and status codes all written by hand); Micronaut / Quarkus (less overhead than Spring, but another framework to learn).

### 5. Storage: records in memory
Each node keeps, per table, a hash map from primary key to the record's attributes. Nothing is written to disk, so a node starts empty after a restart. The coordinator in single-node mode uses the same in-memory store. Both the local store and a remote shard implement one internal "record backend" interface, so the coordinator code is the same in both modes and the mode only chooses the backend.
- *Consequence for the benchmark*: a YCSB record is about 1 KB, plus Java's object overhead, and the heap is about 512 MiB. The 1,000,000 records assumed in the M1 budget do not fit in 2 shards. The record count is set after a short trial run so that each shard's half fits in its heap, and it is written down with the results.
- *Alternative*: storing records on disk. Rejected: M2 does not ask for durability; it is the topic of M3.

### 6. Coordinator metadata
Table definitions and the shard list are kept in memory on the coordinator. After a coordinator restart they are empty: the shards register again when they start (on a deploy and at the start of a lab session all nodes restart, see decision 9), and the client registers its table again (the benchmark script does this first).
- *Alternative*: a metadata file on the coordinator's disk. Rejected: not needed while the records themselves are lost on a restart.

### 7. Ring with stable positions
The ring is a sorted map from hash to shard ID. The positions of a shard's virtual nodes are computed from its ID (`hash(shardId + "#" + i)`), not from its address. Shard IDs are fixed in the template (`shard-a`, `shard-b`), not taken from the instance ID.

### 8. Stack layout: two stacks, as in ADR 0002
- `margosha-vpc` (existing) + the S3 gateway endpoint on the private route table. The endpoint belongs with the network, and the route table ID is not exported, so adding it there avoids a new export.
- `margosha-app`: the artifacts bucket, security groups, coordinator, shard A, shard B, and the YCSB client as a conditional resource (parameter `BenchClient`, default `false`).

The bucket name is fixed by convention (`margosha-artifacts-<account-id>`), so the endpoint policy in `margosha-vpc` can name it without importing from the app stack. The bucket is created in the same stack as the instances, so on the very first deploy the jar is not there yet when they boot; the nodes retry the download until it is (decision 9).
- Switching `BenchClient` adds or removes only the client instance. The coordinator and the shards are not replaced, because none of their properties change.
- *Alternatives*: a separate stack for the bucket (one more stack and deploy step, only to avoid the retry on the first boot); a separate stack for the YCSB client (one more stack; the conditional resource is enough).

### 9. Application delivery: nodes download the jar on boot
The pipeline uploads the jar to `s3://<bucket>/releases/margosha.jar` and then reboots the coordinator and the shards (`aws ec2 reboot-instances`, instance IDs from the app stack outputs). On every boot, the systemd service downloads the jar from the bucket before it starts (`ExecStartPre`, `aws s3 cp`, retried until the jar exists). UserData only installs Corretto 21, writes `/etc/margosha/node.env` (role, shard ID, coordinator address, heap `-Xmx512m`) and installs the systemd unit.
- *Alternatives*: SSM Run Command (no SSM on the shards without paid interface endpoints); `cfn-init` / `cfn-hup` (needs the CloudFormation endpoint, which the shards cannot reach); SSH from GitHub runners through the coordinator (opens SSH to the internet and puts a private key in secrets).
- A deploy restarts every node, so the records in memory are lost. This is accepted in M2 (decision 5).

### 10. How shards find the coordinator
Shard UserData gets the coordinator's private IP with `!GetAtt Coordinator.PrivateIp`. A private IP stays the same across stop/start and reboot, so shards find the coordinator again after each lab session and each deploy. On start a shard registers, retrying until the coordinator accepts it, because the coordinator may still be booting. The coordinator's public IP changes each session and is only used by the operator.

### 11. Security groups
- Coordinator SG: API port from `10.0.0.0/16` and from parameter `OperatorCidr`; SSH (22) from `OperatorCidr` only.
- Shard SG: API port and SSH only from the coordinator SG. Shards are reached for debugging by SSH through the coordinator (`vockey`).
- Bench SG: no inbound rules; SSH from `OperatorCidr` only.

### 12. Pipeline (GitHub Actions)
One workflow, `.github/workflows/pipeline.yml`, with two jobs:
- **check** (every push and pull request): `./mvnw verify` (builds the jar and runs the tests).
- **deploy** (`main` and manual start; needs `check`; one run at a time): `aws cloudformation deploy --no-fail-on-empty-changeset` for `margosha-vpc` and `margosha-app`; upload the jar (and the YCSB binding jar); reboot the coordinator and the shards.
- The AWS credentials are the Learner Lab session credentials from repository secrets. They expire with every session, so they are refreshed before a deploy (the README says how).

### 13. Benchmark
- YCSB 0.17.0 release on the client (public subnet, so it can download it from GitHub), Corretto 21 from dnf, and the binding jar from the artifacts bucket. YCSB is started with `java -cp` and `-db <binding class>`, so the binding does not need to be added to YCSB's binding list.
- The API has no scan, so workload E cannot run. One of the core workloads A, B, C, D or F is run; which one, and why it is representative, is written in the benchmark docs.
- YCSB updates send only some of a record's fields, while UPDATE replaces the whole value, so an updated record keeps only the updated fields. This does not change the measured latency or throughput.
- Procedure: register table `usertable` (partition key only), load the records, run the chosen workload.
- Raw outputs are copied to `s3://<bucket>/results/` before the client is removed, then into `m2/benchmark/` with a `results.md` summary.

### 14. Tests
- `ring`: spread and key-movement properties from the `shard-routing` spec.
- `node`: coordinator with 2 in-process shards on local ports: single-node mode, routing of writes and reads, registration.
- `ycsb-binding`: round trip against an in-process coordinator.

## Risks / Trade-offs

- [`t3.micro` memory limits how many records fit, and Spring Boot takes part of the heap] → the record count is set after a trial run and written down with the results.
- [Records are lost on every restart (deploy, end of a lab session)] → accepted for M2; the benchmark loads its data in the same session it runs; durability comes in M3.
- [CPU credits run out during the benchmark (standard mode throttles to 10% per vCPU)] → keep the run short, set by the trial run.
- [Records already stored are not moved when the ring changes] → shards are registered before data is loaded, and the limitation is written in the M2 docs.
- [Lab credentials in GitHub secrets expire every session] → refresh the secrets before a deploy; the README says how.
- [Single coordinator: one point of failure and a possible bottleneck in the benchmark] → accepted for M2.
- [Wrong endpoint policy or repository bucket name blocks shard bootstrap] → the network spec scenario `dnf install` through the endpoint is checked right after the network update, before the app stack is deployed.

## Migration Plan

1. Update `margosha-vpc` with the S3 endpoint (existing stack, in-place update, no replacement).
2. Create `margosha-app`, upload the first jar; the nodes pick it up on their next download retry, and the shards register.
3. From then on, the pipeline does steps 1-2 on every push to `main` and reboots the nodes; steps with no changes are skipped.

**Rollback:** application: upload the previous jar and reboot the nodes. Infrastructure: CloudFormation rolls back a failed update by itself; a bad update that succeeded is fixed by reverting the commit.

**Teardown order:** empty the artifacts bucket → delete `margosha-app` → delete `margosha-vpc` (the exports must no longer be imported).

## Open Questions

- Which workload, and the record count, operation count and thread count: set after a short trial run; recorded with the results.
- Whether the coordinator's API needs `OperatorCidr` access at all, or SSH tunnelling is enough for manual tests; the parameter can default to a closed value.
