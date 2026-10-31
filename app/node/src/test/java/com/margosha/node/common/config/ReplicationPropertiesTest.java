package com.margosha.node.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;

import com.margosha.node.NodeApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ConfigurableApplicationContext;

class ReplicationPropertiesTest {

	@Test
	void quorumsThatOverlapAreAccepted() {
		// Arrange
		int factor = 3;

		// Act
		ReplicationProperties properties = quorum(factor, 2, 2);

		// Assert
		assertThat(properties.followerConfirmations()).isEqualTo(1);
	}

	@ParameterizedTest
	@CsvSource({
			"3, 1, 2, R + W must be greater than N",
			"3, 1, 1, R + W must be greater than N",
			"3, 0, 3, (W) must be between 1 and N = 3",
			"3, 4, 2, (W) must be between 1 and N = 3",
			"3, 2, 0, (R) must be between 1 and N = 3",
			"3, 2, 4, (R) must be between 1 and N = 3",
			"0, 1, 1, (N) must be 1 or higher"})
	void settingsThatBreakTheRulesAreRefused(int factor, int writeQuorum, int readQuorum, String rule) {
		// Arrange
		String expected = rule;

		// Act
		Throwable thrown = catchThrowable(() -> quorum(factor, writeQuorum, readQuorum));

		// Assert
		assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(expected);
	}

	@Test
	void coordinatorWithQuorumsThatDontOverlapDoesNotStart() {
		// Arrange
		String[] args = {"coordinator", "--mode=single", "--server.port=0",
				"--margosha.replication.factor=3", "--margosha.replication.write-quorum=1",
				"--margosha.replication.read-quorum=2"};

		// Act
		Throwable thrown = catchThrowable(() -> NodeApplication.run(args).close());

		// Assert
		assertThat(thrown).isNotNull();
		assertThat(thrown).rootCause().isInstanceOf(IllegalArgumentException.class)
				.hasMessage("R + W must be greater than N, so every read meets the latest write; got R = 2, W = 1, N = 3");
	}

	@Test
	void replicaWithQuorumsThatDontOverlapDoesNotStart() {
		// Arrange
		String[] args = {"shard", "--server.port=0", "--margosha.shard.id=shard-a",
				"--margosha.data-dir=target/test-data/refused-replica", "--margosha.replication.write-quorum=1"};

		// Act
		Throwable thrown = catchThrowable(() -> NodeApplication.run(args).close());

		// Assert
		assertThat(thrown).rootCause().isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("R + W must be greater than N");
	}

	@Test
	void nodeWithoutQuorumSettingsUsesTheConfirmedDefaults() {
		// Arrange
		String[] args = {"coordinator", "--mode=single", "--server.port=0"};

		// Act
		ReplicationProperties properties;
		try (ConfigurableApplicationContext context = NodeApplication.run(args)) {
			properties = context.getBean(ReplicationProperties.class);
		}

		// Assert
		assertThat(properties.factor()).isEqualTo(3);
		assertThat(properties.writeQuorum()).isEqualTo(2);
		assertThat(properties.readQuorum()).isEqualTo(2);
		assertThat(properties.ackTimeout()).isEqualTo(Duration.ofSeconds(1));
		assertThat(properties.longPoll()).isEqualTo(Duration.ofSeconds(5));
	}

	@Test
	void unknownReplicaRoleStopsTheReplica() {
		// Arrange
		String[] args = {"shard", "--server.port=0", "--margosha.shard.id=shard-a",
				"--margosha.data-dir=target/test-data/unknown-role", "--margosha.replica.role=boss"};

		// Act
		Throwable thrown = catchThrowable(() -> NodeApplication.run(args).close());

		// Assert
		assertThat(thrown).isNotNull();
		assertThat(thrown).hasStackTraceContaining("margosha.replica.role");
	}

	private static ReplicationProperties quorum(int factor, int writeQuorum, int readQuorum) {
		return new ReplicationProperties(factor, writeQuorum, readQuorum, Duration.ofSeconds(1), Duration.ofSeconds(5),
				500, Duration.ofMillis(500), Duration.ofSeconds(5));
	}
}
