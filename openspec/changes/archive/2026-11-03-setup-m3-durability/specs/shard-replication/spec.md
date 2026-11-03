# Spec Delta

## Purpose

Describes how each shard keeps several copies of its records: the replica set with one leader, the leader's ordered
log that followers consume, and the write and read quorums that decide when a write is committed and which version a
read returns.

## ADDED Requirements

### Requirement: Replica set per shard
Each shard SHALL be served by a replica set of N replicas: exactly one leader and N − 1 followers. Every replica of a
shard SHALL hold a copy of all of that shard's records. N SHALL be the replication factor chosen in the N/W/R ADR.

#### Scenario: Three replicas per shard
- **WHEN** the deployed store is running
- **THEN** each of its shards has 3 registered replicas: 1 leader and 2 followers

### Requirement: Writes go through the leader
CREATE, UPDATE and DELETE for a shard SHALL be applied by its leader only. The leader SHALL check the request against
its own state (for example, that the record exists for an UPDATE) before accepting it. A follower SHALL refuse a write
sent to it directly.

#### Scenario: Write sent to a follower
- **WHEN** a client of the replica API sends a CREATE to a follower
- **THEN** the follower refuses it and stores nothing

### Requirement: Ordered log with versions
The leader SHALL record every accepted write in its shard's log, in the order it accepts them, with the log structure
chosen in the log structure ADR. Every write SHALL give the record a version higher than any earlier version of that
record, assigned as chosen in the ordering guarantees ADR. A DELETE SHALL also be recorded with its own version, so a deleted record can be told apart from one that a
replica never received.

#### Scenario: Versions follow the order of writes
- **WHEN** a record is created and then updated
- **THEN** the update's version is higher than the create's version

#### Scenario: Delete keeps a version
- **WHEN** a record is deleted
- **THEN** the log holds the delete for its key with a version higher than the record's last write

### Requirement: Followers consume the log in order
Each follower SHALL receive the leader's log entries after the last position it has stored, by the mechanism chosen in
the replica synchronisation ADR, keep them in its own log in the same order, apply them, and store its new position. A
follower SHALL never apply an entry before all entries before it in the log.

#### Scenario: Follower catches up
- **WHEN** records are written while a follower is running
- **THEN** the follower applies them in log order and its stored position reaches the leader's last entry

### Requirement: Follower resync after a restart
After a restart, a follower SHALL rebuild its records from its own log and then receive only the leader's entries after
its stored position.

#### Scenario: Follower restarts while writes continue
- **WHEN** a follower is stopped, records are written, and the follower is started again
- **THEN** it receives only the entries written while it was down, and afterwards holds the same records as the leader

