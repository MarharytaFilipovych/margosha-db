# AI design review — Margosha DB, Milestone 3

Nov 3, 2026

**Reviewed:** `openspec/changes/setup-m3-durability/` (proposal, design, specs, tasks), ADR 0007–0011,
`cfn/margosha-app.yaml`, `.github/workflows/pipeline.yml`, `app/node` (replication log, replica storage, leader,
follower, replica registry, quorum reader, table registry), `m3/replication.md`, `m3/aws_checks/`,
`m3/end_to_end/fresh-deploy.md`, `m3/benchmark/procedure.md`, `m3/benchmark/results.md`

**Prompt given to the AI:** "Critique the architecture of my Milestone 3: replica sets, the replication log, the
leader's write path and acknowledgements, followers and resync, quorum reads, the infrastructure and pipeline changes,
and the benchmark. Find weaknesses, contradictions and risky assumptions, and say how serious each one is."

## 1. A replica that lost its log rejoins as if nothing happened (high)

Nothing tells a replica's log apart from another log of the same shard. The only thing a follower sends is a number
(`after=<its last version>`), and the leader trusts it. Two cases go wrong silently:

- **The leader comes back empty, the followers don't.** The leader starts at version 0; the followers ask for entries
  after, say, 280,939. `ReplicationLeader.fetch` logs a warning and doesn't count it as a confirmation, so every write
  answers `503` (and still stays in the leader's log, "outcome unknown"). Meanwhile quorum reads that ask a follower
  return the old data, because its versions are higher. Once the leader's new log passes 280,939, the followers'
  position *is* counted, and they append the leader's entries 280,940+ on top of a different history:
  `appendReplicated` only checks that the version is the next number. From then on the replicas hold the same version
  numbers for different writes, and "highest version wins" compares numbers from two histories.
- **A follower comes back empty.** It registers within seconds and is in the read rotation while it is still copying
  from 0. Take a write confirmed by the leader and `shard-a-2` while `shard-a-3` was behind; `shard-a-2`'s data folder is
  then emptied and it restarts. A read that asks `shard-a-2` and `shard-a-3` finds neither has the write and returns the
  older value as the latest. The R + W > N argument in ADR 0007 assumes a confirmation is never undone, but losing the
  disk undoes it.

How a log gets lost is not far-fetched here: an instance replaced by a stack update (root volume with
`DeleteOnTermination: true`, see finding 2), the benchmark procedure's reset step (`find /var/lib/margosha -delete` on
all 7 nodes by hand; skipping one node is enough), or deleting a log that ADR 0009 says can't be started ("a bad line in
the middle stops the replica"), which is the obvious way out. Fix: give each log an identity written as its first line
(a random ID created with the leader's log; a follower copies it) and send it with every fetch; the leader refuses a
follower with another ID or a position ahead of its own, and logs an error instead of a warning. A follower that starts
behind registers only after it reaches the leader's `lastVersion` from its first fetch (`ReplicationBatch` already
carries it).

