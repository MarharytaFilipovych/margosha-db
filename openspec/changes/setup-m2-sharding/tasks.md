# Tasks

## 1. Decisions (before the code that depends on them)

- [x] ADR 0004: API protocol and format in `adr/`; verify it names the protocol and the spec format (design.md decision 1 lists the alternatives)
- [x] ADR 0005: hash function and number of virtual nodes in `adr/`; verify it cites the spread and key-movement numbers from the ring simulation in `m2/ring-simulation/`
- [x] ADR 0006: partition key strategy in `adr/`; verify it says what is hashed (partition key only, or partition + sort key) and how YCSB keys map to it

## 2. Project scaffolding and CI checks

- [x] Create the Maven (wrapper committed) Java 21 multi-module project under `app/` (parent `pom.xml`) with modules `api`, `ring`, `node`, `ycsb-binding`; verify `./mvnw verify` succeeds on an empty project
- [x] Make `node` build one executable Spring Boot jar (`spring-boot-maven-plugin`) with `coordinator` and `shard` subcommands; verify `java -jar margosha.jar coordinator --mode=single` starts
- [x] Add `.github/workflows/pipeline.yml` with the `check` job (`./mvnw verify`); verify the job passes on GitHub for a push and fails for a commit with a failing test

## 3. API specification (after ADR 0004)

- [x] Write the API specification file in the format from ADR 0004, covering register table, EXISTS / CREATE / READ / UPDATE / DELETE, and register shard, with all errors from the `kv-api` spec
- [x] Write the Java models and client for the spec in `api`; verify they compile and a serialization round-trip test passes

## 4. Consistent hashing ring (after ADR 0005 and ADR 0006)

- [x] Implement the ring in `ring`: hash function interface, configurable virtual nodes, vnode positions from `hash(shardId#i)`, add shard; verify unit tests for lookup and add pass
- [x] Implement the routing-key function from ADR 0006; verify a unit test shows the same primary key always maps to the same routing key
- [x] Add tests for the `shard-routing` properties (40-60% split of 100,000 keys over 2 shards; at most 45% of keys move when a 3rd shard is added); verify the tests pass with the setting from ADR 0005 (MurmurHash3 128-bit, 128 virtual nodes)

## 5. In-memory storage

- [x] Implement the in-memory record store (one map per table) in `node`; verify put / get / exists / update / delete unit tests pass

## 6. Coordinator in single-node mode

- [x] Implement the API server with Spring Boot (Spring Web) with the record backend interface and the local in-memory backend; verify `coordinator --mode=single` serves CREATE / READ / EXISTS / UPDATE / DELETE in an integration test
- [x] Implement register table with the validation, conflict and not-found errors from the `kv-api` spec; verify each `kv-api` scenario has a passing integration test
- [x] Return unexpected errors as `INTERNAL_ERROR` (500) in the shared error format; verify an integration test where an operation throws gets `{code, message}` and not Spring's default error body

## 7. Sharded mode: registration, writes and reads

- [x] Implement the shard role: serves the record operations on its own in-memory store; verify an integration test calls a shard directly
- [x] Implement the coordinator's register shard operation and shard registration on start, retrying until the coordinator accepts it; verify tests for a shard starting while the coordinator runs and before it is up
- [x] Implement the remote shard backend and route CREATE / UPDATE / DELETE through the ring; verify with 2 in-process shards that each record is stored on exactly its owning shard
- [x] Route READ and EXISTS through the ring; verify reads hit only the owning shard
- [x] Return `SHARD_UNAVAILABLE` (503) when no shard is registered or the owning shard does not answer within the timeout, without sending the request to another shard; verify with a stopped in-process shard that its keys fail and the other shard's keys still succeed
- [x] Verify the "ring changes do not move data" scenario: add a shard after loading, check that moved keys read as not found
- [x] Write `m2/routing.md`: how keys reach a shard, single-node vs sharded mode, what happens when a shard is added; verify its examples match the tests

## 8. Network: S3 gateway endpoint

