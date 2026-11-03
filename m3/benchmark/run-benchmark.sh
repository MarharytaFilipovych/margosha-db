#!/bin/bash
# Runs one YCSB benchmark against Margosha DB (M3: 2 shards of 3 replicas). Runs on the bench client (margosha-bench),
# see procedure.md.
#
#   LABEL=warmup ./run-benchmark.sh              load + run (the warm-up; its run results are discarded)
#   LABEL=run1 SKIP_LOAD=1 ./run-benchmark.sh    run phase only, on the records already loaded (the measured runs)
#
# The bench client should be in unlimited credit mode while benchmarking (procedure.md, step 2). In standard mode the
# script stops before starting if it has fewer than MIN_CREDITS CPU credits (default 10): with 0 credits a t3.small is
# held at 20% CPU, and the benchmark measures the client instead of the store.
#
# Every parameter can be changed with an environment variable. The defaults are the M2 values (procedure.md).
# Outputs go to ~/results/<UTC time>-<workload>-r<records>-o<operations>-t<threads>[-<label>]/.
set -euo pipefail

WORKLOAD=${WORKLOAD:-workloada}
RECORDS=${RECORDS:-100000}
OPERATIONS=${OPERATIONS:-200000}
THREADS=${THREADS:-8}
SKIP_LOAD=${SKIP_LOAD:-0}
MIN_CREDITS=${MIN_CREDITS:-10}      # the bench client's CPU credits needed to start
LABEL=${LABEL:-}                    # warmup, run1, run2, run3
COMMIT=${COMMIT:-}                  # the benchmarked commit; empty = the deployed version (AppVersion)

TABLE=usertable
KEY_ATTRIBUTE=ycsb_key
YCSB=/opt/ycsb
BINDING=$HOME/margosha-ycsb-binding.jar
REPLICATION_FACTOR=3                # replicas per shard (N): fixed in the template, the app's default

# MARGOSHA_URL (coordinator's private address) and MARGOSHA_BUCKET, written by the bench client's UserData.
source /etc/profile.d/margosha-bench.sh

imds() {
  local token
  token=$(curl -sf -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 60")
  curl -sf -H "X-aws-ec2-metadata-token: $token" "http://169.254.169.254/latest/meta-data/$1"
}
# The AWS CLI on the instance has no region configured.
export AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION:-$(imds placement/region)}

# 0. The bench client's CPU: in unlimited credit mode it is never held back, so its credits do not matter. In standard
# mode, check its own credit balance (CloudWatch keeps a data point every 5 minutes).
INSTANCE_ID=$(imds instance-id)
CREDIT_MODE=$(aws ec2 describe-instance-credit-specifications --instance-ids "$INSTANCE_ID" \
  --query "InstanceCreditSpecifications[0].CpuCredits" --output text 2>/dev/null || echo unknown)
CREDITS=$(aws cloudwatch get-metric-statistics --namespace AWS/EC2 --metric-name CPUCreditBalance \
  --dimensions "Name=InstanceId,Value=$INSTANCE_ID" --statistics Minimum --period 300 \
  --start-time "$(date -u -d '-15 min' +%Y-%m-%dT%H:%M:%S)" --end-time "$(date -u +%Y-%m-%dT%H:%M:%S)" \
  --query "sort_by(Datapoints,&Timestamp)[-1].Minimum" --output text 2>/dev/null || echo unknown)
if [ "$CREDIT_MODE" = "unlimited" ]; then
  echo "Bench client in unlimited credit mode: its CPU is not held back, so its credits ($CREDITS) are not checked."
elif [[ "$CREDITS" =~ ^[0-9]+(\.[0-9]+)?$ ]]; then
  echo "Bench client CPU credits: $CREDITS (needs at least $MIN_CREDITS)"
  if awk -v c="$CREDITS" -v m="$MIN_CREDITS" 'BEGIN { exit !(c < m) }'; then
    echo "Too few CPU credits: the client would be held at its baseline CPU and measure itself, not the store."
    echo "Switch it to unlimited credit mode (procedure.md, step 2), or wait until it has at least $MIN_CREDITS"
    echo "(a t3.small earns 24 per hour), or set MIN_CREDITS=0 to run anyway."
    exit 1
  fi
else
  echo "Bench client CPU credits: unknown ('$CREDITS', no data point yet?). Check them from the laptop (procedure.md, step 4)."
fi

RESULTS=$HOME/results/$(date -u +%Y%m%dT%H%M%SZ)-$WORKLOAD-r$RECORDS-o$OPERATIONS-t$THREADS${LABEL:+-$LABEL}
mkdir -p "$RESULTS"
echo "Results: $RESULTS"

