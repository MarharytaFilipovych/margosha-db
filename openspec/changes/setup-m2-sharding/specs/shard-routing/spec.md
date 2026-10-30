# Spec Delta

## Purpose

Describes how the coordinator decides where data lives: single-node mode, shard registration, the consistent hashing ring with virtual nodes, and routing of record writes and reads to shards.

## ADDED Requirements

### Requirement: Single-node mode
The coordinator SHALL be able to run in single-node mode, selected when it starts. In single-node mode it SHALL serve the whole API and store all records itself, without any shards.

#### Scenario: Records stored on the coordinator
- **WHEN** the coordinator runs in single-node mode and a client creates and then reads a record
- **THEN** both operations succeed and no request is sent to any shard

### Requirement: Shard registration
In sharded mode, the coordinator SHALL offer an API operation to register a shard with a shard ID and the address it serves on. Each shard SHALL call it when it starts, retrying until the coordinator accepts it, and SHALL then call it again at a fixed interval, so a coordinator that restarted gets its shards back. A registered shard SHALL be added to the ring; registering it again at the same address SHALL change nothing.

#### Scenario: Shard registers on start
- **WHEN** a shard starts while the coordinator is running
- **THEN** the shard is registered with its ID and address and owns part of the ring

#### Scenario: Coordinator not reachable yet
- **WHEN** a shard starts before the coordinator is up
- **THEN** the shard keeps retrying and is registered once the coordinator is up

#### Scenario: Coordinator restarts
- **WHEN** the coordinator restarts while the shards keep running
- **THEN** each shard is registered again within the re-registration interval, without restarting the shard, and requests are routed to it again

### Requirement: Consistent hashing ring
In sharded mode, the coordinator SHALL map record keys to shards with a consistent hashing ring in which each shard owns several virtual nodes. The hash function and the number of virtual nodes per shard SHALL be the ones chosen in the hash function ADR, and SHALL be configurable.

#### Scenario: Keys are spread across shards
- **WHEN** 100,000 distinct partition keys are placed on a ring with 2 shards
- **THEN** each shard owns between 40% and 60% of them

#### Scenario: Adding a shard moves only part of the keys
- **WHEN** a third shard is added to a ring of 2 shards holding 100,000 keys
- **THEN** the only keys that change owner are the ones that move to the new shard, and they are at most 45% of all keys

### Requirement: Routing is deterministic
The shard for a record SHALL be decided only by the record's routing key (derived from its primary key as set by the partition key strategy ADR) and the current ring. The same key SHALL always go to the same shard while the ring does not change.

#### Scenario: Same key, same shard
- **WHEN** the same primary key is written and later read with no change to the registered shards
- **THEN** both requests are handled by the same shard

### Requirement: Shard writes
In sharded mode, CREATE, UPDATE and DELETE SHALL be forwarded to the shard that owns the record's key, and the coordinator SHALL return that shard's result to the client.

#### Scenario: Write lands on one shard
- **WHEN** a client creates a record in sharded mode
- **THEN** the record is stored on exactly one shard: the owner of its key

### Requirement: Shard reads
In sharded mode, READ and EXISTS SHALL be forwarded to the shard that owns the record's key, and the coordinator SHALL return that shard's result to the client.

#### Scenario: Read from the owning shard
- **WHEN** a client reads a record that was created in sharded mode
- **THEN** the coordinator asks only the owning shard and returns the stored record

### Requirement: Ring changes do not move data
In this milestone, registering a new shard SHALL change only where new requests go; records already stored SHALL stay on the shard that holds them.

#### Scenario: Shard added after data was loaded
- **WHEN** a shard is added after records were written
- **THEN** records whose keys moved to the new shard are reported as not found until they are written again

### Requirement: Shard unavailable
In sharded mode, a record operation SHALL fail with a service-unavailable error when no shard is registered, or when the shard that owns the key does not answer within a configured timeout. The coordinator SHALL NOT send the request to another shard instead.

#### Scenario: No shard registered
- **WHEN** a client creates a record in sharded mode while no shard is registered
- **THEN** the request fails with a service-unavailable error

#### Scenario: Owning shard is down
- **WHEN** the shard that owns a key is stopped and a client reads that key
- **THEN** the request fails with a service-unavailable error after the timeout, and requests for keys owned by the other shard still succeed
