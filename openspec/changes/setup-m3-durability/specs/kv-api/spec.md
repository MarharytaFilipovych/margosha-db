# Spec Delta

## ADDED Requirements

### Requirement: Table definitions survive a coordinator restart
In sharded mode, the coordinator SHALL keep registered table definitions on its disk, so that after a restart the
tables are still registered and their records, kept by the replicas, can be used without registering the tables again.

#### Scenario: Coordinator restarts
- **WHEN** a table is registered, a record is created in it, and the coordinator restarts
- **THEN** the record can be read without registering the table again, and registering the table again fails because it already exists