### Requirement: Only the shard's own followers are served
A follower SHALL name its shard and replica ID in every request for entries. The leader SHALL serve the request, and
count it as a confirmation, only when the shard is its own and the replica ID is one of the shard's other replica IDs
(`<shard>-1` to `<shard>-N` without the leader's own); otherwise it SHALL refuse it with a validation error.

#### Scenario: Unknown replica ID
- **WHEN** a request for entries names a replica ID that is not one of the shard's followers
- **THEN** the leader refuses it and does not count it as a confirmation

#### Scenario: Follower of another shard
- **WHEN** a follower of shard B is configured with shard A's leader address
- **THEN** shard A's leader refuses its requests and the follower stores nothing

### Requirement: Logs of different histories are never mixed
Every log SHALL have an ID, created by the leader for a new log and copied by a follower whose log is empty. A
follower with entries SHALL send its log's ID with every request for entries. The leader SHALL refuse the request,
copy nothing and not count it as a confirmation when the follower's log ID is not its own, or when the follower's
position is past the leader's last entry. A log with no entries SHALL have no ID.

#### Scenario: Leader lost its log
- **WHEN** a leader whose followers hold its entries restarts with an empty data folder and then records more entries than the followers have
- **THEN** the leader refuses the followers' requests, the followers keep the entries they had, and writes fail with a service-unavailable error

#### Scenario: Follower lost its log
- **WHEN** a follower restarts with an empty data folder
- **THEN** it takes the leader's log ID and receives the whole log again

### Requirement: An empty follower serves reads only after catching up
A follower that starts with an empty log SHALL register with the coordinator only once it holds every entry its
leader had when it started; until then it is not asked for reads. A follower that starts with entries in its log
SHALL register at once, also while its leader is down.

#### Scenario: Follower starts empty
- **WHEN** a follower starts with an empty data folder while its leader holds entries
- **THEN** it registers only after it has stored the leader's entries

#### Scenario: Empty follower without its leader
- **WHEN** a follower starts with an empty data folder and its leader cannot be reached
- **THEN** it does not register

### Requirement: Write quorum
A write SHALL be committed, and the client answered with success, only when W replicas of the shard (the leader
included) have stored its log entry, as "stored" is defined in the log structure ADR. If W replicas do not store it
within a configured timeout, the write SHALL fail with a service-unavailable error.

#### Scenario: Write committed with one follower
- **WHEN** W is 2, one follower of the shard is down, and a client creates a record
- **THEN** the create succeeds once the leader and the running follower have stored it

#### Scenario: Not enough replicas for a write
- **WHEN** W is 2, both followers of the shard are down, and a client creates a record
- **THEN** the create fails with a service-unavailable error after the timeout

### Requirement: Outcome of a failed write is not guaranteed
When a write fails because the write quorum was not reached, the entry SHALL stay in the leader's log and MAY become
visible after the followers catch up. The API documentation SHALL state that a service-unavailable answer to a write
means its outcome is unknown.

#### Scenario: Write becomes visible later
- **WHEN** a create failed because both followers were down, and a follower then comes back
- **THEN** a later read of that key may return the record

### Requirement: Quorum reads return the newest version
READ and EXISTS SHALL ask R replicas of the owning shard and return the result with the highest version among their
answers. A delete with the highest version SHALL count as "record does not exist".

#### Scenario: One replica is behind
- **WHEN** R is 2, a record was updated, and one of the two replicas asked has not applied the update yet
- **THEN** the read returns the updated record

#### Scenario: Deleted record
- **WHEN** a record was deleted and one replica asked still holds the old value
- **THEN** the read reports that the record does not exist

### Requirement: Reads are spread over all replicas
The coordinator SHALL choose which R replicas of a shard to ask so that, over many reads, every running replica of
the shard answers about the same number of reads, followers included.

#### Scenario: Even read load
- **WHEN** 3,000 reads are made for keys of one shard with R = 2 and all 3 replicas running
- **THEN** each replica answers between 1,800 and 2,200 of them

### Requirement: Reads with replicas down
If a replica asked for a read does not answer, or has not answered within a configured hedge delay that is much
shorter than the replica timeout, the coordinator SHALL ask another replica of the shard and use the first R answers.
A replica that failed to answer SHALL be asked last for a configured time, after which one read tries it again. The
read SHALL fail with a service-unavailable error only when fewer than R replicas answer.

#### Scenario: One follower down
- **WHEN** R is 2 and one follower of the shard is down
- **THEN** reads of that shard's keys still succeed

#### Scenario: Replica that does not answer
- **WHEN** R is 2 and one replica of the shard accepts connections but never answers
- **THEN** every read of that shard's keys succeeds in less than the replica timeout

#### Scenario: Two replicas down
- **WHEN** R is 2 and two of the shard's three replicas are down
- **THEN** reads of that shard's keys fail with a service-unavailable error

### Requirement: Configurable quorum
N, W and R SHALL be configuration settings. A node SHALL refuse to start when W or R is lower than 1 or higher than N,
or when R + W is not greater than N.

#### Scenario: Quorums that don't overlap
- **WHEN** the coordinator is started with N = 3, W = 1 and R = 2
- **THEN** it refuses to start and reports that R + W must be greater than N

#### Scenario: Default quorum
- **WHEN** no quorum settings are given
- **THEN** N, W and R have the values chosen in the N/W/R ADR (N = 3, W = 2, R = 2)

### Requirement: No leader failover
A shard's leader SHALL be fixed by configuration; no follower SHALL take over its role. While a shard's leader is
down, writes to that shard SHALL fail with a service-unavailable error, and reads SHALL keep working as long as R
replicas answer.

#### Scenario: Leader is down
- **WHEN** a shard's leader is stopped
- **THEN** writes for that shard's keys fail with a service-unavailable error, and reads of them succeed from the two followers when R is 2
