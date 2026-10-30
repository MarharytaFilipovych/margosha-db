# Spec Delta

## MODIFIED Requirements

### Requirement: Shard registration
In sharded mode, the coordinator SHALL offer an API operation to register a replica with its shard ID, replica ID, role (leader or follower) and the address it serves on. Each replica SHALL call it when it starts, retrying until the coordinator accepts it, and SHALL then call it again at a fixed interval, so a coordinator that restarted gets its replicas back. A shard SHALL be added to the ring when its first replica registers; registering a replica again with the same details SHALL change nothing.

#### Scenario: Shard registers on start
- **WHEN** the replicas of a shard start while the coordinator is running
- **THEN** each replica is registered with its shard ID, replica ID, role and address, and the shard owns part of the ring

#### Scenario: Coordinator not reachable yet
- **WHEN** a replica starts before the coordinator is up
- **THEN** the replica keeps retrying and is registered once the coordinator is up

#### Scenario: Coordinator restarts
- **WHEN** the coordinator restarts while the replicas keep running
- **THEN** each replica is registered again within the re-registration interval, without restarting the replica, and requests are routed to the shard again

#### Scenario: Second leader for a shard
- **WHEN** a replica registers as leader of a shard that already has a different registered leader
- **THEN** the registration fails with a validation error and the registered leader is kept

### Requirement: Shard writes
In sharded mode, CREATE, UPDATE and DELETE SHALL be forwarded to the leader of the shard that owns the record's key, and the coordinator SHALL return the leader's result to the client.

#### Scenario: Write lands on one shard
- **WHEN** a client creates a record in sharded mode
- **THEN** the record is stored by exactly one shard, the owner of its key: written by that shard's leader and copied to its followers, and no other shard holds it

### Requirement: Shard reads
In sharded mode, READ and EXISTS SHALL be sent to R replicas of the shard that owns the record's key, as set by the shard replication rules, and the coordinator SHALL return the newest result among their answers.

#### Scenario: Read from the owning shard
- **WHEN** a client reads a record that was created in sharded mode
- **THEN** the coordinator asks only replicas of the owning shard and returns the stored record

### Requirement: Shard unavailable
In sharded mode, a record operation SHALL fail with a service-unavailable error when no shard is registered; a write SHALL also fail this way when the owning shard's leader is not registered or does not answer within a configured timeout, and a read when fewer than R replicas of the owning shard answer within it. The coordinator SHALL NOT send the request to another shard instead.

#### Scenario: No shard registered
- **WHEN** a client creates a record in sharded mode while no shard is registered
- **THEN** the request fails with a service-unavailable error

#### Scenario: Owning shard is down
- **WHEN** all replicas of the shard that owns a key are stopped and a client reads that key
- **THEN** the request fails with a service-unavailable error after the timeout, and requests for keys owned by the other shard still succeed

#### Scenario: Owning shard's leader is down
- **WHEN** the leader of the shard that owns a key is stopped and a client updates that key
- **THEN** the update fails with a service-unavailable error after the timeout, and requests for keys owned by the other shard still succeed
