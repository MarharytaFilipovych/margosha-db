package com.margosha.node.coordinator.service;

import static com.margosha.node.data.TestData.SHARD_A;
import static com.margosha.node.data.TestData.SHARD_B;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.shard;
import static com.margosha.node.data.TestData.userKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ShardRegistration;
import com.margosha.node.coordinator.exception.ShardUnavailableException;
import com.margosha.node.common.storage.InMemoryRecordStorage;
import com.margosha.node.common.storage.RecordStorage;
import com.margosha.ring.RingSettings;
import org.junit.jupiter.api.Test;

class ShardRegistryServiceTest {

	private static final ShardRegistration SHARD_A_1 = shard(SHARD_A, 8081);
	private static final ShardRegistration SHARD_A_2 = shard(SHARD_A, 9091);
	private static final ShardRegistration SHARD_B_1 = shard(SHARD_B, 8082);

	private final List<ShardRegistration> connected = new ArrayList<>();
	private final Map<ShardRegistration, RecordStorage> shardStorages = new HashMap<>();
	private final ShardRegistryService registry = new ShardRegistryService(RingSettings.load().newRing(), shard -> {
		connected.add(shard);
		return shardStorages.computeIfAbsent(shard, s -> new InMemoryRecordStorage());
	});

	@Test
	void registeredShardOwnsTheRing() {
		// Act
		ShardRegistration registered = registry.register(SHARD_A_1);

		// Assert
		assertThat(registered).isEqualTo(SHARD_A_1);
		assertThat(registry.shards()).containsExactly(SHARD_A);
		assertThat(registry.shardIdFor(USER_42)).isEqualTo(SHARD_A);
		assertThat(registry.ownerOf(USER_42)).isSameAs(shardStorages.get(SHARD_A_1));
	}

	@Test
	void keysAreSplitBetweenTwoShards() {
		// Arrange
		registry.register(SHARD_A_1);
		registry.register(SHARD_B_1);

		// Act
		Map<String, List<PrimaryKey>> keysByShard = userKeys(100).stream()
				.collect(Collectors.groupingBy(registry::shardIdFor));

		// Assert
		assertThat(keysByShard.keySet()).containsExactlyInAnyOrder(SHARD_A, SHARD_B);
		keysByShard.get(SHARD_B).forEach(key ->
				assertThat(registry.ownerOf(key)).isSameAs(shardStorages.get(SHARD_B_1)));
	}

	@Test
	void sameKeyAlwaysGoesToTheSameShard() {
		// Arrange
		registry.register(SHARD_A_1);
		registry.register(SHARD_B_1);

		// Act
		Set<String> owners = Stream.generate(() -> registry.shardIdFor(USER_42)).limit(20).collect(Collectors.toSet());

		// Assert
		assertThat(owners).hasSize(1);
	}

	@Test
	void registeringKnownShardAgainReplacesItsAddress() {
		// Arrange
		registry.register(SHARD_A_1);

		// Act
		registry.register(SHARD_A_2);

		// Assert
		assertThat(registry.shards()).containsExactly(SHARD_A);
		assertThat(connected).containsExactly(SHARD_A_1, SHARD_A_2);
		assertThat(registry.ownerOf(USER_42)).isSameAs(shardStorages.get(SHARD_A_2));
	}

	@Test
	void registeringKnownShardAgainAtTheSameAddressKeepsItsConnection() {
		// Arrange
		registry.register(SHARD_A_1);
		RecordStorage before = registry.ownerOf(USER_42);

		// Act
		registry.register(SHARD_A_1);
		registry.register(SHARD_A_1);

		// Assert
		assertThat(registry.shards()).containsExactly(SHARD_A);
		assertThat(connected).containsExactly(SHARD_A_1);
		assertThat(registry.ownerOf(USER_42)).isSameAs(before);
	}

	@Test
	void noRegisteredShardIsShardUnavailable() {
		// Act & Assert
		assertThatThrownBy(() -> registry.ownerOf(USER_42))
				.isInstanceOfSatisfying(ShardUnavailableException.class, e -> {
					assertThat(e.getCode()).isEqualTo(ErrorCode.SHARD_UNAVAILABLE);
					assertThat(e.getStatus().value()).isEqualTo(503);
				})
				.hasMessage("No shard is registered");
	}

	// Invalid registrations are rejected by @Valid in the controller: ShardRegistrationControllerTest.
}