# 1. What is deployed: the version (for the binding jar) and the quorum settings, from the app stack's parameters.
STACK=${STACK:-margosha-app}
stack_param() {
  aws cloudformation describe-stacks --stack-name "$STACK" \
    --query "Stacks[0].Parameters[?ParameterKey=='$1'].ParameterValue" --output text
}
VERSION=${VERSION:-$(stack_param AppVersion)}
WRITE_QUORUM=$(stack_param WriteQuorum)
READ_QUORUM=$(stack_param ReadQuorum)
echo "Version $VERSION, N=$REPLICATION_FACTOR, W=$WRITE_QUORUM, R=$READ_QUORUM"

# 2. The binding jar that the pipeline published with that version.
aws s3 cp --only-show-errors "s3://$MARGOSHA_BUCKET/releases/$VERSION/margosha-ycsb-binding.jar" "$BINDING"
BINDING_SHA=$(sha256sum "$BINDING" | cut -c1-16)
COMMIT=${COMMIT:-$VERSION}
echo "Binding: s3://$MARGOSHA_BUCKET/releases/$VERSION/margosha-ycsb-binding.jar, sha256 $BINDING_SHA"

# 3. Register the table. A fresh store has no tables; 409 means records from an earlier load are still there.
if [ "$SKIP_LOAD" != "1" ]; then
  code=$(curl -s -m 10 -o "$RESULTS/register-table.json" -w '%{http_code}' -X POST "$MARGOSHA_URL/tables" \
    -H 'Content-Type: application/json' -d "{\"name\":\"$TABLE\",\"partitionKey\":\"$KEY_ATTRIBUTE\"}")
  echo "Register $TABLE: HTTP $code $(cat "$RESULTS/register-table.json")"
  if [ "$code" = "409" ]; then
    echo "The store still holds an earlier load. Reset the store first (procedure.md, step 3)."
    exit 1
  elif [ "$code" != "201" ]; then
    echo "Could not register the table. Is the coordinator up and are all 6 replicas registered?"
    exit 1
  fi
fi

cat > "$RESULTS/params.txt" <<EOF
date_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
label=$LABEL
commit=$COMMIT
ycsb_version=$(basename "$(readlink -f "$YCSB")" | sed 's/^ycsb-//')
workload=$WORKLOAD
recordcount=$RECORDS
operationcount=$OPERATIONS
threads=$THREADS
skip_load=$SKIP_LOAD
table=$TABLE
replication_factor=$REPLICATION_FACTOR
write_quorum=$WRITE_QUORUM
read_quorum=$READ_QUORUM
coordinator=$MARGOSHA_URL
binding_sha256=$BINDING_SHA
bench_instance_type=$(imds instance-type)
bench_credit_mode=$CREDIT_MODE
bench_cpu_credits_at_start=$CREDITS
java=$(java -version 2>&1 | head -n 1)
EOF
cp "$YCSB/workloads/$WORKLOAD" "$RESULTS/$WORKLOAD"

ycsb() {
  # The binding is added to YCSB's classpath and named with -db, so it doesn't need to be in YCSB's binding list.
  java -cp "$YCSB/lib/*:$BINDING" site.ycsb.Client "$@" \
    -db com.margosha.ycsb.MargoshaDbClient \
    -P "$YCSB/workloads/$WORKLOAD" \
    -p margosha.url="$MARGOSHA_URL" \
    -p margosha.partitionkey="$KEY_ATTRIBUTE" \
    -p table="$TABLE" \
    -p recordcount="$RECORDS" \
    -p operationcount="$OPERATIONS" \
    -threads "$THREADS" \
    -s
}

# 4. Load, then run. stdout = final results, stderr = progress every 10 s.
if [ "$SKIP_LOAD" != "1" ]; then
  echo "Loading $RECORDS records with $THREADS threads..."
  ycsb -load > "$RESULTS/load.txt" 2> "$RESULTS/load.log"
fi
echo "Running $WORKLOAD: $OPERATIONS operations with $THREADS threads..."
ycsb -t > "$RESULTS/run.txt" 2> "$RESULTS/run.log"

# 5. Short summary, and whether anything failed.
echo
for phase in load run; do
  [ -f "$RESULTS/$phase.txt" ] || continue
  echo "== $phase =="
  grep -E '^\[(OVERALL|INSERT|READ|UPDATE|READ-MODIFY-WRITE)\], (RunTime|Throughput|Operations|AverageLatency|95th|99th)' "$RESULTS/$phase.txt" || true
done
failed=$(grep -hE -- '-FAILED\]|Return=(ERROR|NOT_FOUND|BAD_REQUEST|SERVICE_UNAVAILABLE|UNEXPECTED_STATE)' \
  "$RESULTS"/load.txt "$RESULTS"/run.txt 2>/dev/null || true)
echo
if [ -z "$failed" ]; then
  echo "No failed operations."
else
  echo "FAILED OPERATIONS:"
  echo "$failed"
  grep -h '^margosha:' "$RESULTS"/load.log "$RESULTS"/run.log 2>/dev/null | head -n 10 || true
fi
echo "Results: $RESULTS"
