# Application infrastructure (`margosha-app`)

**Template:** [margosha-app.yaml](../cfn/margosha-app.yaml)
**Stack name:** `margosha-app` (needs [`margosha-vpc`](../cfn/margosha-vpc-stack.yaml) with the [S3 endpoint](s3_gateway_endpoint.md))
**Region:** `us-east-1`
**Spec:** `app-infrastructure` in `openspec/changes/setup-m2-sharding`; this note covers tasks 9.1–9.6.

The stack holds everything the application needs on top of the network: the bucket the jar is delivered through,
the three database nodes, their firewalls, and the YCSB client while benchmarking.

```
                        internet
                            │  (only OperatorCidr: API 8080 + SSH 22)
 ┌──────────────── VPC 10.0.0.0/16 ─────────────────────────────────────────────┐
 │  public subnet 10.0.0.0/24 (us-east-1a)                                      │
 │    ┌──────────────────────┐        ┌──────────────────────────┐              │
 │    │ margosha-coordinator │        │ margosha-bench (t3.small)│ only when    │
 │    │ t3.micro  10.0.0.143 │◄───────│ YCSB 0.17.0              │ BenchClient  │
 │    └─────────┬────────────┘  8080  └──────────────────────────┘ = true       │
 │              │ 8080 (shard SG allows only the coordinator SG)                │
 │   ┌──────────┴───────────┐                                                   │
 │   ▼                      ▼                                                   │
 │  private A 10.0.1.0/24   private B 10.0.2.0/24                               │
 │  ┌──────────────────┐    ┌──────────────────┐                                │
 │  │ margosha-shard-a │    │ margosha-shard-b │   no public IP, no internet    │
 │  │ t3.micro         │    │ t3.micro         │                                │
 │  └────────┬─────────┘    └────────┬─────────┘                                │
 │           └──── S3 gateway endpoint (dnf repos + artifacts bucket) ───┐      │
 └───────────────────────────────────────────────────────────────────────┼──────┘
                                                                         ▼
                                             s3://margosha-artifacts-<account-id>/releases/margosha.jar
```

---

## What is in the template

### Parameters

