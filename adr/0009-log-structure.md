# ADR 0009: Log structure

## Context

The leader records every write in a log, and the followers copy it. We need to decide on:
1. **Where the log lives.**
2. **What an entry contains.**
3. **The format**, and how we notice a write that was cut off by a crash.
4. **When an entry counts as stored**, which is what "W replicas stored it" means (ADR 0007).
5. **How the log grows.**

## Alternatives considered

### Where the log lives

The brief gives examples ("Kafka, RabbitMQ, physical file on S3, etc").
- **Kafka or RabbitMQ:** a real log broker, but it needs its own instance (9 of 9 while benchmarking), and Kafka needs
  more than the 1 GiB of RAM a `t3.micro` has. Rejected.
- **File on S3:** an S3 object can't be appended to, so every write would be a new object. Rejected.
- **A file on each replica's own disk:** the same idea as the brief's "physical file", but local. Appending is fast and
  free, and each replica has its own copy, so losing one disk loses one replica, not the data.

### What an entry contains

- **The client's request** ("update key 5"): every follower would repeat the leader's checks and could come to a
  different answer. Rejected.
- **The result** (the full new value, or "deleted"): a follower just stores it, no checks needed.

### Format

- **Binary:** smaller and faster, but can't be read without a tool. Rejected.
- **Text, one JSON line per entry:** can be easily explored with `tail`. Bigger, but fine for our size.
- Each line gets a **checksum (CRC32)**. Without it, a half-written last line after a crash would break the rebuild.

### When an entry counts as stored

- **After `write()`:** the data is in the operating system's memory, not yet on disk. Fast, but lost if the machine dies. Rejected.
- **After `fsync()`:** the data is forced to the disk. Costs about 1–3 ms per write, but survives a power cut. The brief asks W replicas to "durably ack" a write.

### Growth

- **Trim old entries:** needs snapshots, which are out of scope. Rejected.
- **The log grows forever:** the benchmark writes about 1M entries, far below the 8 GiB disk.

## Decision

An **append-only file on each replica's disk**, one **JSON line per entry** holding the **result of the write**.
Each line gets a checksum (CRC32), checked when the log is read on start. 
It shows whether a line is complete and unchanged.
An entry counts as stored only **after `fsync()`**. The log is never trimmed in M3.

On start, a replica reads its log and rebuilds its records in memory. A bad last line is dropped (it was cut off by a
crash, so it was never confirmed). A bad line in the middle means the file is damaged, and the replica refuses to start.

## Consequences

- Records survive restarts, deploys and the end of a lab session.
- Every write pays one `fsync()` on the leader and one on a follower, so writes are slower than in M2. The benchmark
  shows how much.
- The log can be read with `tail` to show what was written.
- Start-up gets slower as the log grows, because the whole log is read again.
- If a replica's disk is lost, that replica is lost, but the other 2 replicas still have the data.
