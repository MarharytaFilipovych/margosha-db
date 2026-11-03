# Spec Delta

## ADDED Requirements

### Requirement: Table definitions survive a coordinator restart
In sharded mode, the coordinator SHALL keep registered table definitions on its disk, so that after a restart the
tables are still registered and their records, kept by the replicas, can be used without registering the tables again.

#### Scenario: Coordinator restarts
- **WHEN** a table is registered, a record is created in it, and the coordinator restarts
- **THEN** the record can be read without registering the table again, and registering the table again fails because it already exists

#### Scenario: Writing a table definition to disk fails
- **WHEN** writing a table definition to the coordinator's disk fails part way, or forcing it to disk fails
- **THEN** the registration fails, the table is not registered, and the file is cut back to what it held before, so no part of that definition is left in it and the coordinator still starts
