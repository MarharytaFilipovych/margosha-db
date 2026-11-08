package com.margosha.node.coordinator.controller;

import static com.margosha.node.data.TestData.ANN;
import static com.margosha.node.data.TestData.SHARD_A;
import static com.margosha.node.data.TestData.SHARD_B;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.testDataDir;
import static com.margosha.node.data.TestData.unique;
import static com.margosha.node.data.TestData.usersTable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.model.Attributes;
import com.margosha.api.model.ShardRegistration;
import com.margosha.api.model.TableRecord;
import com.margosha.node.coordinator.storage.ReplicaClient;
import com.margosha.node.data.InProcessNode;
import com.margosha.node.data.InProcessReplicaSet;
import com.margosha.node.shard.replication.model.VersionedRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Conflict resolution: concurrent writes to one key end with the same Last-Write-Wins winner everywhere. */
class ConcurrentWritesTest {

	private static final Duration WAIT = Duration.ofSeconds(15);
	private static final int UPDATES_PER_CLIENT = 50;

	private InProcessNode coordinator;
	private final String users = unique(USERS);

	@BeforeEach
	void setUp() {
		coordinator = InProcessNode.replicatedCoordinator(0, testDataDir("coordinator"));
		coordinator.client().registerTable(usersTable(users));
	}

	@AfterEach
	void tearDown() {
		coordinator.close();
	}

	@Test
	void twoClientsUpdatingOneKeyEndWithTheSameWinnerOnEveryReplica() throws Exception {
		// Arrange
		try (InProcessReplicaSet shardA = startRegistered(SHARD_A);
				InProcessReplicaSet shardB = startRegistered(SHARD_B);
				ExecutorService clients = Executors.newFixedThreadPool(2)) {
			coordinator.client().createRecord(users, ANN);
			InProcessReplicaSet owner = SHARD_A.equals(coordinator.shardRegistry().shardIdFor(USER_42)) ? shardA : shardB;
			CountDownLatch start = new CountDownLatch(1);
			List<Future<?>> done = new ArrayList<>();

			// Act: two clients update the same key at the same time
			for (String clientName : List.of("first", "second")) {
				CoordinatorClient client = coordinator.client();
				done.add(clients.submit(() -> {
					start.await(10, TimeUnit.SECONDS);
					IntStream.range(0, UPDATES_PER_CLIENT).forEach(i -> client.updateRecord(users, USER_42,
							Attributes.of(Map.of("name", clientName + "-" + i))));
					return null;
				}));
			}
			start.countDown();
			for (Future<?> client : done) {
				client.get(30, TimeUnit.SECONDS);
			}

			// Assert: once the followers caught up, every replica holds the leader's last write
			VersionedRecord winner = owner.getLeader().replica().readVersioned(users, USER_42).orElseThrow();
			await().atMost(WAIT).untilAsserted(() -> owner.replicas().forEach(replica ->
					assertThat(replica.replica().readVersioned(users, USER_42)).contains(winner)));
			assertThat(winner.recordVersion().vector())
					.containsExactly(Map.entry(owner.getLeader().registration().replicaId(), 1L + 2 * UPDATES_PER_CLIENT));
			List<TableRecord> reads = IntStream.range(0, 30)
					.mapToObj(i -> coordinator.client().readRecord(users, USER_42)).toList();
			assertThat(reads).allSatisfy(read -> assertThat(read.values()).containsEntry("name",
					winner.attributes().values().get("name")));
		}
	}

	private InProcessReplicaSet startRegistered(String shardId) {
		InProcessReplicaSet replicaSet = InProcessReplicaSet.start(shardId, coordinator.address());
		replicaSet.replicas().forEach(replica -> {
			ShardRegistration registration = replica.registration();
			await().atMost(WAIT).until(() -> coordinator.shardRegistry().replicaSet(registration.shardId())
					.map(set -> set.replicas().stream().map(ReplicaClient::registration).toList().contains(registration))
					.orElse(false));
		});
		return replicaSet;
	}
}
