package com.margosha.node.shard.replication;

import static com.margosha.node.data.TestData.ANN_ATTRIBUTES;
import static com.margosha.node.data.TestData.BOB_ATTRIBUTES;
import static com.margosha.node.data.TestData.SHARD_A;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.testDataDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.time.Duration;

import com.margosha.node.common.metrics.ReplicationLagMetrics;
import com.margosha.node.data.InProcessNode;
import org.junit.jupiter.api.Test;

class ReplicationLagTest {

	private static final Duration WAIT = Duration.ofSeconds(15);
	private static final String FOLLOWER_2 = SHARD_A + "-2";
	private static final String FOLLOWER_3 = SHARD_A + "-3";

	@Test
	void stoppedFollowersLagGrowsAndReturnsToZeroAfterItCatchesUp() throws InterruptedException {
		// Arrange
		Path follower3Dir = testDataDir(FOLLOWER_3);
		try (InProcessNode leader = InProcessNode.leader(SHARD_A, testDataDir(SHARD_A + "-1"));
				InProcessNode follower2 = InProcessNode.follower(SHARD_A, FOLLOWER_2, testDataDir(FOLLOWER_2), leader)) {
			InProcessNode follower3 = InProcessNode.follower(SHARD_A, FOLLOWER_3, follower3Dir, leader);
			try {
				leader.storage().create(USERS, USER_42, ANN_ATTRIBUTES);
				await().atMost(WAIT).until(() -> lag(leader, FOLLOWER_2) == 0 && lag(leader, FOLLOWER_3) == 0);
				follower3.close();

				// Act
				leader.storage().update(USERS, USER_42, BOB_ATTRIBUTES);
				Thread.sleep(1_500);
				double first = lag(leader, FOLLOWER_3);
				Thread.sleep(1_000);
				double second = lag(leader, FOLLOWER_3);

				// Assert
				assertThat(first).isGreaterThanOrEqualTo(1.4);
				assertThat(second).isGreaterThanOrEqualTo(first + 0.9);
				assertThat(lag(leader, FOLLOWER_2)).as("follower 2 has the write").isZero();

				// Act
				follower3 = InProcessNode.follower(SHARD_A, FOLLOWER_3, follower3Dir, leader);

				// Assert
				await().atMost(WAIT).until(() -> lag(leader, FOLLOWER_3) == 0);
			} finally {
				follower3.close();
			}
		}
	}

	private static double lag(InProcessNode leader, String followerId) {
		return leader.meterRegistry().get(ReplicationLagMetrics.LAG).tag("follower", followerId).gauge().value();
	}
}
