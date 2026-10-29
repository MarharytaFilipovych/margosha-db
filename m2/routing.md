# Routing: how a key reaches a shard

This page describes how the coordinator decides where a record lives in M2.
Decisions behind it: [ADR 0004](../adr/0004-api-protocol.md) (REST + JSON),
[ADR 0005](../adr/0005-hash-function-and-vnodes.md) (MurmurHash3 128-bit, 128 virtual nodes),
[ADR 0006](../adr/0006-partition-key-strategy.md) (route by partition key only).

## Two modes

The coordinator is started with a mode:

```
java -jar margosha.jar coordinator --mode=single
java -jar margosha.jar coordinator --mode=sharded
java -jar margosha.jar shard --margosha.shard.id=shard-a --margosha.coordinator.url=http://<coordinator-ip>:8080
```

| | single | sharded |
|---|---|---|
| Where records are stored | in the coordinator's own memory | on the shards, in their memory |
| Record storage | `InMemoryRecordStorage` | `ShardRoutingService` -> one `RemoteShardStorageService` per shard |
| `POST /shards` | not served | registers a shard |
| No shard registered | n/a | every record operation answers `503 SHARD_UNAVAILABLE` |

The rest of the coordinator (table registry, key validation, `RecordService`) is the same code in both modes.
Only the record storage differs: the mode chooses which `RecordStorage` bean exists (`CoordinatorConfig`).

### Where the code is

Code used by only one role is under `com.margosha.node.coordinator` or `com.margosha.node.shard`; code both roles
use (`RecordStorage`, `InMemoryRecordStorage`, the shared exceptions, `ApiExceptionHandler`) stays directly under
`com.margosha.node`.

| Class | Runs on | Job |
|---|---|---|
| `shard.service.CoordinatorRegistrationService` | shard | Sends `POST /shards` to the coordinator when the shard starts, retrying until accepted |
| `coordinator.service.ShardRegistryService` | coordinator | Keeps the registered shards and the ring; finds the shard that owns a key |
| `coordinator.storage.ShardRoutingService` | coordinator | The `RecordStorage` of sharded mode: sends each operation to the owning shard |
| `coordinator.storage.RemoteShardStorageService` | coordinator | Calls one shard over HTTP and turns its errors back into coordinator errors |

## Path of one request (sharded mode)

Example: `GET /tables/users/records/42` on a coordinator with `shard-a` and `shard-b` registered.

1. **Table and key check** (coordinator, `RecordService`): the table `users` must exist, and the key must
   match its definition (partition key only, no `sortKey`). Errors here (`TABLE_NOT_FOUND`, `VALIDATION_ERROR`)
   never reach a shard.
2. **Routing key** (`RoutingKeys.of`): the partition key `42`. The sort key is not used, so all records of one
   partition are on the same shard (ADR 0006).
3. **Owner on the ring** (`HashRing.shardFor`): `murmur3("42")` is looked up on the ring; the first virtual node
   after it (wrapping around) gives the owning shard ID.
4. **Forward** (`ShardRoutingService` -> `RemoteShardStorageService`): the request goes to that one shard only, as
   `GET <shard address>/tables/users/records/42`.
5. **Answer**: the shard returns the stored attributes; the coordinator adds the key attributes back and answers
   `{"user_id":"42", ...}`. A `404 RECORD_NOT_FOUND` or `409 RECORD_ALREADY_EXISTS` from the shard is passed on
   as the same error.

CREATE, READ, EXISTS, UPDATE and DELETE all follow these steps.
A shard has no table definitions, so the coordinator always puts the key in the path and sends only the
non-key attributes in the body (see the shard notes in `app/api/src/main/resources/openapi.yaml`).

### What the tests show

