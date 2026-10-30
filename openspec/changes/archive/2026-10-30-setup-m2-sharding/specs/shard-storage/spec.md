# Spec Delta

## Purpose

Describes how a node (a shard, or the coordinator in single-node mode) keeps records.

## ADDED Requirements

### Requirement: Records kept in memory
A node SHALL keep the records of each table in memory. Records SHALL NOT be kept across a restart of the node.

#### Scenario: Read after write on a running node
- **WHEN** a record is written to a node and read back while the node keeps running
- **THEN** the read returns the record

#### Scenario: Node restarts
- **WHEN** a node restarts
- **THEN** it starts with no records
