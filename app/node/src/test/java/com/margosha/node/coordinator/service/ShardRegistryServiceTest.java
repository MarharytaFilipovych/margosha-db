package com.margosha.node.coordinator.service;

import static com.margosha.node.data.TestData.SHARD_A;
import static com.margosha.node.data.TestData.SHARD_B;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.follower;
import static com.margosha.node.data.TestData.shard;
import static com.margosha.node.data.TestData.userKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ReplicaRole;
import com.margosha.api.model.ShardRegistration;
import com.margosha.node.common.exception.ValidationException;
import com.margosha.node.coordinator.exception.ShardUnavailableException;
import com.margosha.node.coordinator.storage.ReplicaClient;
import com.margosha.node.data.FakeReplica;
import com.margosha.ring.RingSettings;
import org.junit.jupiter.api.Test;

class ShardRegistryServiceTest {

	private static final ShardRegistration LEADER_A = shard(SHARD_A, 8081);
	private static final ShardRegistration FOLLOWER_A_2 = follower(SHARD_A, SHARD_A + "-2", 8082);
	private static final ShardRegistration FOLLOWER_A_3 = follower(SHARD_A, SHARD_A + "-3", 8083);
	private static final ShardRegistration LEADER_B = shard(SHARD_B, 9091);

	private final List<ShardRegistration> connected = new ArrayList<>();
	private final ShardRegistryService registry = new ShardRegistryService(RingSettings.load().newRing(), replica -> {
		connected.add(replica);
		return new FakeReplica(replica);
	});

	@Test
	void firstReplicaPutsItsShardOnTheRing() {
		// Arrange
		ShardRegistration replica = FOLLOWER_A_2;

		// Act
		ShardRegistration registered = registry.register(replica);

		// Assert
		assertThat(registered).isEqualTo(replica);
		assertThat(registry.shards()).containsExactly(SHARD_A);
		assertThat(registry.shardIdFor(USER_42)).isEqualTo(SHARD_A);
	}

	@Test
	void replicaSetHoldsTheLeaderAndTheFollowers() {
		// Arrange
		Stream.of(FOLLOWER_A_3, LEADER_A, FOLLOWER_A_2).forEach(registry::register);

		// Act
		ReplicaSet replicaSet = registry.replicaSetFor(USER_42);

		// Assert
		assertThat(replicaSet.getShardId()).isEqualTo(SHARD_A);
		assertThat(replicaSet.leader().registration()).isEqualTo(LEADER_A);
		assertThat(registrations(replicaSet.replicas())).containsExactly(LEADER_A, FOLLOWER_A_2, FOLLOWER_A_3);
		assertThat(registry.shards()).containsExactly(SHARD_A);
	}

	@Test
	void shardWithoutARegisteredLeaderHasNoWriter() {
		// Arrange
		registry.register(FOLLOWER_A_2);

		// Act
		Throwable thrown = catchThrowable(() -> registry.replicaSetFor(USER_42).leader());

		// Assert
		assertThat(thrown).isInstanceOf(ShardUnavailableException.class)
				.hasMessage("Shard shard-a has no registered leader");
	}

	@Test
	void secondLeaderForAShardIsRejectedAndTheFirstIsKept() {
		// Arrange
		registry.register(LEADER_A);
		ShardRegistration secondLeader = new ShardRegistration(SHARD_A, SHARD_A + "-9", ReplicaRole.LEADER,
				"http://localhost:8089");

		// Act
		Throwable thrown = catchThrowable(() -> registry.register(secondLeader));

		// Assert
		assertThat(thrown).isInstanceOf(ValidationException.class)
				.hasMessage("Shard shard-a already has leader shard-a-1; replica shard-a-9 cannot register as a second leader");
		assertThat(registry.replicaSetFor(USER_42).leader().registration()).isEqualTo(LEADER_A);
		assertThat(connected).containsExactly(LEADER_A);
	}

	@Test
	void replicaBeyondNIsRejectedSoQuorumsStillOverlap() {
		// Arrange
		ShardRegistryService threeReplicas = new ShardRegistryService(RingSettings.load().newRing(), FakeReplica::new,
				ReplicaSet.DEFAULT_SKIP_AFTER_FAILURE, 3);
		Stream.of(LEADER_A, FOLLOWER_A_2, FOLLOWER_A_3).forEach(threeReplicas::register);
		ShardRegistration fourth = follower(SHARD_A, SHARD_A + "-4", 8084);

		// Act
		Throwable thrown = catchThrowable(() -> threeReplicas.register(fourth));

		// Assert
		assertThat(thrown).isInstanceOf(ValidationException.class)
				.hasMessage("Shard shard-a already has 3 replicas [shard-a-1, shard-a-2, shard-a-3]; replica shard-a-4"
						+ " cannot register as another one");
		assertThat(registrations(threeReplicas.replicaSetFor(USER_42).replicas()))
				.containsExactly(LEADER_A, FOLLOWER_A_2, FOLLOWER_A_3);
	}

	@Test
	void registeredReplicaCanStillMoveWhenTheShardIsFull() {
		// Arrange
		ShardRegistryService threeReplicas = new ShardRegistryService(RingSettings.load().newRing(), FakeReplica::new,
				ReplicaSet.DEFAULT_SKIP_AFTER_FAILURE, 3);
		Stream.of(LEADER_A, FOLLOWER_A_2, FOLLOWER_A_3).forEach(threeReplicas::register);
		ShardRegistration moved = follower(SHARD_A, SHARD_A + "-3", 8093);

		// Act
		threeReplicas.register(moved);

		// Assert
		assertThat(registrations(threeReplicas.replicaSetFor(USER_42).replicas()))
				.containsExactly(LEADER_A, FOLLOWER_A_2, moved);
	}

