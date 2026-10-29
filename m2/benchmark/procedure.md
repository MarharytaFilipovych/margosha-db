# M2 benchmark procedure

**Spec:** `benchmarking` in `openspec/changes/setup-m2-sharding`; tasks 11.3–11.6.
**Script:** [run-benchmark.sh](run-benchmark.sh) (runs on the bench client).
**Binding:** `com.margosha.ycsb.MargoshaDbClient` in [app/ycsb-binding](../../app/ycsb-binding), published by the pipeline to
`s3://margosha-artifacts-<account-id>/releases/margosha-ycsb-binding.jar`.

The benchmark runs YCSB on the bench client (`margosha-bench`, `t3.small`, public subnet) against the coordinator's private
address. The coordinator routes every request to one of the 2 shards. Everything is in the same VPC, so no traffic leaves AWS.

```
laptop ──ssh/scp──► margosha-bench (YCSB 0.17.0 + binding jar)
                        │ HTTP, coordinator's private IP:8080
                        ▼
                    coordinator ──► shard A / shard B
```

## What is fixed

| | Value | Why |
|---|---|---|
| YCSB | 0.17.0 release (`/opt/ycsb` on the bench client) | Installed by the bench client's UserData |
| Workload | **A** (`workloada`): 50% read, 50% update, zipfian key choice | See [Workload](#workload) |
| Table | `usertable`, partition key `ycsb_key`, no sort key | [ADR 0006](../../adr/0006-partition-key-strategy.md): YCSB's key is the partition key |
| Record | 10 fields × 100 bytes (YCSB default) | Same record shape as other YCSB results |
| `recordcount` | set after the trial run (step 5) | Must fit in the shards' heap (`-Xmx512m`) |
| `operationcount` | set after the trial run | Run long enough to be stable, short enough for the CPU credits |
| `threads` | set after the trial run | Where throughput stops growing |
| Nodes | coordinator + 2 shards, `t3.micro`, standard credits | [app_infrastructure.md](../app_infrastructure.md) |

### Workload

The API has no scan, so workload E is out. Of the others:
- **A** (50/50 read/update) uses both paths through the ring, and writes are the harder case for the store. **Chosen.**
- B and C (95–100% reads) say little about writes.
- D inserts new records during the run, so memory keeps growing on 1 GiB nodes and results depend on how full they are.
- F (read-modify-write) sends the same two requests as A, but in pairs; it adds nothing that A doesn't measure.

UPDATE replaces all attributes, while YCSB's update sends one field, so an updated record keeps only that field. That
doesn't change the latency or throughput measured; YCSB doesn't check the values (`dataintegrity` is off).

---

## Steps

All laptop commands start from the repo root, in the same terminal window. First run the **session setup and output
variables** from [app_infrastructure.md, steps 0 and 3](../app_infrastructure.md#0-session-setup); they define `Show-Log`,
`Out`, `$BUCKET`, `$COORD`, `$SHARD_A`, `$SHARD_B` and `$MY_IP` (`show_log`, `out`, … on macOS).

### 1. Deploy the commit to benchmark

Push it to `main`, or start **Actions → pipeline → Run workflow** on `main` (README, "Deploy pipeline"). The run's Summary
lists both jars. Note the commit from the Summary: it goes into the results.

Check that both jars are in the bucket:

Windows (PowerShell):
```powershell
aws s3 ls "s3://$BUCKET/releases/"
$COMMIT = git rev-parse --short origin/main
```

macOS (Terminal):
```bash
aws s3 ls "s3://$BUCKET/releases/"
COMMIT=$(git rev-parse --short origin/main)
```
Expected: `margosha.jar` and `margosha-ycsb-binding.jar`, uploaded at the time of the run.

### 2. Turn on the bench client

`OperatorCidr` opens SSH to the bench client from your IP. The pipeline keeps both parameters as they are on later deploys.

Windows (PowerShell):
```powershell
aws cloudformation deploy --stack-name margosha-app --template-file cfn/margosha-app.yaml `
  --parameter-overrides BenchClient=true "OperatorCidr=$MY_IP/32" --no-fail-on-empty-changeset
$BENCH    = Out BenchClientInstanceId
$BENCH_IP = aws ec2 describe-instances --instance-ids $BENCH --query "Reservations[0].Instances[0].PublicIpAddress" --output text
Wait-Log $BENCH "BENCH-READY" 15
```

macOS (Terminal):
```bash
aws cloudformation deploy --stack-name margosha-app --template-file cfn/margosha-app.yaml \
  --parameter-overrides BenchClient=true "OperatorCidr=$MY_IP/32" --no-fail-on-empty-changeset
BENCH=$(out BenchClientInstanceId)
BENCH_IP=$(aws ec2 describe-instances --instance-ids "$BENCH" --query "Reservations[0].Instances[0].PublicIpAddress" --output text)
wait_log "$BENCH" "BENCH-READY" 15
```

**SSH key:** on the lab page, **AWS Details → Download PEM** saves `labsuser.pem`. Put it in `~/.ssh/`.

Windows (PowerShell). OpenSSH refuses a key file other users can read, so the permissions are narrowed once:
```powershell
$PEM = "$HOME\.ssh\labsuser.pem"
icacls $PEM /inheritance:r /grant:r "$($env:USERNAME):R"
function Bench { ssh -i $PEM -o StrictHostKeyChecking=accept-new "ec2-user@$BENCH_IP" @args }
Bench "java -version; ls /opt/ycsb/workloads"
```

macOS (Terminal):
```bash
PEM=~/.ssh/labsuser.pem
chmod 400 "$PEM"
bench() { ssh -i "$PEM" -o StrictHostKeyChecking=accept-new "ec2-user@$BENCH_IP" "$@"; }
bench "java -version; ls /opt/ycsb/workloads"
```
Expected: `openjdk version "21…"` and the YCSB workload files (`workloada` … `workloadf`).

Copy the script to the bench client (again after any change to it):

Windows (PowerShell):
```powershell
scp -i $PEM m2/benchmark/run-benchmark.sh "ec2-user@$($BENCH_IP):"
```

macOS (Terminal):
```bash
scp -i "$PEM" m2/benchmark/run-benchmark.sh "ec2-user@$BENCH_IP:"
```

### 3. Start from an empty store

Records live in memory, so a reboot of the three nodes empties the store; the shards register again by themselves.
Do this before every **load** (a load into a store that already has `usertable` stops with a message).

Windows (PowerShell):
```powershell
aws ec2 reboot-instances --instance-ids $COORD $SHARD_A $SHARD_B
Start-Sleep 90
Show-Log $COORD "Registered shard"
```

macOS (Terminal):
```bash
aws ec2 reboot-instances --instance-ids "$COORD" "$SHARD_A" "$SHARD_B"
sleep 90
show_log "$COORD" "Registered shard"
```
The **last two** lines must be `Registered shard shard-a` and `shard-b` with a time after the reboot (the log also keeps
earlier boots). If not, wait 30 s and run `Show-Log` again.

### 4. Check the CPU credits

`t3.micro` with standard credits is throttled to 10% CPU when its credits run out, which would make the results meaningless.

Windows (PowerShell):
```powershell
foreach ($id in $COORD, $SHARD_A, $SHARD_B) {
  aws cloudwatch get-metric-statistics --namespace AWS/EC2 --metric-name CPUCreditBalance `
    --dimensions "Name=InstanceId,Value=$id" --statistics Minimum --period 300 `
    --start-time (Get-Date).ToUniversalTime().AddMinutes(-15).ToString("s") --end-time (Get-Date).ToUniversalTime().ToString("s") `
    --query "sort_by(Datapoints,&Timestamp)[-1].Minimum" --output text
}
```

macOS (Terminal):
```bash
for id in "$COORD" "$SHARD_A" "$SHARD_B"; do
  aws cloudwatch get-metric-statistics --namespace AWS/EC2 --metric-name CPUCreditBalance \
    --dimensions "Name=InstanceId,Value=$id" --statistics Minimum --period 300 \
    --start-time "$(date -u -v-15M +%Y-%m-%dT%H:%M:%S 2>/dev/null || date -u -d '-15 min' +%Y-%m-%dT%H:%M:%S)" \
    --end-time "$(date -u +%Y-%m-%dT%H:%M:%S)" \
    --query "sort_by(Datapoints,&Timestamp)[-1].Minimum" --output text
done
```
Each number is the balance in credits (1 credit = 1 vCPU at 100% for 1 minute). Write them down; a run of a few minutes
uses only a few credits per node. `None` means no data point yet (the instance just started): wait 5 minutes.

### 5. Trial run: choose the thread count and the record and operation counts

Empty the store (step 3). Then load a small data set once, and run the workload with more and more threads without reloading:

Windows (PowerShell):
```powershell
Bench "RECORDS=10000 OPERATIONS=20000 THREADS=8 COMMIT=$COMMIT bash run-benchmark.sh"
foreach ($t in 4, 16, 32, 64) { Bench "RECORDS=10000 OPERATIONS=20000 THREADS=$t SKIP_LOAD=1 COMMIT=$COMMIT bash run-benchmark.sh" }
```

macOS (Terminal):
```bash
bench "RECORDS=10000 OPERATIONS=20000 THREADS=8 COMMIT=$COMMIT bash run-benchmark.sh"
for t in 4 16 32 64; do bench "RECORDS=10000 OPERATIONS=20000 THREADS=$t SKIP_LOAD=1 COMMIT=$COMMIT bash run-benchmark.sh"; done
```
Each run prints throughput and latencies and ends with `No failed operations.` or the failures.

- **Threads:** the smallest count where throughput stops growing by more than ~10%; more threads after that only add latency.
- **Records:** must fit in memory. Load the chosen size into a fresh store (step 3) and check that no node ran out of heap:
  `Show-Log $SHARD_A "OutOfMemoryError|margosha-run.*ERROR"` (and shard B) prints nothing.
- **Operations:** about 3–5 minutes at the trial throughput (`operations ≈ throughput × 240`).

The trial outputs stay on the bench client in `~/results/`; only the chosen values go into the table at the top.

### 6. The benchmark run (task 11.4)

Empty the store (step 3), then run with the values from step 5:

Windows (PowerShell):
```powershell
Bench "RECORDS=<records> OPERATIONS=<operations> THREADS=<threads> COMMIT=$COMMIT bash run-benchmark.sh"
```

macOS (Terminal):
```bash
bench "RECORDS=<records> OPERATIONS=<operations> THREADS=<threads> COMMIT=$COMMIT bash run-benchmark.sh"
```
The last lines say `No failed operations.` or list the failed operations; failures are written into the results.

### 7. Copy the outputs into the repo (task 11.5)

The run's folder (printed as `Results: …`) has `params.txt`, the workload file, `register-table.json`, and the YCSB
output: `load.txt` / `run.txt` (final numbers) and `load.log` / `run.log` (progress every 10 s).

Windows (PowerShell):
```powershell
New-Item -ItemType Directory -Force m2/benchmark/raw | Out-Null
scp -i $PEM -r "ec2-user@$($BENCH_IP):results/<run folder>" m2/benchmark/raw/
```

macOS (Terminal):
```bash
mkdir -p m2/benchmark/raw
scp -i "$PEM" -r "ec2-user@$BENCH_IP:results/<run folder>" m2/benchmark/raw/
```
The summary goes into [results.md](results.md): workload and why, parameters, throughput, avg / p95 / p99 latency per
operation (`[READ]`, `[UPDATE]`, `[INSERT]` lines in `run.txt` / `load.txt`, in µs), the commit and the instance types.

### 8. Turn off the bench client (task 11.6)

Windows (PowerShell):
```powershell
aws cloudformation deploy --stack-name margosha-app --template-file cfn/margosha-app.yaml `
  --parameter-overrides BenchClient=false --no-fail-on-empty-changeset
aws ec2 describe-instances --instance-ids $BENCH --query "Reservations[0].Instances[0].State.Name"
$COORD_PUBLIC = aws ec2 describe-instances --instance-ids $COORD --query "Reservations[0].Instances[0].PublicIpAddress" --output text
$API = "http://$($COORD_PUBLIC):8080"
Api POST "/tables/usertable/records" '{"ycsb_key":"after-bench","field0":"x"}'
Api GET  "/tables/usertable/records/after-bench"
```

macOS (Terminal):
```bash
aws cloudformation deploy --stack-name margosha-app --template-file cfn/margosha-app.yaml \
  --parameter-overrides BenchClient=false --no-fail-on-empty-changeset
aws ec2 describe-instances --instance-ids "$BENCH" --query "Reservations[0].Instances[0].State.Name"
COORD_PUBLIC=$(aws ec2 describe-instances --instance-ids "$COORD" --query "Reservations[0].Instances[0].PublicIpAddress" --output text)
API="http://$COORD_PUBLIC:8080"
api POST "/tables/usertable/records" '{"ycsb_key":"after-bench","field0":"x"}'
api GET  "/tables/usertable/records/after-bench"
```
Expected: `"terminated"` (or `"shutting-down"` for a minute), then `201` and `200`: the store still answers, and
`usertable` from the benchmark is still registered (the nodes were not restarted).

`OperatorCidr` is not passed, so it keeps its value: the API is still open to your IP for the last two calls.

---

## Trial run results

*Filled in after step 5.*
