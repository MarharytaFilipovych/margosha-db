package com.margosha.node.coordinator.controller;

import static com.margosha.node.data.TestData.ANN;
import static com.margosha.node.data.TestData.SHARD_A;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.apiError;
import static com.margosha.node.data.TestData.freePort;
import static com.margosha.node.data.TestData.localAddress;
import static com.margosha.node.data.TestData.testDataDir;
import static com.margosha.node.data.TestData.unique;
import static com.margosha.node.data.TestData.usersTable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.time.Duration;

import com.margosha.api.exception.ApiException;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.TableRecord;
import com.margosha.node.data.InProcessNode;
import com.margosha.node.data.InProcessReplicaSet;
import org.junit.jupiter.api.Test;

class DurableTablesTest {

	private static final Duration WAIT = Duration.ofSeconds(15);

	@Test
	void recordCanBeReadAfterACoordinatorRestartWithoutRegisteringItsTableAgain() {
		// Arrange
		int port = freePort();
		Path coordinatorDir = testDataDir("coordinator");
		String users = unique(USERS);
		try (InProcessReplicaSet shardA = InProcessReplicaSet.start(SHARD_A, localAddress(port))) {
			try (InProcessNode coordinator = InProcessNode.replicatedCoordinator(port, coordinatorDir)) {
				awaitAllRegistered(coordinator);
				coordinator.client().registerTable(usersTable(users));
				coordinator.client().createRecord(users, ANN);
			}

			// Act
			try (InProcessNode restarted = InProcessNode.replicatedCoordinator(port, coordinatorDir)) {
				awaitAllRegistered(restarted);
				TableRecord read = restarted.client().readRecord(users, USER_42);
				ApiException registerAgain = apiError(() -> restarted.client().registerTable(usersTable(users)));

				// Assert
				assertThat(read).isEqualTo(ANN);
				assertThat(registerAgain.isConflict()).isTrue();
				assertThat(registerAgain.code()).isEqualTo(ErrorCode.TABLE_ALREADY_EXISTS);
			}
		}
	}

	private static void awaitAllRegistered(InProcessNode coordinator) {
		await().atMost(WAIT).until(() -> coordinator.shardRegistry().replicaSet(SHARD_A)
				.map(set -> set.replicas().size() == 3).orElse(false));
	}
}
