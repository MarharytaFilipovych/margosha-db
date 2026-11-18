#!/usr/bin/env bash
# Prints one request as a timeline: every node's log lines with this trace ID, by start time, with durations.
# Reads the console output of every running node (aws ec2 get-console-output, no SSH), keeps the JSON log lines whose
# traceId is the given one, and prints them in order of start time (the line's time minus its durationMs).
# Needs jq (included in macOS 15 and later; otherwise: brew install jq). Node clocks are UTC on AWS.
#
# With --files it reads every node's log files instead (/var/lib/margosha/logs, kept up to 200 MB per node) over SSH,
# the replicas through the coordinator. This also finds older requests and requests made under load.
#
#   ./m4/tracing/commands/trace.sh 3f1c9a2e4b7d4e1a9c3f2a8b6d0e5f71
#   ./m4/tracing/commands/trace.sh 3f1c9a2e4b7d4e1a9c3f2a8b6d0e5f71 --files "$PEM"   # the nodes' log files over SSH
#   ./m4/tracing/commands/trace.sh my-trace ./saved-consoles     # saved console output instead: one <node>.log per node
set -euo pipefail

trace_id=${1:?usage: trace.sh <trace-id> [folder of <node>.log files | --files <key pair .pem file>]}
from_dir=""
pem=""
if [ "${2:-}" = "--files" ]; then
  pem=${3:?--files needs the key pair .pem file}
  where="log files"
else
  from_dir=${2:-}
  where="console output"
fi

# Every line with the trace ID in every node's log files, as "<node><TAB><line>"
log_files() {
  local nodes coordinator search
  nodes=$(aws ec2 describe-instances \
    --filters "Name=tag:Name,Values=margosha-coordinator,margosha-shard-*" "Name=instance-state-name,Values=running" \
    --query "Reservations[].Instances[].[Tags[?Key=='Name']|[0].Value, PrivateIpAddress, PublicIpAddress]" --output text)
  coordinator=$(printf '%s\n' "$nodes" | awk '$1 == "margosha-coordinator" { print $3 }')
  [ -n "$coordinator" ] || { echo "The coordinator is not running." >&2; exit 1; }
  # Trace IDs are only letters, digits and dashes, so the ID needs no quoting
  search="sudo zgrep -h -F $trace_id /var/lib/margosha/logs/margosha.log*"
  printf '%s\n' "$nodes" | while read -r name private _; do
    if [ "$name" = "margosha-coordinator" ]; then
      ssh -n -i "$pem" -o StrictHostKeyChecking=accept-new -o LogLevel=ERROR "ec2-user@$coordinator" "$search" || true
    else
      ssh -n -i "$pem" -o StrictHostKeyChecking=accept-new -o LogLevel=ERROR \
        -o "ProxyCommand=ssh -i \"$pem\" -o StrictHostKeyChecking=accept-new -o LogLevel=ERROR -W %h:%p ec2-user@$coordinator" \
        "ec2-user@$private" "$search" || true
    fi | awk -v node="${name#margosha-}" '{ print node "\t" $0 }'
  done
}

# Every console line of every node, as "<node><TAB><line>"
consoles() {
  if [ -n "$pem" ]; then
    log_files
    return
  fi
  if [ -n "$from_dir" ]; then
    for file in "$from_dir"/*.log; do
      awk -v node="$(basename "$file" .log)" '{ sub(/\r$/, ""); print node "\t" $0 }' "$file"
    done
    return
  fi
  aws ec2 describe-instances \
    --filters "Name=tag:Name,Values=margosha-coordinator,margosha-shard-*" "Name=instance-state-name,Values=running" \
    --query "Reservations[].Instances[].[InstanceId, Tags[?Key=='Name']|[0].Value]" --output text |
  while read -r id name; do
    { aws ec2 get-console-output --instance-id "$id" --latest --output text || true; } |
      awk -v node="${name#margosha-}" '{ sub(/\r$/, ""); print node "\t" $0 }'
  done
}

steps=$(consoles | { grep -F "\"$trace_id\"" || true; } | jq -R -r --arg trace "$trace_id" '
  (index("\t")) as $tab | .[:$tab] as $node | .[$tab + 1:]
  | (index("{\"")) as $json | select($json != null) | .[$json:]
  | (try fromjson catch empty) | select(type == "object" and .traceId == $trace)
  | (.["@timestamp"] | capture("^(?<s>[^.Z+]+)(\\.(?<f>[0-9]+))?")) as $t
  | (($t.s + "Z" | fromdateiso8601) * 1000 + ("0." + ($t.f // "0") | tonumber) * 1000) as $end
  | (.durationMs // null) as $ms
  | ($end - ($ms // 0)) as $start
  | (if .toReplica then "  -> call \(.toReplica): \(.method) \(.path)"
     elif .method and $ms != null then "\(.method) \(.path)"
     else "  \(.level): \(.message)" end) as $step
  | [$start, -($ms // 0), $node, $step, (.status // .error // "" | tostring), ($ms // "" | tostring)]
  | @tsv')

if [ -z "$steps" ]; then
  echo "No log lines with trace ID $trace_id in the $where of the running nodes." >&2
  exit 1
fi

# Same start: the longer step first, so a request comes before the calls it made.
# The step comes last, so a long path never cuts off the other columns.
sorted=$(printf '%s\n' "$steps" | sort -t "$(printf '\t')" -k1,1n -k2,2n)
first=$(printf '%s\n' "$sorted" | head -n 1 | cut -f1)
printf '%-12s  %5s  %5s  %-6s  %-11s  %s\n' 'START (UTC)' '+MS' 'MS' 'STATUS' 'NODE' 'STEP'
printf '%s\n' "$sorted" | awk -F '\t' -v first="$first" '{
  ms = int($1 % 1000); s = int($1 / 1000) % 86400
  printf "%02d:%02d:%02d.%03d  %5d  %5s  %-6s  %-11s  %s\n", s / 3600, (s % 3600) / 60, s % 60, ms, $1 - first, $6, $5, $3, $4
}'