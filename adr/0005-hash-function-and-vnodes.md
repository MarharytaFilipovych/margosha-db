# ADR 0005: Hash function and number of virtual nodes

## Context

In sharded mode the Coordinator uses a consistent hashing ring to decide which shard stores each record (M2 task 3).
The ring needs two settings:

- **A hash function**: it turns a key into a number, which is the key's position on the ring.
  Each shard also gets positions on the ring in the same way.
- **The number of virtual nodes per shard**: how many positions each shard has on the ring.

A key belongs to the first shard position after it, going clockwise around the ring.
If each shard has only one position, the ring is cut into a few big pieces, and how big each piece is depends on luck.
If each shard has many positions, it owns many small pieces, and in total the shards get about the same share.

The `shard-routing` spec says what is good enough: with 2 shards, each shard must own 40-60% of the keys.
When a third shard is added, at most 45% of the keys may move, and they may only move to the new shard.
The ideal values are 50% and 33.3%.

All YCSB keys look almost the same (`user` followed by digits).
The hash must still give them very different positions on the ring, otherwise similar keys end up on the same shard.

The numbers below come from a simulation that was written before the ring code: [m2/ring-simulation](../m2/ring-simulation/README.md).
It puts 100,000 YCSB-style keys on a ring with `shard-a` and `shard-b`, and then adds `shard-c`.
The result also depends on where the shard names happen to land on the ring.
So the "worst split" column builds the 2-shard ring again with 10 other pairs of shard names and shows the biggest difference from 50%.
A small number means the split is not just luck.
Full output: [results.txt](../m2/ring-simulation/results.txt).

## Alternatives considered

### Hash function

| Hash (128 virtual nodes) | shard-a share | Keys moved to shard-c | Worst split with other shard names |
|---|---|---|---|
| MurmurHash3 128-bit | 52.8% | 33.2% | ±4.6% |
| MurmurHash3 32-bit | 48.9% | 30.9% | ±7.4% |
| xxHash64 | 50.9% | 38.2% | ±5.0% |
| MD5 | 48.4% | 36.1% | ±4.5% |
| CRC32 | 55.9% | 22.3% | ±9.1% |
| Java `String.hashCode` | 100% | 0% | ±50% |

- *Java `String.hashCode`*: rejected.
  One shard gets 100% of the keys.
  The virtual node names (`shard-a#0`, `shard-a#1`, ...) are almost the same strings, and this hash gives almost the same strings almost the same numbers.
  So all positions of a shard end up next to each other on the ring.
- *CRC32*: rejected.
  It is made for finding errors in data, not for spreading keys evenly, and it gives one shard 55-58% of the keys.
- *MD5*: spreads keys well and is built into Java (`MessageDigest`), so no extra library is needed.
  Rejected because it is a security hash: it is slower than the others, and this project doesn't need its security.
- *xxHash64*: the fastest one, and spreads keys well.
  Rejected because its Java libraries are less common than MurmurHash3's, and its speed doesn't matter here: a call over the network takes much longer than any hash.
- *MurmurHash3 32-bit*: spreads keys well.
  Rejected because the 128-bit version depends less on the shard names (±4.6% vs ±7.4%) and has many more possible positions (64 bits instead of 32).
- *MurmurHash3 128-bit*: fast, spreads similar keys well, and it is the standard choice for this: Cassandra uses it by default to place data on its ring (`Murmur3Partitioner`).

### Number of virtual nodes (MurmurHash3 128-bit)

| Virtual nodes | shard-a share | Keys moved to shard-c | Worst split with other shard names | Ring entries (3 shards) |
|---|---|---|---|---|
| 1 | 47.3% | 32.6% | ±42.2% | 3 |
| 16 | 70.1% | 36.2% | ±19.4% | 48 |
| 64 | 55.5% | 36.0% | ±7.9% | 192 |
| 128 | 52.8% | 33.2% | ±4.6% | 384 |
| 256 | 46.7% | 33.8% | ±5.7% | 768 |

- *1 (no virtual nodes)*: rejected.
  The split is pure luck: with other shard names it is up to ±42% away from 50%.
- *16*: rejected.
  One shard gets 70.1% of the keys, which is above the 60% limit.
- *64*: good enough, but the split still depends on luck (±7.9%).
- *256*: not better than 128 here, and the ring is twice as big.
  More virtual nodes help mostly when there are many shards.
- *128*: the split that depends least on the shard names (±4.6%), and the ring is still very small.

## Decision

**MurmurHash3 128-bit** with **128 virtual nodes per shard**.
Only the first 64 bits of the hash are used as the position on the ring.

The positions of a shard are `hash("<shardId>#<i>")` for `i = 0..127`, for example `hash("shard-a#0")`, `hash("shard-a#1")`, and so on.
The hash function and the number of virtual nodes are settings, so they can be changed without changing the code.

## Consequences

- With 2 shards, each shard owns about half of the keys (52.8% and 47.2% in the simulation), which is inside the 40-60% limit.
- When a shard is added, only the keys that now belong to the new shard move (33.2% in the simulation, the ideal is 33.3%).
  No key moves between the old shards.
  This helps in M5 (rebalancing): only these keys will have to be copied to the new shard.
- The ring is very small: 256 entries for 2 shards, 384 for 3.
  Finding the shard for a key is a search in a sorted map, which is very fast compared to the network call to the shard.
- MurmurHash3 is not part of Java, so the project needs one extra library for it (for example Guava or Apache Commons Codec).
- MurmurHash3 is not a security hash, so someone who knows it could make up keys that all go to one shard.
  That doesn't matter for this project.
- The numbers come from a Python simulation.
  In Java the exact numbers will be a bit different, but the result is the same.
  The ring tests in the application check the same 40-60% and 45% limits with the chosen setting.
- The positions are computed from the shard ID, not from its address.
  So if a shard comes back with a new address, it still owns the same keys.
