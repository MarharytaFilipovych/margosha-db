package com.margosha.node.shard.replication;

import static com.margosha.node.data.InProcessNode.follower;
import static com.margosha.node.data.InProcessNode.leader;
import static com.margosha.node.data.TestData.ANN_ATTRIBUTES;
import static com.margosha.node.data.TestData.BOB_ATTRIBUTES;
import static com.margosha.node.data.TestData.SHARD_A;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.USER_7;
import static com.margosha.node.data.TestData.apiError;
import static com.margosha.node.data.TestData.eventually;
import static com.margosha.node.data.TestData.freePort;
import static com.margosha.node.data.TestData.localAddress;
import static com.margosha.node.data.TestData.userAttributes;
import static com.margosha.node.data.TestData.userKeys;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.margosha.api.exception.ApiException;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ReplicaStatus;
import com.margosha.node.data.InProcessNode;
import com.margosha.node.shard.replication.model.LogEntry;
import com.margosha.node.shard.replication.model.VersionedRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplicaSetTest {

	private static final String FOLLOWER_2 = SHARD_A + "-2";
	private static final String FOLLOWER_3 = SHARD_A + "-3";
	private static final Duration CATCH_UP = Duration.ofSeconds(10);

	@TempDir
	Path leaderDir;

	@TempDir
	Path follower2Dir;

	@TempDir
	Path follower3Dir;

	@Test
	void confirmedWriteIsStoredByTheLeaderAndAtLeastOneFollower() {
		// Arrange
		try (InProcessNode leader = leader(SHARD_A, leaderDir);
				InProcessNode follower2 = follower(SHARD_A, FOLLOWER_2, follower2Dir, leader);
				InProcessNode follower3 = follower(SHARD_A, FOLLOWER_3, follower3Dir, leader)) {

			// Act
			leader.shardClient().createRecord(USERS, USER_42, ANN_ATTRIBUTES);

			// Assert
			long version = leader.replica().lastVersion();
			long followersWithTheWrite = Stream.of(follower2, follower3)
					.filter(follower -> follower.replica().lastVersion() >= version)
					.count();
			assertThat(version).isEqualTo(1);
			assertThat(followersWithTheWrite).isGreaterThanOrEqualTo(1);
		}
	}

	@Test
	void writeSucceedsWithOneFollowerDown() {
		// Arrange
		try (InProcessNode leader = leader(SHARD_A, leaderDir);
				InProcessNode follower2 = follower(SHARD_A, FOLLOWER_2, follower2Dir, leader)) {

			// Act
			leader.shardClient().createRecord(USERS, USER_42, ANN_ATTRIBUTES);
			leader.shardClient().updateRecord(USERS, USER_42, BOB_ATTRIBUTES);

			// Assert
			assertThat(follower2.replica().readVersioned(USERS, USER_42))
					.contains(new VersionedRecord(2, BOB_ATTRIBUTES));
		}
	}

	@Test
	void writeWithBothFollowersDownFailsAfterTheTimeoutAndStaysInTheLeadersLog() {
		// Arrange
		try (InProcessNode leader = leader(SHARD_A, leaderDir)) {
			long start = System.nanoTime();

			// Act
			ApiException error = apiError(() -> leader.shardClient().createRecord(USERS, USER_42, ANN_ATTRIBUTES));

			// Assert
			Duration waited = Duration.ofNanos(System.nanoTime() - start);
			assertThat(error.isUnavailable()).isTrue();
			assertThat(error.code()).isEqualTo(ErrorCode.SHARD_UNAVAILABLE);
			assertThat(error.error().message()).contains("outcome is unknown");
			assertThat(waited).isGreaterThanOrEqualTo(InProcessNode.ACK_TIMEOUT);
			assertThat(leader.replica().lastVersion()).isEqualTo(1);
			assertThat(leader.replica().readVersioned(USERS, USER_42)).contains(new VersionedRecord(1, ANN_ATTRIBUTES));
		}
	}

	@Test
	void writeTheFollowersMissedReachesThemWhenOneComesBack() {
		// Arrange
		try (InProcessNode leader = leader(SHARD_A, leaderDir)) {
			ApiException unconfirmed = apiError(() -> leader.shardClient().createRecord(USERS, USER_42, ANN_ATTRIBUTES));
			assertThat(unconfirmed.code()).isEqualTo(ErrorCode.SHARD_UNAVAILABLE);

			// Act
			try (InProcessNode follower2 = follower(SHARD_A, FOLLOWER_2, follower2Dir, leader)) {

				// Assert
				eventually(CATCH_UP, () -> assertThat(follower2.replica().readVersioned(USERS, USER_42))
						.contains(new VersionedRecord(1, ANN_ATTRIBUTES)));
			}
		}
	}

	@Test
	void followersReceiveTheEntriesInLogOrderAndTheLeaderRecordsTheirPositions() {
		// Arrange
		try (InProcessNode leader = leader(SHARD_A, leaderDir);
				InProcessNode follower2 = follower(SHARD_A, FOLLOWER_2, follower2Dir, leader);
				InProcessNode follower3 = follower(SHARD_A, FOLLOWER_3, follower3Dir, leader)) {

			// Act
			for (PrimaryKey key : userKeys(20)) {
				leader.shardClient().createRecord(USERS, key, userAttributes(key));
			}
			leader.shardClient().updateRecord(USERS, userKeys(1).getFirst(), BOB_ATTRIBUTES);
			leader.shardClient().deleteRecord(USERS, userKeys(2).getLast());

			// Assert
			List<LogEntry> leaderLog = leader.replica().entriesAfter(0, 1000);
			assertThat(leaderLog).hasSize(22);
			eventually(CATCH_UP, () -> {
				assertThat(follower2.replica().entriesAfter(0, 1000)).isEqualTo(leaderLog);
				assertThat(follower3.replica().entriesAfter(0, 1000)).isEqualTo(leaderLog);
				assertThat(leader.replicationLeader().followerPositions()).isEqualTo(Map.of(
						FOLLOWER_2, follower2.replica().lastVersion(),
						FOLLOWER_3, follower3.replica().lastVersion()));
			});
			assertThat(leader.replicationLeader().followerPositions().values()).containsOnly(22L);
		}
	}

	@Test
	void bothFollowersReachTheLeadersLastEntryAndHoldTheSameRecords() {
		// Arrange
		List<PrimaryKey> keys = userKeys(30);
		try (InProcessNode leader = leader(SHARD_A, leaderDir);
				InProcessNode follower2 = follower(SHARD_A, FOLLOWER_2, follower2Dir, leader);
				InProcessNode follower3 = follower(SHARD_A, FOLLOWER_3, follower3Dir, leader)) {

			// Act
			for (PrimaryKey key : keys) {
				leader.shardClient().createRecord(USERS, key, userAttributes(key));
			}
			leader.shardClient().deleteRecord(USERS, keys.getFirst());

			// Assert
			long last = leader.replica().lastVersion();
			eventually(CATCH_UP, () -> {
				assertThat(follower2.replica().lastVersion()).isEqualTo(last);
				assertThat(follower3.replica().lastVersion()).isEqualTo(last);
			});
			for (PrimaryKey key : keys) {
				var onLeader = leader.replica().readVersioned(USERS, key);
				assertThat(follower2.replica().readVersioned(USERS, key)).isEqualTo(onLeader);
				assertThat(follower3.replica().readVersioned(USERS, key)).isEqualTo(onLeader);
			}
			ReplicaStatus status = follower2.shardClient().status();
			assertThat(status.role()).isEqualTo("follower");
			assertThat(status.lastVersion()).isEqualTo(last);
			assertThat(status.entriesReceived()).isEqualTo(last);
		}
	}

	@Test
	void followerRefusesADirectWriteAndTheLogFetch() {
		// Arrange
		try (InProcessNode leader = leader(SHARD_A, leaderDir);
				InProcessNode follower2 = follower(SHARD_A, FOLLOWER_2, follower2Dir, leader)) {

			// Act
			ApiException write = apiError(() -> follower2.shardClient().createRecord(USERS, USER_7, ANN_ATTRIBUTES));
			ApiException fetch = apiError(() -> follower2.shardClient().fetchLog(0, FOLLOWER_3, 10));

			// Assert
			assertThat(write.status()).isEqualTo(421);
			assertThat(write.code()).isEqualTo(ErrorCode.NOT_LEADER);
			assertThat(fetch.status()).isEqualTo(421);
			assertThat(fetch.code()).isEqualTo(ErrorCode.NOT_LEADER);
			assertThat(follower2.replica().readVersioned(USERS, USER_7)).isEmpty();
			assertThat(follower2.replica().lastVersion()).isZero();
		}
	}

	@Test
	void followerKeepsRetryingUntilItsLeaderIsUpAndThenCatchesUp() {
		// Arrange
		int leaderPort = freePort();
		try (InProcessNode follower2 = follower(SHARD_A, FOLLOWER_2, follower2Dir, localAddress(leaderPort))) {
			eventually(CATCH_UP, () -> assertThat(follower2.followerReplicator().isRunning()).isTrue());

			// Act
			try (InProcessNode leader = leader(SHARD_A, leaderDir, leaderPort)) {
				leader.shardClient().createRecord(USERS, USER_42, ANN_ATTRIBUTES);

				// Assert
				assertThat(follower2.replica().readVersioned(USERS, USER_42))
						.contains(new VersionedRecord(1, ANN_ATTRIBUTES));
			}
		}
	}

	@Test
	void restartedFollowerReceivesOnlyTheEntriesItMissed() {
		// Arrange
		List<PrimaryKey> missed = userKeys(100);
		try (InProcessNode leader = leader(SHARD_A, leaderDir);
				InProcessNode follower2 = follower(SHARD_A, FOLLOWER_2, follower2Dir, leader)) {
			leader.shardClient().createRecord(USERS, USER_42, ANN_ATTRIBUTES);
			InProcessNode follower3 = follower(SHARD_A, FOLLOWER_3, follower3Dir, leader);
			eventually(CATCH_UP, () -> assertThat(follower3.replica().lastVersion()).isEqualTo(1));
			follower3.close();
			for (PrimaryKey key : missed) {
				leader.shardClient().createRecord(USERS, key, userAttributes(key));
			}

			// Act
			try (InProcessNode restarted = follower(SHARD_A, FOLLOWER_3, follower3Dir, leader)) {

				// Assert
				eventually(CATCH_UP, () -> assertThat(restarted.replica().lastVersion()).isEqualTo(101));
				assertThat(restarted.followerReplicator().entriesReceived()).isEqualTo(100);
				assertThat(restarted.replica().readVersioned(USERS, USER_42))
						.isEqualTo(leader.replica().readVersioned(USERS, USER_42));
				for (PrimaryKey key : missed) {
					assertThat(restarted.replica().readVersioned(USERS, key))
							.isEqualTo(leader.replica().readVersioned(USERS, key));
				}
				assertThat(follower2.replica().lastVersion()).isEqualTo(101);
			}
		}
	}
}
