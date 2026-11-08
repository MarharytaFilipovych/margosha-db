package com.margosha.node.shard.service;

import static com.margosha.node.data.TestData.ANN_ATTRIBUTES;
import static com.margosha.node.data.TestData.BOB_ATTRIBUTES;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.USER_7;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import java.util.Map;

import com.margosha.api.model.ApiError;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.Hlc;
import com.margosha.api.model.RecordVersion;
import com.margosha.api.model.ReplicaExists;
import com.margosha.api.model.ReplicaRecord;
import com.margosha.node.shard.replication.exception.ReplicaRecordNotFoundException;
import com.margosha.node.shard.replication.storage.ReplicaStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplicaReadServiceTest {

	private static final String LEADER_ID = "shard-a-1";

	@TempDir
	Path dataDir;

	private ReplicaStorage storage;
	private ReplicaReadService reads;

	@BeforeEach
	void setUp() {
		storage = ReplicaStorage.open(dataDir, LEADER_ID, () -> 1_000L);
		reads = new ReplicaReadService(storage);
	}

	@AfterEach
	void tearDown() {
		storage.close();
	}

	@Test
	void readReturnsTheAttributesWithTheirVersion() {
		// Arrange
		storage.create(USERS, USER_42, ANN_ATTRIBUTES);
		storage.update(USERS, USER_42, BOB_ATTRIBUTES);

		// Act
		ReplicaRecord record = reads.read(USERS, USER_42);

		// Assert
		assertThat(record).isEqualTo(new ReplicaRecord(BOB_ATTRIBUTES, 2, version(2, 1_000, 1)));
	}

	@Test
	void readOfAKeyNeverWrittenIsNotFoundWithVersionZero() {
		// Arrange
		storage.create(USERS, USER_7, ANN_ATTRIBUTES);

		// Act
		Throwable thrown = catchThrowable(() -> reads.read(USERS, USER_42));

		// Assert
		assertThat(thrown).isInstanceOfSatisfying(ReplicaRecordNotFoundException.class, e -> {
			assertThat(e.getLogVersion()).isZero();
			assertThat(e.toApiError()).isEqualTo(new ApiError(ErrorCode.RECORD_NOT_FOUND, e.getMessage(), 0L));
		});
	}

	@Test
	void readOfADeletedRecordIsNotFoundWithTheDeletesVersion() {
		// Arrange
		storage.create(USERS, USER_42, ANN_ATTRIBUTES);
		storage.delete(USERS, USER_42);

		// Act
		Throwable thrown = catchThrowable(() -> reads.read(USERS, USER_42));

		// Assert
		assertThat(thrown).isInstanceOfSatisfying(ReplicaRecordNotFoundException.class,
				e -> {
					assertThat(e.getLogVersion()).isEqualTo(2);
					assertThat(e.toApiError().recordVersion()).isEqualTo(version(2, 1_000, 1));
				});
	}

	@Test
	void existsReturnsWhetherTheRecordExistsAndItsVersion() {
		// Arrange
		storage.create(USERS, USER_42, ANN_ATTRIBUTES);
		storage.create(USERS, USER_7, BOB_ATTRIBUTES);
		storage.delete(USERS, USER_7);

		// Act
		ReplicaExists present = reads.exists(USERS, USER_42);
		ReplicaExists deleted = reads.exists(USERS, USER_7);
		ReplicaExists neverWritten = reads.exists("orders", USER_42);

		// Assert
		assertThat(present).isEqualTo(new ReplicaExists(true, 1, version(1, 1_000, 0)));
		assertThat(deleted).isEqualTo(new ReplicaExists(false, 3, version(2, 1_000, 2)));
		assertThat(neverWritten).isEqualTo(new ReplicaExists(false, 0, null));
	}

	@Test
	void everyReadAndExistsIsCounted() {
		// Arrange
		storage.create(USERS, USER_42, ANN_ATTRIBUTES);

		// Act
		reads.read(USERS, USER_42);
		catchThrowable(() -> reads.read(USERS, USER_7));
		reads.exists(USERS, USER_42);

		// Assert
		assertThat(reads.readsServed()).isEqualTo(3);
	}

	private static RecordVersion version(long counter, long l, long c) {
		return new RecordVersion(Map.of(LEADER_ID, counter), new Hlc(l, c), LEADER_ID);
	}
}
