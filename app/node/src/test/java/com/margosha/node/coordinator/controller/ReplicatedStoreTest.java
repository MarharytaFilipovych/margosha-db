package com.margosha.node.coordinator.controller;

import static com.margosha.node.data.TestData.ANN;
import static com.margosha.node.data.TestData.ANN_ATTRIBUTES;
import static com.margosha.node.data.TestData.BOB;
import static com.margosha.node.data.TestData.BOB_ATTRIBUTES;
import static com.margosha.node.data.TestData.SHARD_A;
import static com.margosha.node.data.TestData.SHARD_B;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.apiError;
import static com.margosha.node.data.TestData.testDataDir;
import static com.margosha.node.data.TestData.unique;
import static com.margosha.node.data.TestData.user;
import static com.margosha.node.data.TestData.userAttributes;
import static com.margosha.node.data.TestData.userKeys;
import static com.margosha.node.data.TestData.usersTable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.exception.ApiException;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ReplicaRole;
import com.margosha.api.model.ShardRegistration;
import com.margosha.api.model.TableRecord;
import com.margosha.node.coordinator.service.ReplicaSet;
import com.margosha.node.coordinator.storage.ReplicaClient;
import com.margosha.node.data.InProcessNode;
import com.margosha.node.data.InProcessReplicaSet;
import com.margosha.node.shard.replication.model.VersionedRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReplicatedStoreTest {

	private static final Duration WAIT = Duration.ofSeconds(15);
	private static final int READS = 30;

	private InProcessNode coordinator;
	private CoordinatorClient client;
	private final String users = unique(USERS);

	@BeforeEach
	void setUp() {
		coordinator = InProcessNode.replicatedCoordinator(0, testDataDir("coordinator"));
		client = coordinator.client();
		client.registerTable(usersTable(users));
	}

	@AfterEach
	void tearDown() {
		coordinator.close();
	}

	// 4.1: replica registration

	@Test
	void everyReplicaOfAShardRegistersWithItsIdAndRole() {
		// Arrange
		try (InProcessReplicaSet shardA = startRegistered(SHARD_A)) {

			// Act
			ReplicaSet replicaSet = coordinator.shardRegistry().replicaSet(SHARD_A).orElseThrow();

			// Assert
			assertThat(coordinator.shardRegistry().shards()).containsExactly(SHARD_A);
			assertThat(replicaSet.leader().registration()).isEqualTo(shardA.getLeader().registration());
			assertThat(replicaSet.replicas()).extracting(replica -> replica.registration().role())
					.containsExactly(ReplicaRole.LEADER, ReplicaRole.FOLLOWER, ReplicaRole.FOLLOWER);
		}
	}

	@Test
	void secondLeaderForAShardIsRejected() {
		// Arrange
		try (InProcessReplicaSet shardA = startRegistered(SHARD_A)) {
			ShardRegistration secondLeader = new ShardRegistration(SHARD_A, SHARD_A + "-9", ReplicaRole.LEADER,
					shardA.getFollower2().address());

			// Act
			ApiException error = apiError(() -> client.registerShard(secondLeader));

			// Assert
			assertThat(error.isValidationError()).isTrue();
			assertThat(coordinator.shardRegistry().replicaSet(SHARD_A).orElseThrow().leader().registration())
					.isEqualTo(shardA.getLeader().registration());
		}
	}

	// 4.2: writes go to the owner's leader

	@Test
	void eachRecordIsWrittenByItsOwnersLeaderAndCopiedToItsFollowersOnly() {
		// Arrange
		try (InProcessReplicaSet shardA = startRegistered(SHARD_A);
				InProcessReplicaSet shardB = startRegistered(SHARD_B)) {
			List<PrimaryKey> keys = userKeys(40);

			// Act
			keys.forEach(key -> client.createRecord(users, user(key)));

			// Assert
			Map<String, List<PrimaryKey>> keysByShard = keys.stream()
					.collect(Collectors.groupingBy(coordinator.shardRegistry()::shardIdFor));
			assertThat(keysByShard.keySet()).containsExactlyInAnyOrder(SHARD_A, SHARD_B);
			await().atMost(WAIT).untilAsserted(() -> keys.forEach(key -> {
				InProcessReplicaSet owner = SHARD_A.equals(coordinator.shardRegistry().shardIdFor(key)) ? shardA : shardB;
				InProcessReplicaSet other = owner == shardA ? shardB : shardA;
				owner.replicas().forEach(replica ->
						assertThat(replica.replica().read(users, key)).isEqualTo(userAttributes(key)));
				other.replicas().forEach(replica ->
						assertThat(replica.replica().readVersioned(users, key)).isEmpty());
			}));
		}
	}

	// 4.3: quorum reads

	@Test
	void readReturnsTheNewestVersionWhileAFollowerLagsBehind() {
		// Arrange
		try (InProcessReplicaSet shardA = startRegistered(SHARD_A)) {
			client.createRecord(users, ANN);
			awaitOnAllReplicas(shardA, USER_42);
			InProcessNode lagging = shardA.cutOffFollower3();
			awaitRegistered(lagging);
			client.updateRecord(users, USER_42, BOB_ATTRIBUTES);

			// Act
			List<TableRecord> reads = IntStream.range(0, READS).mapToObj(i -> client.readRecord(users, USER_42)).toList();

			// Assert
			assertThat(lagging.replica().read(users, USER_42)).isEqualTo(ANN_ATTRIBUTES);
			assertThat(reads).containsOnly(BOB);
		}
	}

	@Test
	void deletedRecordIsNotFoundWhileAFollowerStillHoldsTheOldValue() {
		// Arrange
		try (InProcessReplicaSet shardA = startRegistered(SHARD_A)) {
			client.createRecord(users, ANN);
			awaitOnAllReplicas(shardA, USER_42);
			InProcessNode lagging = shardA.cutOffFollower3();
			awaitRegistered(lagging);
			client.deleteRecord(users, USER_42);

			// Act
			List<ApiException> reads = IntStream.range(0, READS)
					.mapToObj(i -> apiError(() -> client.readRecord(users, USER_42))).toList();
			List<Boolean> exists = IntStream.range(0, READS).mapToObj(i -> client.recordExists(users, USER_42)).toList();

			// Assert
			assertThat(lagging.replica().read(users, USER_42)).isEqualTo(ANN_ATTRIBUTES);
			assertThat(reads).allSatisfy(error -> assertThat(error.code()).isEqualTo(ErrorCode.RECORD_NOT_FOUND));
			assertThat(exists).containsOnly(false);
		}
	}

	@Test
	void readsAndWritesWorkWithOneFollowerDown() {
		// Arrange
		try (InProcessReplicaSet shardA = startRegistered(SHARD_A)) {
			client.createRecord(users, ANN);
			shardA.getFollower3().close();

			// Act
			client.updateRecord(users, USER_42, BOB_ATTRIBUTES);
			List<TableRecord> reads = IntStream.range(0, READS).mapToObj(i -> client.readRecord(users, USER_42)).toList();

			// Assert
			assertThat(reads).containsOnly(BOB);
		}
	}

	@Test
	void twoReplicasDownMakeTheShardUnavailable() {
		// Arrange
		try (InProcessReplicaSet shardA = startRegistered(SHARD_A)) {
			client.createRecord(users, ANN);
			shardA.getFollower2().close();
			shardA.getFollower3().close();

			// Act
			ApiException read = apiError(() -> client.readRecord(users, USER_42));
			ApiException write = apiError(() -> client.updateRecord(users, USER_42, BOB_ATTRIBUTES));

			// Assert
			assertThat(read.isUnavailable()).isTrue();
			assertThat(read.error().message()).isEqualTo("Only 1 of the 2 replicas a read needs answered for shard shard-a");
			assertThat(write.isUnavailable()).isTrue();
			assertThat(write.error().message()).contains("outcome is unknown");
		}
	}

	@Test
	void leaderDownStopsWritesButReadsStillWork() {
		// Arrange
		try (InProcessReplicaSet shardA = startRegistered(SHARD_A)) {
			client.createRecord(users, ANN);
			awaitOnAllReplicas(shardA, USER_42);
			shardA.getLeader().close();

			// Act
			ApiException write = apiError(() -> client.updateRecord(users, USER_42, BOB_ATTRIBUTES));
			List<TableRecord> reads = IntStream.range(0, READS).mapToObj(i -> client.readRecord(users, USER_42)).toList();

			// Assert
			assertThat(write.isUnavailable()).isTrue();
			assertThat(write.error().message()).isEqualTo("Replica shard-a-1 of shard shard-a did not answer");
			assertThat(reads).containsOnly(ANN);
		}
	}

	@Test
	void readsAreSpreadEvenlyOverAllReplicasFollowersIncluded() {
		// Arrange
		try (InProcessReplicaSet shardA = startRegistered(SHARD_A)) {
			client.createRecord(users, ANN);
			List<Long> before = readsServed(shardA);

			// Act
			IntStream.range(0, 3000).forEach(i -> client.readRecord(users, USER_42));

			// Assert
			List<Long> after = readsServed(shardA);
			List<Long> served = IntStream.range(0, 3).mapToObj(i -> after.get(i) - before.get(i)).toList();
			assertThat(served).allSatisfy(count -> assertThat(count).isBetween(1800L, 2200L));
		}
	}

	private InProcessReplicaSet startRegistered(String shardId) {
		InProcessReplicaSet replicaSet = InProcessReplicaSet.start(shardId, coordinator.address());
		replicaSet.replicas().forEach(this::awaitRegistered);
		return replicaSet;
	}

	private void awaitRegistered(InProcessNode replica) {
		ShardRegistration registration = replica.registration();
		await().atMost(WAIT).until(() -> coordinator.shardRegistry().replicaSet(registration.shardId())
				.map(set -> set.replicas().stream().map(ReplicaClient::registration).toList().contains(registration))
				.orElse(false));
	}

	private void awaitOnAllReplicas(InProcessReplicaSet replicaSet, PrimaryKey key) {
		long last = replicaSet.getLeader().replica().lastVersion();
		await().atMost(WAIT).untilAsserted(() -> replicaSet.replicas().forEach(replica ->
				assertThat(replica.replica().readVersioned(users, key).map(VersionedRecord::version)).contains(last)));
	}

	private static List<Long> readsServed(InProcessReplicaSet replicaSet) {
		return replicaSet.replicas().stream().map(replica -> replica.shardClient().status().readsServed()).toList();
	}
}
