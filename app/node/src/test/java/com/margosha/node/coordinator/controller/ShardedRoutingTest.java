package com.margosha.node.coordinator.controller;

import static com.margosha.node.data.TestData.ANN;
import static com.margosha.node.data.TestData.ANN_ATTRIBUTES;
import static com.margosha.node.data.TestData.BOB_ATTRIBUTES;
import static com.margosha.node.data.TestData.ORDERS;
import static com.margosha.node.data.TestData.ORDER_1;
import static com.margosha.node.data.TestData.ORDER_2;
import static com.margosha.node.data.TestData.ORDER_42_1;
import static com.margosha.node.data.TestData.ORDER_42_2;
import static com.margosha.node.data.TestData.OTHER_ATTRIBUTES;
import static com.margosha.node.data.TestData.SHARD_A;
import static com.margosha.node.data.TestData.SHARD_B;
import static com.margosha.node.data.TestData.TOTAL_10;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.apiError;
import static com.margosha.node.data.TestData.client;
import static com.margosha.node.data.TestData.ordersTable;
import static com.margosha.node.data.TestData.unique;
import static com.margosha.node.data.TestData.user;
import static com.margosha.node.data.TestData.userAttributes;
import static com.margosha.node.data.TestData.userKeys;
import static com.margosha.node.data.TestData.usersTable;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.exception.ApiException;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.TableRecord;
import com.margosha.node.annotation.ShardedCoordinatorTest;
import com.margosha.node.data.InProcessNode;
import com.margosha.node.coordinator.service.ShardRegistryService;
import com.margosha.node.storage.RecordStorage;
import com.margosha.node.coordinator.storage.ShardRoutingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

@ShardedCoordinatorTest
class ShardedRoutingTest {

	private static final int KEY_COUNT = 100;

	@LocalServerPort
	private int port;

	@Autowired
	private ShardRegistryService shardRegistry;

	@Autowired
	private RecordStorage coordinatorStorage;

	private CoordinatorClient client;
	private final String users = unique(USERS);

	@BeforeEach
	void setUp() {
		client = client(port);
		client.registerTable(usersTable(users));
	}

	@Test
	void shardedModeRoutesThroughTheRing() {
		// Arrange
		try (InProcessNode shardA = InProcessNode.shard(SHARD_A);
			 InProcessNode shardB = InProcessNode.shard(SHARD_B)) {

			// Act
			register(shardA, shardB);

			// Assert
			assertThat(coordinatorStorage).isInstanceOf(ShardRoutingService.class);
			assertThat(shardRegistry.shards()).containsExactlyInAnyOrder(SHARD_A, SHARD_B);
		}
	}

	@Test
	void eachRecordIsStoredOnlyOnItsOwningShard() {
		// Arrange
		try (InProcessNode shardA = InProcessNode.shard(SHARD_A);
			 InProcessNode shardB = InProcessNode.shard(SHARD_B)) {
			register(shardA, shardB);
			List<PrimaryKey> keys = userKeys(KEY_COUNT);

			// Act
			keys.forEach(key -> client.createRecord(users, user(key)));

			// Assert
			for (PrimaryKey key : keys) {
				assertThat(owner(key, shardA, shardB).storage().read(users, key)).isEqualTo(userAttributes(key));
				assertThat(notOwner(key, shardA, shardB).storage().exists(users, key)).isFalse();
			}
			assertThat(keys)
					.anyMatch(key -> owner(key, shardA, shardB) == shardA)
					.anyMatch(key -> owner(key, shardA, shardB) == shardB);
		}
	}

	@Test
	void updateAndDeleteChangeOnlyTheOwningShard() {
		// Arrange
		try (InProcessNode shardA = InProcessNode.shard(SHARD_A);
			 InProcessNode shardB = InProcessNode.shard(SHARD_B)) {
			register(shardA, shardB);
			List<PrimaryKey> keys = userKeys(KEY_COUNT);
			keys.forEach(key -> notOwner(key, shardA, shardB).storage().create(users, key, OTHER_ATTRIBUTES));
			keys.forEach(key -> client.createRecord(users, user(key)));
			List<PrimaryKey> updated = keys.subList(0, KEY_COUNT / 2);
			List<PrimaryKey> deleted = keys.subList(KEY_COUNT / 2, KEY_COUNT);

			// Act
			updated.forEach(key -> client.updateRecord(users, key, BOB_ATTRIBUTES));
			deleted.forEach(key -> client.deleteRecord(users, key));

			// Assert: the owner changed, the record planted on the other shard did not
			updated.forEach(key -> assertThat(owner(key, shardA, shardB).storage().read(users, key)).isEqualTo(BOB_ATTRIBUTES));
			deleted.forEach(key -> assertThat(owner(key, shardA, shardB).storage().exists(users, key)).isFalse());
			keys.forEach(key -> assertThat(notOwner(key, shardA, shardB).storage().read(users, key)).isEqualTo(OTHER_ATTRIBUTES));
		}
	}

