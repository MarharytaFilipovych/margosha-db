# Ring simulation

Evidence for [ADR 0005](../../adr/0005-hash-function-and-vnodes.md): how evenly different hash functions and virtual node counts spread YCSB keys over a consistent hashing ring, and how many keys move when a shard is added.

This is a standalone experiment, written before the ring code. It is not part of the application. The ring tests in the application confirm the chosen setting later.

## Run

```bash
pip install mmh3 xxhash
python ring_simulation.py
```

The output of the run used in the ADR is in [results.txt](results.txt).

## What it does

- Generates 100,000 keys in YCSB's default format (`user` + a hash of the record number, e.g. `user6284781860667377211`).
- Builds a ring with `shard-a` and `shard-b`. Each shard gets `vnodes` positions: `hash("<shardId>#<i>")`.
- A key belongs to the first shard clockwise from `hash(key)`.
- Adds `shard-c` and counts which keys change owner.
- Repeats the 2-shard split with 10 other pairs of shard names, to show how much the result depends on luck.

## Columns

| Column | Meaning | Ideal |
|---|---|---|
| `shard-a %` | share of the keys owned by `shard-a` with 2 shards | 50% |
| `moved to shard-c %` | share of the keys that change owner when `shard-c` is added | 33.3% |
| `other moves` | keys that moved somewhere other than the new shard | 0 |
| `ring entries` | ring size with 3 shards | — |
| `worst \|a-50\| %` | worst deviation from 50% over the 10 other pairs of shard names | 0 |