	@Test
	void registeringAgainWithTheSameDetailsChangesNothing() {
		// Arrange
		registry.register(LEADER_A);
		ReplicaClient before = registry.replicaSetFor(USER_42).leader();

		// Act
		registry.register(LEADER_A);
		registry.register(LEADER_A);

		// Assert
		assertThat(connected).containsExactly(LEADER_A);
		assertThat(registry.replicaSetFor(USER_42).leader()).isSameAs(before);
	}

	@Test
	void replicaRegisteringWithANewAddressReplacesItsEntry() {
		// Arrange
		registry.register(LEADER_A);
		ShardRegistration moved = new ShardRegistration(SHARD_A, LEADER_A.replicaId(), ReplicaRole.LEADER,
				"http://localhost:7071");

		// Act
		registry.register(moved);

		// Assert
		assertThat(connected).containsExactly(LEADER_A, moved);
		assertThat(registrations(registry.replicaSetFor(USER_42).replicas())).containsExactly(moved);
		assertThat(registry.replicaSetFor(USER_42).leader().registration()).isEqualTo(moved);
	}

	@Test
	void keysAreSplitBetweenTwoShards() {
		// Arrange
		registry.register(LEADER_A);
		registry.register(LEADER_B);

		// Act
		Map<String, List<PrimaryKey>> keysByShard = userKeys(100).stream()
				.collect(Collectors.groupingBy(registry::shardIdFor));

		// Assert
		assertThat(keysByShard.keySet()).containsExactlyInAnyOrder(SHARD_A, SHARD_B);
		keysByShard.get(SHARD_B).forEach(key ->
				assertThat(registry.replicaSetFor(key).leader().registration()).isEqualTo(LEADER_B));
	}

	@Test
	void sameKeyAlwaysGoesToTheSameShard() {
		// Arrange
		registry.register(LEADER_A);
		registry.register(LEADER_B);

		// Act
		Set<String> owners = Stream.generate(() -> registry.shardIdFor(USER_42)).limit(20).collect(Collectors.toSet());

		// Assert
		assertThat(owners).hasSize(1);
	}

	@Test
	void readsStartOneReplicaFurtherEachTime() {
		// Arrange
		Stream.of(LEADER_A, FOLLOWER_A_2, FOLLOWER_A_3).forEach(registry::register);
		ReplicaSet replicaSet = registry.replicaSetFor(USER_42);

		// Act
		List<List<ShardRegistration>> orders = Stream.generate(() -> registrations(replicaSet.readOrder(2)))
				.limit(4).toList();

		// Assert
		assertThat(orders).containsExactly(
				List.of(LEADER_A, FOLLOWER_A_2, FOLLOWER_A_3),
				List.of(FOLLOWER_A_2, FOLLOWER_A_3, LEADER_A),
				List.of(FOLLOWER_A_3, LEADER_A, FOLLOWER_A_2),
				List.of(LEADER_A, FOLLOWER_A_2, FOLLOWER_A_3));
	}

	@Test
	void replicaThatFailedGoesToTheEndOfTheReadOrder() {
		// Arrange
		Stream.of(LEADER_A, FOLLOWER_A_2, FOLLOWER_A_3).forEach(registry::register);
		ReplicaSet replicaSet = registry.replicaSetFor(USER_42);
		replicaSet.failed(replicaSet.replicas().get(1));

		// Act
		List<List<ShardRegistration>> orders = Stream.generate(() -> registrations(replicaSet.readOrder(2)))
				.limit(3).toList();

		// Assert
		assertThat(orders).containsExactly(
				List.of(LEADER_A, FOLLOWER_A_3, FOLLOWER_A_2),
				List.of(FOLLOWER_A_3, LEADER_A, FOLLOWER_A_2),
				List.of(FOLLOWER_A_3, LEADER_A, FOLLOWER_A_2));
	}

	@Test
	void replicaThatAnswersAgainGetsItsPlaceBack() {
		// Arrange
		Stream.of(LEADER_A, FOLLOWER_A_2, FOLLOWER_A_3).forEach(registry::register);
		ReplicaSet replicaSet = registry.replicaSetFor(USER_42);
		replicaSet.failed(replicaSet.replicas().get(1));

		// Act
		replicaSet.answered(replicaSet.replicas().get(1));

		// Assert
		assertThat(registrations(replicaSet.readOrder(2))).containsExactly(LEADER_A, FOLLOWER_A_2, FOLLOWER_A_3);
	}

	@Test
	void noRegisteredShardIsShardUnavailable() {
		// Arrange
		ShardRegistryService empty = registry;

		// Act
		Throwable thrown = catchThrowable(() -> empty.replicaSetFor(USER_42));

		// Assert
		assertThat(thrown).isInstanceOfSatisfying(ShardUnavailableException.class, e -> {
					assertThat(e.getCode()).isEqualTo(ErrorCode.SHARD_UNAVAILABLE);
					assertThat(e.getStatus().value()).isEqualTo(503);
				})
				.hasMessage("No shard is registered");
	}

	private static List<ShardRegistration> registrations(List<ReplicaClient> replicas) {
		return replicas.stream().map(ReplicaClient::registration).toList();
	}
}
