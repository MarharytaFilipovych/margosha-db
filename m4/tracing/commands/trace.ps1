<#
.SYNOPSIS
  Prints one request as a timeline: every node's log lines with this trace ID, by start time, with durations.

.DESCRIPTION
  Reads the console output of every running node (aws ec2 get-console-output, no SSH), keeps the JSON log lines
  whose traceId is the given one, and prints them in order of start time (the line's time minus its durationMs).
  A node's console output holds only about its last 64 KB, so it finds requests from the last minutes.
  With -Files it reads every node's log files instead (/var/lib/margosha/logs, kept up to 200 MB per node) over SSH,
  the replicas through the coordinator. This also finds older requests and requests made under load.

.EXAMPLE
  .\m4\tracing\commands\trace.ps1 -TraceId 3f1c9a2e4b7d4e1a9c3f2a8b6d0e5f71

.EXAMPLE
  # The nodes' log files over SSH, with the key pair's .pem file
  .\m4\tracing\commands\trace.ps1 -TraceId 3f1c9a2e4b7d4e1a9c3f2a8b6d0e5f71 -Files -Pem $PEM

.EXAMPLE
  # Saved console output instead of AWS: one file per node, named <node>.log
  .\m4\tracing\commands\trace.ps1 -TraceId my-trace -FromDir .\saved-consoles
#>
param(
    [Parameter(Mandatory = $true)] [string] $TraceId,
    [string] $FromDir,
    [switch] $Files,
    [string] $Pem
)
$ErrorActionPreference = 'Stop'

function Get-LogFiles {
    if (-not $Pem) { throw "-Files needs -Pem, the key pair's .pem file" }
    $nodes = aws ec2 describe-instances `
        --filters "Name=tag:Name,Values=margosha-coordinator,margosha-shard-*" "Name=instance-state-name,Values=running" `
        --query "Reservations[].Instances[].[Tags[?Key=='Name']|[0].Value, PrivateIpAddress, PublicIpAddress]" --output text |
        ForEach-Object { $name, $private, $public = $_ -split "`t"; [pscustomobject]@{ Name = $name; Private = $private; Public = $public } }
    $coordinator = ($nodes | Where-Object Name -eq 'margosha-coordinator').Public
    if (-not $coordinator) { throw "The coordinator is not running" }
    # Trace IDs are only letters, digits and dashes, so the ID needs no quoting. The folder belongs to the margosha
    # user, so the * must be expanded by a root shell, not by ec2-user's
    $search = "sudo sh -c 'zgrep -h -F $TraceId /var/lib/margosha/logs/margosha.log*'"
    $options = @('-i', $Pem, '-o', 'StrictHostKeyChecking=accept-new', '-o', 'LogLevel=ERROR')
    foreach ($node in $nodes) {
        if ($node.Name -eq 'margosha-coordinator') {
            $lines = ssh @options "ec2-user@$coordinator" $search
        } else {
            # Windows PowerShell 5.1 can't pass quotes inside an argument, so the .pem path must have no spaces
            $jump = "ProxyCommand=ssh -i $Pem -o StrictHostKeyChecking=accept-new -o LogLevel=ERROR -W %h:%p ec2-user@$coordinator"
            $lines = ssh @options -o $jump "ec2-user@$($node.Private)" $search
        }
        [pscustomobject]@{ Node = $node.Name -replace '^margosha-', ''; Text = ($lines -join "`n") }
    }
}

function Get-Consoles {
    if ($FromDir) {
        Get-ChildItem $FromDir -Filter *.log | ForEach-Object {
            [pscustomobject]@{ Node = $_.BaseName; Text = [IO.File]::ReadAllText($_.FullName) }
        }
        return
    }
    if ($Files) {
        Get-LogFiles
        return
    }
    $nodes = aws ec2 describe-instances `
        --filters "Name=tag:Name,Values=margosha-coordinator,margosha-shard-*" "Name=instance-state-name,Values=running" `
        --query "Reservations[].Instances[].[InstanceId, Tags[?Key=='Name']|[0].Value]" --output text
    foreach ($node in $nodes) {
        $id, $name = $node -split "`t"
        $text = (aws ec2 get-console-output --instance-id $id --latest --output text) -join "`n"
        [pscustomobject]@{ Node = $name -replace '^margosha-', ''; Text = $text }
    }
}

$steps = foreach ($console in Get-Consoles) {
    foreach ($line in $console.Text -split "`r?`n") {
        if (-not $line.Contains("`"$TraceId`"")) { continue }
        $json = $line.IndexOf('{"')
        if ($json -lt 0) { continue }
        try { $log = $line.Substring($json) | ConvertFrom-Json } catch { continue }
        if ($log.traceId -ne $TraceId) { continue }

        $end = [DateTimeOffset]::Parse($log.'@timestamp')
        $ms = if ($null -ne $log.durationMs) { [long]$log.durationMs } else { $null }
        if ($log.toReplica) {
            $step = "  -> call $($log.toReplica): $($log.method) $($log.path)"   # the coordinator's call to a replica
        } elseif ($log.method -and $null -ne $ms) {
            $step = "$($log.method) $($log.path)"                             # a node finished handling the request
        } else {
            $step = "  $($log.level): $($log.message)"                        # any other line of the request
        }
        [pscustomobject]@{
            Start  = if ($null -ne $ms) { $end.AddMilliseconds(-$ms) } else { $end }
            Node   = $console.Node
            Step   = $step
            Status = if ($log.status) { $log.status } elseif ($log.error) { $log.error } else { '' }
            Ms     = $ms
        }
    }
}

if (-not $steps) {
    $where = if ($Files) { 'log files' } else { 'console output' }
    Write-Host "No log lines with trace ID $TraceId in the $where of the running nodes."
    exit 1
}
$first = ($steps | Sort-Object Start | Select-Object -First 1).Start
$nodeWidth = [Math]::Max(4, ($steps | ForEach-Object { $_.Node.Length } | Measure-Object -Maximum).Maximum)
$format = "{0,-12}  {1,5}  {2,5}  {3,-6}  {4,-$nodeWidth}  {5}"
# The step comes last, so a long path never cuts off the other columns
$format -f 'START (UTC)', '+MS', 'MS', 'STATUS', 'NODE', 'STEP'
# Same start: the longer step first, so a request comes before the calls it made
$steps | Sort-Object Start, @{ Expression = { - [long]$_.Ms } } | ForEach-Object {
    $format -f $_.Start.UtcDateTime.ToString('HH:mm:ss.fff'), [long]($_.Start - $first).TotalMilliseconds,
        $_.Ms, $_.Status, $_.Node, $_.Step
}