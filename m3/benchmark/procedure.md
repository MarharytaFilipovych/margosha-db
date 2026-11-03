# M3 benchmark procedure

**Spec:** `benchmarking` in `openspec/changes/setup-m3-durability`.

**Script:** [run-benchmark.sh](run-benchmark.sh) (runs on the bench client).

**Binding:** `com.margosha.ycsb.MargoshaDbClient` in [app/ycsb-binding](../../app/ycsb-binding), published by the pipeline
with every version to `s3://margosha-artifacts-<account-id>/releases/<version>/margosha-ycsb-binding.jar`.

**M2 baseline:** [m2/benchmark/procedure.md](../../m2/benchmark/procedure.md) and
[m2/benchmark/results.md](../../m2/benchmark/results.md).

YCSB runs on the bench client (`margosha-bench`, `t3.small`, public subnet) against the coordinator's private address.
The coordinator sends each write to the leader of the key's shard and each read to 2 of its 3 replicas. Everything is in
the same VPC.

```
laptop ──ssh/scp──► margosha-bench (YCSB 0.17.0 + binding jar)
                        │ HTTP, coordinator's private IP:8080
                        ▼
                    coordinator ──► shard A: leader + 2 followers
                                └─► shard B: leader + 2 followers
```

## What is fixed

The workload and parameters are **the same as in M2**, so the results can be compared. What changed is the store.

