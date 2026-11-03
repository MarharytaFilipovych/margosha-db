# Tasks

## 1. Decisions (before the code that depends on them)

- [x] 1.1 ADR 0007: values of N / W / R in `adr/` (N = 3, W = 2, R = 2, configurable; alternatives W = 1 / R = 3 and W = 3 / R = 1; behaviour with one and two replicas down; CP/AP per scenario); verify it states the R + W > N argument and what each setting costs in latency (design.md "Mandatory ADRs")
- [x] 1.2 ADR 0008: replica synchronisation mechanism in `adr/` (who copies a write, who starts the transfer, how a follower waits for new entries, how a restarted follower catches up; alternatives in design.md "Mandatory ADRs"); verify it names how a restarted follower knows where to resume
- [x] 1.3 ADR 0009: log structure in `adr/` (where the log lives, what an entry contains, the format and how a crash in the middle of a write is detected, when an entry counts as stored, how the log grows; alternatives in design.md "Mandatory ADRs"); verify it states what "stored" means for the write quorum
- [x] 1.4 ADR 0010: ordering guarantees in `adr/` (who sets the order, how versions are assigned, how deletes are versioned, the scope of the guarantee, what a client sees; alternatives in design.md "Mandatory ADRs"); verify it states the ordering guarantee and what it does not guarantee
- [x] 1.5 ADR 0011: write acknowledgement in `adr/` (how a follower confirms, when the leader answers: async / semi-sync / fully sync, the timeout and what a 503 means; alternatives in design.md "Mandatory ADRs"); verify it names where the leader waits for confirmations and that its timeout is shorter than the coordinator's

## 2. Log-structured storage (after ADRs 0009 and 0010)

- [x] 2.1 Implement the replication log in `node` as decided in ADRs 0009 and 0010: append an entry with the next version, read the entries after a position, rebuild from the log on start, and handle a write cut off by a crash as the ADR says; verify unit tests for append / read-after / rebuild / a write cut off by a crash / a damaged entry that is not the last one
- [x] 2.2 Implement the replica storage on top of the log: per-table maps of key â†’ version + attributes or a deleted marker, rebuilt from the log on start, served from memory, plus an "apply entry" path for followers; verify unit tests that a store rebuilt from its log holds the same records and versions, and that a deleted record reads as not found with its version
- [x] 2.3 Keep single-node mode on the M2 in-memory storage; verify the existing single-mode tests still pass unchanged

## 3. Replication between leader and followers (after ADRs 0007, 0008 and 0011)

