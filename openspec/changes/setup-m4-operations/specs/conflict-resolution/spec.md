# Spec Delta

## Purpose

Gives every write of a record a version for conflict resolution and defines the Last-Write-Wins rule that decides
which copy wins when replicas hold different versions of the same record.

## ADDED Requirements

### Requirement: Every write is versioned
Every CREATE, UPDATE and DELETE SHALL give the record a version (a timestamp or vector clock, as chosen in the record
versioning ADR), assigned by the shard's leader when it accepts the write. The version SHALL be stored with the write
and kept across restarts.

#### Scenario: Version survives a restart
- **WHEN** a record is written and its replica restarts
- **THEN** the replica returns the record with the same version it had before the restart

#### Scenario: Delete has a version
- **WHEN** a record is deleted
- **THEN** the replicas keep the delete with its own version

#### Scenario: Writes in order
- **WHEN** a record is created and then updated
- **THEN** the update's version is later than the create's under the Last-Write-Wins rule

### Requirement: Last-Write-Wins between replica copies
When copies of a record from different replicas are compared, the copy with the latest version SHALL win. Ties SHALL
be broken by the deterministic rule chosen in the record versioning ADR, so that every comparison of the same copies
picks the same winner. A delete that wins SHALL mean the record does not exist.

#### Scenario: Later version wins
- **WHEN** two replicas return different values for a record and one has a later version
- **THEN** the value with the later version is used

#### Scenario: Deleted copy wins
- **WHEN** one replica holds a delete with a later version and another holds an older value
- **THEN** the record is reported as not existing

#### Scenario: Equal versions
- **WHEN** two copies have equal versions
- **THEN** the tie-break rule picks the same copy each time the two are compared, whichever replica answered first

### Requirement: Concurrent writes resolve to the last one
When several clients write the same record at the same time, afterwards every replica of the shard, and every read,
SHALL return the confirmed write that wins under the Last-Write-Wins rule.

#### Scenario: Two clients update the same key
- **WHEN** two clients update the same record at the same time and both updates succeed
- **THEN** once the followers have caught up, every replica of the shard holds the same winning update, and reads return it
