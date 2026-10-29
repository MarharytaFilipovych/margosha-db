#!/bin/bash
# Runs one YCSB benchmark against Margosha DB. Runs on the bench client (margosha-bench), see procedure.md.
#
#   ./run-benchmark.sh                        load + run with the parameters below
#   THREADS=32 SKIP_LOAD=1 ./run-benchmark.sh  run phase only, on the records already loaded (trial runs)
#
# Every parameter can be changed with an environment variable. The defaults are the ones in procedure.md.
# Outputs go to ~/results/<UTC time>-<workload>-r<records>-o<operations>-t<threads>/.
set -euo pipefail

WORKLOAD=${WORKLOAD:-workloada}
RECORDS=${RECORDS:-100000}
OPERATIONS=${OPERATIONS:-200000}
THREADS=${THREADS:-8}
SKIP_LOAD=${SKIP_LOAD:-0}
COMMIT=${COMMIT:-unknown}           # the benchmarked commit (from the pipeline run's Summary)

TABLE=usertable
KEY_ATTRIBUTE=ycsb_key
YCSB=/opt/ycsb
BINDING=$HOME/margosha-ycsb-binding.jar

# MARGOSHA_URL (coordinator's private address) and MARGOSHA_BUCKET, written by the bench client's UserData.
source /etc/profile.d/margosha-bench.sh

imds() {
  local token
  token=$(curl -sf -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 60")
  curl -sf -H "X-aws-ec2-metadata-token: $token" "http://169.254.169.254/latest/meta-data/$1"
}
# The AWS CLI on the instance has no region configured.
export AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION:-$(imds placement/region)}

RESULTS=$HOME/results/$(date -u +%Y%m%dT%H%M%SZ)-$WORKLOAD-r$RECORDS-o$OPERATIONS-t$THREADS
mkdir -p "$RESULTS"
echo "Results: $RESULTS"

# 1. The binding jar that the pipeline published with the deployed commit.
aws s3 cp --only-show-errors "s3://$MARGOSHA_BUCKET/releases/margosha-ycsb-binding.jar" "$BINDING"
BINDING_SHA=$(sha256sum "$BINDING" | cut -c1-16)
echo "Binding: s3://$MARGOSHA_BUCKET/releases/margosha-ycsb-binding.jar, sha256 $BINDING_SHA"

# 2. Register the table. A fresh store has no tables; 409 means records from an earlier load are still there.
if [ "$SKIP_LOAD" != "1" ]; then
  code=$(curl -s -m 10 -o "$RESULTS/register-table.json" -w '%{http_code}' -X POST "$MARGOSHA_URL/tables" \
    -H 'Content-Type: application/json' -d "{\"name\":\"$TABLE\",\"partitionKey\":\"$KEY_ATTRIBUTE\"}")
  echo "Register $TABLE: HTTP $code $(cat "$RESULTS/register-table.json")"
  if [ "$code" = "409" ]; then
    echo "The store still holds an earlier load. Reboot the coordinator and the shards first (procedure.md, step 3)."
    exit 1
  elif [ "$code" != "201" ]; then
    echo "Could not register the table. Is the coordinator up and are both shards registered?"
    exit 1
  fi
fi

cat > "$RESULTS/params.txt" <<EOF
date_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
commit=$COMMIT
ycsb_version=$(basename "$(readlink -f "$YCSB")" | sed 's/^ycsb-//')
workload=$WORKLOAD
recordcount=$RECORDS
operationcount=$OPERATIONS
threads=$THREADS
skip_load=$SKIP_LOAD
table=$TABLE
coordinator=$MARGOSHA_URL
binding_sha256=$BINDING_SHA
bench_instance_type=$(imds instance-type)
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

# 3. Load, then run. stdout = final results, stderr = progress every 10 s.
if [ "$SKIP_LOAD" != "1" ]; then
  echo "Loading $RECORDS records with $THREADS threads..."
  ycsb -load > "$RESULTS/load.txt" 2> "$RESULTS/load.log"
fi
echo "Running $WORKLOAD: $OPERATIONS operations with $THREADS threads..."
ycsb -t > "$RESULTS/run.txt" 2> "$RESULTS/run.log"

# 4. Short summary, and whether anything failed.
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
