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
| `recordcount` | **100,000** | About 50,000 per shard, roughly 125 MB of heap each, well inside `-Xmx512m`; load takes ~4 min at the trial's ~410 inserts/s |
| `operationcount` | **200,000** | ~3.5 min at the trial's ~1,000 ops/s: long enough to be stable, short enough for the CPU credits |
| `threads` | **8** | Throughput peaks at 8 threads in the [trial](#trial-run-results); more threads only add latency |
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

All laptop commands run from the repo root, in **one** terminal window (the variables and helpers are lost when it closes).

### 0. Session setup

Credentials from the lab page go into `~/.aws/credentials` as usual. Then:

Windows (PowerShell):
```powershell
cd "C:\4 course uni\margosha-db"
aws configure set region us-east-1
aws sts get-caller-identity
# Without these two lines the AWS CLI on Windows fails on the "→" in the node logs
$env:PYTHONUTF8 = '1'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

function Show-Log($id, $pattern = 'margosha:|Registered shard|Running as|BENCH-READY') {
  aws ec2 get-console-output --instance-id $id --latest --output text | Select-String $pattern
}
function Wait-Log($id, $pattern, $minutes = 10) {
  $end = (Get-Date).AddMinutes($minutes)
  while ((Get-Date) -lt $end) {
    $hit = aws ec2 get-console-output --instance-id $id --latest --output text | Select-String $pattern
    if ($hit) { return $hit }
    Start-Sleep 15
  }
  "not seen within $minutes min: $pattern"
}
function Api($method, $path, $body) {
  try {
    $r = Invoke-WebRequest -UseBasicParsing -Method $method -Uri "$API$path" -ContentType 'application/json' -Body $body
    "$($r.StatusCode) $($r.Content)"
  } catch {
    $resp = $_.Exception.Response
    if ($resp) { $sr = New-Object IO.StreamReader($resp.GetResponseStream()); "$([int]$resp.StatusCode) $($sr.ReadToEnd())" }
    else { "ERROR $($_.Exception.Message)" }
  }
}
function Out($key) {
  aws cloudformation describe-stacks --stack-name margosha-app --query "Stacks[0].Outputs[?OutputKey=='$key'].OutputValue" --output text
}

$BUCKET  = Out ArtifactsBucketName
$COORD   = Out CoordinatorInstanceId
$SHARD_A = Out ShardAInstanceId
$SHARD_B = Out ShardBInstanceId
"$BUCKET $COORD $SHARD_A $SHARD_B"
```

macOS (Terminal):
```bash
cd ~/margosha-db          # wherever the repo is cloned
aws configure set region us-east-1
aws sts get-caller-identity

show_log() {
  aws ec2 get-console-output --instance-id "$1" --latest --output text \
    | grep -E "${2:-margosha:|Registered shard|Running as|BENCH-READY}"
}
wait_log() {
  local id=$1 pattern=$2 minutes=${3:-10}
  local end=$(( $(date +%s) + minutes * 60 )) hit
  while [ "$(date +%s)" -lt "$end" ]; do
    hit=$(aws ec2 get-console-output --instance-id "$id" --latest --output text | grep -E "$pattern")
    if [ -n "$hit" ]; then echo "$hit"; return 0; fi
    sleep 15
  done
  echo "not seen within $minutes min: $pattern"
}
api() {
  local args=(-s -m 10 -o /tmp/api-body -w '%{http_code}' -X "$1" "$API$2" -H 'Content-Type: application/json')
  [ -n "${3:-}" ] && args+=(-d "$3")
  rm -f /tmp/api-body
  echo "$(curl "${args[@]}") $(cat /tmp/api-body 2>/dev/null)"
}
out() { aws cloudformation describe-stacks --stack-name margosha-app --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text; }

BUCKET=$(out ArtifactsBucketName)
COORD=$(out CoordinatorInstanceId)
SHARD_A=$(out ShardAInstanceId)
SHARD_B=$(out ShardBInstanceId)
echo "$BUCKET $COORD $SHARD_A $SHARD_B"
```
The last line must print the bucket name and three `i-…` IDs. Blank values mean the credentials have expired.

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
$MY_IP = (Invoke-RestMethod https://checkip.amazonaws.com).Trim()
aws cloudformation deploy --stack-name margosha-app --template-file cfn/margosha-app.yaml `
  --parameter-overrides BenchClient=true "OperatorCidr=$MY_IP/32" --no-fail-on-empty-changeset
$BENCH    = Out BenchClientInstanceId
$BENCH_IP = aws ec2 describe-instances --instance-ids $BENCH --query "Reservations[0].Instances[0].PublicIpAddress" --output text
Wait-Log $BENCH "BENCH-READY" 15
```

macOS (Terminal):
```bash
MY_IP=$(curl -s https://checkip.amazonaws.com)
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

With standard credits, an instance whose balance reaches 0 is held at its baseline CPU (`t3.micro` 10%, `t3.small` 20%),
which makes the results meaningless. **This includes the bench client:** a new T3 instance in standard mode starts with
**0 credits** (T3 has no launch credits), and its UserData (Java, YCSB) uses what it earns at first. A `t3.small` earns
24 credits per hour, so wait until the bench client has **at least 20** (about an hour after it was created) before the
trial and before the real run. A 5-minute run at full CPU costs it about 10.

Windows (PowerShell):
```powershell
foreach ($id in $BENCH, $COORD, $SHARD_A, $SHARD_B) {
  aws cloudwatch get-metric-statistics --namespace AWS/EC2 --metric-name CPUCreditBalance `
    --dimensions "Name=InstanceId,Value=$id" --statistics Minimum --period 300 `
    --start-time (Get-Date).ToUniversalTime().AddMinutes(-15).ToString("s") --end-time (Get-Date).ToUniversalTime().ToString("s") `
    --query "sort_by(Datapoints,&Timestamp)[-1].Minimum" --output text
}
```

macOS (Terminal):
```bash
for id in "$BENCH" "$COORD" "$SHARD_A" "$SHARD_B"; do
  aws cloudwatch get-metric-statistics --namespace AWS/EC2 --metric-name CPUCreditBalance \
    --dimensions "Name=InstanceId,Value=$id" --statistics Minimum --period 300 \
    --start-time "$(date -u -v-15M +%Y-%m-%dT%H:%M:%S 2>/dev/null || date -u -d '-15 min' +%Y-%m-%dT%H:%M:%S)" \
    --end-time "$(date -u +%Y-%m-%dT%H:%M:%S)" \
    --query "sort_by(Datapoints,&Timestamp)[-1].Minimum" --output text
done
```
The numbers are the balances of the bench client, the coordinator, shard A and shard B, in credits (1 credit = 1 vCPU at
100% for 1 minute). Write them down. The nodes use only a few credits per run. `None` means no data point yet (the
instance just started): wait 5 minutes.

Run the same check **after** each run as well: if the bench client is back at 0, its numbers are limited by its own CPU,
not by the store.

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

### 6. The benchmark run

Check the credits (step 4: the bench client needs at least 10, the nodes at least 15), empty the store (step 3), then run
with the values from the table at the top (they are also the script's defaults). Load and run take about 8 minutes:

Windows (PowerShell):
```powershell
Bench "RECORDS=100000 OPERATIONS=200000 THREADS=8 COMMIT=$COMMIT bash run-benchmark.sh"
Show-Log $SHARD_A "OutOfMemoryError|ERROR \d+ ---"
Show-Log $SHARD_B "OutOfMemoryError|ERROR \d+ ---"
```

macOS (Terminal):
```bash
bench "RECORDS=100000 OPERATIONS=200000 THREADS=8 COMMIT=$COMMIT bash run-benchmark.sh"
show_log "$SHARD_A" "OutOfMemoryError|ERROR [0-9]+ ---"
show_log "$SHARD_B" "OutOfMemoryError|ERROR [0-9]+ ---"
```
(`ERROR <pid> ---` is how Spring writes an error-level log line. A plain `error` would also match the harmless `WARN`
lines from a shard retrying registration while the coordinator boots.)
The run ends with `No failed operations.` or lists the failed operations; failures are written into the results. The two
`Show-Log` lines must print nothing (no shard ran out of heap). Then check the credits again (step 4): if the bench client
reached 0, the run was limited by the client and has to be repeated later.

### 7. Copy the outputs into the repo

The run's folder (printed as `Results: …`) has `params.txt`, the workload file, `register-table.json`, and the YCSB
output: `load.txt` / `run.txt` (final numbers) and `load.log` / `run.log` (progress every 10 s).

The commands take the newest folder in `~/results`, which is the run from step 6:

Windows (PowerShell):
```powershell
$RUN = (Bench "ls -td results/*/ | head -n 1").Trim().TrimEnd('/')
$RUN
New-Item -ItemType Directory -Force m2/benchmark/raw | Out-Null
scp -i $PEM -r "ec2-user@$($BENCH_IP):$RUN" m2/benchmark/raw/
Get-ChildItem "m2/benchmark/raw/$(Split-Path $RUN -Leaf)"
```

macOS (Terminal):
```bash
RUN=$(bench "ls -td results/*/ | head -n 1" | sed 's:/*$::')
echo "$RUN"
mkdir -p m2/benchmark/raw
scp -i "$PEM" -r "ec2-user@$BENCH_IP:$RUN" m2/benchmark/raw/
ls "m2/benchmark/raw/$(basename "$RUN")"
```
`$RUN` must be the folder printed as `Results: …` at the end of step 6. The copy has 7 files: `params.txt`,
`workloada`, `register-table.json`, `load.txt`, `load.log`, `run.txt`, `run.log`.
The summary goes into [results.md](results.md): workload and why, parameters, throughput, avg / p95 / p99 latency per
operation (`[READ]`, `[UPDATE]`, `[INSERT]` lines in `run.txt` / `load.txt`, in µs), the commit and the instance types.

### 8. Turn off the bench client

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

Run on **2026-10-29** (binding sha256 `cb0b0ab68cebdf63`), workload A, 10,000 records loaded once with 4 threads, then 20,000 operations per
thread count (`SKIP_LOAD=1`). CPU credits before / after: bench client 9.4 / 17.9, coordinator 18.1 / 21.0, shards 20.9 / 25.7
and 21.1 / 25.9, so nothing was throttled.

| Threads | Throughput (ops/s) | READ avg / p95 / p99 (ms) | UPDATE avg / p95 / p99 (ms) | Failed |
|---|---|---|---|---|
| 4 | 750 | 4.6 / 11.8 / 17.1 | 5.7 / 14.3 / 20.2 | 0 |
| **8** | **1,006** | 6.1 / 12.1 / 23.3 | 9.1 / 18.5 / 31.3 | 0 |
| 16 | 914 | 12.3 / 29.4 / 57.1 | 21.1 / 44.9 / 81.8 | 0 |
| 32 | 915 | 23.7 / 54.0 / 95.4 | 43.1 / 89.5 / 146.6 | 0 |

The load (10,000 inserts, 4 threads) ran at 410 ops/s, avg 9.4 ms, p99 37 ms.

Throughput is highest at 8 threads. From 16 threads on it stays flat while latency doubles with every doubling of the
threads, so the store is saturated at about 1,000 ops/s and extra threads only wait in queues. **8 threads** is used.

**A first trial was thrown away.** It ran right after the bench client was created, while its CPU credit balance was 0
(a new T3 in standard mode gets no launch credits). The client was held at 20% CPU, and throughput fell from 758 ops/s to
153 ops/s over the runs while the nodes were nearly idle: it measured the client, not the store. This is why step 4
checks the bench client's credits.
