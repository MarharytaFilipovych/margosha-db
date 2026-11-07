# Spec Delta

## RENAMED Requirements

- FROM: `### Requirement: Quorum reads return the newest version`
- TO: `### Requirement: Quorum reads return the Last-Write-Wins winner`

## MODIFIED Requirements

### Requirement: Ordered log with versions
The leader SHALL record every accepted write in its shard's log, in the order it accepts them, with the log structure
chosen in the log structure ADR. Every write SHALL give the record a version higher than any earlier version of that
record, assigned as chosen in the ordering guarantees ADR. A DELETE SHALL also be recorded with its own version, so a deleted record can be told apart from one that a
replica never received. Every log entry SHALL also hold the write's version chosen in the record versioning ADR, and
followers SHALL keep it unchanged in their own logs.

#### Scenario: Versions follow the order of writes
- **WHEN** a record is created and then updated
- **THEN** the update's version is higher than the create's version

#### Scenario: Delete keeps a version
- **WHEN** a record is deleted
- **THEN** the log holds the delete for its key with a version higher than the record's last write

#### Scenario: Followers keep the write's version
- **WHEN** a write is replicated to a follower
- **THEN** the follower's log entry has the same log version and the same record version as the leader's

### Requirement: Quorum reads return the Last-Write-Wins winner
READ and EXISTS SHALL ask R replicas of the owning shard and return the answer that wins under the Last-Write-Wins
rule of the record versioning ADR. A delete that wins SHALL count as "record does not exist". Replica reads SHALL
return the record's version.

#### Scenario: One replica is behind
- **WHEN** R is 2, a record was updated, and one of the two replicas asked has not applied the update yet
- **THEN** the read returns the updated record

#### Scenario: Deleted record
- **WHEN** a record was deleted and one replica asked still holds the old value
- **THEN** the read reports that the record does not exist
