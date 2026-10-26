package com.margosha.ring;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class HashRing {

    private record Snapshot(NavigableMap<Long, String> positions, Set<String> shards) { }

    private final HashFunction hashFunction;
    private final int virtualNodes;
    private volatile Snapshot snapshot = new Snapshot(Collections.emptyNavigableMap(), Set.of());

    public HashRing(HashFunction hashFunction, int virtualNodes) {
        if (virtualNodes < 1) {
            throw new IllegalArgumentException("virtualNodes must be at least 1, was " + virtualNodes);
        }
        this.hashFunction = Objects.requireNonNull(hashFunction, "hashFunction");
        this.virtualNodes = virtualNodes;
    }

    public synchronized boolean addShard(String shardId) {
        if (shardId == null || shardId.isBlank()) {
            throw new IllegalArgumentException("shardId must be set");
        }
        Snapshot current = snapshot;
        if (current.shards().contains(shardId)) {
            return false;
        }
        TreeMap<Long, String> positions = new TreeMap<>(current.positions());
        for (int i = 0; i < virtualNodes; i++) {
            // On a (very unlikely) collision the smaller shard ID wins, so the result does not depend on add order.
            positions.merge(hashFunction.hash(shardId + "#" + i), shardId,
                    (a, b) -> a.compareTo(b) <= 0 ? a : b);
        }
        TreeSet<String> shards = new TreeSet<>(current.shards());
        shards.add(shardId);
        snapshot = new Snapshot(Collections.unmodifiableNavigableMap(positions), Collections.unmodifiableSet(shards));
        return true;
    }

    public Optional<String> shardFor(String routingKey) {
        Objects.requireNonNull(routingKey, "routingKey");
        NavigableMap<Long, String> positions = snapshot.positions();
        if (positions.isEmpty()) {
            return Optional.empty();
        }
        Map.Entry<Long, String> owner = positions.higherEntry(hashFunction.hash(routingKey));
        if (owner == null) {
            owner = positions.firstEntry();
        }
        return Optional.of(owner.getValue());
    }

    public Set<String> shards() {
        return snapshot.shards();
    }

    public int size() {
        return snapshot.positions().size();
    }

    public int virtualNodes() {
        return virtualNodes;
    }
}
