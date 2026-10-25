"""Consistent hashing ring simulation for ADR 0005 (hash function and number of virtual nodes).

Places 100,000 YCSB-style keys on a ring with 2 shards (shard-a, shard-b), then adds a
third shard (shard-c), for several hash functions and virtual node counts. For each case
it prints:
- shard-a %:            share of the keys owned by shard-a with 2 shards (ideal: 50%)
- moved to shard-c %:   share of the keys that change owner when shard-c is added (ideal: 33.3%)
- other moves:          keys that moved somewhere other than the new shard (must be 0)
- ring entries:         size of the ring with 3 shards
- worst |a-50| %:       worst deviation from 50% over 10 other pairs of shard names
                        (shows how much the split depends on luck)

Run:  pip install mmh3 xxhash
      python ring_simulation.py
"""
import bisect
import hashlib
import zlib

import mmh3
import xxhash

N_KEYS = 100_000
VNODE_COUNTS = (1, 16, 64, 128, 256)
M64 = (1 << 64) - 1


def fnv64(value):
    """FNV-1a 64-bit over the 8 bytes of a long, as YCSB's Utils.fnvhash64 does."""
    h = 0xCBF29CE484222325
    for _ in range(8):
        h ^= value & 0xFF
        value >>= 8
        h = (h * 1099511628211) & M64
    return h if h < (1 << 63) else h - (1 << 64)


def ycsb_key(i):
    """YCSB's default key format (insertorder=hashed): "user" + hash of the record number."""
    return "user" + str(abs(fnv64(i)))


def java_string_hashcode(s):
    h = 0
    for c in s:
        h = (31 * h + ord(c)) & 0xFFFFFFFF
    return h


HASHES = {
    "MurmurHash3 32-bit": lambda s: mmh3.hash(s, signed=False),
    "MurmurHash3 128-bit (first 64 bits)": lambda s: mmh3.hash64(s, signed=False)[0],
    "xxHash64": lambda s: xxhash.xxh64_intdigest(s.encode()),
    "MD5 (first 64 bits)": lambda s: int.from_bytes(hashlib.md5(s.encode()).digest()[:8], "big"),
    "CRC32": lambda s: zlib.crc32(s.encode()),
    "Java String.hashCode": java_string_hashcode,
}


def build_ring(hash_fn, shards, vnodes):
    """Sorted ring positions: each shard gets `vnodes` positions, hash("<shardId>#<i>")."""
    points = sorted((hash_fn(f"{shard}#{i}"), shard) for shard in shards for i in range(vnodes))
    return [p for p, _ in points], [s for _, s in points]


def owner(ring, key_hash):
    """The first shard clockwise from the key's position (wrapping around the end)."""
    positions, shards = ring
    i = bisect.bisect_right(positions, key_hash)
    return shards[i % len(shards)]


def main():
    keys = [ycsb_key(i) for i in range(N_KEYS)]
    print(f"{N_KEYS} YCSB-style keys, e.g. {keys[0]}")
    print(f"{'hash':38}{'vnodes':>7}{'shard-a %':>11}{'moved to shard-c %':>20}"
          f"{'other moves':>13}{'ring entries':>14}{'worst |a-50| %':>16}")
    for name, hash_fn in HASHES.items():
        key_hashes = [hash_fn(k) for k in keys]
        for vnodes in VNODE_COUNTS:
            ring2 = build_ring(hash_fn, ["shard-a", "shard-b"], vnodes)
            ring3 = build_ring(hash_fn, ["shard-a", "shard-b", "shard-c"], vnodes)
            before = [owner(ring2, h) for h in key_hashes]
            after = [owner(ring3, h) for h in key_hashes]

            share_a = before.count("shard-a") / N_KEYS * 100
            moved = sum(1 for b, a in zip(before, after) if b != a)
            moved_to_c = sum(1 for b, a in zip(before, after) if b != a and a == "shard-c")

            worst = 0.0
            sample = key_hashes[:20_000]
            for t in range(10):
                ring = build_ring(hash_fn, [f"s{t}-a", f"s{t}-b"], vnodes)
                owners = [owner(ring, h) for h in sample]
                worst = max(worst, abs(owners.count(f"s{t}-a") / len(sample) * 100 - 50))

            print(f"{name:38}{vnodes:>7}{share_a:>11.1f}{moved / N_KEYS * 100:>20.1f}"
                  f"{moved - moved_to_c:>13}{len(ring3[0]):>14}{worst:>16.1f}")


if __name__ == "__main__":
    main()
