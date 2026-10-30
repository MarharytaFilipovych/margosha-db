package com.margosha.node.coordinator.service;

import static org.springframework.util.StringUtils.hasText;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ShardRegistration;
import com.margosha.node.coordinator.exception.ShardUnavailableException;
import com.margosha.node.exception.ValidationException;
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
		validate(shard);
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

	private static void validate(ShardRegistration shard) {
		if (shard == null) {
			throw new ValidationException("Shard registration is required");
		}
		if (!hasText(shard.shardId())) {
			throw new ValidationException("Shard ID must not be empty");
		}
		if (!hasText(shard.address())) {
			throw new ValidationException("Address of shard " + shard.shardId() + " must not be empty");
		}
		if (!isHttpUrl(shard.address())) {
			throw new ValidationException("Address of shard " + shard.shardId() + " must be an http(s) URL with a host");
		}
	}

	private static boolean isHttpUrl(String address) {
		try {
			URI uri = URI.create(address);
			return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && hasText(uri.getHost());
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
