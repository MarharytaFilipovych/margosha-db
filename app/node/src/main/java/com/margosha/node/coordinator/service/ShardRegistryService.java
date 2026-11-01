package com.margosha.node.coordinator.service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ShardRegistration;
import com.margosha.node.coordinator.exception.ShardUnavailableException;
import com.margosha.node.storage.RecordStorage;
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
	private final Function<ShardRegistration, RecordStorage> connect;
	private final Map<String, RecordStorage> shardStorages = new ConcurrentHashMap<>();
	private final Map<String, String> shardAddresses = new ConcurrentHashMap<>();

	public synchronized ShardRegistration register(ShardRegistration shard) {
		if (shard.address().equals(shardAddresses.get(shard.shardId()))) {
			log.debug("Shard {} at {} is still registered", shard.shardId(), shard.address());
			return shard;
		}
		shardStorages.put(shard.shardId(), connect.apply(shard));
		shardAddresses.put(shard.shardId(), shard.address());
		boolean added = ring.addShard(shard.shardId());
		log.info("{} shard {} at {}", added ? "Registered" : "Updated", shard.shardId(), shard.address());
		return shard;
	}

	public String shardIdFor(PrimaryKey key) {
		return ring.shardFor(RoutingKeys.of(key)).orElseThrow(ShardUnavailableException::noShardRegistered);
	}

	public RecordStorage ownerOf(PrimaryKey key) {
		return shardStorages.get(shardIdFor(key));
	}

	public Set<String> shards() {
		return ring.shards();
	}
}
