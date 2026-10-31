package com.margosha.node.shard.controller;

import static com.margosha.node.data.TestData.ANN_ATTRIBUTES;
import static com.margosha.node.data.TestData.BOB_ATTRIBUTES;
import static com.margosha.node.data.TestData.ORDERS;
import static com.margosha.node.data.TestData.ORDER_42_1;
import static com.margosha.node.data.TestData.ORDER_42_2;
import static com.margosha.node.data.TestData.TOTAL_10;
import static com.margosha.node.data.TestData.TOTAL_20;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USERS_TABLE;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.USER_7;
import static com.margosha.node.data.TestData.apiError;
import static com.margosha.node.data.TestData.client;
import static com.margosha.node.data.TestData.shardClient;
import static com.margosha.node.data.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;

import com.margosha.api.client.ShardClient;
import com.margosha.api.exception.ApiException;
import com.margosha.api.model.Attributes;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ReplicaExists;
import com.margosha.api.model.ReplicaRecord;
import com.margosha.api.model.ReplicaStatus;
import com.margosha.node.annotation.ShardNodeTest;
import com.margosha.node.common.storage.RecordStorage;
import com.margosha.node.shard.replication.leader.LeaderRecordStorage;
import com.margosha.node.shard.replication.storage.ReplicaStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

@ShardNodeTest
class ShardControllerTest {

	@LocalServerPort
	private int port;

	@Autowired
	private RecordStorage storage;

	@Autowired
	private ReplicaStorage replica;

	private ShardClient shard;
	private final String users = unique(USERS);
	private final String orders = unique(ORDERS);

	@BeforeEach
	void setUp() {
		shard = shardClient(port);
	}

	@Test
	void shardIsALeaderKeepingItsRecordsInItsReplicationLog() {
		// Arrange
		long before = replica.lastVersion();

		// Act
		shard.createRecord(users, USER_42, ANN_ATTRIBUTES);

		// Assert
		assertThat(storage).isInstanceOf(LeaderRecordStorage.class);
		assertThat(replica.lastVersion()).isEqualTo(before + 1);
		assertThat(replica.entriesAfter(before, 10)).singleElement()
				.satisfies(entry -> assertThat(entry.attributes()).isEqualTo(ANN_ATTRIBUTES));
	}

	@Test
	void readReturnsTheAttributesWithTheirVersion() {
		// Arrange
		shard.createRecord(users, USER_42, ANN_ATTRIBUTES);
		long created = replica.lastVersion();
		shard.updateRecord(users, USER_42, BOB_ATTRIBUTES);
		long updated = replica.lastVersion();

		// Act
		ReplicaRecord read = shard.readVersioned(users, USER_42);

		// Assert
		assertThat(updated).isGreaterThan(created);
		assertThat(read).isEqualTo(new ReplicaRecord(BOB_ATTRIBUTES, updated));
	}

	@Test
	void readOfAKeyNeverWrittenIsNotFoundWithVersionZero() {
		// Arrange
		shard.createRecord(users, USER_7, ANN_ATTRIBUTES);

		// Act
		ApiException error = apiError(() -> shard.readVersioned(users, USER_42));

		// Assert
		assertThat(error.isNotFound()).isTrue();
		assertThat(error.code()).isEqualTo(ErrorCode.RECORD_NOT_FOUND);
		assertThat(error.error().version()).isZero();
	}

	@Test
	void readOfADeletedRecordIsNotFoundWithTheDeletesVersion() {
		// Arrange
		shard.createRecord(users, USER_42, ANN_ATTRIBUTES);
		shard.deleteRecord(users, USER_42);
		long deleted = replica.lastVersion();

		// Act
		ApiException error = apiError(() -> shard.readVersioned(users, USER_42));

		// Assert
		assertThat(error.isNotFound()).isTrue();
		assertThat(error.code()).isEqualTo(ErrorCode.RECORD_NOT_FOUND);
		assertThat(error.error().version()).isEqualTo(deleted);
	}

	@Test
	void existsReturnsWhetherTheRecordExistsAndItsVersion() {
		// Arrange
		shard.createRecord(users, USER_42, ANN_ATTRIBUTES);
		long created = replica.lastVersion();
		shard.createRecord(users, USER_7, BOB_ATTRIBUTES);
		shard.deleteRecord(users, USER_7);
		long deleted = replica.lastVersion();

		// Act
		ReplicaExists present = shard.existsVersioned(users, USER_42);
		ReplicaExists removed = shard.existsVersioned(users, USER_7);
		ReplicaExists neverWritten = shard.existsVersioned(orders, ORDER_42_1);

		// Assert
		assertThat(present).isEqualTo(new ReplicaExists(true, created));
		assertThat(removed).isEqualTo(new ReplicaExists(false, deleted));
		assertThat(neverWritten).isEqualTo(new ReplicaExists(false, 0));
	}