	@Test
	void readReturnsTheOwnersRecordNotTheOtherShards() {
		// Arrange: a different record under the same key on the other shard
		try (InProcessNode shardA = InProcessNode.shard(SHARD_A);
			 InProcessNode shardB = InProcessNode.shard(SHARD_B)) {
			register(shardA, shardB);
			client.createRecord(users, ANN);
			notOwner(USER_42, shardA, shardB).storage().create(users, USER_42, OTHER_ATTRIBUTES);

			// Act
			TableRecord read = client.readRecord(users, USER_42);

			// Assert
			assertThat(read).isEqualTo(ANN);
		}
	}

	@Test
	void readDoesNotFallBackToTheOtherShard() {
		// Arrange: the key is only on the shard that does not own it
		try (InProcessNode shardA = InProcessNode.shard(SHARD_A);
			 InProcessNode shardB = InProcessNode.shard(SHARD_B)) {
			register(shardA, shardB);
			notOwner(USER_42, shardA, shardB).storage().create(users, USER_42, ANN_ATTRIBUTES);

			// Act
			ApiException error = apiError(() -> client.readRecord(users, USER_42));

			// Assert
			assertThat(error.code()).isEqualTo(ErrorCode.RECORD_NOT_FOUND);
		}
	}

	@Test
	void existsAsksOnlyTheOwningShard() {
		// Arrange
		try (InProcessNode shardA = InProcessNode.shard(SHARD_A);
			 InProcessNode shardB = InProcessNode.shard(SHARD_B)) {
			register(shardA, shardB);
			PrimaryKey onOwner = userKeys(KEY_COUNT).get(0);
			PrimaryKey onlyOnOtherShard = userKeys(KEY_COUNT).get(1);
			owner(onOwner, shardA, shardB).storage().create(users, onOwner, ANN_ATTRIBUTES);
			notOwner(onlyOnOtherShard, shardA, shardB).storage().create(users, onlyOnOtherShard, ANN_ATTRIBUTES);

			// Act
			boolean existsOnOwner = client.recordExists(users, onOwner);
			boolean existsOnlyOnOtherShard = client.recordExists(users, onlyOnOtherShard);

			// Assert
			assertThat(existsOnOwner).isTrue();
			assertThat(existsOnlyOnOtherShard).isFalse();
		}
	}

	@Test
	void sameKeyIsWrittenAndReadOnTheSameShard() {
		// Arrange
		try (InProcessNode shardA = InProcessNode.shard(SHARD_A);
			 InProcessNode shardB = InProcessNode.shard(SHARD_B)) {
			register(shardA, shardB);
			String ownerBeforeWrite = shardRegistry.shardIdFor(USER_42);

			// Act
			client.createRecord(users, ANN);
			TableRecord read = client.readRecord(users, USER_42);

			// Assert
			assertThat(read).isEqualTo(ANN);
			assertThat(shardRegistry.shardIdFor(USER_42)).isEqualTo(ownerBeforeWrite);
			assertThat(owner(USER_42, shardA, shardB).storage().exists(users, USER_42)).isTrue();
		}
	}

	@Test
	void recordsOfOnePartitionAreOnOneShard() {
		// Arrange
		try (InProcessNode shardA = InProcessNode.shard(SHARD_A);
			 InProcessNode shardB = InProcessNode.shard(SHARD_B)) {
			register(shardA, shardB);
			String orders = unique(ORDERS);
			client.registerTable(ordersTable(orders));

			// Act
			client.createRecord(orders, ORDER_1);
			client.createRecord(orders, ORDER_2);

			// Assert
			InProcessNode owner = owner(ORDER_42_1, shardA, shardB);
			assertThat(owner(ORDER_42_2, shardA, shardB)).isSameAs(owner);
			assertThat(owner.storage().read(orders, ORDER_42_1)).isEqualTo(TOTAL_10);
			assertThat(owner.storage().exists(orders, ORDER_42_2)).isTrue();
		}
	}

	private void register(InProcessNode... shards) {
		for (InProcessNode shard : shards) {
			client.registerShard(shard.registration());
		}
	}

	private InProcessNode owner(PrimaryKey key, InProcessNode shardA, InProcessNode shardB) {
		return SHARD_A.equals(shardRegistry.shardIdFor(key)) ? shardA : shardB;
	}

	private InProcessNode notOwner(PrimaryKey key, InProcessNode shardA, InProcessNode shardB) {
		return owner(key, shardA, shardB) == shardA ? shardB : shardA;
	}
}