- [x] 3.1 Add the replication settings (`margosha.replication.factor`, `.write-quorum`, `.read-quorum`, the confirmation timeout, `margosha.data-dir`) with the defaults from ADR 0007 (N = 3, W = 2, R = 2), and refuse to start when W or R is out of 1..N or R + W â‰¤ N; verify a test that a node with N = 3, W = 1, R = 2 does not start and reports the rule
- [x] 3.2 Leader write path: check against its state (409 / 404 as in M2), record the write in the log (ADR 0009) in the order and with the version ADR 0010 decides, apply it, then wait for W âˆ’ 1 follower confirmations or the timeout (ADR 0011) (503 `SHARD_UNAVAILABLE`, outcome unknown); writes serialised per shard; verify tests for a committed write, a write with one follower down (succeeds), and with both followers down (503, entry kept in the leader's log)
- [x] 3.3 Leader side of the synchronisation mechanism from ADR 0008, including how it learns each follower's confirmed position (ADR 0011); verify tests that followers receive entries in log order and that the leader's recorded confirmations match what the followers stored
- [x] 3.4 Follower side of the synchronisation mechanism from ADR 0008: receive entries after its position, store them in its log, apply them, confirm, recover from errors; a follower refuses direct writes with `421 NOT_LEADER`; verify an in-process test (leader + 2 followers) that both followers reach the leader's last entry and hold the same records, and that a direct write to a follower is refused
- [x] 3.5 Follower resync: verify an in-process test that a follower stopped while 100 records are written, then restarted, receives only those 100 entries and then holds the same records as the leader
- [x] 3.6 Replica read endpoints return the version (READ: attributes + version; not found / deleted: 404 with version; EXISTS: `{exists, version}`); verify shard controller tests for each case
- [x] 3.7 Replica status endpoint (role, last log position, followers' confirmed positions on the leader, reads served) for the AWS checks and the demo; verify a test that its counters change after a write and a read

## 4. Coordinator (after group 3)

- [x] 4.1 Replica registration: `shardId`, `replicaId`, `role`, `address`; ring keyed by shard ID; a second, different leader for a shard is rejected (400); repeats change nothing; replicas re-register every 10 s as in M2; verify registry unit tests and the M2 registration tests updated for replicas (including "coordinator restarts")
- [x] 4.2 Route CREATE / UPDATE / DELETE to the owning shard's leader, 503 when no leader is registered or it does not answer within the write timeout (longer than the leader's ack timeout); verify an in-process test with 2 shards Ã— 3 replicas that each record is written by its owner's leader and copied to that shard's followers only
- [x] 4.3 Quorum reads: R replicas chosen round-robin per shard, asked in parallel, a third asked when one fails, highest version wins, deleted / missing = not found, 503 when fewer than R answer; EXISTS the same way; verify in-process tests for a lagging replica (newest returned), a deleted record still held by one replica, one follower down (reads work), two replicas down (503), and 3,000 reads spreading 1,800â€“2,200 per replica
- [x] 4.4 Durable table definitions on the coordinator's disk (appended, forced to disk, loaded on start); verify a test that after a coordinator restart a record can be read without registering its table, and registering it again answers 409
- [x] 4.5 Update `openapi.yaml`: replica registration body, versioned replica reads, the replication endpoints from ADR 0008, `NOT_LEADER`, and "503 on a write means outcome unknown"; update the Java models and clients to match; verify the serialization round-trip tests pass and the spec lists every new field and error
- [x] 4.6 Update the YCSB binding round-trip test to run against the replicated store in-process (2 shards Ã— 3 replicas); verify it passes with no change to the binding itself
- [x] 4.7 Write `m3/replication.md`: replica sets, the write path with W, the log and versions, followers and resync, quorum reads, what happens with one and two replicas or the leader down; verify its examples match the tests in groups 3 and 4

## 5. Infrastructure and pipeline (after group 4)

- [x] 5.1 `cfn/margosha-app.yaml`: 6 replica instances placed as in design.md decision 1 with their tags (shard ID, replica ID, role, leader IP, coordinator IP), parameters `WriteQuorum` / `ReadQuorum` passed as tags, shard security group allowing the API port from itself, outputs for all 7 instance IDs; boot script builds the replica arguments and creates the data directory; verify `cfn-lint` passes and a change set preview lists the 4 new replicas and the replaced nodes
- [x] 5.2 Pipeline: reboot / start every node from the stack outputs, and wait until each runs the new jar and the coordinator logged all 6 replica registrations after the deploy began; verify the workflow works
- [x] 5.3 Deploy through the pipeline; verify on AWS the placement (each shard in both private subnets, leaders in different AZs, no public IPs), all 6 replicas registered, and a follower can reach its leader while the bench client cannot reach any replica
- [x] 5.4 Verify replication on AWS with the status endpoint: after writes, every replica of a shard reaches the leader's last entry; stop one follower, write, start it, and show it received only the missed range; record the time to rebuild from the log on start
- [x] 5.5 Verify failure behaviour on AWS: one follower down (reads and writes work), both followers of a shard down (writes 503 after the ack timeout, reads 503), leader down (writes 503, reads work); write the results with commands into `m3/aws_checks/`
- [x] 5.6 Verify durability on AWS: write records, push an application-only commit, and read them back after the deploy; also after stopping and starting all instances (end of a lab session); add the results to `m3/aws_checks/`

## 6. Benchmark (after group 5)

- [x] 6.1 Update `m2/benchmark/procedure.md` into `m3/benchmark/procedure.md`: same workload and parameters as M2, a discarded warm-up run then three measured runs, the quorum settings written to `params.txt`, CPU credits of all 8 instances checked; update `run-benchmark.sh` if needed; verify the documented commands run as written
- [x] 6.2 Run the benchmark; verify YCSB reports no failed operations in the measured runs, or the failures are written down
- [x] 6.3 Copy the raw outputs into `m3/benchmark/raw/` and write `m3/benchmark/results.md`: each run, the median, the M2 numbers side by side, the explanation of the throughput and p99 changes (write quorum, read quorum, the log's disk writes, 6 replicas serving reads), CPU per node, commit, instance types; verify it links to the raw output and answers "how did p99 change and why"
- [x] 6.4 Set `BenchClient` back to `false`; verify the client is gone and the store still answers

## 7. End-to-end checks

- [ ] 7.1 Fresh deploy from the pipeline into an account where only `margosha-vpc` exists; verify all 7 nodes come up, every replica registers, and the store works with no manual console steps
- [ ] 7.2 Run `openspec validate setup-m3-durability --strict`; verify it passes

## 8. Deliverables (apply to every milestone)

- [ ] 8.1 README: M3 section with how to bring it up, how to verify it, what changed since M2; verify the commands run as written in a new lab session
- [ ] 8.2 Demo
- [ ] 8.3 Transcript of the AI critique of the architecture

## Workflow follow-up

- Archive the change after the milestone is reviewed.
- After archiving, fill in the `Purpose` of the new main spec if it is left as TBD, and fix links to `openspec/changes/setup-m3-durability/` that point at the old path.
