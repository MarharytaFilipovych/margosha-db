package com.margosha.node.coordinator.service;

import java.util.Map;
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
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ShardRegistryService {

	@NonNull
	private final HashRing ring;

	@NonNull
	private final Function<ShardRegistration, ReplicaClient> connect;

	private final Map<String, ReplicaSet> replicaSets = new ConcurrentHashMap<>();

	/** The registration is validated in the controller ({@code @Valid}). */
	public synchronized ShardRegistration register(ShardRegistration replica) {
		ReplicaSet replicaSet = replicaSets.computeIfAbsent(replica.shardId(), ReplicaSet::new);
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
