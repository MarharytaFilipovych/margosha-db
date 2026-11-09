package com.margosha.node.coordinator.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ReplicaRole;
import com.margosha.api.model.ShardRegistration;
import com.margosha.node.common.metrics.ClusterMetrics;
import com.margosha.node.coordinator.exception.ShardUnavailableException;
import com.margosha.node.coordinator.replica.ReplicaClient;
import com.margosha.node.coordinator.replica.ReplicaSet;
import com.margosha.ring.HashRing;
import com.margosha.ring.RoutingKeys;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ShardRegistryService {

	private final HashRing ring;
	private final Function<ShardRegistration, ReplicaClient> connect;
	private final Duration skipAfterFailure;
	private final int replicationFactor;
	private final MeterRegistry meterRegistry;

	private final Map<String, ReplicaSet> replicaSets = new ConcurrentHashMap<>();

	public ShardRegistryService(HashRing ring, Function<ShardRegistration, ReplicaClient> connect) {
		this(ring, connect, ReplicaSet.DEFAULT_SKIP_AFTER_FAILURE, Integer.MAX_VALUE);
	}

	public ShardRegistryService(HashRing ring, Function<ShardRegistration, ReplicaClient> connect,
			Duration skipAfterFailure, int replicationFactor) {
		this(ring, connect, skipAfterFailure, replicationFactor, new SimpleMeterRegistry());
	}

	public ShardRegistryService(@NonNull HashRing ring, @NonNull Function<ShardRegistration, ReplicaClient> connect,
			@NonNull Duration skipAfterFailure, int replicationFactor, @NonNull MeterRegistry meterRegistry) {
		this.ring = ring;
		this.connect = connect;
		this.skipAfterFailure = skipAfterFailure;
		this.replicationFactor = replicationFactor;
		this.meterRegistry = meterRegistry;
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
		if (newShard) {
			ClusterMetrics.registerShard(meterRegistry, this, replica.shardId());
		}
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

	public List<ReplicaClient> replicas() {
		return replicaSets.values().stream().flatMap(set -> set.replicas().stream()).toList();
	}

	public long replicaCount(String shardId, ReplicaRole role) {
		return replicaSet(shardId).map(set -> set.replicas().stream()
				.filter(replica -> replica.registration().role() == role)
				.count()).orElse(0L);
	}
}
