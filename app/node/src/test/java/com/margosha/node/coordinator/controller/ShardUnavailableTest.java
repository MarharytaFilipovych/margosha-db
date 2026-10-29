package com.margosha.node.coordinator.controller;

import static com.margosha.node.data.TestData.ANN;
import static com.margosha.node.data.TestData.SHARD_A;
import static com.margosha.node.data.TestData.SHARD_B;
import static com.margosha.node.data.TestData.SHARD_TIMEOUT;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.apiError;
import static com.margosha.node.data.TestData.client;
import static com.margosha.node.data.TestData.shard;
import static com.margosha.node.data.TestData.silentServer;
import static com.margosha.node.data.TestData.unique;
import static com.margosha.node.data.TestData.user;
import static com.margosha.node.data.TestData.userKeys;
import static com.margosha.node.data.TestData.usersTable;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.exception.ApiException;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.TableRecord;
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
class ShardUnavailableTest {

	private static final int KEY_COUNT = 50;

	@LocalServerPort
	private int port;

	@Autowired
	private ShardRegistryService shardRegistry;

	private CoordinatorClient client;
	private final String users = unique(USERS);

	@BeforeEach
	void setUp() {
		client = client(port);
		client.registerTable(usersTable(users));
	}

	@Test
	void noRegisteredShardIsShardUnavailable() {
		// Act
		ApiException create = apiError(() -> client.createRecord(users, ANN));
		ApiException read = apiError(() -> client.readRecord(users, USER_42));

		// Assert
		assertShardUnavailable(create, "No shard is registered");
		assertShardUnavailable(read, "No shard is registered");
	}

	@Test
	void stoppedShardMakesOnlyItsOwnKeysUnavailable() {
		// Arrange
		try (InProcessNode shardA = InProcessNode.shard(SHARD_A);
			 InProcessNode shardB = InProcessNode.shard(SHARD_B)) {
			register(shardA, shardB);
			List<PrimaryKey> keys = userKeys(KEY_COUNT);
			keys.forEach(key -> client.createRecord(users, user(key)));
			Map<String, List<PrimaryKey>> keysByShard = keys.stream().collect(Collectors.groupingBy(shardRegistry::shardIdFor));
			shardB.close();

			// Act
			List<ApiException> shardBErrors = keysByShard.get(SHARD_B).stream()
					.map(key -> apiError(() -> client.readRecord(users, key)))
					.toList();
			List<ApiException> shardAErrors = keysByShard.get(SHARD_A).stream()
					.map(key -> apiError(() -> client.readRecord(users, key)))
					.toList();

			// Assert
			assertThat(shardBErrors).isNotEmpty().allSatisfy(error -> assertShardUnavailable(error, "Shard shard-b did not answer"));
			assertThat(shardAErrors).isNotEmpty().containsOnlyNulls();
			assertThat(shardA.isRunning()).isTrue();
		}
	}

	@Test
	void writeForStoppedShardIsNotSentToTheOtherShard() {
		// Arrange
		try (InProcessNode shardA = InProcessNode.shard(SHARD_A);
			 InProcessNode shardB = InProcessNode.shard(SHARD_B)) {
			register(shardA, shardB);
			PrimaryKey keyOfShardB = keyOwnedBy(SHARD_B);
			shardB.close();

			// Act
			ApiException error = apiError(() -> client.createRecord(users, user(keyOfShardB)));

			// Assert
			assertShardUnavailable(error, "Shard shard-b did not answer");
			assertThat(shardA.storage().exists(users, keyOfShardB)).isFalse();
		}
	}

	@Test
	void shardThatDoesNotAnswerFailsAfterTheTimeout() throws IOException {
		try (InProcessNode shardA = InProcessNode.shard(SHARD_A);
			 ServerSocket hangingShardB = silentServer()) {
			// Arrange
			register(shardA);
			client.registerShard(shard(SHARD_B, hangingShardB.getLocalPort()));
			PrimaryKey keyOfShardA = keyOwnedBy(SHARD_A);
			PrimaryKey keyOfShardB = keyOwnedBy(SHARD_B);
			client.createRecord(users, user(keyOfShardA));
			long start = System.nanoTime();

			// Act
			ApiException error = apiError(() -> client.readRecord(users, keyOfShardB));
			Duration waited = Duration.ofNanos(System.nanoTime() - start);
			TableRecord readFromShardA = client.readRecord(users, keyOfShardA);

			// Assert
			assertShardUnavailable(error, "Shard shard-b did not answer");
			assertThat(waited).isGreaterThanOrEqualTo(SHARD_TIMEOUT);
			assertThat(readFromShardA).isEqualTo(user(keyOfShardA));
		}
	}

	private void register(InProcessNode... shards) {
		for (InProcessNode shard : shards) {
			client.registerShard(shard.registration());
		}
	}

	private PrimaryKey keyOwnedBy(String shardId) {
		return userKeys(KEY_COUNT).stream()
				.filter(key -> shardId.equals(shardRegistry.shardIdFor(key)))
				.findFirst()
				.orElseThrow();
	}

	private static void assertShardUnavailable(ApiException error, String message) {
		assertThat(error).isNotNull();
		assertThat(error.isUnavailable()).isTrue();
		assertThat(error.code()).isEqualTo(ErrorCode.SHARD_UNAVAILABLE);
		assertThat(error.error().message()).isEqualTo(message);
	}
}