> **Response: fixed.** Each log now has an ID in `replication.log.id`, next to the log. The leader creates it for a
> new log, a follower takes the leader's while its own log is empty, and an empty log has no ID (a leftover ID file
> is deleted on start, so deleting only the log can't keep the old ID). A follower with entries sends its log ID with
> every fetch. The leader answers `409 LOG_MISMATCH`, copies nothing and doesn't count the fetch as a confirmation
> when the ID is not its own or the position is past its last entry (before, that case was only a warning). Both
> sides log an `ERROR`. A follower that starts with an empty log first asks the leader for its last version, and
> registers with the coordinator only once it has that many entries. A follower with its own log still registers at
> once, also while the leader is down. New tests: `ReplicaSetTest.leaderThatLostItsLogCopiesNothingOnTopOfTheFollowersLog`
> (the new log has more entries than the follower, so only the ID tells the histories apart),
> `followerAheadOfItsLeaderIsRefused`, `followerThatLostItsLogTakesTheLeadersIdAndCopiesEverythingAgain`,
> `emptyFollowerIsReadyToServeOnlyOnceItHasTheLeadersEntries`,
> `emptyFollowerWithoutItsLeaderIsNotReadyButOneWithItsOwnLogIs`,
> `ReplicatedStoreTest.emptyFollowerThatCannotReachItsLeaderDoesNotRegister`, and six log ID tests in
> `ReplicaStorageTest`. A leader that lost its log now stops taking writes instead of mixing histories; the recovery
> (copy a follower's log to it) is in `replication.md`, "When a log is lost". Logs written before this change have no
> ID file: the leader creates one on its first start, and each follower takes it on its first fetch. Not run on AWS
> yet.

## 2. Any change to the launch template deletes every record (high)

Records survive reboots, stop/start and application deploys (checked in `m3/aws_checks/`), because those keep the
instances. But the log and the table file live on each instance's root volume, which is deleted with the instance, and
all 7 instances are built from one launch template. Any change to it (the boot script, the instance type, `AmiId`,
`-Xmx`) replaces all 7 at once, so the next infrastructure change, in M4 or M5, empties the store just as M2's deploys
did. The design mentions the replacement only for the M2 → M3 move ("their data was in memory") and for rollback; it is
not listed under Risks. Fix: put `/var/lib/margosha` on a separate EBS volume per node (`AWS::EC2::Volume` +
`VolumeAttachment`, `DeletionPolicy: Snapshot` or `Retain`), mounted by the boot script; or at least write down that a
template change means data loss and replace replicas one at a time, which needs finding 1 fixed first.

## 3. One stopped replica makes two of every three reads of its shard take over 2 s (medium–high)

`one-follower-down.md` shows it: with `shard-a-3` stopped, reads that picked it took 2.2 s. The coordinator asks R = 2
replicas, waits for the 2 s shard timeout on the stopped one (a stopped instance doesn't refuse the connection, it just
doesn't answer), and only then asks the third. The round-robin order includes the stopped replica in 2 of 3 reads, and
the coordinator never remembers that it failed: `ReplicaSet` never removes or skips a replica. So "with one follower
down, reads work" (ADR 0007, `replication.md`) is true, but with YCSB's 8 threads throughput would drop to a few
operations per second for that shard. This is the main case replication is for, and it was not benchmarked. Fix: ask all
3 replicas and use the first 2 answers (the alternative design decision 4 already names); or ask the third replica after
a short delay (around the read p99, ~15 ms) instead of after the timeout; or skip a replica for a few seconds after it
fails. A shorter connect timeout (e.g. 200 ms, inside the VPC) helps in any case.

> **Response: fixed.** I used two of the suggestions together. A read that still misses an answer after 50 ms also
> asks the next replica and uses the first R answers (`margosha.shard.read-hedge-delay`). 50 ms is well above a
> replica's usual answer time, since a whole read's p99 was about 15 ms in the benchmark. And a replica that failed to
> answer goes to the end of the read order for 5 s (`margosha.shard.skip-after-failure`). After that, one read that
> would ask it tries it again: an answer puts it back in the rotation, another failure skips it for 5 s more. So a
> stopped follower costs at most 50 ms on the first read that runs into it, and nothing after that. When all replicas
> answer, a read still asks exactly R, so the even-spread check is unchanged. I didn't shorten the connect timeout:
> with the hedge, it no longer decides how long a read waits. New tests:
> `ReplicatedStoreTest.replicaThatDoesNotAnswerDoesNotHoldUpReads` (a server that accepts connections and never
> answers is registered as `shard-a-3`; every read finishes well under the 2 s timeout),
> `QuorumReaderTest.replicaThatDoesNotAnswerDelaysAReadByTheHedgeDelayNotTheTimeout`,
> `replicaThatIsDownIsAskedLastUntilItsSkipTimeIsOver`, `replicaThatAnswersAgainIsBackInTheRotation`, and two
> read-order tests in `ShardRegistryServiceTest`. The 2.2 s reads in `aws_checks/one-follower-down.md` are from before
> the fix; that check has not been run on AWS again yet.

## 4. A read can return a write that isn't confirmed, and the next read can go back to the older value (medium)

The leader applies a write to its in-memory records before it waits for the follower (`ReplicaStorage.update`, then
`LeaderRecordStorage.committed`), and `ReplicaReadService` serves those records. So a read that includes the leader can
return version 57 while no follower has it yet, and the next read, sent to the two followers by the round-robin, returns
56. In normal operation the window is a few milliseconds, but it stays open as long as the followers can't confirm while
the coordinator can still reach them (a follower's replication thread in its 5 s backoff, a broken self-rule in the
shard security group). It also means a client can read a write whose `503` it is still waiting for. ADR 0010 promises
"the latest confirmed write", which still holds; the design asked the ADR to answer "whether a client can read a value
older than one it read before", and the ADR doesn't. Fix: at least write it in ADR 0010's "not guaranteed" list. A
complete fix is for the leader to answer reads only up to the highest version W replicas have (it already knows it from
`followerPositions`), keeping the previous value of a key until its new version is confirmed.

> **Response: accepted, documented.** The behaviour is described with an example in `m3/replication.md`, section
> "What a read does not guarantee: monotonic reads". It covers when the window stays open longer than a few
> milliseconds, and why the newer value never disappears (M3 has no failover or log truncation). I wrote it there
> rather than in ADR 0010, next to the quorum read it belongs to. I didn't make the complete fix: the leader would
> have to keep each key's previous value until its new version is confirmed, which changes the write path that was
> benchmarked. Consistency models are M4's topic, so it is planned there.

## 5. Nothing checks who a replica is (medium)

M2's finding 2 (anyone who reaches the coordinator can register a shard) is still open, and now has more consequences:

- The registry accepts **any number** of replica IDs per shard; N = 3 is only a start-up check of the settings. A fourth
  registration (a wrong tag, a test `curl` from the operator IP, which can reach `POST /shards`) gives 4 replicas with
  W = 2 and R = 2, so R + W is no longer greater than N, and a read can miss the latest write. Replicas are never
  removed either.
- The leader counts a confirmation from **any** `follower=` name. One `GET /replication/log?after=<last>&follower=x` from
  any machine in the shard or coordinator security group "confirms" writes that only the leader has.
- A follower whose `MargoshaLeaderIp` points to the other shard's leader copies that shard's log and serves it as its
  own shard's data; nothing compares shard IDs on a fetch.
- The highest version wins, unchecked: a replica that answers a very large version wins every read it is part of.

In the lab the exposure is small, but all four are also misconfiguration risks, and the third goes unnoticed. Fix: let
the coordinator accept only the replica IDs it expects per shard (N of them, or a list from the stack), let the leader
accept confirmations only from its two follower IDs (passed as tags), send the shard ID with every fetch, and move
`/shards` to a port that only the shard security group reaches.

> **Response: partly fixed.** Three of the four points are fixed in the application. None of these fixes needed a
> template change: a new tag would have changed the launch template and replaced every instance (finding 2).
> - *Any number of replicas:* a shard now accepts at most N replica IDs (`margosha.replication.factor`). A fourth one
>   gets `400` and the shard keeps its three. A registered replica can still register again at a new address.
>   Tests: `ShardRegistryServiceTest.replicaBeyondNIsRejectedSoQuorumsStillOverlap` and
>   `registeredReplicaCanStillMoveWhenTheShardIsFull`.
> - *Any follower name confirms:* the leader now serves, and counts as confirmations, only the replica IDs
>   `<shard>-1` to `<shard>-N` other than its own. The stack already names replicas that way (`MargoshaReplicaId`),
>   so the leader knows its followers' IDs from N, with no new tag. Any other name gets `400`. Test:
>   `ReplicaSetTest.fetchFromAnUnknownReplicaIsNotAConfirmation`.
> - *Follower of the wrong shard:* every fetch now names the follower's shard (`shard=`), and a leader refuses another
>   shard's follower with `400`. The follower logs an `ERROR` and stores nothing. Test:
>   `ReplicaSetTest.followerOfAnotherShardIsRefused`.
> - *Not fixed, accepted for the lab:* `POST /shards` is still on the client port. Someone who can reach it could
>   still register a fake address under one of a shard's replica IDs, which reads would use until the real replica
>   registers again (within 10 s), and that fake could answer a very large version. Only the operator's IP and the
>   VPC can reach that port, the same exposure as M2's finding 2. Moving registration to a port that only the shard
>   security group reaches is an infrastructure change, planned with the security work in M5.

## 6. Rebuild and catch-up were measured with 200–300 entries, not at the size the store reaches (medium)

`rebuild-time.md` measured 0.2 s for 176–305 entries and concludes start-up is "not a problem at this size". After the
benchmark the logs held 280,939 (shard A) and 219,602 (shard B) entries, and no replica was restarted at that size, even
though the design's risk list says the rebuild time is "recorded on AWS" for exactly this reason (and the pipeline gives
up after 12 minutes). The rebuild reads the file one byte at a time and checks and parses every line. Catch-up is the
bigger unknown: a follower applies the entries of a fetched batch one by one, and each `appendReplicated` forces the file
to disk. A follower that was down during one benchmark run (≈ 100,000 entries per shard) needs ~100,000 separate syncs
to catch up, while the shard keeps writing about 250 entries per second; during that time W = 2 depends on the other
follower alone. Heap use with ~50,000 full-size records per replica in `-Xmx512m` was not checked either. Fix: restart a
replica, and stop a follower for a whole run, right after a benchmark and record the times and heap; store a fetched
batch with one sync (append all its entries, sync once, then send the next fetch). The confirmation still means "on
disk", because the next fetch is sent after the sync.

## 7. Losing one AZ still stops the whole store (medium–low)

Design decision 1 says losing an AZ "leaves every shard with at least one replica and one shard with its leader". That
is true, but one replica can't serve R = 2: losing `us-east-1b` leaves `shard-b` with only `shard-b-2`, so it can
neither read nor write, and losing `us-east-1a` takes the coordinator (public subnet A) and with it every request. M1's
finding 1 still applies. Fix: state it in the design and ADR 0007 as it is; the real fix is one replica per AZ in three
AZs, which needs a third private subnet in the VPC stack (ADR 0003 left room in the CIDR plan).

## 8. "The store is not at its limit" depends on the coordinator's CPU credits (medium–low)

`results.md` explains the lower throughput by latency (8 threads ÷ average latency), which fits the numbers. But the
coordinator ran at 36–63% CPU (5-minute averages) on a `t3.micro` in standard mode, whose baseline is 10% per vCPU and
which earns 12 credits per hour. At about half of its 2 vCPUs it spends about 60 credits per hour, so from its lowest
balance of 113 it would be held at its baseline after roughly two more hours of load. The 20-minute benchmark didn't
reach that, but a longer run, or the "more client threads" the results suggest, would measure the coordinator's credits.
It is also the busiest machine again (M2's finding 6), now with two replica requests per read. Fix: write the credit
budget in `results.md`; for later comparisons run the coordinator in unlimited mode during the runs (as the client is)
or record its balance before and after each run.

## 9. Smaller points (low)

- Every deploy still reboots all 7 nodes at once, so each one is an outage (accepted, M5). With durable data a rolling
  deploy is now possible: followers first, then the leader, waiting until the status shows each follower caught up.
- The leader's 1 s acknowledgement timeout starts after the append. Time spent waiting for the shard's write lock and
  the sync isn't counted, so under load the coordinator's 2 s can run out first and the client gets the coordinator's
  `503` instead of the leader's. The meaning is the same ("outcome unknown"), but ADR 0011's "the leader's answer
  reaches the client" is not guaranteed.
- With the leader down, every write waits 2 s for the timeout before `503`, and nothing reports that a shard has no
  writable leader except these errors. A health line on the coordinator (shards without a leader that answers) would
  make the demo and the defense clearer.
- A failed `CREATE` stays in the leader's log, so retrying it answers `409` for the client's own write (M2's finding 9).
  It is documented as "outcome unknown", but the client API has no way to retry a create safely.
- One lock and `notifyAll` serve both the long-polls and the writers waiting for confirmations, so each confirmation
  wakes every waiting writer. Fine with 8 client threads; worth checking if the thread count goes up.
- The table definitions are on the coordinator's root volume only, so finding 2 applies to them too, and losing them
  makes every record unreachable (`TABLE_NOT_FOUND`) even though the replicas still hold it.
