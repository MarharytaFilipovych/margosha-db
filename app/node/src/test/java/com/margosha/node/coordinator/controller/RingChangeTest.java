package com.margosha.node.coordinator.controller;

import static com.margosha.node.data.TestData.SHARD_A;
import static com.margosha.node.data.TestData.SHARD_B;
import static com.margosha.node.data.TestData.SHARD_C;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.apiError;
import static com.margosha.node.data.TestData.client;
import static com.margosha.node.data.TestData.unique;
import static com.margosha.node.data.TestData.user;
import static com.margosha.node.data.TestData.userAttributes;
import static com.margosha.node.data.TestData.userKeys;
import static com.margosha.node.data.TestData.usersTable;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.PrimaryKey;
import com.margosha.node.annotation.ShardedCoordinatorTest;
import com.margosha.node.data.InProcessNode;
import com.margosha.node.coordinator.service.ShardRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

@ShardedCoordinatorTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RingChangeTest {

	private static final int KEY_COUNT = 200;

	@LocalServerPort
	private int port;

	@Autowired
	private ShardRegistryService shardRegistry;

	private final InProcessNode shardA = InProcessNode.shard(SHARD_A);
	private final InProcessNode shardB = InProcessNode.shard(SHARD_B);

	private CoordinatorClient client;
	private final String users = unique(USERS);

	@BeforeEach
	void setUp() {
		client = client(port);
		client.registerShard(shardA.registration());
		client.registerShard(shardB.registration());
		client.registerTable(usersTable(users));
	}

	@Test
	void keysMovedToTheNewShardAreNotFoundUntilWrittenAgain() {
		// Arrange
		List<PrimaryKey> keys = userKeys(KEY_COUNT);
		keys.forEach(key -> client.createRecord(users, user(key)));
		Map<PrimaryKey, String> ownerBefore = keys.stream()
				.collect(Collectors.toMap(Function.identity(), shardRegistry::shardIdFor));
		try (InProcessNode shardC = InProcessNode.shard(SHARD_C)) {

			// Act
			client.registerShard(shardC.registration());

			// Assert
			List<PrimaryKey> moved = keys.stream().filter(key -> SHARD_C.equals(shardRegistry.shardIdFor(key))).toList();
			List<PrimaryKey> stayed = keys.stream().filter(key -> !moved.contains(key)).toList();
			assertThat(moved).isNotEmpty();
			moved.forEach(key -> {
				assertThat(apiError(() -> client.readRecord(users, key)).code()).isEqualTo(ErrorCode.RECORD_NOT_FOUND);
				assertThat(client.recordExists(users, key)).isFalse();
			});
			stayed.forEach(key -> assertThat(client.readRecord(users, key)).isEqualTo(user(key)));
			stayed.forEach(key -> assertThat(shardRegistry.shardIdFor(key)).isEqualTo(ownerBefore.get(key)));
		}
	}

	@Test
	void recordsStayOnTheShardThatHoldsThem() {
		// Arrange
		List<PrimaryKey> keys = userKeys(KEY_COUNT);
		keys.forEach(key -> client.createRecord(users, user(key)));
		try (InProcessNode shardC = InProcessNode.shard(SHARD_C)) {

			// Act
			client.registerShard(shardC.registration());

			// Assert
			List<PrimaryKey> moved = keys.stream().filter(key -> SHARD_C.equals(shardRegistry.shardIdFor(key))).toList();
			assertThat(moved).isNotEmpty();
			moved.forEach(key -> {
				assertThat(shardC.storage().exists(users, key)).isFalse();
				assertThat(shardA.storage().exists(users, key) || shardB.storage().exists(users, key)).isTrue();
			});
		}
	}

	@Test
	void movedKeyWrittenAgainIsStoredOnTheNewShard() {
		// Arrange
		List<PrimaryKey> keys = userKeys(KEY_COUNT);
		keys.forEach(key -> client.createRecord(users, user(key)));
		try (InProcessNode shardC = InProcessNode.shard(SHARD_C)) {
			client.registerShard(shardC.registration());
			PrimaryKey moved = keys.stream().filter(key -> SHARD_C.equals(shardRegistry.shardIdFor(key))).findFirst().orElseThrow();

			// Act
			client.createRecord(users, user(moved));

			// Assert
			assertThat(client.readRecord(users, moved)).isEqualTo(user(moved));
			assertThat(shardC.storage().read(users, moved)).isEqualTo(userAttributes(moved));
		}
	}
}