| Parameter | Default | Meaning |
|---|---|---|
| `NetworkStackName` | `margosha-vpc` | Stack whose exports (`VpcId`, subnet IDs) are imported with `!ImportValue` |
| `VpcCidr` | `10.0.0.0/16` | VPC range; the coordinator accepts API traffic from it |
| `AmiId` | `ami-041607313c6a2a618` | Amazon Linux 2023 x86_64, **pinned** (see [choices](#design-choices)) |
| `KeyName` | `vockey` | Learner Lab SSH key |
| `ApiPort` | `8080` | API port on the coordinator and the shards |
| `OperatorCidr` | empty | Your IP as `/32`. Opens the coordinator's API and SSH (and the bench client's SSH) to it. Empty = closed |
| `BenchClient` | `false` | `true` creates the YCSB client |

### Resources

| Resource | What it is |
|---|---|
| `ArtifactsBucket` | `margosha-artifacts-<account-id>`: all public access blocked, ACLs off (`BucketOwnerEnforced`), SSE-S3. The jar lives at `releases/margosha.jar`. The name is fixed by convention so the S3 endpoint policy in `margosha-vpc` can name it |
| `CoordinatorSecurityGroup` | API port from `VpcCidr`; plus `CoordinatorApiFromOperator` and `CoordinatorSshFromOperator` (API, SSH from `OperatorCidr`) when `OperatorCidr` is set |
| `ShardSecurityGroup` | API port and SSH **only from the coordinator security group** |
| `BenchSecurityGroup` | Bench client only: no inbound except `BenchSshFromOperator` (SSH from `OperatorCidr`) |
| `NodeLaunchTemplate` | Shared settings and boot script of the coordinator and the shards (below) |
| `Coordinator` | Public subnet, coordinator SG, tag `MargoshaRole=coordinator` |
| `ShardA`, `ShardB` | Private subnets A and B, shard SG, tags `MargoshaRole=shard`, `MargoshaShardId=shard-a`/`shard-b`, `MargoshaCoordinatorIp=!GetAtt Coordinator.PrivateIp` |
| `BenchClientInstance` | Only when `BenchClient=true`: `t3.small` in the public subnet with Java 21 and YCSB 0.17.0 |

**Outputs:** bucket name, the three instance IDs (used by the pipeline to reboot the nodes), the coordinator's private IP
(stable) and public IP (at the last deploy; it changes every lab session), and the bench client's ID and public IP when it exists.

### The launch template (coordinator and shards)

| Setting | Value | Why |
|---|---|---|
| Instance type | `t3.micro` | M1 [quotas and budget](../m1/quotas_and_budget.md) |
| CPU credits | `standard` | T3 defaults to `unlimited`, which can bill extra; standard throttles instead |
| Disk | 8 GiB gp3, deleted with the instance | Budget |
| Instance profile | `LabInstanceProfile` | Gives the nodes S3 read access to the bucket; the only role the lab allows |
| Metadata | IMDSv2 required, **instance tags in metadata enabled** | The boot script reads the node's role from its own tags |

### How a node starts

**First boot only (UserData):**
1. Install Java 21 (`java-21-amazon-corretto-headless`) with `dnf`, through the S3 endpoint; retried up to 5 times.
2. Create the system user `margosha` (the node does not run as root).
3. Write `/etc/margosha/stack.env`: bucket, port, region (the only values CloudFormation fills in).
4. Write `/usr/local/bin/margosha-prepare`, `/usr/local/bin/margosha-run` and `/etc/systemd/system/margosha.service`.
5. `systemctl enable margosha` and start it without waiting (`--no-block`), so the first boot does not hang while the bucket is empty.

**Every boot (`margosha.service`):**

```
ExecStartPre = margosha-prepare
   1. read tags from instance metadata → role, shard ID, coordinator IP (+ own private IP)
      shards with a missing value log it and exit → systemd retries in 10 s
   2. write /etc/margosha/node.env with the java arguments
   3. aws s3 cp s3://<bucket>/releases/margosha.jar, retrying every 15 s until it exists
   4. log "downloaded …, sha256 <first 16 hex>"
ExecStart    = margosha-run
   java -Xmx512m -jar /opt/margosha/margosha.jar coordinator --mode=sharded --server.port=8080
   java -Xmx512m -jar /opt/margosha/margosha.jar shard --server.port=8080 --margosha.shard.id=shard-a
        --margosha.coordinator.url=http://10.0.0.143:8080 --margosha.shard.host=10.0.1.135
```

Other unit settings: `TimeoutStartSec=infinity` (waiting for the jar can take long), `Restart=on-failure` after 10 s,
`SuccessExitStatus=143` (Java's exit code on `systemctl stop`), and `StandardOutput=journal+console`: the node's log also
goes to the **serial console**, so it can be read with `aws ec2 get-console-output` from a laptop, without SSH.
The shards have no public IP and SSH to them only works through the coordinator, so this is how all checks below read the logs.

Because the jar is downloaded on every start, a new version is deployed by uploading it and rebooting the nodes
(design decision 9). A shard registers itself with the coordinator when it starts and retries until the coordinator
answers, so the start order of the three nodes does not matter.

### The YCSB client

Not a database node, so it does not use the launch template, but it has the same disk, credit and profile settings.
Its UserData installs Java 21 (retried), downloads YCSB 0.17.0 from GitHub (it is in the public subnet), checks that
`/opt/ycsb/lib/core-*.jar` exists, writes `MARGOSHA_URL` (coordinator's private address) and `MARGOSHA_BUCKET` to
`/etc/profile.d/margosha-bench.sh`, and prints `BENCH-READY` to the console. The YCSB binding jar is added in task 11.

## Design choices

- **One launch template + tags instead of three copies of the boot script.** Role, shard ID and coordinator IP are
  instance tags; `!GetAtt Coordinator.PrivateIp` sits in the shards' tags. The tags are re-read on every boot, so a
  changed tag is picked up on the next reboot. *Cost:* changing the launch template (the boot script or the machine
  settings) **replaces** the coordinator and the shards; they get new IDs and IPs. Records are in memory and lost on any
  restart anyway (design decision 5), so this is accepted.
- **Pinned AMI.** With an SSM-parameter AMI, CloudFormation resolves "latest" on every update, and an update after
  Amazon publishes a new AMI would replace all nodes, including a plain `BenchClient` switch. To move to a newer AMI on
  purpose: `aws ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64`, then
  deploy with `AmiId=<new id>`.
- **Operator access is a parameter, closed by default.** The API is plain HTTP without authentication (accepted for M2),
  so it is only opened to one IP, and only when given. The operator rules are separate `SecurityGroupIngress`
  resources with a `Condition`, so "closed" means the rule does not exist.
- **Shards are reachable only from the coordinator** (API and SSH), not from the rest of the VPC and not from the
  bench client. SSH to a shard: `ssh -A -J ec2-user@<coordinator-public-ip> ec2-user@<shard-private-ip>` with
  `labsuser.pem` loaded into the SSH agent.
- **Bucket and instances in one stack.** On the first deploy the bucket is empty when the nodes boot; they wait for the
  jar (task 9.3 below shows this) instead of needing a separate bucket stack.

---

## Deploy and verify

Run from the repo root, all in one terminal window (the variables do not survive closing it). Results are from the run
on **2026-10-29** from Windows, Learner Lab account `797089401427`, after [`margosha-vpc`](s3_gateway_endpoint.md) was created.

Each step has a **Windows (PowerShell)** and a **macOS (Terminal)** version. They run the same `aws` calls; only the
shell syntax differs:

| | Windows (PowerShell) | macOS (zsh/bash) |
|---|---|---|
| Continue a command on the next line | `` ` `` (backtick) | `\` |
| Save a command's output in a variable | `$VPC_ID = aws ...` | `VPC_ID=$(aws ...)` |
| Filter output lines | `Select-String "text"` | `grep -E "text"` |
| Call the API | `Invoke-WebRequest` (helper `Api`) | `curl` (helper `api`) |
| Helpers | `Show-Log`, `Wait-Log`, `Api`, `Out` | `show_log`, `wait_log`, `api`, `out` |
| Temp folder | `$env:TEMP` | `/tmp` |

Credentials and region are set up as in the [S3 endpoint note](s3_gateway_endpoint.md#setup).

### 0. Session setup

Windows (PowerShell):
```powershell
cd "C:\4 course uni\margosha-db"
aws configure set region us-east-1
aws sts get-caller-identity
$env:Path += ";$env:APPDATA\Python\Python312\Scripts"
# The console logs contain non-ASCII characters (e.g. "→"). Without these two lines the AWS CLI on Windows fails with
# "'charmap' codec can't encode character '\u2192'".
$env:PYTHONUTF8 = '1'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$MY_IP = (Invoke-RestMethod https://checkip.amazonaws.com).Trim()
```

Helpers: read a node's log from its serial console, wait for a line, and call the API printing the status code.
```powershell
function Show-Log($id, $pattern = 'margosha:|Registered shard|Running as|Started NodeApplication|BENCH-READY|PROBE') {
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
```

macOS (Terminal) — no encoding fix is needed:
```bash
cd ~/margosha-db          # wherever the repo is cloned
aws configure set region us-east-1
aws sts get-caller-identity
MY_IP=$(curl -s https://checkip.amazonaws.com)

# Helpers: show_log <instance> [pattern], wait_log <instance> <pattern> [minutes], api <METHOD> <path> [json]
show_log() {
  aws ec2 get-console-output --instance-id "$1" --latest --output text \
    | grep -E "${2:-margosha:|Registered shard|Running as|Started NodeApplication|BENCH-READY|PROBE}"
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
```
`get-console-output --latest` returns the last 64 KB of the console, which can span the previous boot too: after a
reboot, the lines of the new boot are the ones **after** the kernel's `RPC: Registered …` lines.

### 1. Lint and validate (task 9.1)

Windows (PowerShell):
```powershell
cfn-lint cfn/margosha-app.yaml
aws cloudformation validate-template --template-body file://cfn/margosha-app.yaml `
  --query "Parameters[].[ParameterKey,DefaultValue]" --output table
```

macOS (Terminal):
```bash
cfn-lint cfn/margosha-app.yaml
aws cloudformation validate-template --template-body file://cfn/margosha-app.yaml \
  --query "Parameters[].[ParameterKey,DefaultValue]" --output table
```
**Result:** `cfn-lint` printed nothing (no findings); the template is valid with the 7 parameters and defaults listed above.

### 2. Create the stack (task 9.3)

Windows (PowerShell):
```powershell
aws cloudformation deploy --stack-name margosha-app --template-file cfn/margosha-app.yaml `
  --parameter-overrides "OperatorCidr=$MY_IP/32" --no-fail-on-empty-changeset
aws cloudformation describe-stacks --stack-name margosha-app --query "Stacks[0].StackStatus"
aws cloudformation describe-stacks --stack-name margosha-app --query "Stacks[0].Outputs[].[OutputKey,OutputValue]" --output table
```

macOS (Terminal):
```bash
aws cloudformation deploy --stack-name margosha-app --template-file cfn/margosha-app.yaml \
  --parameter-overrides "OperatorCidr=$MY_IP/32" --no-fail-on-empty-changeset
aws cloudformation describe-stacks --stack-name margosha-app --query "Stacks[0].StackStatus"
aws cloudformation describe-stacks --stack-name margosha-app --query "Stacks[0].Outputs[].[OutputKey,OutputValue]" --output table
```
**Result:** `CREATE_COMPLETE` in **69 s** (13:30:21 → 13:31:30).

| Output | Value |
|---|---|
| `ArtifactsBucketName` | `margosha-artifacts-797089401427` |
| `CoordinatorInstanceId` | `i-05739d05ece08721c` |
| `ShardAInstanceId` | `i-08901330fec840b4f` |
| `ShardBInstanceId` | `i-0343fac16b42e6072` |
| `CoordinatorPrivateIp` | `10.0.0.143` |
| `CoordinatorPublicIp` | `54.242.130.4` |

### 3. Load the outputs into variables

Re-run after opening a new window (together with step 0).

Windows (PowerShell):
```powershell
function Out($key) { aws cloudformation describe-stacks --stack-name margosha-app --query "Stacks[0].Outputs[?OutputKey=='$key'].OutputValue" --output text }
$BUCKET  = Out ArtifactsBucketName
$COORD   = Out CoordinatorInstanceId
$SHARD_A = Out ShardAInstanceId
$SHARD_B = Out ShardBInstanceId
$COORD_PRIVATE = Out CoordinatorPrivateIp
$COORD_PUBLIC  = aws ec2 describe-instances --instance-ids $COORD --query "Reservations[0].Instances[0].PublicIpAddress" --output text
$API = "http://$($COORD_PUBLIC):8080"
```

macOS (Terminal):
```bash
out() { aws cloudformation describe-stacks --stack-name margosha-app --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text; }
BUCKET=$(out ArtifactsBucketName)
COORD=$(out CoordinatorInstanceId)
SHARD_A=$(out ShardAInstanceId)
SHARD_B=$(out ShardBInstanceId)
COORD_PRIVATE=$(out CoordinatorPrivateIp)
COORD_PUBLIC=$(aws ec2 describe-instances --instance-ids "$COORD" --query "Reservations[0].Instances[0].PublicIpAddress" --output text)
API="http://$COORD_PUBLIC:8080"
```

### 4. Instance settings (spec: placement, Learner Lab limits)

Windows (PowerShell):
```powershell
aws ec2 describe-instances --instance-ids $COORD $SHARD_A $SHARD_B `
  --query "Reservations[].Instances[].{Name:Tags[?Key=='Name']|[0].Value,Type:InstanceType,State:State.Name,Subnet:SubnetId,PrivateIp:PrivateIpAddress,PublicIp:PublicIpAddress,Profile:IamInstanceProfile.Arn}" `
  --output table
aws ec2 describe-instance-credit-specifications --instance-ids $COORD $SHARD_A $SHARD_B --output table
aws ec2 describe-volumes --filters "Name=attachment.instance-id,Values=$COORD,$SHARD_A,$SHARD_B" `
  --query "Volumes[].{Instance:Attachments[0].InstanceId,SizeGiB:Size,Type:VolumeType}" --output table
```

macOS (Terminal):
```bash
aws ec2 describe-instances --instance-ids "$COORD" "$SHARD_A" "$SHARD_B" \
  --query "Reservations[].Instances[].{Name:Tags[?Key=='Name']|[0].Value,Type:InstanceType,State:State.Name,Subnet:SubnetId,PrivateIp:PrivateIpAddress,PublicIp:PublicIpAddress,Profile:IamInstanceProfile.Arn}" \
  --output table
aws ec2 describe-instance-credit-specifications --instance-ids "$COORD" "$SHARD_A" "$SHARD_B" --output table
aws ec2 describe-volumes --filters "Name=attachment.instance-id,Values=$COORD,$SHARD_A,$SHARD_B" \
  --query "Volumes[].{Instance:Attachments[0].InstanceId,SizeGiB:Size,Type:VolumeType}" --output table
```
**Result:**

| Name | Type | Subnet | Private IP | Public IP | Profile | Credits | Disk |
|---|---|---|---|---|---|---|---|
| `margosha-coordinator` | `t3.micro` | public `subnet-0c54312177c96f8c5` | `10.0.0.143` | `54.242.130.4` | `LabInstanceProfile` | `standard` | 8 GiB gp3 |
| `margosha-shard-a` | `t3.micro` | private A `subnet-0bfe08bb08c6efd0d` | `10.0.1.135` | **none** | `LabInstanceProfile` | `standard` | 8 GiB gp3 |
| `margosha-shard-b` | `t3.micro` | private B `subnet-00472566adefe5b57` | `10.0.2.244` | **none** | `LabInstanceProfile` | `standard` | 8 GiB gp3 |

### 5. The nodes wait for the jar (task 9.2)

The bucket is empty right after the deploy.

Windows (PowerShell):
```powershell
Wait-Log $COORD "not available yet"
Show-Log $SHARD_A
```

macOS (Terminal):
```bash
wait_log "$COORD" "not available yet"
show_log "$SHARD_A"
```
**Result** (shard A, trimmed; the coordinator and shard B looked the same):
```
[  139.882104] margosha-prepare[3649]: margosha: role shard, args: shard --server.port=8080 --margosha.shard.id=shard-a --margosha.coordinator.url=http://10.0.0.143:8080 --margosha.shard.host=10.0.1.135
[  153.718648] margosha-prepare[3649]: margosha: s3://margosha-artifacts-797089401427/releases/margosha.jar not available yet, retrying in 15s
[  174.042315] margosha-prepare[3649]: margosha: s3://margosha-artifacts-797089401427/releases/margosha.jar not available yet, retrying in 15s
...
```
The tags were read correctly (role, shard ID, coordinator's private IP, own IP), and the node retried about every 15 s.
First-boot setup (Java install) finished 65–199 s after boot, depending on the node.

### 6. Upload the jar; the nodes start and the shards register (tasks 9.2, 9.3)

The jar is built by `./mvnw verify` in `app/` (Windows: `.\mvnw.cmd -B verify`).

Windows (PowerShell):
```powershell
(Get-FileHash app/node/target/margosha.jar -Algorithm SHA256).Hash.Substring(0,16).ToLower()
aws s3 cp app/node/target/margosha.jar "s3://$BUCKET/releases/margosha.jar"
Wait-Log $COORD "Registered shard shard-b|Registered shard shard-a"
Show-Log $COORD
Show-Log $SHARD_A
Show-Log $SHARD_B
```

macOS (Terminal):
```bash
shasum -a 256 app/node/target/margosha.jar | cut -c1-16
aws s3 cp app/node/target/margosha.jar "s3://$BUCKET/releases/margosha.jar"
wait_log "$COORD" "Registered shard shard-b|Registered shard shard-a"
show_log "$COORD"
show_log "$SHARD_A"
show_log "$SHARD_B"
```
**Result:** local hash `04d13b6a38322f45`; uploaded at 13:42:51 (20,493,363 bytes).

Coordinator:
```
[  730.420545] margosha-prepare[4481]: margosha: downloaded s3://margosha-artifacts-797089401427/releases/margosha.jar, sha256 04d13b6a38322f45
[  735.032590] margosha-run[26905]: ... Started NodeApplication in 3.584 seconds (process running for 4.552)
[  735.042964] margosha-run[26905]: ... Running as coordinator (mode: sharded)
[  744.501861] margosha-run[26905]: ... ShardRegistryService : Registered shard shard-b at http://10.0.2.244:8080
[  744.627525] margosha-run[26905]: ... ShardRegistryService : Registered shard shard-a at http://10.0.1.135:8080
```
Shard A (shard B the same with `shard-b`, `10.0.2.244`):
```
[  735.133285] margosha-prepare[3649]: margosha: downloaded s3://margosha-artifacts-797089401427/releases/margosha.jar, sha256 04d13b6a38322f45
[  739.790863] margosha-run[26956]: ... Running as shard
[  740.066289] margosha-run[26956]: ... CoordinatorRegistrationService : Registered shard shard-a at http://10.0.1.135:8080
```
All three nodes downloaded the same jar (hash matches the local one) **through the S3 endpoint** (the shards have no
other way out), started in under 4 s, and both shards were registered **30 s after the upload** (11:43:21 UTC).

### 7. Create and read records through the coordinator (task 9.3)

Windows (PowerShell):
```powershell
Api POST "/tables" '{"name":"users","partitionKey":"user_id"}'
Api POST "/tables/users/records" '{"user_id":"42","name":"Anna","city":"Kyiv"}'
Api GET  "/tables/users/records/42"
Api GET  "/tables/users/records/42/exists"
Api GET  "/tables/users/records/nobody"
1..20 | ForEach-Object { Api POST "/tables/users/records" ("{""user_id"":""u$_"",""name"":""n$_""}") } | Group-Object { $_.Substring(0,3) } | Select-Object Name, Count
1..20 | ForEach-Object { Api GET "/tables/users/records/u$_" } | Group-Object { $_.Substring(0,3) } | Select-Object Name, Count
```

macOS (Terminal) — the last two lines print each status code with how many times it came back:
```bash
api POST "/tables" '{"name":"users","partitionKey":"user_id"}'
api POST "/tables/users/records" '{"user_id":"42","name":"Anna","city":"Kyiv"}'
api GET  "/tables/users/records/42"
api GET  "/tables/users/records/42/exists"
api GET  "/tables/users/records/nobody"
for i in $(seq 1 20); do api POST "/tables/users/records" "{\"user_id\":\"u$i\",\"name\":\"n$i\"}"; done | cut -c1-3 | sort | uniq -c
for i in $(seq 1 20); do api GET "/tables/users/records/u$i"; done | cut -c1-3 | sort | uniq -c
```
**Result:**
```
201 {"name":"users","partitionKey":"user_id"}
201 {"name":"Anna","user_id":"42","city":"Kyiv"}
200 {"name":"Anna","user_id":"42","city":"Kyiv"}
200 {"exists":true}
404 {"code":"RECORD_NOT_FOUND","message":"Record nobody not found in table users"}
201 × 20
200 × 20
```
With two shards on the ring, 20 keys land on both of them, so all 40 calls succeeding means both shards answer the
coordinator. The request went laptop → coordinator's public IP (allowed by `OperatorCidr`) → shard's private IP.

### 8. Access checks (task 9.4)

**Security group rules:**

Windows (PowerShell):
```powershell
aws ec2 describe-security-groups --filters "Name=tag:Name,Values=margosha-coordinator-sg,margosha-shard-sg" `
  --query "SecurityGroups[].{Name:Tags[?Key=='Name']|[0].Value,Rules:IpPermissions[].{Port:FromPort,Cidrs:IpRanges[].CidrIp,FromGroups:UserIdGroupPairs[].GroupId}}" --output json
aws ec2 describe-security-groups --filters "Name=tag:Name,Values=margosha-coordinator-sg" --query "SecurityGroups[0].GroupId" --output text
```

macOS (Terminal):
```bash
aws ec2 describe-security-groups --filters "Name=tag:Name,Values=margosha-coordinator-sg,margosha-shard-sg" \
  --query "SecurityGroups[].{Name:Tags[?Key=='Name']|[0].Value,Rules:IpPermissions[].{Port:FromPort,Cidrs:IpRanges[].CidrIp,FromGroups:UserIdGroupPairs[].GroupId}}" --output json
aws ec2 describe-security-groups --filters "Name=tag:Name,Values=margosha-coordinator-sg" --query "SecurityGroups[0].GroupId" --output text
```
**Result:**

| Group | Port | Allowed from |
|---|---|---|
| `margosha-coordinator-sg` (`sg-041de533329cc431c`) | 8080 | `10.0.0.0/16`, `188.163.74.247/32` (operator) |
| | 22 | `188.163.74.247/32` |
| `margosha-shard-sg` | 8080 | `sg-041de533329cc431c` (coordinator SG) only |
| | 22 | `sg-041de533329cc431c` only |

**From the public subnet:** a throwaway instance in the public subnet (VPC default security group, not the coordinator's)
tries the coordinator's and both shards' private IPs at boot and prints the result to its console.

Windows (PowerShell). The script must have Linux line endings, so the `\r\n` from the here-string are replaced:
```powershell
$SHARD_A_IP = aws ec2 describe-instances --instance-ids $SHARD_A --query "Reservations[0].Instances[0].PrivateIpAddress" --output text
$SHARD_B_IP = aws ec2 describe-instances --instance-ids $SHARD_B --query "Reservations[0].Instances[0].PrivateIpAddress" --output text
$PUBLIC_SUBNET = aws cloudformation list-exports --query "Exports[?Name=='margosha-vpc-PublicSubnetId'].Value" --output text

$probe = @'
#!/bin/bash
exec > >(tee /dev/console) 2>&1
check() {
  if curl -s -m 5 -o /dev/null "http://$2:8080/tables/x/records/y"; then echo "PROBE $1 $2: reachable"
  else echo "PROBE $1 $2: NOT reachable"; fi
}
check coordinator COORD_IP
check shard-a SHARD_A_IP
check shard-b SHARD_B_IP
echo "PROBE done"
'@
$probe = $probe.Replace('COORD_IP', $COORD_PRIVATE).Replace('SHARD_A_IP', $SHARD_A_IP).Replace('SHARD_B_IP', $SHARD_B_IP)
[IO.File]::WriteAllText("$env:TEMP\probe.sh", ($probe -replace "`r`n", "`n"))   # Linux line endings

$PROBE = aws ec2 run-instances `
  --image-id resolve:ssm:/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 `
  --instance-type t3.micro --subnet-id $PUBLIC_SUBNET `
  --user-data "file://$env:TEMP\probe.sh" `
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=margosha-access-probe}]' `
  --query "Instances[0].InstanceId" --output text
aws ec2 wait instance-running --instance-ids $PROBE
Wait-Log $PROBE "PROBE done"
Show-Log $PROBE "PROBE"
aws ec2 terminate-instances --instance-ids $PROBE   # after reading the result: the console is gone once terminated
```

macOS (Terminal). The heredoc fills in the IPs; `\$1` and `\$2` stay as the script's own arguments:
```bash
SHARD_A_IP=$(aws ec2 describe-instances --instance-ids "$SHARD_A" --query "Reservations[0].Instances[0].PrivateIpAddress" --output text)
SHARD_B_IP=$(aws ec2 describe-instances --instance-ids "$SHARD_B" --query "Reservations[0].Instances[0].PrivateIpAddress" --output text)
PUBLIC_SUBNET=$(aws cloudformation list-exports --query "Exports[?Name=='margosha-vpc-PublicSubnetId'].Value" --output text)

cat > /tmp/probe.sh <<EOF
#!/bin/bash
exec > >(tee /dev/console) 2>&1
check() {
  if curl -s -m 5 -o /dev/null "http://\$2:8080/tables/x/records/y"; then echo "PROBE \$1 \$2: reachable"
  else echo "PROBE \$1 \$2: NOT reachable"; fi
}
check coordinator $COORD_PRIVATE
check shard-a $SHARD_A_IP
check shard-b $SHARD_B_IP
echo "PROBE done"
EOF

PROBE=$(aws ec2 run-instances \
  --image-id resolve:ssm:/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
  --instance-type t3.micro --subnet-id "$PUBLIC_SUBNET" \
  --user-data file:///tmp/probe.sh \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=margosha-access-probe}]' \
  --query "Instances[0].InstanceId" --output text)
aws ec2 wait instance-running --instance-ids "$PROBE"
wait_log "$PROBE" "PROBE done"
show_log "$PROBE" "PROBE"
aws ec2 terminate-instances --instance-ids "$PROBE"   # after reading the result
```
`curl` without `-f` succeeds on any HTTP answer (even 404), so "reachable" means a TCP connection was accepted;
"NOT reachable" means it timed out after 5 s.

**Result:**
```
PROBE coordinator 10.0.0.143: reachable
PROBE shard-a 10.0.1.135: NOT reachable
PROBE shard-b 10.0.2.244: NOT reachable
PROBE done
```
Together with steps 4 and 7: the shards have no public IP; from the public subnet only the coordinator can reach a
shard's API (the coordinator's own requests in step 7 worked); the coordinator answers on its private IP.

### 9. Reboot: every boot downloads the jar again (task 9.2)

All three are rebooted, as the pipeline will do. Rebooting only the coordinator would make it forget the shards
(the shard list is in memory, and a shard registers only when it starts).

Windows (PowerShell):
```powershell
aws ec2 reboot-instances --instance-ids $COORD $SHARD_A $SHARD_B
Start-Sleep 120
Show-Log $COORD
Show-Log $SHARD_A "Registered shard|downloaded"
Api POST "/tables" '{"name":"users","partitionKey":"user_id"}'
Api POST "/tables/users/records" '{"user_id":"42","name":"Anna","city":"Kyiv"}'
Api GET  "/tables/users/records/42"
```

macOS (Terminal):
```bash
aws ec2 reboot-instances --instance-ids "$COORD" "$SHARD_A" "$SHARD_B"
sleep 120
show_log "$COORD"
show_log "$SHARD_A" "Registered shard|downloaded"
api POST "/tables" '{"name":"users","partitionKey":"user_id"}'
api POST "/tables/users/records" '{"user_id":"42","name":"Anna","city":"Kyiv"}'
api GET  "/tables/users/records/42"
```
**Result** (coordinator, lines of the new boot):
```
[    5.534237] margosha-prepare[1849]: margosha: role coordinator, args: coordinator --mode=sharded --server.port=8080
[    7.459166] margosha-prepare[1849]: margosha: downloaded s3://margosha-artifacts-797089401427/releases/margosha.jar, sha256 04d13b6a38322f45
[   13.036651] margosha-run[1938]: ... Running as coordinator (mode: sharded)
[   17.064981] margosha-run[1938]: ... Registered shard shard-b at http://10.0.2.244:8080
[   17.768412] margosha-run[1938]: ... Registered shard shard-a at http://10.0.1.135:8080
```
Shard A: `downloaded … sha256 04d13b6a38322f45` at 7.7 s, `Registered shard shard-a` at 14.1 s after boot.
API: registering `users` returned `201` again (the table had been forgotten: everything is in memory), then `201` / `200`
for the record. The coordinator runs the jar from the bucket on every boot: it was downloaded again 7.5 s after boot.

### 10. Stop and start, as at the end of a lab session (task 9.5)

Windows (PowerShell):
```powershell
aws ec2 stop-instances --instance-ids $COORD $SHARD_A $SHARD_B
aws ec2 wait instance-stopped --instance-ids $COORD $SHARD_A $SHARD_B
aws ec2 start-instances --instance-ids $COORD $SHARD_A $SHARD_B
aws ec2 wait instance-running --instance-ids $COORD $SHARD_A $SHARD_B
$COORD_PUBLIC = aws ec2 describe-instances --instance-ids $COORD --query "Reservations[0].Instances[0].PublicIpAddress" --output text
$API = "http://$($COORD_PUBLIC):8080"
Wait-Log $COORD "Registered shard shard-b|Registered shard shard-a"
Show-Log $COORD "Registered shard"
Api POST "/tables" '{"name":"users","partitionKey":"user_id"}'
Api POST "/tables/users/records" '{"user_id":"7","name":"Ola"}'
Api GET  "/tables/users/records/7"
```

macOS (Terminal):
```bash
aws ec2 stop-instances --instance-ids "$COORD" "$SHARD_A" "$SHARD_B"
aws ec2 wait instance-stopped --instance-ids "$COORD" "$SHARD_A" "$SHARD_B"
aws ec2 start-instances --instance-ids "$COORD" "$SHARD_A" "$SHARD_B"
aws ec2 wait instance-running --instance-ids "$COORD" "$SHARD_A" "$SHARD_B"
COORD_PUBLIC=$(aws ec2 describe-instances --instance-ids "$COORD" --query "Reservations[0].Instances[0].PublicIpAddress" --output text)
API="http://$COORD_PUBLIC:8080"
wait_log "$COORD" "Registered shard shard-b|Registered shard shard-a"
show_log "$COORD" "Registered shard"
api POST "/tables" '{"name":"users","partitionKey":"user_id"}'
api POST "/tables/users/records" '{"user_id":"7","name":"Ola"}'
api GET  "/tables/users/records/7"
```
**Result:**

| | Before | After |
|---|---|---|
| Coordinator public IP | `54.242.130.4` | `3.87.48.180` (changed) |
| Coordinator private IP | `10.0.0.143` | `10.0.0.143` (same) |

```
[   14.340639] margosha-run[1968]: ... Registered shard shard-b at http://10.0.2.244:8080
[   14.343289] margosha-run[1968]: ... Registered shard shard-a at http://10.0.1.135:8080
```
Both shards registered again 14 s after the start, with no change to the template; table, create and read: `201`, `201`, `200`.
This works because the shards find the coordinator by its private IP, which survives stop/start. The operator has to
look up the new public IP (the stack output `CoordinatorPublicIp` still shows the one from the last deploy).

### 11. `BenchClient` on, then off (task 9.6)

**Instance IDs before:**

Windows (PowerShell):
```powershell
aws cloudformation describe-stack-resources --stack-name margosha-app `
  --query "StackResources[?ResourceType=='AWS::EC2::Instance'].[LogicalResourceId,PhysicalResourceId]" --output table
```

macOS (Terminal):
```bash
aws cloudformation describe-stack-resources --stack-name margosha-app \
  --query "StackResources[?ResourceType=='AWS::EC2::Instance'].[LogicalResourceId,PhysicalResourceId]" --output table
```
| Coordinator | ShardA | ShardB |
|---|---|---|
| `i-05739d05ece08721c` | `i-08901330fec840b4f` | `i-0343fac16b42e6072` |

**On:** preview the change set, then run it.

Windows (PowerShell):
```powershell
aws cloudformation deploy --stack-name margosha-app --template-file cfn/margosha-app.yaml `
  --parameter-overrides BenchClient=true "OperatorCidr=$MY_IP/32" --no-execute-changeset
$CS = aws cloudformation list-change-sets --stack-name margosha-app --query "Summaries[?ExecutionStatus=='AVAILABLE'].ChangeSetName | [0]" --output text
aws cloudformation describe-change-set --stack-name margosha-app --change-set-name $CS `
  --query "Changes[].ResourceChange.[Action,LogicalResourceId,ResourceType,Replacement]" --output table
aws cloudformation execute-change-set --stack-name margosha-app --change-set-name $CS
aws cloudformation wait stack-update-complete --stack-name margosha-app
$BENCH = Out BenchClientInstanceId
aws ec2 describe-instances --instance-ids $BENCH --query "Reservations[0].Instances[0].{Type:InstanceType,State:State.Name,PublicIp:PublicIpAddress,Profile:IamInstanceProfile.Arn}" --output table
aws ec2 describe-instance-credit-specifications --instance-ids $BENCH --output table
Wait-Log $BENCH "BENCH-READY" 15
```

macOS (Terminal):
```bash
aws cloudformation deploy --stack-name margosha-app --template-file cfn/margosha-app.yaml \
  --parameter-overrides BenchClient=true "OperatorCidr=$MY_IP/32" --no-execute-changeset
CS=$(aws cloudformation list-change-sets --stack-name margosha-app --query "Summaries[?ExecutionStatus=='AVAILABLE'].ChangeSetName | [0]" --output text)
aws cloudformation describe-change-set --stack-name margosha-app --change-set-name "$CS" \
  --query "Changes[].ResourceChange.[Action,LogicalResourceId,ResourceType,Replacement]" --output table
aws cloudformation execute-change-set --stack-name margosha-app --change-set-name "$CS"
aws cloudformation wait stack-update-complete --stack-name margosha-app
BENCH=$(out BenchClientInstanceId)
aws ec2 describe-instances --instance-ids "$BENCH" --query "Reservations[0].Instances[0].{Type:InstanceType,State:State.Name,PublicIp:PublicIpAddress,Profile:IamInstanceProfile.Arn}" --output table
aws ec2 describe-instance-credit-specifications --instance-ids "$BENCH" --output table
wait_log "$BENCH" "BENCH-READY" 15
```
**Result:**

| Action | Resource | Type | Replacement |
|---|---|---|---|
| Add | `BenchClientInstance` | `AWS::EC2::Instance` | – |
| Add | `BenchSecurityGroup` | `AWS::EC2::SecurityGroup` | – |
| Add | `BenchSshFromOperator` | `AWS::EC2::SecurityGroupIngress` | – |

Client `i-039ea0a74a0a6fd7a`: `t3.small`, `running`, public IP `3.92.182.34`, `LabInstanceProfile`, credits `standard`; 119 s after boot:
```
BENCH-READY ycsb=/opt/ycsb coordinator=http://10.0.0.143:8080
```

**Off:**

Windows (PowerShell):
```powershell
aws cloudformation deploy --stack-name margosha-app --template-file cfn/margosha-app.yaml `
  --parameter-overrides BenchClient=false "OperatorCidr=$MY_IP/32" --no-execute-changeset
$CS = aws cloudformation list-change-sets --stack-name margosha-app --query "Summaries[?ExecutionStatus=='AVAILABLE'].ChangeSetName | [0]" --output text
aws cloudformation describe-change-set --stack-name margosha-app --change-set-name $CS `
  --query "Changes[].ResourceChange.[Action,LogicalResourceId,ResourceType,Replacement]" --output table
aws cloudformation execute-change-set --stack-name margosha-app --change-set-name $CS
aws cloudformation wait stack-update-complete --stack-name margosha-app
aws ec2 describe-instances --instance-ids $BENCH --query "Reservations[0].Instances[0].State.Name"
aws ec2 describe-volumes --filters "Name=attachment.instance-id,Values=$BENCH" --query "Volumes[].VolumeId"
aws cloudformation describe-stack-resources --stack-name margosha-app `
  --query "StackResources[?ResourceType=='AWS::EC2::Instance'].[LogicalResourceId,PhysicalResourceId]" --output table
Api GET "/tables/users/records/7"
```

macOS (Terminal):
```bash
aws cloudformation deploy --stack-name margosha-app --template-file cfn/margosha-app.yaml \
  --parameter-overrides BenchClient=false "OperatorCidr=$MY_IP/32" --no-execute-changeset
CS=$(aws cloudformation list-change-sets --stack-name margosha-app --query "Summaries[?ExecutionStatus=='AVAILABLE'].ChangeSetName | [0]" --output text)
aws cloudformation describe-change-set --stack-name margosha-app --change-set-name "$CS" \
  --query "Changes[].ResourceChange.[Action,LogicalResourceId,ResourceType,Replacement]" --output table
aws cloudformation execute-change-set --stack-name margosha-app --change-set-name "$CS"
aws cloudformation wait stack-update-complete --stack-name margosha-app
aws ec2 describe-instances --instance-ids "$BENCH" --query "Reservations[0].Instances[0].State.Name"
aws ec2 describe-volumes --filters "Name=attachment.instance-id,Values=$BENCH" --query "Volumes[].VolumeId"
aws cloudformation describe-stack-resources --stack-name margosha-app \
  --query "StackResources[?ResourceType=='AWS::EC2::Instance'].[LogicalResourceId,PhysicalResourceId]" --output table
api GET "/tables/users/records/7"
```
**Result:**
- The change set had only `Remove` for the same three bench resources.
- The client was `"terminated"` and its volume list was `[]`.
- The instance IDs afterwards were `i-05739d05ece08721c`, `i-08901330fec840b4f` and `i-0343fac16b42e6072`, the
  same as before, so nothing was replaced.
- `GET /tables/users/records/7` still returned `200 {"name":"Ola","user_id":"7"}`, so the nodes weren't even restarted.

Both change sets name the bench resources only. Without the preview (`--no-execute-changeset`), `aws cloudformation deploy`
does the same in one command.

---

## Results by task and spec scenario

| Task / scenario | Evidence | Result |
|---|---|---|
| 9.1 template, `cfn-lint` | step 1 | ✅ no findings |
| 9.2 systemd unit downloads the jar on every boot, retries until it exists | steps 5, 6, 9 | ✅ retried for ~9 min while the bucket was empty; downloaded on first start and again after the reboot, same hash |
| 9.3 deploy, shards register, create and read through the coordinator | steps 2, 6, 7 | ✅ `CREATE_COMPLETE` in 69 s; both shards registered 30 s after the jar upload; 21 creates + 21 reads OK |
| 9.4 shards private, shard API only from the coordinator, coordinator reachable on private IP | steps 4, 7, 8 | ✅ |
| 9.5 stop/start, shards register again | step 10 | ✅ registered 14 s after start; private IP kept, public IP changed |
| 9.6 `BenchClient` true → false, nodes not replaced | step 11 | ✅ only bench resources added/removed; instance IDs unchanged |
| *Deploy from templates alone* | steps 2, 6, 7 | ✅ no console steps; only "upload the jar" |
| *Shards are private* | step 4 | ✅ |
| *Standard credits*, profile, type per role | steps 4, 11 | ✅ |
| *Shard not reachable from the client* | step 8 (probe in the public subnet, like the bench client) | ✅ |
| *Coordinator reachable from the client* | step 8 | ✅ |
| *New version deployed* (upload + reboot → new jar) | steps 6, 9 (same jar re-downloaded on reboot) | ✅ mechanism; with a changed jar in task 10 |
| *Lab session restart* | step 10 | ✅ |
| *Client removed after a benchmark* | step 11 | ✅ instance and volume gone, nodes kept |

After the checks the three instances were stopped (`aws ec2 stop-instances`) to save budget. The next session's lab
start (or `aws ec2 start-instances`) brings them back, and they download the jar and register on their own.

---

## Console screenshots

Taken after the checks above, while the coordinator and the shards were **stopped** (so they show `Stopped` and the
coordinator has no public IP at that moment). Everything else is as deployed.

| Resource | Expected | Confirmed in console |
|---|---|---|
| Stack | `margosha-app`, `UPDATE_COMPLETE` (last update: `BenchClient` back to `false`), 9 resources, 6 outputs, 7 parameters | ✅ |
| Coordinator | `t3.micro`, public subnet, private IP `10.0.0.143`, `LabRole`, `vockey`, credits `standard`, IMDSv2, tags in metadata enabled, tag `MargoshaRole=coordinator`, 8 GiB gp3 | ✅ |
| Shard A | `t3.micro`, private subnet A (`us-east-1a`), `10.0.1.135`, **no public IP**, shard SG, tags `shard` / `shard-a` / `10.0.0.143` | ✅ |
| Shard B | `t3.micro`, private subnet B (`us-east-1b`), `10.0.2.244`, **no public IP**, shard SG, tags `shard` / `shard-b` / `10.0.0.143` | ✅ |
| Launch template | `margosha-app-node`, version 1, AMI `ami-041607313c6a2a618`, `t3.micro`, `vockey` | ✅ |
| Coordinator SG | 8080 from `10.0.0.0/16` and the operator `/32`, 22 from the operator | ✅ |
| Shard SG | 8080 and 22 only from `sg-041de533329cc431c` (coordinator SG) | ✅ (instance Security tabs) |
| Bucket | `releases/margosha.jar`, 19.5 MB, SSE-S3, bucket owner enforced (no ACLs), no public access | ✅ |
| Bench client | `t3.small`, credits `standard`, now `Terminated` | ✅ |

### CloudFormation stack
Stack info: `UPDATE_COMPLETE`; created 13:30:22, last updated 13:54:53 (the `BenchClient=false` update).
![](images/app/stack-info.png)
Latest deployment timeline: the `BenchClient=false` update touched only the three bench resources.
![](images/app/stack-timeline.png)
Resources (9): bucket, launch template, 3 instances, 2 security groups, 2 operator rules. No bench resources.
![](images/app/stack-resources.png)
Outputs. `CoordinatorPublicIp` shows `3.87.48.180`, the IP after the stop/start in step 10: CloudFormation re-read it
during the later `BenchClient` updates. It changes again with every lab session.
![](images/app/stack-outputs.png)
Parameters: pinned AMI, `ApiPort=8080`, `BenchClient=false`, `OperatorCidr` = the operator's `/32`.
![](images/app/stack-parameters.png)

### Instances
The three nodes (stopped), the terminated bench client and the terminated access probe from step 8.
![](images/app/instances.png)

#### Coordinator
![](images/app/coordinator-details.png)
AMI, launch time, key pair `vockey`, credit specification `standard`.
![](images/app/coordinator-instance-details.png)
**Allow tags in instance metadata: Enabled**, which is how the boot script reads `MargoshaRole`.
![](images/app/coordinator-metadata.png)
Coordinator security group and its rules.
![](images/app/coordinator-security.png)
Public subnet `margosha-public-sb`, `us-east-1a`, private IP `10.0.0.143`.
![](images/app/coordinator-networking.png)
![](images/app/coordinator-network-interfaces.png)
One 8 GiB root volume, deleted on termination.
![](images/app/coordinator-storage.png)
Tags: `MargoshaRole=coordinator`, plus the launch template ID and version added by EC2.
![](images/app/coordinator-tags.png)

#### Shard A
No public IPv4 address; subnet `margosha-private-sb-a`.
![](images/app/shard-a-details.png)
![](images/app/shard-a-instance-details.png)
![](images/app/shard-a-metadata.png)
Shard security group: 22 and 8080 only from `sg-041de533329cc431c` (the coordinator's group).
![](images/app/shard-a-security.png)
![](images/app/shard-a-networking.png)
![](images/app/shard-a-network-interfaces.png)
![](images/app/shard-a-storage.png)
Tags read by the boot script: `MargoshaRole=shard`, `MargoshaShardId=shard-a`, `MargoshaCoordinatorIp=10.0.0.143`.
![](images/app/shard-a-tags.png)

#### Shard B
No public IPv4 address; subnet `margosha-private-sb-b`, in the other availability zone (`us-east-1b`).
![](images/app/shard-b-details.png)
![](images/app/shard-b-instance-details.png)
![](images/app/shard-b-metadata.png)
![](images/app/shard-b-security.png)
![](images/app/shard-b-networking.png)
![](images/app/shard-b-network-interfaces.png)
![](images/app/shard-b-storage.png)
![](images/app/shard-b-tags.png)

#### Bench client (terminated after step 11)
`t3.small`, `Terminated`; the network fields are empty because the instance no longer exists.
![](images/app/bench-details.png)
Same AMI and `vockey`, credit specification `standard`.
![](images/app/bench-instance-details.png)
Tags in instance metadata are disabled here: the bench client doesn't use the launch template and doesn't need its tags.
![](images/app/bench-metadata.png)
![](images/app/bench-tags.png)

### Launch template
`margosha-app-node`: one version (never changed since the deploy), AMI, `t3.micro`, `vockey`. Subnet and security groups are
empty on purpose: each instance sets its own.
![](images/app/launch-template.png)
![](images/app/launch-template-versions.png)
![](images/app/launch-template-tags.png)

### Security groups
The two app groups in `margosha-vpc` (`vpc-083b…`), next to the default groups of `margosha-vpc` and of the default VPC.
![](images/app/security-groups.png)
Coordinator group:
![](images/app/sg-coordinator-details.png)
![](images/app/sg-coordinator-inbound.png)
![](images/app/sg-coordinator-outbound.png)
![](images/app/sg-coordinator-tags.png)

### Artifacts bucket
![](images/app/bucket-objects.png)
`releases/margosha.jar`, uploaded 13:42:51 (step 6), 19.5 MB.
![](images/app/bucket-releases.png)
![](images/app/jar-properties.png)
Encrypted at rest with SSE-S3.
![](images/app/jar-encryption.png)
Bucket owner enforced: ACLs are off, and *Everyone* / *Authenticated users* have no access.
![](images/app/jar-permissions.png)

---

## Notes

### Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `'charmap' codec can't encode character '\u2192'` on `get-console-output` | Windows console encoding; the log contains `→` | The two encoding lines in step 0 |
| `Show-Log` / `show_log` shows `RPC: Registered … transport module` | Kernel lines also contain "Registered" | Search for `Registered shard` |
| Nodes keep logging `not available yet` | No jar in `s3://<bucket>/releases/margosha.jar` | Upload it (step 6) |
| Shard logs `missing shard settings` | A tag is missing on the instance | Check the tags in the template, redeploy |
| API calls time out from the laptop | Your IP changed, or `OperatorCidr` is empty | Redeploy with the new `OperatorCidr=<ip>/32` |
| `TABLE_NOT_FOUND` after a reboot / new session | Tables and records are kept in memory | Register the table again (design decision 6) |
| `SHARD_UNAVAILABLE` after rebooting only the coordinator | The coordinator forgot the shards; they register only when they start | Reboot all three nodes |

### Teardown

Empty the bucket first (CloudFormation cannot delete a bucket with objects), then the app stack. Delete
`margosha-vpc` last, and only when tearing everything down (its exports are imported by `margosha-app`).

Windows (PowerShell):
```powershell
aws s3 rm "s3://$BUCKET" --recursive
aws cloudformation delete-stack --stack-name margosha-app
aws cloudformation wait stack-delete-complete --stack-name margosha-app
```

macOS (Terminal):
```bash
aws s3 rm "s3://$BUCKET" --recursive
aws cloudformation delete-stack --stack-name margosha-app
aws cloudformation wait stack-delete-complete --stack-name margosha-app
```
