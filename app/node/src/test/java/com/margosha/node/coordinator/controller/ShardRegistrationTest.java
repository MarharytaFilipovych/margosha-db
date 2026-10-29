package com.margosha.node.coordinator.controller;

import static com.margosha.node.data.TestData.ANN;
import static com.margosha.node.data.TestData.ANN_ATTRIBUTES;
import static com.margosha.node.data.TestData.SHARD_A;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.apiError;
import static com.margosha.node.data.TestData.freePort;
import static com.margosha.node.data.TestData.localAddress;
import static com.margosha.node.data.TestData.unique;
import static com.margosha.node.data.TestData.usersTable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import com.margosha.api.exception.ApiException;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.ShardRegistration;
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

	@Test
	void invalidRegistrationIsRejected() {
		// Arrange
		try (InProcessNode coordinator = InProcessNode.shardedCoordinator(0)) {

			// Act
			ApiException error = apiError(() -> coordinator.client().registerShard(new ShardRegistration(SHARD_A, "")));

			// Assert
			assertThat(error.isValidationError()).isTrue();
			assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
			assertThat(coordinator.shardRegistry().shards()).isEmpty();
		}
	}
}
