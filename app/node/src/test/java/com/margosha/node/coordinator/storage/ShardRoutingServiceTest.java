package com.margosha.node.coordinator.storage;

import static com.margosha.node.data.TestData.ANN_ATTRIBUTES;
import static com.margosha.node.data.TestData.BOB_ATTRIBUTES;
import static com.margosha.node.data.TestData.SHARD_A;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.follower;
import static com.margosha.node.data.TestData.shard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.HashMap;
import java.util.Map;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.ShardRegistration;
import com.margosha.node.common.exception.RecordNotFoundException;
import com.margosha.node.coordinator.exception.ShardUnavailableException;
import com.margosha.node.coordinator.model.ReplicaAnswer;
import com.margosha.node.coordinator.service.QuorumReader;
import com.margosha.node.coordinator.service.ShardRegistryService;
import com.margosha.node.data.FakeReplica;
import com.margosha.ring.RingSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ShardRoutingServiceTest {

	private static final ShardRegistration LEADER = shard(SHARD_A, 8081);
	private static final ShardRegistration FOLLOWER_2 = follower(SHARD_A, SHARD_A + "-2", 8082);
	private static final ShardRegistration FOLLOWER_3 = follower(SHARD_A, SHARD_A + "-3", 8083);

	private final Map<String, FakeReplica> replicas = new HashMap<>();
	private final ShardRegistryService registry = new ShardRegistryService(RingSettings.load().newRing(), replica ->
			replicas.computeIfAbsent(replica.replicaId(), id -> new FakeReplica(replica)));
	private final QuorumReader quorumReader = new QuorumReader(2);
	private final ShardRoutingService storage = new ShardRoutingService(registry, quorumReader);

	@AfterEach
	void tearDown() {
		quorumReader.close();
	}

	@Test
	void writesGoToTheShardsLeaderOnly() {
		// Arrange
		registerAll();

		// Act
		storage.create(USERS, USER_42, ANN_ATTRIBUTES);
		storage.update(USERS, USER_42, BOB_ATTRIBUTES);
		storage.delete(USERS, USER_42);

		// Assert
		assertThat(replicas.get(LEADER.replicaId()).writes()).containsExactly("create 42", "update 42", "delete 42");
		assertThat(replicas.get(FOLLOWER_2.replicaId()).writes()).isEmpty();
		assertThat(replicas.get(FOLLOWER_3.replicaId()).writes()).isEmpty();
	}

	@Test
	void readReturnsTheNewestAnswerOfTheQuorum() {
		// Arrange
		registerAll();
		replicas.get(LEADER.replicaId()).answers(ReplicaAnswer.found(2, BOB_ATTRIBUTES));
		replicas.get(FOLLOWER_2.replicaId()).answers(ReplicaAnswer.found(2, BOB_ATTRIBUTES));
		replicas.get(FOLLOWER_3.replicaId()).answers(ReplicaAnswer.found(1, ANN_ATTRIBUTES));

		// Act
		Attributes first = storage.read(USERS, USER_42);
		Attributes second = storage.read(USERS, USER_42);
		Attributes third = storage.read(USERS, USER_42);

		// Assert
		assertThat(first).isEqualTo(BOB_ATTRIBUTES);
		assertThat(second).isEqualTo(BOB_ATTRIBUTES);
		assertThat(third).isEqualTo(BOB_ATTRIBUTES);
	}

	@Test
	void newestAnswerBeingADeleteIsRecordNotFound() {
		// Arrange
		registerAll();
		replicas.get(LEADER.replicaId()).answers(ReplicaAnswer.notFound(3));
		replicas.get(FOLLOWER_2.replicaId()).answers(ReplicaAnswer.notFound(3));
		replicas.get(FOLLOWER_3.replicaId()).answers(ReplicaAnswer.found(2, ANN_ATTRIBUTES));

		// Act
		Throwable read = catchThrowable(() -> storage.read(USERS, USER_42));
		boolean exists = storage.exists(USERS, USER_42);

		// Assert
		assertThat(read).isInstanceOf(RecordNotFoundException.class);
		assertThat(exists).isFalse();
	}

	@Test
	void writeWithoutARegisteredLeaderIsShardUnavailable() {
		// Arrange
		registry.register(FOLLOWER_2);
		registry.register(FOLLOWER_3);

		// Act
		Throwable thrown = catchThrowable(() -> storage.create(USERS, USER_42, ANN_ATTRIBUTES));

		// Assert
		assertThat(thrown).isInstanceOf(ShardUnavailableException.class)
				.hasMessage("Shard shard-a has no registered leader");
	}

	@Test
	void noRegisteredShardIsShardUnavailable() {
		// Arrange
		ShardRoutingService withoutShards = storage;

		// Act
		Throwable thrown = catchThrowable(() -> withoutShards.read(USERS, USER_42));

		// Assert
		assertThat(thrown).isInstanceOf(ShardUnavailableException.class).hasMessage("No shard is registered");
	}

	private void registerAll() {
		registry.register(LEADER);
		registry.register(FOLLOWER_2);
		registry.register(FOLLOWER_3);
	}
}
