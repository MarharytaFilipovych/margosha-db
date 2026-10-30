# Spec Delta

## Purpose

Describes how the sharded store is benchmarked with YCSB and how the results are kept, so later milestones (M3, M5) can be compared against the M2 baseline.

## ADDED Requirements

### Requirement: YCSB binding
The project SHALL provide a YCSB database binding for the store that maps YCSB's table, key and fields to a table, a partition key and record attributes, and maps YCSB read, insert, update and delete to READ, CREATE, UPDATE and DELETE through the coordinator. YCSB scan SHALL return YCSB's not-implemented status.

#### Scenario: Binding round trip
- **WHEN** YCSB loads records through the binding and then reads them
- **THEN** every read returns the fields that were inserted, and YCSB reports no failed operations

#### Scenario: Scan not supported
- **WHEN** YCSB issues a scan through the binding
- **THEN** the binding returns YCSB's not-implemented status instead of a wrong result

### Requirement: Benchmark procedure
The benchmark SHALL run against the deployed sharded store (coordinator and 2 shards in the Learner Lab), from the YCSB client in the same VPC, with one YCSB core workload that does not use scans. The workload, record count, operation count, thread count and YCSB version SHALL be written down.

#### Scenario: Reproducible run
- **WHEN** someone follows the documented procedure in a later milestone
- **THEN** they run the same workload with the same parameters and get results in the same format

### Requirement: Baseline results kept
The project SHALL keep in the repository the raw YCSB output and a summary with throughput (ops/sec) and the average, 95th and 99th percentile latency of each operation type, together with the commit that was benchmarked.

#### Scenario: Reviewer reads the baseline
- **WHEN** a reviewer opens the M2 benchmark results
- **THEN** they see the workload, its throughput, latency percentiles per operation, the benchmarked commit and a link to the raw output