- [x] Add the S3 gateway endpoint on the private route table to `cfn/margosha-vpc-stack.yaml`, with a policy allowing `s3:GetObject` on `arn:aws:s3:::al2023-repos-us-east-1-de612dc2/*` and read on `margosha-artifacts-<account-id>`; verify `cfn-lint` passes
- [x] Update the `margosha-vpc` stack in the Learner Lab; verify the private route table has the local and S3 prefix-list routes and no IGW route, and an instance in a private subnet can `dnf install` a package
- [x] Update `m1/vpc_creation.md` or add a note in `m2/` describing the endpoint and the verification commands; verify the commands run as written

## 9. Application infrastructure

- [x] Write `cfn/margosha-app.yaml`: bucket `margosha-artifacts-<account-id>` (public access blocked), parameters (AMI, `OperatorCidr`, API port, `BenchClient`), security groups as in design.md decision 11, coordinator in the public subnet, shard A / B in private A / B, `t3.micro`, standard credits, 8 GiB gp3, `LabInstanceProfile`, UserData with role, shard ID and `!GetAtt Coordinator.PrivateIp`, the YCSB client (`t3.small`, public subnet, Corretto 21 and YCSB 0.17.0) only when `BenchClient` is `true`, instance IDs as outputs; verify `cfn-lint` passes
- [x] Write the systemd unit that downloads the jar from the bucket on every boot, retrying until it exists, and then starts the node; verify on the coordinator that after a reboot it runs the jar from the bucket
- [x] Deploy `margosha-app` and upload the first jar; verify both shards register and a record can be created and read through the coordinator
- [x] Verify the `app-infrastructure` access scenarios: shards have no public IP, a shard's API port is not reachable from the public subnet except from the coordinator, the coordinator is reachable on its private IP
- [x] Stop and start all instances (as at the end of a lab session); verify both shards register again
- [x] Set `BenchClient` to `true` and then back to `false`; verify the client is created and deleted, and the coordinator and the shards are not replaced

## 10. Delivery pipeline: deploy job

- [x] Add the `deploy` job (needs `check`, runs on `main` and by hand, one run at a time) using the Learner Lab session credentials from repository secrets
- [x] Add the deploy steps in order (`margosha-vpc`, `margosha-app` with `--no-fail-on-empty-changeset`, jar upload, reboot of the coordinator and the shards); verify an application-only commit ends with the nodes running the new jar and reports no stack changes, and a template change updates that stack
- [x] Document in the README how to refresh the secrets and start a deploy by hand; verify by doing it once in a new lab session

## 11. Benchmark

- [x] Implement the YCSB binding in `ycsb-binding` (table, key → partition key, fields → attributes; read / insert / update / delete; scan returns not-implemented); verify the round-trip test against an in-process coordinator passes
- [ ] Publish the binding jar to the artifacts bucket in the pipeline; verify the bench client downloads it
- [ ] Write the benchmark script and `m2/benchmark/procedure.md` (register `usertable`, load the records, run the chosen workload, copy outputs to `results/`); do a short trial run to choose the workload and set the record, operation and thread counts, and verify the documented commands run as written
- [ ] Run the benchmark; verify YCSB reports no failed operations or the failures are written down
- [ ] Copy the raw outputs into `m2/benchmark/raw/` and write `m2/benchmark/results.md` with the workload and why it was chosen, the parameters, throughput and avg / p95 / p99 latency per operation, the benchmarked commit and the instance types; verify it links to the raw output
- [ ] Set `BenchClient` back to `false`; verify the client instance is gone and the store still answers

## 12. End-to-end checks

- [ ] Fresh deploy from the pipeline into a lab account where only `margosha-vpc` exists; verify the store works with no manual console steps
- [ ] Run `openspec validate setup-m2-sharding --strict`; verify it passes

## 13. Deliverables (apply to every milestone)

- [ ] README: M2 section with how to bring it up, how to verify it, what changed since M1; verify the commands run as written in a new lab session
- [ ] Demo
- [ ] Transcript of the AI critique of the architecture

## Workflow follow-up

- Archive the change after the milestone is reviewed.
- After archiving, fill in the `Purpose` of the new main specs if any are left as TBD.
