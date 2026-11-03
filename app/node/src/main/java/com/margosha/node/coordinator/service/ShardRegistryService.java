package com.margosha.node.coordinator.service;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ShardRegistration;
import com.margosha.node.coordinator.exception.ShardUnavailableException;
import com.margosha.node.coordinator.storage.ReplicaClient;
import com.margosha.ring.HashRing;
import com.margosha.ring.RoutingKeys;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ShardRegistryService {

	private final HashRing ring;
	private final Function<ShardRegistration, ReplicaClient> connect;
	private final Duration skipAfterFailure;
	private final int replicationFactor;

	private final Map<String, ReplicaSet> replicaSets = new ConcurrentHashMap<>();

	public ShardRegistryService(HashRing ring, Function<ShardRegistration, ReplicaClient> connect) {
		this(ring, connect, ReplicaSet.DEFAULT_SKIP_AFTER_FAILURE, Integer.MAX_VALUE);
	}

	public ShardRegistryService(HashRing ring, Function<ShardRegistration, ReplicaClient> connect,
			Duration skipAfterFailure, int replicationFactor) {
		this.ring = Objects.requireNonNull(ring, "ring");
		this.connect = Objects.requireNonNull(connect, "connect");
		this.skipAfterFailure = Objects.requireNonNull(skipAfterFailure, "skipAfterFailure");
		this.replicationFactor = replicationFactor;
	}

	public synchronized ShardRegistration register(ShardRegistration replica) {
		ReplicaSet replicaSet = replicaSets.computeIfAbsent(replica.shardId(),
				shardId -> new ReplicaSet(shardId, skipAfterFailure, replicationFactor));
		ReplicaClient known = replicaSet.get(replica.replicaId());
		if (known != null && known.registration().equals(replica)) {
			log.debug("Replica {} of shard {} at {} is still registered", replica.replicaId(), replica.shardId(),
					replica.address());
			return replica;
		}
		replicaSet.checkCanAdd(replica.replicaId(), replica.role());
		replicaSet.put(connect.apply(replica));
		boolean newShard = ring.addShard(replica.shardId());
		log.info("{} replica {} ({}) of shard {} at {}{}", known == null ? "Registered" : "Updated",
				replica.replicaId(), replica.role().argument(), replica.shardId(), replica.address(),
				newShard ? "; the shard joins the ring" : "");
		return replica;
	}

	public String shardIdFor(PrimaryKey key) {
		return ring.shardFor(RoutingKeys.of(key)).orElseThrow(ShardUnavailableException::noShardRegistered);
	}

	public ReplicaSet replicaSetFor(PrimaryKey key) {
		return replicaSets.get(shardIdFor(key));
	}

	public Optional<ReplicaSet> replicaSet(String shardId) {
		return Optional.ofNullable(replicaSets.get(shardId));
	}

	public Set<String> shards() {
		return ring.shards();
	}
}
