# M2 benchmark results (baseline)

**Spec:** `benchmarking` in `openspec/changes/setup-m2-sharding`; tasks 11.4–11.6.
**How it was run:** [procedure.md](procedure.md) (the trial run that chose the parameters is at the end of it).
**Raw output:** [raw/20261029T211121Z-workloada-r100000-o200000-t8/](raw/20261029T211121Z-workloada-r100000-o200000-t8/)

This is the M2 baseline: one coordinator and two shards, records in memory, every request through the coordinator.
Later milestones (M3, M5) are compared against these numbers.

## What was benchmarked

| | |
|---|---|
| Date | 2026-10-29, 21:11 UTC (Learner Lab, `us-east-1`) |
| Commit | `9a12bf2` ("ycsb binding", deployed by the pipeline) |
| YCSB | 0.17.0, Java 21 (Corretto 21.0.13), binding `com.margosha.ycsb.MargoshaDbClient` (jar sha256 `cb0b0ab68cebdf63`) |
| Workload | **A** (`workloada`): 50% read / 50% update, zipfian key choice, read all fields |
| `recordcount` | 100,000 (10 fields × 100 bytes each) |
| `operationcount` | 200,000 |
| Threads | 8 |
| Table | `usertable`, partition key `ycsb_key` (YCSB's key), no sort key |
| Coordinator | `t3.micro`, public subnet (`us-east-1a`), `-Xmx512m`, standard credits |
| Shards | 2 × `t3.micro`, private subnets A (`us-east-1a`) and B (`us-east-1b`), `-Xmx512m`, standard credits |
| YCSB client | `t3.small`, public subnet, same VPC; calls the coordinator's private IP |

**Why workload A:** the API has no scan, so E can't run. A sends both reads and writes through the ring, and writes are the
harder case for the store. B and C are almost only reads; D keeps inserting during the run, so memory grows on 1 GiB
nodes; F sends the same requests as A, in pairs. Details in [procedure.md](procedure.md#workload).

**Why these parameters:** in the trial, throughput was highest at 8 threads; from 16 threads on it stayed flat while
latency doubled. 100,000 records are about 50,000 per shard, well within the shards' heap. 200,000 operations took
just over 2 minutes. See [procedure.md, trial run results](procedure.md#trial-run-results).

## Results

### Load phase (100,000 INSERT)

| Throughput | Avg | p95 | p99 | Min | Max | Failed |
|---|---|---|---|---|---|---|
| **1,006 ops/s** (99.4 s) | 7.9 ms | 16.9 ms | 33.7 ms | 1.9 ms | 684 ms | 0 (`Return=OK` 100,000) |

### Run phase (workload A, 200,000 operations)

Overall: **1,531 ops/s** (130.6 s).

| Operation | Count | Avg | p95 | p99 | Min | Max | Failed |
|---|---|---|---|---|---|---|---|
| READ | 100,092 | **4.3 ms** | **7.8 ms** | **21.6 ms** | 1.3 ms | 313 ms | 0 (`Return=OK` 100,092) |
| UPDATE | 99,908 | **6.0 ms** | **11.6 ms** | **29.2 ms** | 1.7 ms | 366 ms | 0 (`Return=OK` 99,908) |

**No failed operations** in either phase: every operation returned `OK`, and the binding printed no error lines
(`margosha:` in `load.log` / `run.log`).

### Throughput over time

From YCSB's 10-second status lines (`load.log`, `run.log`), in ops/s:

| Phase | 0–10 s | 10–20 s | 20–30 s | 30–60 s | after 60 s |
|---|---|---|---|---|---|
| Load | 357 | 674 | 834 | 700–750 | 1,260–1,420 |
| Run | 554 | 934 | 1,359 | 1,520–1,810 | 1,390–1,820 |

Both phases start slowly. The nodes had just been rebooted to empty the store, so their JVMs were still compiling the
hot code paths (JIT warm-up), and the YCSB client had just started too. In steady state the run phase holds about
**1,700–1,800 ops/s**. The overall numbers above include the warm-up, as YCSB reports them.

### Resources during the run

From CloudWatch, 5-minute averages:

| Instance | Max CPU | Lowest CPU credit balance |
|---|---|---|
| Coordinator | **63%** | 17.1 |
| Shard A | 14% | 27.0 |
| Shard B | 11% | 27.4 |
| YCSB client | 38% | 18.5 |

No instance ran out of CPU credits, so nothing was throttled. Neither shard logged an `OutOfMemoryError` or an error-level
line.

## What the numbers say

- **The coordinator is the busiest machine.** Every request goes through it: it parses the client's JSON, hashes the key,
  sends a second HTTP request to the shard and passes the answer back. It ran at up to 63% CPU while the shards stayed
  under 15%. So more shards would most likely not raise the throughput much: the single coordinator is the first limit
  (the M2 design accepts this).
- **Updates are slower than reads** (6.0 vs 4.3 ms avg). An update carries a body both ways (client → coordinator → shard)
  and replaces the stored record.
- **p99 is 3–5 times the average.** The averages are a few ms (two HTTP hops inside the VPC). The slowest 1% were not
  measured separately; likely causes are garbage collection pauses on the nodes and requests queueing for the
  coordinator's two vCPUs. The warm-up at the start of each phase also adds to the tail.
- **Records live in memory.** The data was lost at the next reboot (as designed for M2); durability comes in M3.
- YCSB's updates send one field, and UPDATE replaces all attributes, so after the run many records have only one field.
  YCSB doesn't check values, and this doesn't change the latency or throughput.

## After the benchmark

The bench client was switched off with `BenchClient=false`, as in [procedure.md, step 8](procedure.md#8-turn-off-the-bench-client-task-116):

- `aws ec2 describe-instances` → `"terminated"`;
- through the coordinator's public IP: `POST /tables/usertable/records` → `201 {"field0":"x","ycsb_key":"after-bench"}`,
  then `GET /tables/usertable/records/after-bench` → `200`. The store still answered, and `usertable` from the benchmark
  was still registered, so the coordinator and the shards weren't restarted by the stack update.

## Raw files

In [raw/20261029T211121Z-workloada-r100000-o200000-t8/](raw/20261029T211121Z-workloada-r100000-o200000-t8/):

| File | Content |
|---|---|
| [params.txt](raw/20261029T211121Z-workloada-r100000-o200000-t8/params.txt) | Date, commit, YCSB version, workload, counts, threads, coordinator, binding hash, client instance type, Java version |
| [workloada](raw/20261029T211121Z-workloada-r100000-o200000-t8/workloada) | The workload file as used |
| [register-table.json](raw/20261029T211121Z-workloada-r100000-o200000-t8/register-table.json) | The coordinator's answer to registering `usertable` |
| [load.txt](raw/20261029T211121Z-workloada-r100000-o200000-t8/load.txt), [run.txt](raw/20261029T211121Z-workloada-r100000-o200000-t8/run.txt) | YCSB's final results per phase (latencies in µs) |
| [load.log](raw/20261029T211121Z-workloada-r100000-o200000-t8/load.log), [run.log](raw/20261029T211121Z-workloada-r100000-o200000-t8/run.log) | YCSB's progress every 10 s |
