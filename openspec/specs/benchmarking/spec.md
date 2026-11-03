# benchmarking Specification

## Purpose
Describes how the sharded store is benchmarked with YCSB and how the results are kept, so later milestones (M3, M5) can be compared against the M2 baseline.

## Requirements

### Requirement: YCSB binding
The project SHALL provide a YCSB database binding for the store that maps YCSB's table, key and fields to a table, a partition key and record attributes, and maps YCSB read, insert, update and delete to READ, CREATE, UPDATE and DELETE through the coordinator. YCSB scan SHALL return YCSB's not-implemented status.

#### Scenario: Binding round trip
- **WHEN** YCSB loads records through the binding and then reads them
- **THEN** every read returns the fields that were inserted, and YCSB reports no failed operations

#### Scenario: Scan not supported
- **WHEN** YCSB issues a scan through the binding
- **THEN** the binding returns YCSB's not-implemented status instead of a wrong result

### Requirement: Benchmark procedure
The benchmark SHALL run against the deployed store (in M3: the coordinator and 2 shards of 3 replicas each, in the Learner Lab), from the YCSB client in the same VPC, with one YCSB core workload that does not use scans. Each benchmark SHALL start with a warm-up run whose results are discarded, followed by three measured runs. The workload, record count, operation count, thread count, quorum settings and YCSB version SHALL be written down.

#### Scenario: Reproducible run
- **WHEN** someone follows the documented procedure in a later milestone
- **THEN** they run the same workload with the same parameters and get results in the same format

#### Scenario: Results include their spread
- **WHEN** a reviewer opens a benchmark's results
- **THEN** they see each measured run's throughput and latencies, and the median of the three runs

### Requirement: Baseline results kept
The project SHALL keep in the repository the raw YCSB output and a summary with throughput (ops/sec) and the average, 95th and 99th percentile latency of each operation type, together with the commit that was benchmarked.

#### Scenario: Reviewer reads the baseline
- **WHEN** a reviewer opens the M2 benchmark results
- **THEN** they see the workload, its throughput, latency percentiles per operation, the benchmarked commit and a link to the raw output

### Requirement: Comparison with the M2 baseline
The M3 results SHALL be compared against the M2 baseline with the same workload and parameters: throughput and the average, 95th and 99th percentile latency per operation, and an explanation of the differences, including why p99 changed.

#### Scenario: Reviewer compares milestones
- **WHEN** a reviewer opens the M3 benchmark results
- **THEN** they see the M2 and M3 numbers side by side for each operation, the M3 quorum settings, and the explanation of the difference
