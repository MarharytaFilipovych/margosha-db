package com.margosha.node.coordinator.controller;

import static com.margosha.node.data.TestData.ANN;
import static com.margosha.node.data.TestData.ANN_ATTRIBUTES;
import static com.margosha.node.data.TestData.SHARD_A;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.apiError;
import static com.margosha.node.data.TestData.freePort;
import static com.margosha.node.data.InProcessNode.registeringWith;
import static com.margosha.node.data.TestData.localAddress;
import static com.margosha.node.data.TestData.testDataDir;
import static com.margosha.node.data.TestData.unique;
import static com.margosha.node.data.TestData.usersTable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;

import com.margosha.api.exception.ApiException;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.ReplicaRole;
import com.margosha.api.model.ShardRegistration;
import com.margosha.node.coordinator.storage.ReplicaClient;
import com.margosha.node.data.HangingServer;
import com.margosha.node.data.InProcessNode;
import org.junit.jupiter.api.Test;

class ShardRegistrationTest {

	private static final Duration REGISTRATION_WAIT = Duration.ofSeconds(10);

	private final String users = unique(USERS);

	@Test
	void shardStartingWhileCoordinatorRunsIsRegistered() {
		// Arrange
		try (InProcessNode coordinator = InProcessNode.shardedCoordinator(0)) {

			// Act
			try (InProcessNode shard = InProcessNode.shardRegisteringWith(SHARD_A, coordinator.address())) {

				// Assert
				await().atMost(REGISTRATION_WAIT).until(() -> shard.coordinatorRegistration().isRegistered());
				assertThat(coordinator.shardRegistry().shards()).containsExactly(SHARD_A);
				coordinator.client().registerTable(usersTable(users));
				coordinator.client().createRecord(users, ANN);
				assertThat(shard.storage().read(users, USER_42)).isEqualTo(ANN_ATTRIBUTES);
			}
		}
	}

	@Test
	void shardStartingBeforeCoordinatorKeepsRetryingUntilItIsUp() {
		// Arrange
		int coordinatorPort = freePort();
		try (InProcessNode shard = InProcessNode.shardRegisteringWith(SHARD_A, localAddress(coordinatorPort))) {
			await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(2))
					.until(() -> !shard.coordinatorRegistration().isRegistered());

			// Act
			try (InProcessNode coordinator = InProcessNode.shardedCoordinator(coordinatorPort)) {

				// Assert
				await().atMost(REGISTRATION_WAIT).until(() -> shard.coordinatorRegistration().isRegistered());
				assertThat(coordinator.shardRegistry().shards()).containsExactly(SHARD_A);
				coordinator.client().registerTable(usersTable(users));
				coordinator.client().createRecord(users, ANN);
				assertThat(shard.storage().exists(users, USER_42)).isTrue();
			}
		}
	}

	@Test
	void restartedCoordinatorGetsItsShardsBackWithoutRestartingThem() {
		// Arrange
		int coordinatorPort = freePort();
		try (InProcessNode shard = InProcessNode.shardRegisteringWith(SHARD_A, localAddress(coordinatorPort))) {
			try (InProcessNode coordinator = InProcessNode.shardedCoordinator(coordinatorPort)) {
				await().atMost(REGISTRATION_WAIT).until(() -> shard.coordinatorRegistration().isRegistered());
			}

			// Act
			try (InProcessNode coordinator = InProcessNode.shardedCoordinator(coordinatorPort)) {

				// Assert
				await().atMost(REGISTRATION_WAIT).until(() -> coordinator.shardRegistry().shards().contains(SHARD_A));
				coordinator.client().registerTable(usersTable(users));
				coordinator.client().createRecord(users, ANN);
				assertThat(shard.storage().read(users, USER_42)).isEqualTo(ANN_ATTRIBUTES);
			}
		}
	}

	@Test
	void restartedCoordinatorGetsEveryReplicaOfASetBack() {
		// Arrange
		int coordinatorPort = freePort();
		String coordinatorUrl = localAddress(coordinatorPort);
		try (InProcessNode leader = InProcessNode.leader(SHARD_A, testDataDir("leader"), registeringWith(coordinatorUrl));
				InProcessNode follower = InProcessNode.follower(SHARD_A, SHARD_A + "-2", testDataDir("follower"), leader,
						registeringWith(coordinatorUrl))) {
			try (InProcessNode coordinator = InProcessNode.replicatedCoordinator(coordinatorPort, testDataDir("first"))) {
				await().atMost(REGISTRATION_WAIT).until(() -> registeredReplicas(coordinator).size() == 2);
			}

			// Act
			try (InProcessNode coordinator = InProcessNode.replicatedCoordinator(coordinatorPort, testDataDir("second"))) {

				// Assert
				await().atMost(REGISTRATION_WAIT).until(() -> registeredReplicas(coordinator).size() == 2);
				assertThat(registeredReplicas(coordinator)).containsExactly(leader.registration(), follower.registration());
				assertThat(coordinator.shardRegistry().replicaSet(SHARD_A).orElseThrow().leader().registration())
						.isEqualTo(leader.registration());
			}
		}
	}

	@Test
	void shardKeepsRetryingWhenCoordinatorDoesNotAnswer() {
		// Arrange
		try (HangingServer hangingCoordinator = HangingServer.start()) {

			// Act
			try (InProcessNode shard = InProcessNode.shardRegisteringWith(SHARD_A, hangingCoordinator.address())) {

				// Assert
				await().atMost(REGISTRATION_WAIT).until(() -> hangingCoordinator.connections() >= 2);
				assertThat(shard.coordinatorRegistration().isRegistered()).isFalse();
			}
		}
	}

	private static List<ShardRegistration> registeredReplicas(InProcessNode coordinator) {
		return coordinator.shardRegistry().replicaSet(SHARD_A)
				.map(set -> set.replicas().stream().map(ReplicaClient::registration).toList())
				.orElse(List.of());
	}

	@Test
	void invalidRegistrationIsRejected() {
		// Arrange
		try (InProcessNode coordinator = InProcessNode.shardedCoordinator(0)) {

			// Act
			ApiException error = apiError(() -> coordinator.client().registerShard(
					new ShardRegistration(SHARD_A, SHARD_A + "-1", ReplicaRole.LEADER, "")));

			// Assert
			assertThat(error.isValidationError()).isTrue();
			assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
			assertThat(coordinator.shardRegistry().shards()).isEmpty();
		}
	}
}
