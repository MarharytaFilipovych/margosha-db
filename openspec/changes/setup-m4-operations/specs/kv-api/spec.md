# Spec Delta

## MODIFIED Requirements

### Requirement: API specification document
The project SHALL keep a machine-readable API specification in the repository, in the protocol and format chosen by the API protocol ADR. It SHALL describe registering a table, the record operations and shard registration, with their inputs, results and errors. It SHALL also describe the trace ID request and response header, the write's version in replica reads and log entries, and the metrics endpoint a node exposes, if the monitoring stack ADR uses one.

#### Scenario: Specification covers every operation
- **WHEN** a reviewer opens the API specification
- **THEN** it describes register table, EXISTS, CREATE, READ, UPDATE, DELETE on records, and register shard

#### Scenario: Specification covers the M4 additions
- **WHEN** a reviewer opens the API specification
- **THEN** it describes the trace ID header on every operation, the write's version in replica reads and log entries, and the metrics endpoint if there is one

## ADDED Requirements

### Requirement: Client API unchanged apart from the trace ID
The record and table operations SHALL keep their M3 request and response bodies and status codes. The only client
visible addition SHALL be the optional trace ID request header and the trace ID response header.

#### Scenario: Existing client
- **WHEN** the YCSB binding or an M3 client calls the M4 coordinator without a trace ID header
- **THEN** every operation answers with the same bodies and status codes as in M3