| | Value | Same as M2? |
|---|---|---|
| YCSB | 0.17.0 release (`/opt/ycsb` on the bench client) | yes |
| Workload | **A** (`workloada`): 50% read, 50% update, zipfian key choice | yes ([why A](../../m2/benchmark/procedure.md#workload)) |
| Table | `usertable`, partition key `ycsb_key`, no sort key | yes |
| Record | 10 fields × 100 bytes (YCSB default) | yes |
| `recordcount` | **100,000** | yes |
| `operationcount` | **200,000** per run | yes |
| `threads` | **8** | yes (chosen in the [M2 trial](../../m2/benchmark/procedure.md#trial-run-results)) |
| Runs | load, then a **warm-up run** (discarded), then **3 measured runs** on the same data | **new** |
| Nodes | coordinator + 6 replicas (2 shards × leader + 2 followers), `t3.micro`, standard credits | **new** (M2: coordinator + 2 shards) |
| Bench client | `t3.small`, switched to **unlimited** credits while benchmarking, back to standard after | **new** (M2: standard, waited for credits) |
| Replication | N = 3 replicas per shard, write quorum W = 2, read quorum R = 2 (written to `params.txt`) | **new** |
| Data | on each replica's disk (replication log), rebuilt into memory on start | **new** (M2: memory only) |

**Why a warm-up run.** Right after the load, the JVMs are still compiling hot code and the caches are cold, so the first
run is slower than the next ones. Its results are thrown away; only the 3 runs after it count, and the results report
each of them and their median.

**What the 4 runs do to the data.** All runs use the 100,000 loaded records; they update them but add none. The logs grow
by about 1 entry per write: 100,000 + 4 × ~100,000 updates ≈ 500,000 entries in total, about 250,000 per shard, well
under the 8 GiB disks.

---

## Steps

All laptop commands run from the repo root, in **one** terminal window: the variables and helpers are lost when it
closes. Plain `aws` and `scp` commands are written on one line and run as they are on Windows and macOS; the others are
given for both.

### 0. Session setup

Credentials from the lab page go into `~/.aws/credentials` as usual. The SSH key comes from the lab page: **AWS Details →
Download PEM** (`labsuser.pem`), saved in `~/.ssh/`.

This sets the region, gets your IP, defines the helpers, and looks up the stack:
- `Show-Log` / `show_log`: the lines of a node's log that match a pattern (from its serial console, no SSH);
- `Wait-Log` / `wait_log`: waits until a node's log has a line that matches;
- `Out` / `out`: a value from the app stack's outputs;
- `Ip` / `node_ip`: the private IP (or another field) of a running node, by name;
- `Coord` / `coord`: runs a command on the coordinator over SSH;
- `Via-Coord` / `via_coord`: runs a command on a replica over SSH, through the coordinator (the replicas are private);
- `Status` / `status`: a replica's replication status;
- `Credits` / `credits`: the CPU credit balance of the bench client and the 7 nodes.

Windows (PowerShell):
```powershell
cd ~\margosha-db          # wherever the repo is cloned
aws configure set region us-east-1
aws sts get-caller-identity
# Without these two lines the AWS CLI on Windows fails on the "→" in the node logs
$env:PYTHONUTF8 = '1'; [Console]::OutputEncoding = [Text.Encoding]::UTF8
$MY_IP = (Invoke-RestMethod https://checkip.amazonaws.com).Trim()
$PEM = "$HOME\.ssh\labsuser.pem"

function Show-Log($id, $pattern) { aws ec2 get-console-output --instance-id $id --latest --output text | Select-String $pattern }
function Wait-Log($id, $pattern, $minutes = 10) {
  $end = (Get-Date).AddMinutes($minutes)
  while ((Get-Date) -lt $end) {
    $hit = aws ec2 get-console-output --instance-id $id --latest --output text | Select-String $pattern
    if ($hit) { return $hit }
    Start-Sleep 15
  }
  "not seen within $minutes min: $pattern"
}
function Out($key) { aws cloudformation describe-stacks --stack-name margosha-app --query "Stacks[0].Outputs[?OutputKey=='$key'].OutputValue" --output text }
function Ip($name, $field = 'PrivateIpAddress') {
  aws ec2 describe-instances --filters "Name=tag:Name,Values=margosha-$name" "Name=instance-state-name,Values=running" `
    --query "Reservations[0].Instances[0].$field" --output text
}
function Coord { ssh -i $PEM -o StrictHostKeyChecking=accept-new "ec2-user@$COORD_PUB" @args }
function Via-Coord($ip) {
  ssh -i $PEM -o StrictHostKeyChecking=accept-new `
    -o "ProxyCommand=ssh -i $PEM -o StrictHostKeyChecking=accept-new -W %h:%p ec2-user@$COORD_PUB" "ec2-user@$ip" @args
}
function Status($ip) { Coord "curl -s -m 5 http://${ip}:8080/replication/status" }
function Credits {
  foreach ($id in (@($BENCH) + $NODES | Where-Object { $_ })) {
    $name = aws ec2 describe-instances --instance-ids $id --query "Reservations[0].Instances[0].Tags[?Key=='Name']|[0].Value" --output text
    $credits = aws cloudwatch get-metric-statistics --namespace AWS/EC2 --metric-name CPUCreditBalance `
      --dimensions "Name=InstanceId,Value=$id" --statistics Minimum --period 300 `
      --start-time (Get-Date).ToUniversalTime().AddMinutes(-15).ToString("s") --end-time (Get-Date).ToUniversalTime().ToString("s") `
      --query "sort_by(Datapoints,&Timestamp)[-1].Minimum" --output text
    "$name $credits"
  }
}

$BUCKET    = Out ArtifactsBucketName
$VERSION   = aws cloudformation describe-stacks --stack-name margosha-app --query "Stacks[0].Parameters[?ParameterKey=='AppVersion'].ParameterValue" --output text
$COORD     = Out CoordinatorInstanceId
$NODES     = (Out NodeInstanceIds) -split ','
$COORD_PUB = Ip coordinator PublicIpAddress
$REPLICAS  = 'shard-a-1','shard-a-2','shard-a-3','shard-b-1','shard-b-2','shard-b-3' | ForEach-Object { Ip $_ }
"$BUCKET  version $VERSION  coordinator $COORD_PUB  replicas $REPLICAS"
```

macOS (Terminal):
```bash
cd ~/margosha-db          # wherever the repo is cloned
aws configure set region us-east-1
aws sts get-caller-identity
MY_IP=$(curl -s https://checkip.amazonaws.com)
PEM=~/.ssh/labsuser.pem
chmod 400 "$PEM"          # ssh refuses a key that others can read

show_log() { aws ec2 get-console-output --instance-id "$1" --latest --output text | grep -E "$2"; }
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
out() { aws cloudformation describe-stacks --stack-name margosha-app --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text; }
node_ip() {
  aws ec2 describe-instances --filters "Name=tag:Name,Values=margosha-$1" "Name=instance-state-name,Values=running" \
    --query "Reservations[0].Instances[0].${2:-PrivateIpAddress}" --output text
}
coord() { ssh -i "$PEM" -o StrictHostKeyChecking=accept-new "ec2-user@$COORD_PUB" "$@"; }
via_coord() {
  ssh -i "$PEM" -o StrictHostKeyChecking=accept-new \
    -o "ProxyCommand=ssh -i $PEM -o StrictHostKeyChecking=accept-new -W %h:%p ec2-user@$COORD_PUB" "ec2-user@$1" "$2"
}
status() { coord "curl -s -m 5 http://$1:8080/replication/status"; echo; }
credits() {
  for id in $BENCH $NODES; do
    name=$(aws ec2 describe-instances --instance-ids "$id" --query "Reservations[0].Instances[0].Tags[?Key=='Name']|[0].Value" --output text)
    value=$(aws cloudwatch get-metric-statistics --namespace AWS/EC2 --metric-name CPUCreditBalance \
      --dimensions "Name=InstanceId,Value=$id" --statistics Minimum --period 300 \
      --start-time "$(date -u -v-15M +%Y-%m-%dT%H:%M:%S 2>/dev/null || date -u -d '-15 min' +%Y-%m-%dT%H:%M:%S)" \
      --end-time "$(date -u +%Y-%m-%dT%H:%M:%S)" \
      --query "sort_by(Datapoints,&Timestamp)[-1].Minimum" --output text)
    echo "$name $value"
  done
}

BUCKET=$(out ArtifactsBucketName)
VERSION=$(aws cloudformation describe-stacks --stack-name margosha-app --query "Stacks[0].Parameters[?ParameterKey=='AppVersion'].ParameterValue" --output text)
COORD=$(out CoordinatorInstanceId)
NODES=$(out NodeInstanceIds | tr ',' ' ')
COORD_PUB=$(node_ip coordinator PublicIpAddress)
REPLICAS=$(for n in shard-a-1 shard-a-2 shard-a-3 shard-b-1 shard-b-2 shard-b-3; do node_ip $n; done | tr '\n' ' ')
echo "$BUCKET  version $VERSION  coordinator $COORD_PUB  replicas $REPLICAS"
```
The last line must print the bucket, the version, the coordinator's public IP and 6 private IPs. `None` or blank values
mean the credentials have expired or a node is not running.

### 1. Deploy the commit to benchmark

Push it to `main`, or start **Actions → pipeline → Run workflow** on `main` (README, "Deploy pipeline"). Then run step 0
again: it picks up the new version.

Check that both jars of this version are in the bucket:

```bash
aws s3 ls "s3://$BUCKET/releases/$VERSION/"
```
Expected: `margosha.jar` and `margosha-ycsb-binding.jar`. The version is the commit; the script writes it into the
results.

### 2. Turn on the bench client

This creates the bench client (8 instances, within the lab's limit of 9) and opens SSH to it from your IP. The pipeline
keeps both parameters as they are on later deploys:

```bash
aws cloudformation deploy --stack-name margosha-app --template-file cfn/margosha-app.yaml --parameter-overrides BenchClient=true "OperatorCidr=$MY_IP/32" --no-fail-on-empty-changeset
```

Get its ID and public IP, and wait until its setup has finished (`BENCH-READY` in its log). Then define `Bench` / `bench`,
which runs a command on it, and check Java and YCSB. A run prints nothing for minutes, so `ServerAliveInterval` keeps
the SSH connection from being dropped as idle:

Windows (PowerShell):
```powershell
$BENCH    = Out BenchClientInstanceId
$BENCH_IP = Out BenchClientPublicIp
Wait-Log $BENCH "BENCH-READY" 15
function Bench { ssh -i $PEM -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=30 "ec2-user@$BENCH_IP" @args }
Bench "java -version; ls /opt/ycsb/workloads"
```

macOS (Terminal):
```bash
BENCH=$(out BenchClientInstanceId)
BENCH_IP=$(out BenchClientPublicIp)
wait_log "$BENCH" "BENCH-READY" 15
bench() { ssh -i "$PEM" -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=30 "ec2-user@$BENCH_IP" "$@"; }
bench "java -version; ls /opt/ycsb/workloads"
```

**Switch the bench client to unlimited credit mode.** A new T3 instance in standard mode starts with **0 CPU
credits** and is then held at 20% CPU (`t3.small`), so the benchmark would measure the client instead of the store. A
`t3.small` earns only 24 credits per hour, so waiting for enough would take close to an hour. In unlimited mode it runs
at full speed at once. AWS charges the CPU it uses above its baseline at $0.05 per vCPU-hour: a few cents for the whole
benchmark. Only the bench client is switched; the 7 nodes stay in standard mode. It is switched back after the runs
(end of step 5).

```bash
aws ec2 modify-instance-credit-specification --instance-credit-specifications "InstanceId=$BENCH,CpuCredits=unlimited"
```
```bash
aws ec2 describe-instance-credit-specifications --instance-ids $BENCH --query "InstanceCreditSpecifications[0].CpuCredits" --output text
```
The first command lists the instance under `SuccessfulInstanceCreditSpecifications`; the second prints `unlimited`.
Expected: `openjdk version "21…"` and the YCSB workload files (`workloada` … `workloadf`).

Copy the script to the bench client (again after any change to it):

```bash
scp -i $PEM m3/benchmark/run-benchmark.sh "ec2-user@${BENCH_IP}:"
```

### 3. Start from an empty store

In M3 the data is on the replicas' disks, so **a reboot no longer empties the store** (unlike M2). Each node has to be
stopped, its data folder emptied, and started again:
- the coordinator keeps its table definitions in `/var/lib/margosha/tables.log`;
- each replica keeps its records in `/var/lib/margosha/replication.log`.

**This deletes all data in the store**, including the records from the AWS checks. Do it before every **load** (a load
into a store that already has `usertable` stops with a message).

First stop the service and empty the data folder on all 7 nodes. The folder can only be read by the `margosha` user,
so the files are deleted with `sudo find … -delete` (a `sudo rm /var/lib/margosha/*` would not work: your own shell
expands the `*` and cannot see into the folder). Then start the coordinator and the 6 replicas again:

Windows (PowerShell):
```powershell
$WIPE = "sudo systemctl stop margosha && sudo find /var/lib/margosha -mindepth 1 -delete && echo stopped and emptied"
Coord $WIPE
foreach ($ip in $REPLICAS) { Via-Coord $ip $WIPE }
Coord "sudo systemctl start --no-block margosha"
foreach ($ip in $REPLICAS) { Via-Coord $ip "sudo systemctl start --no-block margosha" }
```

macOS (Terminal):
```bash
WIPE="sudo systemctl stop margosha && sudo find /var/lib/margosha -mindepth 1 -delete && echo stopped and emptied"
coord "$WIPE"
for ip in $REPLICAS; do via_coord "$ip" "$WIPE"; done
coord "sudo systemctl start --no-block margosha"
for ip in $REPLICAS; do via_coord "$ip" "sudo systemctl start --no-block margosha"; done
```
Each of the 7 nodes prints `stopped and emptied`.

Wait 90 seconds, then check that all 6 replicas registered again and that every replica is empty:

Windows (PowerShell):
```powershell
Start-Sleep 90
Show-Log $COORD "Registered replica" | Select-Object -Last 6
foreach ($ip in $REPLICAS) { Status $ip }
```

macOS (Terminal):
```bash
sleep 90
show_log "$COORD" "Registered replica" | tail -n 6
for ip in $REPLICAS; do status "$ip"; done
```
Expected: 6 `Registered replica …` lines with a time after the reset (the log also keeps earlier starts), and
`"lastVersion":0` on every replica. If not, wait 30 s and run them again.

### 4. Check the CPU credits

With standard credits, an instance whose balance reaches 0 is held at its baseline CPU (`t3.micro` 10%, `t3.small`
20%), which makes the results meaningless. Show the balances of all 8 instances (bench client + 7 nodes):

Windows (PowerShell):
```powershell
Credits
```

macOS (Terminal):
```bash
credits
```
The numbers are credits (1 credit = 1 vCPU at 100% for 1 minute). `None` means no data point yet (the instance just
started): wait 5 minutes.

- **Nodes:** at least **15** each. The leaders work hardest.
- **Bench client:** its balance does not matter in unlimited mode (step 2); it can show 0. In standard mode at 0 it
  would measure itself instead of the store, as the [M2 trial](../../m2/benchmark/procedure.md#trial-run-results)
  showed.

Write the numbers down. Check them again after each run (step 5).

The script also checks the bench client before it starts. In unlimited mode it prints so and goes on. In standard mode
it stops with `Too few CPU credits` if the bench client has fewer than `MIN_CREDITS` (10 by default). It writes the mode
and the balance into `params.txt` (`bench_credit_mode`, `bench_cpu_credits_at_start`).

### 5. Load, warm-up, and 3 measured runs

Empty the store first (step 3) and check the credits (step 4). Then load the 100,000 records and do the warm-up run
(label `warmup`, about 8 minutes):

Windows (PowerShell):
```powershell
Bench "LABEL=warmup COMMIT=$VERSION bash run-benchmark.sh"
Credits
```

macOS (Terminal):
```bash
bench "LABEL=warmup COMMIT=$VERSION bash run-benchmark.sh"
credits
```
It prints `Bench client in unlimited credit mode …`, `Version …, N=3, W=2, R=2`, the load and run numbers, and ends
with `No failed operations.` or the failures.

Then the 3 measured runs on the same data (`SKIP_LOAD=1`, about 3.5 minutes each). Run them one at a time and check the
credits after each; if a node is under 5, wait before the next one (a `t3.micro` earns 12 credits per hour):

Windows (PowerShell):
```powershell
Bench "LABEL=run1 SKIP_LOAD=1 COMMIT=$VERSION bash run-benchmark.sh"
Credits
Bench "LABEL=run2 SKIP_LOAD=1 COMMIT=$VERSION bash run-benchmark.sh"
Credits
Bench "LABEL=run3 SKIP_LOAD=1 COMMIT=$VERSION bash run-benchmark.sh"
Credits
```

macOS (Terminal):
```bash
bench "LABEL=run1 SKIP_LOAD=1 COMMIT=$VERSION bash run-benchmark.sh"
credits
bench "LABEL=run2 SKIP_LOAD=1 COMMIT=$VERSION bash run-benchmark.sh"
credits
bench "LABEL=run3 SKIP_LOAD=1 COMMIT=$VERSION bash run-benchmark.sh"
credits
```

After the runs, check that no node logged an error or ran out of heap, and that every follower has all of its leader's
entries:

Windows (PowerShell):
```powershell
foreach ($id in $NODES) { Show-Log $id "OutOfMemoryError|ERROR \d+ ---" }
foreach ($ip in $REPLICAS) { Status $ip }
```

macOS (Terminal):
```bash
for id in $NODES; do show_log "$id" "OutOfMemoryError|ERROR [0-9]+ ---"; done
for ip in $REPLICAS; do status "$ip"; done
```
The first command must print nothing. (`ERROR <pid> ---` is how Spring writes an error-level line; a plain `error` would
also match harmless `WARN` lines.) In the statuses, each follower's `lastVersion` must equal its leader's.

If a run failed, or a node reached 0 credits during it, that run has to be repeated.

When all runs are done, **switch the bench client back to standard mode**, so it costs nothing extra while the outputs
are copied:

```bash
aws ec2 modify-instance-credit-specification --instance-credit-specifications "InstanceId=$BENCH,CpuCredits=standard"
```

### 6. Copy the outputs into the repo

Each run has its own folder on the bench client, ending in its label (`…-warmup`, `…-run1`, `…-run2`, `…-run3`):
- `params.txt`: the parameters, the version, and the quorum settings (`replication_factor`, `write_quorum`,
  `read_quorum`);
- the workload file;
- `run.txt` / `run.log`: the run's final numbers and its progress every 10 s;
- the warm-up folder also has the load: `register-table.json`, `load.txt`, `load.log`.

Copy the 4 newest folders, which are the runs from step 5:

Windows (PowerShell):
```powershell
$RUNS = (Bench "ls -td results/*/ | head -n 4") | ForEach-Object { $_.Trim().TrimEnd('/') }
$RUNS
New-Item -ItemType Directory -Force m3/benchmark/raw | Out-Null
foreach ($r in $RUNS) { scp -i $PEM -r "ec2-user@${BENCH_IP}:$r" m3/benchmark/raw/ }
Get-ChildItem m3/benchmark/raw
```

macOS (Terminal):
```bash
RUNS=$(bench "ls -td results/*/ | head -n 4" | sed 's:/*$::')
echo "$RUNS"
mkdir -p m3/benchmark/raw
for r in $RUNS; do scp -i "$PEM" -r "ec2-user@$BENCH_IP:$r" m3/benchmark/raw/; done
ls m3/benchmark/raw
```
`$RUNS` must be the 4 folders of step 5 (`-warmup`, `-run1`, `-run2`, `-run3`). The warm-up folder has 7 files, each
run folder 4.

The summary goes into `results.md`: each measured run's throughput and avg / p95 / p99 latency per operation (`[READ]`,
`[UPDATE]` lines in `run.txt`, in µs), their median, the load's numbers, the quorum settings, the commit, and the
comparison with M2.

### 7. Turn off the bench client

Remove it (back to 7 instances), and check that it is gone:

```bash
aws cloudformation deploy --stack-name margosha-app --template-file cfn/margosha-app.yaml --parameter-overrides BenchClient=false --no-fail-on-empty-changeset
```
```bash
aws ec2 describe-instances --instance-ids $BENCH --query "Reservations[0].Instances[0].State.Name"
```
Expected: `"terminated"` (or `"shutting-down"` for a minute).

Check that the store still answers, by writing and reading one record in `usertable`:

Windows (PowerShell):
```powershell
Invoke-RestMethod -Method Post -Uri "http://${COORD_PUB}:8080/tables/usertable/records" -ContentType 'application/json' -Body '{"ycsb_key":"after-bench","field0":"x"}'
Invoke-RestMethod "http://${COORD_PUB}:8080/tables/usertable/records/after-bench"
```

macOS (Terminal):
```bash
curl -s -X POST "http://$COORD_PUB:8080/tables/usertable/records" -H 'Content-Type: application/json' -d '{"ycsb_key":"after-bench","field0":"x"}'; echo
curl -s "http://$COORD_PUB:8080/tables/usertable/records/after-bench"; echo
```
Expected: the record `{"ycsb_key":"after-bench","field0":"x"}` twice. `OperatorCidr` was not passed, so it keeps its
value and the API is still open to your IP.

