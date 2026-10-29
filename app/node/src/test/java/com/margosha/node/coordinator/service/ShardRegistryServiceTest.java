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
import com.margosha.node.exception.ValidationException;
import com.margosha.node.storage.InMemoryRecordStorage;
import com.margosha.node.storage.RecordStorage;
import com.margosha.ring.RingSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
	void noRegisteredShardIsShardUnavailable() {
		// Act & Assert
		assertThatThrownBy(() -> registry.ownerOf(USER_42))
				.isInstanceOfSatisfying(ShardUnavailableException.class, e -> {
					assertThat(e.getCode()).isEqualTo(ErrorCode.SHARD_UNAVAILABLE);
					assertThat(e.getStatus().value()).isEqualTo(503);
				})
				.hasMessage("No shard is registered");
	}

	static Stream<Arguments> invalidRegistrations() {
		return Stream.of(
				Arguments.of(null, "Shard registration is required"),
				Arguments.of(new ShardRegistration(null, "http://localhost:8081"), "Shard ID must not be empty"),
				Arguments.of(new ShardRegistration(" ", "http://localhost:8081"), "Shard ID must not be empty"),
				Arguments.of(new ShardRegistration(SHARD_A, ""), "Address of shard shard-a must not be empty"),
				Arguments.of(new ShardRegistration(SHARD_A, "localhost:8081"),
						"Address of shard shard-a must be an http(s) URL with a host"),
				Arguments.of(new ShardRegistration(SHARD_A, "ftp://localhost:8081"),
						"Address of shard shard-a must be an http(s) URL with a host"),
				Arguments.of(new ShardRegistration(SHARD_A, "http://bad host"),
						"Address of shard shard-a must be an http(s) URL with a host"));
	}

	@ParameterizedTest
	@MethodSource("invalidRegistrations")
	void invalidRegistrationIsRejectedAndNotAdded(ShardRegistration shard, String message) {
		// Act & Assert
		assertThatThrownBy(() -> registry.register(shard))
				.isInstanceOf(ValidationException.class)
				.hasMessage(message);

		// Assert
		assertThat(connected).isEmpty();
		assertThat(registry.shards()).isEmpty();
	}
}
