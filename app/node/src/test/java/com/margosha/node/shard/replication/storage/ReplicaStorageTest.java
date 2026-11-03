package com.margosha.node.shard.replication.storage;

import static com.margosha.node.data.TestData.ANN_ATTRIBUTES;
import static com.margosha.node.data.TestData.BOB_ATTRIBUTES;
import static com.margosha.node.data.TestData.ORDERS;
import static com.margosha.node.data.TestData.ORDER_42_1;
import static com.margosha.node.data.TestData.ORDER_42_2;
import static com.margosha.node.data.TestData.TOTAL_10;
import static com.margosha.node.data.TestData.TOTAL_20;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.USER_7;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.PrimaryKey;
import com.margosha.node.common.exception.RecordAlreadyExistsException;
import com.margosha.node.common.exception.RecordNotFoundException;
import com.margosha.node.shard.replication.model.LogEntry;
import com.margosha.node.shard.replication.model.VersionedRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplicaStorageTest {

	@TempDir
	Path leaderDir;

	@TempDir
	Path followerDir;

	@Test
	void newLogHasNoIdUntilTheLeaderGivesItOne() {
		// Arrange
		try (ReplicaStorage store = ReplicaStorage.open(leaderDir)) {

			// Act
			String id = store.ensureLogId();

			// Assert
			assertThat(id).isNotBlank();
			assertThat(store.logId()).contains(id);
			assertThat(store.ensureLogId()).isEqualTo(id);
		}
	}

	@Test
	void logIdSurvivesARestart() {
		// Arrange
		String id;
		try (ReplicaStorage store = ReplicaStorage.open(leaderDir)) {
			id = store.ensureLogId();
			store.create(USERS, USER_42, ANN_ATTRIBUTES);
		}

		// Act
		try (ReplicaStorage reopened = ReplicaStorage.open(leaderDir)) {

			// Assert
			assertThat(reopened.logId()).contains(id);
		}
	}

	@Test
	void emptyLogDropsAnIdLeftBehind() throws Exception {
		// Arrange: only the log was deleted, its ID file stayed
		try (ReplicaStorage store = ReplicaStorage.open(leaderDir)) {
			store.ensureLogId();
			store.create(USERS, USER_42, ANN_ATTRIBUTES);
		}
		Files.delete(leaderDir.resolve(ReplicaStorage.LOG_FILE));

		// Act
		try (ReplicaStorage reopened = ReplicaStorage.open(leaderDir)) {

			// Assert
			assertThat(reopened.logId()).isEmpty();
			assertThat(Files.exists(leaderDir.resolve(ReplicaStorage.LOG_ID_FILE))).isFalse();
		}
	}

	@Test
	void logWrittenBeforeLogIdsExistedHasNoIdAndKeepsItsRecords() throws Exception {
		// Arrange
		try (ReplicaStorage store = ReplicaStorage.open(leaderDir)) {
			store.ensureLogId();
			store.create(USERS, USER_42, ANN_ATTRIBUTES);
		}
		Files.delete(leaderDir.resolve(ReplicaStorage.LOG_ID_FILE));

		// Act
		try (ReplicaStorage reopened = ReplicaStorage.open(leaderDir)) {

			// Assert
			assertThat(reopened.logId()).isEmpty();
			assertThat(reopened.read(USERS, USER_42)).isEqualTo(ANN_ATTRIBUTES);
		}
	}

	@Test
	void followerTakesTheLeadersIdWhileItsLogIsEmpty() {
		// Arrange
		try (ReplicaStorage leader = ReplicaStorage.open(leaderDir);
				ReplicaStorage follower = ReplicaStorage.open(followerDir)) {
			String leaderId = leader.ensureLogId();

			// Act
			follower.adoptLogId(leaderId);

			// Assert
			assertThat(follower.logId()).contains(leaderId);
		}
	}

	@Test
	void logWithEntriesCannotTakeAnotherId() {
		// Arrange
		try (ReplicaStorage follower = ReplicaStorage.open(followerDir)) {
			follower.ensureLogId();
			follower.create(USERS, USER_42, ANN_ATTRIBUTES);

			// Act
			Throwable thrown = catchThrowable(() -> follower.adoptLogId("another-log"));

			// Assert
			assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("another-log");
		}
	}

	@Test
	void createdRecordIsReadWithItsVersion() {
		// Arrange
		try (ReplicaStorage store = ReplicaStorage.open(leaderDir)) {

			// Act
			store.create(USERS, USER_42, ANN_ATTRIBUTES);

			// Assert
			assertThat(store.read(USERS, USER_42)).isEqualTo(ANN_ATTRIBUTES);
			assertThat(store.exists(USERS, USER_42)).isTrue();
			assertThat(store.readVersioned(USERS, USER_42)).contains(new VersionedRecord(1, ANN_ATTRIBUTES));
		}
	}

	@Test
	void updateGivesTheRecordAHigherVersion() {
		// Arrange
		try (ReplicaStorage store = ReplicaStorage.open(leaderDir)) {
			store.create(USERS, USER_42, ANN_ATTRIBUTES);

			// Act
			store.update(USERS, USER_42, BOB_ATTRIBUTES);

			// Assert
			assertThat(store.read(USERS, USER_42)).isEqualTo(BOB_ATTRIBUTES);
			assertThat(store.readVersioned(USERS, USER_42)).contains(new VersionedRecord(2, BOB_ATTRIBUTES));
		}
	}

	@Test
	void deletedRecordReadsAsNotFoundWithItsVersion() {
		// Arrange
		try (ReplicaStorage store = ReplicaStorage.open(leaderDir)) {
			store.create(USERS, USER_42, ANN_ATTRIBUTES);

			// Act
			store.delete(USERS, USER_42);

			// Assert
			Optional<VersionedRecord> versioned = store.readVersioned(USERS, USER_42);
			assertThat(versioned).isPresent();
			assertThat(versioned.get().deleted()).isTrue();
			assertThat(versioned.get().version()).isEqualTo(2);
			assertThat(store.exists(USERS, USER_42)).isFalse();
			assertThat(catchThrowable(() -> store.read(USERS, USER_42))).isInstanceOf(RecordNotFoundException.class);
		}
	}

	@Test
	void recordNeverWrittenHasNoVersion() {
		// Arrange
		try (ReplicaStorage store = ReplicaStorage.open(leaderDir)) {
			store.create(USERS, USER_42, ANN_ATTRIBUTES);

			// Act
			Optional<VersionedRecord> versioned = store.readVersioned(USERS, USER_7);

			// Assert
			assertThat(versioned).isEmpty();
			assertThat(store.exists(USERS, USER_7)).isFalse();
		}
	}

	@Test
	void deletedKeyCanBeCreatedAgainWithANewVersion() {
		// Arrange
		try (ReplicaStorage store = ReplicaStorage.open(leaderDir)) {
			store.create(USERS, USER_42, ANN_ATTRIBUTES);
			store.delete(USERS, USER_42);

			// Act
			store.create(USERS, USER_42, BOB_ATTRIBUTES);

			// Assert
			assertThat(store.readVersioned(USERS, USER_42)).contains(new VersionedRecord(3, BOB_ATTRIBUTES));
		}
	}

	@Test
	void writesThatFailTheirCheckAreNotLogged() {
		// Arrange
		try (ReplicaStorage store = ReplicaStorage.open(leaderDir)) {
			store.create(USERS, USER_42, ANN_ATTRIBUTES);
			store.create(USERS, USER_7, BOB_ATTRIBUTES);
			store.delete(USERS, USER_7);

			// Act
			Throwable createExisting = catchThrowable(() -> store.create(USERS, USER_42, BOB_ATTRIBUTES));
			Throwable updateMissing = catchThrowable(() -> store.update(ORDERS, ORDER_42_1, TOTAL_10));
			Throwable deleteMissing = catchThrowable(() -> store.delete(ORDERS, ORDER_42_1));
			Throwable updateDeleted = catchThrowable(() -> store.update(USERS, USER_7, ANN_ATTRIBUTES));
			Throwable deleteDeleted = catchThrowable(() -> store.delete(USERS, USER_7));

			// Assert
			assertThat(createExisting).isInstanceOf(RecordAlreadyExistsException.class);
			assertThat(updateMissing).isInstanceOf(RecordNotFoundException.class);
			assertThat(deleteMissing).isInstanceOf(RecordNotFoundException.class);
			assertThat(updateDeleted).isInstanceOf(RecordNotFoundException.class);
			assertThat(deleteDeleted).isInstanceOf(RecordNotFoundException.class);
			assertThat(store.lastVersion()).isEqualTo(3);
			assertThat(store.read(USERS, USER_42)).isEqualTo(ANN_ATTRIBUTES);
		}
	}

	@Test
	void storeRebuiltFromItsLogHoldsTheSameRecordsAndVersions() {
		// Arrange
		List<PrimaryKey> userKeys = List.of(USER_42, USER_7);
		List<PrimaryKey> orderKeys = List.of(ORDER_42_1, ORDER_42_2);
		Map<PrimaryKey, Optional<VersionedRecord>> usersBefore;
		Map<PrimaryKey, Optional<VersionedRecord>> ordersBefore;
		long lastVersionBefore;
		try (ReplicaStorage store = ReplicaStorage.open(leaderDir)) {
			store.create(USERS, USER_42, ANN_ATTRIBUTES);
			store.create(USERS, USER_7, BOB_ATTRIBUTES);
			store.update(USERS, USER_42, BOB_ATTRIBUTES);
			store.create(ORDERS, ORDER_42_1, TOTAL_10);
			store.create(ORDERS, ORDER_42_2, TOTAL_20);
			store.delete(ORDERS, ORDER_42_1);
			usersBefore = snapshot(store, USERS, userKeys);
			ordersBefore = snapshot(store, ORDERS, orderKeys);
			lastVersionBefore = store.lastVersion();
		}

		// Act
		try (ReplicaStorage rebuilt = ReplicaStorage.open(leaderDir)) {

			// Assert
			assertThat(snapshot(rebuilt, USERS, userKeys)).isEqualTo(usersBefore);
			assertThat(snapshot(rebuilt, ORDERS, orderKeys)).isEqualTo(ordersBefore);
			assertThat(rebuilt.lastVersion()).isEqualTo(lastVersionBefore).isEqualTo(6);
			assertThat(rebuilt.read(USERS, USER_42)).isEqualTo(BOB_ATTRIBUTES);
			assertThat(rebuilt.exists(ORDERS, ORDER_42_1)).isFalse();
			assertThat(rebuilt.readVersioned(ORDERS, ORDER_42_1).map(VersionedRecord::version)).contains(6L);
		}
	}

	@Test
	void rebuiltStoreContinuesWithTheNextVersion() {
		// Arrange
		try (ReplicaStorage store = ReplicaStorage.open(leaderDir)) {
			store.create(USERS, USER_42, ANN_ATTRIBUTES);
			store.delete(USERS, USER_42);
		}

		// Act
		try (ReplicaStorage rebuilt = ReplicaStorage.open(leaderDir)) {
			rebuilt.create(USERS, USER_42, BOB_ATTRIBUTES);

			// Assert
			assertThat(rebuilt.readVersioned(USERS, USER_42)).contains(new VersionedRecord(3, BOB_ATTRIBUTES));
		}
	}

	@Test
	void followerAppliesTheLeadersEntriesAndHoldsTheSameRecords() {
		// Arrange
		try (ReplicaStorage leader = ReplicaStorage.open(leaderDir);
				ReplicaStorage follower = ReplicaStorage.open(followerDir)) {
			leader.create(USERS, USER_42, ANN_ATTRIBUTES);
			leader.create(USERS, USER_7, BOB_ATTRIBUTES);
			leader.update(USERS, USER_42, BOB_ATTRIBUTES);
			leader.delete(USERS, USER_7);

			// Act
			leader.entriesAfter(follower.lastVersion(), 100).forEach(follower::apply);

			// Assert
			assertThat(follower.lastVersion()).isEqualTo(leader.lastVersion()).isEqualTo(4);
			assertThat(snapshot(follower, USERS, List.of(USER_42, USER_7)))
					.isEqualTo(snapshot(leader, USERS, List.of(USER_42, USER_7)));
			assertThat(follower.entriesAfter(0, 100)).isEqualTo(leader.entriesAfter(0, 100));
		}
	}

	@Test
	void followerKeepsAppliedEntriesAfterARestart() {
		// Arrange
		try (ReplicaStorage leader = ReplicaStorage.open(leaderDir)) {
			leader.create(USERS, USER_42, ANN_ATTRIBUTES);
			leader.delete(USERS, USER_42);
			try (ReplicaStorage follower = ReplicaStorage.open(followerDir)) {
				leader.entriesAfter(0, 100).forEach(follower::apply);
			}

			// Act
			try (ReplicaStorage restarted = ReplicaStorage.open(followerDir)) {

				// Assert
				assertThat(restarted.lastVersion()).isEqualTo(2);
				assertThat(restarted.readVersioned(USERS, USER_42).map(VersionedRecord::deleted)).contains(true);
				assertThat(restarted.entriesAfter(0, 100)).isEqualTo(leader.entriesAfter(0, 100));
			}
		}
	}

	@Test
	void followerIgnoresAnEntryItAlreadyHas() {
		// Arrange
		LogEntry first = LogEntry.put(1, USERS, USER_42, ANN_ATTRIBUTES);
		try (ReplicaStorage follower = ReplicaStorage.open(followerDir)) {
			follower.apply(first);

			// Act
			follower.apply(first);

			// Assert
			assertThat(follower.lastVersion()).isEqualTo(1);
			assertThat(follower.entriesAfter(0, 100)).containsExactly(first);
		}
	}

	@Test
	void followerRefusesAnEntryThatWouldSkipAVersion() {
		// Arrange
		try (ReplicaStorage follower = ReplicaStorage.open(followerDir)) {
			follower.apply(LogEntry.put(1, USERS, USER_42, ANN_ATTRIBUTES));

			// Act
			Throwable thrown = catchThrowable(() -> follower.apply(LogEntry.put(3, USERS, USER_7, BOB_ATTRIBUTES)));

			// Assert
			assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
			assertThat(follower.lastVersion()).isEqualTo(1);
			assertThat(follower.exists(USERS, USER_7)).isFalse();
		}
	}

	@Test
	void concurrentCreatesOfTheSameKeyLetExactlyOneWinAndLogOneEntry() throws Exception {
		// Arrange
		int threads = 16;
		CountDownLatch start = new CountDownLatch(1);
		try (ReplicaStorage store = ReplicaStorage.open(leaderDir);
				ExecutorService executor = Executors.newFixedThreadPool(threads)) {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				Attributes attributes = Attributes.of(Map.of("writer", String.valueOf(i)));
				results.add(executor.submit(() -> {
					if (!start.await(10, TimeUnit.SECONDS)) {
						throw new IllegalStateException("start signal not given");
					}
					try {
						store.create(USERS, USER_42, attributes);
						return true;
					} catch (RecordAlreadyExistsException e) {
						return false;
					}
				}));
			}

			// Act
			start.countDown();
			int created = 0;
			for (Future<Boolean> result : results) {
				if (result.get()) {
					created++;
				}
			}

			// Assert
			assertThat(created).isEqualTo(1);
			assertThat(store.lastVersion()).isEqualTo(1);
		}
	}

	private static Map<PrimaryKey, Optional<VersionedRecord>> snapshot(ReplicaStorage store, String table,
			List<PrimaryKey> keys) {
		Map<PrimaryKey, Optional<VersionedRecord>> records = new HashMap<>();
		keys.forEach(key -> records.put(key, store.readVersioned(table, key)));
		return records;
	}
}