	@Test
	void statusCountersChangeAfterAWriteAndARead() {
		// Arrange
		ReplicaStatus before = shard.status();

		// Act
		shard.createRecord(users, USER_42, ANN_ATTRIBUTES);
		ReplicaStatus afterWrite = shard.status();
		shard.readRecord(users, USER_42);
		ReplicaStatus afterRead = shard.status();

		// Assert
		assertThat(before.shardId()).isEqualTo("test-shard");
		assertThat(before.role()).isEqualTo("leader");
		assertThat(before.followerPositions()).isEmpty();
		assertThat(afterWrite.lastVersion()).isEqualTo(before.lastVersion() + 1);
		assertThat(afterWrite.readsServed()).isEqualTo(before.readsServed());
		assertThat(afterRead.readsServed()).isEqualTo(afterWrite.readsServed() + 1);
		assertThat(afterRead.lastVersion()).isEqualTo(afterWrite.lastVersion());
	}

	@Test
	void createdRecordCanBeReadBack() {
		// Act
		Attributes created = shard.createRecord(users, USER_42, ANN_ATTRIBUTES);

		// Assert
		assertThat(created).isEqualTo(ANN_ATTRIBUTES);
		assertThat(shard.readRecord(users, USER_42)).isEqualTo(ANN_ATTRIBUTES);
		assertThat(storage.read(users, USER_42)).isEqualTo(ANN_ATTRIBUTES);
	}

	@Test
	void sortKeyMakesRecordsOfOnePartitionDistinct() {
		// Act
		shard.createRecord(orders, ORDER_42_1, TOTAL_10);
		shard.createRecord(orders, ORDER_42_2, TOTAL_20);

		// Assert
		assertThat(shard.readRecord(orders, ORDER_42_1)).isEqualTo(TOTAL_10);
		assertThat(shard.readRecord(orders, ORDER_42_2)).isEqualTo(TOTAL_20);
	}

	@Test
	void existsIsTrueForPresentAndFalseForAbsentKey() {
		// Arrange
		shard.createRecord(users, USER_42, ANN_ATTRIBUTES);

		// Act
		boolean present = shard.recordExists(users, USER_42);
		boolean absent = shard.recordExists(users, USER_7);

		// Assert
		assertThat(present).isTrue();
		assertThat(absent).isFalse();
	}

	@Test
	void updateReplacesAllAttributes() {
		// Arrange
		shard.createRecord(users, USER_42, ANN_ATTRIBUTES);

		// Act
		Attributes updated = shard.updateRecord(users, USER_42, BOB_ATTRIBUTES);

		// Assert
		assertThat(updated).isEqualTo(BOB_ATTRIBUTES);
		assertThat(shard.readRecord(users, USER_42)).isEqualTo(BOB_ATTRIBUTES);
	}

	@Test
	void deletedRecordNoLongerExists() {
		// Arrange
		shard.createRecord(users, USER_42, ANN_ATTRIBUTES);

		// Act
		shard.deleteRecord(users, USER_42);

		// Assert
		assertThat(shard.recordExists(users, USER_42)).isFalse();
	}

	@Test
	void createOfExistingKeyIsConflict() {
		// Arrange
		shard.createRecord(users, USER_42, ANN_ATTRIBUTES);

		// Act
		ApiException error = apiError(() -> shard.createRecord(users, USER_42, BOB_ATTRIBUTES));

		// Assert
		assertThat(error.isConflict()).isTrue();
		assertThat(error.code()).isEqualTo(ErrorCode.RECORD_ALREADY_EXISTS);
		assertThat(shard.readRecord(users, USER_42)).isEqualTo(ANN_ATTRIBUTES);
	}

	@Test
	void missingRecordIsNotFound() {
		// Act
		ApiException read = apiError(() -> shard.readRecord(users, USER_42));
		ApiException update = apiError(() -> shard.updateRecord(users, USER_42, BOB_ATTRIBUTES));
		ApiException delete = apiError(() -> shard.deleteRecord(users, USER_42));

		// Assert
		assertThat(read.code()).isEqualTo(ErrorCode.RECORD_NOT_FOUND);
		assertThat(update.code()).isEqualTo(ErrorCode.RECORD_NOT_FOUND);
		assertThat(delete.code()).isEqualTo(ErrorCode.RECORD_NOT_FOUND);
	}

	@Test
	void blankPartitionKeyIsValidationError() {
		// Act
		ApiException error = apiError(() -> shard.readRecord(users, PrimaryKey.of(" ")));

		// Assert
		assertThat(error.isValidationError()).isTrue();
		assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
		assertThat(error.error().message()).isEqualTo("Missing partition key value");
	}

	@Test
	void shardDoesNotServeTableRegistration() {
		// Act
		ApiException error = apiError(() -> client(port).registerTable(USERS_TABLE));

		// Assert
		assertThat(error.status()).isIn(404, 405);
	}
}
