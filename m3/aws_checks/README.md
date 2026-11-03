# M3 checks on AWS

Checks of the replicated store on AWS. Account `797089401427`, region `us-east-1`, 2026-11-02. All times are UTC.

Start with [setup.md](setup.md): it sets up the terminal and the helpers that the checks use.

Plain `aws` commands are written on one line, so they run as they are on **Windows (PowerShell)** and **macOS
(Terminal)**. Commands that use shell features (variables, helpers, loops, scripts) are given for both.

| Check | Result |
|---|---|
| [The first M3 deploy and the 9-instance limit](instance-limit.md) | The first deploy failed: old and new nodes together were 10 instances. Fixed by terminating the old nodes |
| [Deploy through the pipeline](deploy.md) | The pipeline created the M3 stack: coordinator + 6 replicas, all on version `5e23026` |
| [All replicas register with the coordinator](replica-registration.md) | All 6 replicas registered |
| [Placement](placement.md) | Each shard in both private subnets, leaders in different AZs, no public IPs on replicas |
| [A follower can reach its leader](follower-to-leader.md) | Both shards: the follower gets an answer from its leader, across AZs |
| [The bench client cannot reach the replicas](bench-client-isolation.md) | The bench client reaches the coordinator, but none of the 6 replicas |
| [Followers keep up with their leader](replicas-in-sync.md) | After writes, every follower is at its leader's last entry |
| [A restarted follower gets only what it missed](missed-range.md) | A follower that was down for 111 writes received exactly those 111 |
| [Rebuild time on start](rebuild-time.md) | A replica rebuilds 176–305 log entries in 173–245 ms |
| [One follower down](one-follower-down.md) | Reads and writes still work; the leader shows which follower is behind; run again after the fix for critique finding 3 (no read waits for the stopped follower) |
| [Both followers down](both-followers-down.md) | Reads and writes to that shard get `503`; a write marked "outcome unknown" was in fact applied |
| [Data survives an app-only deploy](data-survives-deploy.md) | Every record and every replica's last entry are the same after the deploy |
| [Data survives stopping and starting all instances](data-survives-stop-start.md) | Every record is still there after all 7 nodes were stopped and started, as at the end of a lab session |
| [Leader down](leader-down.md) | Reads still work; writes to that shard get `503`; the other shard is not affected |
