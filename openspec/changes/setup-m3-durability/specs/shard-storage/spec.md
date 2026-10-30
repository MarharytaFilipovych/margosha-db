# Spec Delta

## ADDED Requirements

### Requirement: Replica records survive a restart
A replica SHALL keep its records on its own disk, so that after a restart (a reboot, a deploy, or the Learner Lab
stopping and starting the instance) it holds the same records as before, without asking another node.

#### Scenario: Replica restarts
- **WHEN** records are written to a shard and one of its replicas restarts
- **THEN** after the restart that replica holds the records it had stored before, at the same versions

#### Scenario: Whole store restarts
- **WHEN** the coordinator and every replica are rebooted at the same time
- **THEN** every committed record can be read again once they are back

### Requirement: Single-node mode keeps records in memory
In single-node mode the coordinator SHALL keep records in memory only, as in M2; they SHALL NOT be kept across its
restart. Single-node mode is for local use and tests, not for the deployed store.

#### Scenario: Single-node coordinator restarts
- **WHEN** a coordinator in single-node mode restarts
- **THEN** it starts with no records

## REMOVED Requirements

### Requirement: Records kept in memory
**Reason**: M3 makes the shards durable: a replica keeps its records across a restart.
**Migration**: Replicas follow "Replica records survive a restart"; single-node mode keeps the old behaviour under
"Single-node mode keeps records in memory". Data written by M2 is not migrated: it was in memory and is lost when the
M2 shards are replaced.