| Behaviour | Test |
|---|---|
| Each of 100 records is stored on its owner and not on the other shard; both shards get records | `ShardedRoutingTest.eachRecordIsStoredOnlyOnItsOwningShard` |
| UPDATE and DELETE change only the owner | `ShardedRoutingTest.updateAndDeleteChangeOnlyTheOwningShard` |
| READ never returns a record that only the other shard has | `ShardedRoutingTest.readReturnsTheOwnersRecordNotTheOtherShards`, `readDoesNotFallBackToTheOtherShard` |
| EXISTS asks only the owner | `ShardedRoutingTest.existsAsksOnlyTheOwningShard` |
| `orders` records `42/order-1` and `42/order-2` are on one shard | `ShardedRoutingTest.recordsOfOnePartitionAreOnOneShard` |

## Shard registration

A shard registers itself when it starts: `POST /shards` with `{"shardId":"shard-a","address":"http://10.0.1.15:8080"}`.

- The shard ID is fixed (`shard-a`, `shard-b`); the ring positions are `hash("shard-a#0") ... hash("shard-a#127")`,
  so they do not depend on the IP address.
- The address is `http://<host>:<port>`. The host is `margosha.shard.host`, or the machine's own address if it is not set.
- If the coordinator is not up yet, the shard keeps trying until it is accepted
  (`ShardRegistrationTest.shardStartingBeforeCoordinatorKeepsRetryingUntilItIsUp`). The first retry comes after
  `margosha.shard.register-retry-interval` (2 s); the pause doubles after each failure up to
  `margosha.shard.register-retry-max-interval` (30 s), so a coordinator that stays down costs about 120 log lines
  an hour, not 1,800 (`RetryBackoffTest`). Attempts never stop: a shard that gave up would run without getting any keys.
- Each attempt has a connect and read timeout of `margosha.shard.register-timeout` (2 s), so a coordinator that accepts
  the connection but never answers counts as a failed attempt instead of stopping registration
  (`ShardRegistrationTest.shardKeepsRetryingWhenCoordinatorDoesNotAnswer`).
- Registering a known shard ID again only replaces its address; the ring does not change.
- A registration without an ID or without an `http(s)://host` address is rejected with `400 VALIDATION_ERROR`.

The coordinator keeps the shard list in memory. After a coordinator restart the list is empty until the shards
register again; in M2 all nodes restart together (deploy, start of a lab session), so this happens on its own.

## When a shard does not answer

`RemoteShardStorageService` calls a shard with a connect and read timeout of `margosha.shard.timeout` (2 s).
If the shard cannot be reached or does not answer in time, the coordinator answers
`503 SHARD_UNAVAILABLE` (`"Shard shard-b did not answer"`). The request is **not** sent to another shard:
there is no replica in M2, and another shard would answer "not found" or store the record in the wrong place.

- Stopped shard: its keys fail with 503, keys of the other shard still work
  (`ShardUnavailableTest.stoppedShardMakesOnlyItsOwnKeysUnavailable`); a CREATE for its key is not stored on the other
  shard (`writeForStoppedShardIsNotSentToTheOtherShard`).
- Shard that accepts the connection but never answers: 503 after the timeout
  (`ShardUnavailableTest.shardThatDoesNotAnswerFailsAfterTheTimeout`).
- No shard registered: `503 "No shard is registered"` (`noRegisteredShardIsShardUnavailable`).

## Adding a shard

Adding a shard to the ring moves part of the key space to it: 33.2% of 100,000 keys when going from 2 to 3 shards
with the chosen setting (`m2/ring-simulation/results.txt`; at most 45% by the `shard-routing` spec, checked in
`ShardRoutingPropertiesTest`). No key moves between the two old shards.

**M2 does not move data.** Records already stored stay on the shard that holds them. So after a shard is added:

- keys that moved to the new shard read as `404 RECORD_NOT_FOUND`, and EXISTS answers `false`;
  the old copy is still on the old shard but is never asked for;
- keys that did not move read as before;
- writing a moved key again stores it on the new shard, and from then on it reads normally.

This is checked in `RingChangeTest` (200 records on 2 shards, then `shard-c` is added).
Because of this, shards are registered before data is loaded (the deployment starts all of them together), and
rebalancing is left for a later milestone.
