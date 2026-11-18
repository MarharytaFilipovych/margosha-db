package com.margosha.node.coordinator.controller;

import static com.margosha.node.data.TestData.SHARD_A;
import static com.margosha.node.data.TestData.SHARD_B;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.freePort;
import static com.margosha.node.data.TestData.localAddress;
import static com.margosha.node.data.TestData.testDataDir;
import static com.margosha.node.data.TestData.unique;
import static com.margosha.node.data.TestData.user;
import static com.margosha.node.data.TestData.userKeys;
import static com.margosha.node.data.TestData.usersTable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ShardRegistration;
import com.margosha.node.coordinator.replica.ReplicaClient;
import com.margosha.node.data.FakeCloudWatch;
import com.margosha.node.data.InProcessNode;
import com.margosha.node.data.InProcessReplicaSet;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;

class ReplicaMetricsRelayTest {

	private static final Duration WAIT = Duration.ofSeconds(20);
	private static final String UP = "margosha.replica.up.value";
	private static final String RECORDS = "margosha.replica.records.value";

	@Test
	void metricsOfAllSevenNodesAreSentAndAStoppedReplicaIsDown() {
		// Arrange
		try (InProcessNode coordinator = InProcessNode.replicatedCoordinator(0, testDataDir("coordinator"),
				"--margosha.metrics.cloudwatch.region=us-east-1", "--margosha.metrics.cloudwatch.step=1s",
				"--margosha.metrics.relay-interval=200ms", FakeCloudWatch.SOURCE);
				InProcessReplicaSet shardA = InProcessReplicaSet.start(SHARD_A, coordinator.address());
				InProcessReplicaSet shardB = InProcessReplicaSet.start(SHARD_B, coordinator.address())) {
			List<InProcessNode> replicas = Stream.of(shardA, shardB).flatMap(set ->
					set.replicas().stream()).toList();
			replicas.forEach(replica -> awaitRegistered(coordinator, replica));
			FakeCloudWatch cloudWatch = coordinator.bean(FakeCloudWatch.class);
			CoordinatorClient client = coordinator.client();
			String users = unique(USERS);
			client.registerTable(usersTable(users));
			List<PrimaryKey> keys = userKeys(20);

			// Act
			keys.forEach(key -> client.createRecord(users, user(key)));
			keys.forEach(key -> client.readRecord(users, key));

			// Assert: the coordinator's own metrics, and every replica's, are sent marked with its ID
			await().atMost(WAIT).untilAsserted(() -> {
				assertThat(cloudWatch.sent()).anyMatch(named("margosha.requests.count")
						.and(with("role", "coordinator")));
				for (InProcessNode replica : replicas) {
					String id = replica.replicaId();
					assertThat(cloudWatch.last(UP, "replica", id)).as("%s is up", id).contains(1.0);
					assertThat(cloudWatch.sent()).as("records of %s", id)
							.anyMatch(named(RECORDS).and(with("replica", id)));
				}
				assertThat(cloudWatch.sent()).as("the leader's latency, relayed")
						.anyMatch(named("margosha.requests.percentile.value").and(with("replica", SHARD_A + "-1"))
								.and(with("phi", "0.99")));
				assertThat(cloudWatch.sent()).as("the leader's lag, relayed")
						.anyMatch(named("margosha.replication.lag.value").and(with("replica", SHARD_A + "-1"))
								.and(with("follower", SHARD_A + "-2")));
			});
			assertThat(cloudWatch.sent()).as("ADR 0012: no sum, average or maximum")
					.noneMatch(datum -> datum.metricName().matches(".*\\.(sum|avg|max)"));

			// Act: a replica stops
			String stopped = SHARD_A + "-3";
			shardA.getFollower3().close();

			// Assert
			await().atMost(WAIT).until(() -> cloudWatch.last(UP, "replica", stopped).orElseThrow() == 0.0);
			assertThat(cloudWatch.last(UP, "replica", SHARD_A + "-2")).contains(1.0);
		}
	}

	@Test
	void replicasFirstRequestsAreCountedInFull() {
		// Arrange
		try (InProcessNode coordinator = startCoordinator(0, testDataDir("coordinator"));
				InProcessReplicaSet shardA = InProcessReplicaSet.start(SHARD_A, coordinator.address());
				InProcessReplicaSet shardB = InProcessReplicaSet.start(SHARD_B, coordinator.address())) {
			FakeCloudWatch cloudWatch = coordinator.bean(FakeCloudWatch.class);
			awaitAllRead(cloudWatch, shardA, shardB);
			String users = unique(USERS);
			coordinator.client().registerTable(usersTable(users));

			// Act
			userKeys(20).forEach(key -> coordinator.client().createRecord(users, user(key)));

			// Assert
			await().atMost(WAIT).untilAsserted(() -> assertThat(leaderCreates(cloudWatch)).isEqualTo(20));
		}
	}

	@Test
	void afterTheCoordinatorRestartsReplicasEarlierRequestsAreNotCountedAgain() {
		// Arrange
		int port = freePort();
		Path coordinatorDir = testDataDir("coordinator");
		try (InProcessNode first = startCoordinator(port, coordinatorDir);
				InProcessReplicaSet shardA = InProcessReplicaSet.start(SHARD_A, localAddress(port));
				InProcessReplicaSet shardB = InProcessReplicaSet.start(SHARD_B, localAddress(port))) {
			awaitAllRead(first.bean(FakeCloudWatch.class), shardA, shardB);
			String users = unique(USERS);
			first.client().registerTable(usersTable(users));
			userKeys(20).forEach(key -> first.client().createRecord(users, user(key)));
			first.close();
			try (InProcessNode second = startCoordinator(port, coordinatorDir)) {
				FakeCloudWatch cloudWatch = second.bean(FakeCloudWatch.class);
				awaitAllRead(cloudWatch, shardA, shardB);

				// Act
				userKeys(25).subList(20, 25).forEach(key -> second.client().createRecord(users, user(key)));

				// Assert
				await().atMost(WAIT).during(Duration.ofSeconds(2))
						.untilAsserted(() -> assertThat(leaderCreates(cloudWatch)).isEqualTo(5));
			}
		}
	}

	@Test
	void expectedReplicaThatNeverRegistersIsDownAfterTheGraceTime() {
		// Arrange
		try (InProcessNode coordinator = startCoordinator(0, testDataDir("coordinator"),
				"--margosha.metrics.expected-replica-grace=1s")) {
			FakeCloudWatch cloudWatch = coordinator.bean(FakeCloudWatch.class);

			// Act
			try (InProcessReplicaSet shardA = InProcessReplicaSet.start(SHARD_A, coordinator.address())) {

				// Assert
				awaitAllRead(cloudWatch, shardA);
				await().atMost(WAIT).untilAsserted(() -> Stream.of(1, 2, 3).map(number -> SHARD_B + "-" + number)
						.forEach(id -> assertThat(cloudWatch.last(UP, "replica", id)).as("%s is down", id)
								.contains(0.0)));
			}
		}
	}

	@Test
	void expectedReplicaThatHasNotRegisteredHasNoValueDuringTheGraceTime() {
		// Arrange
		try (InProcessNode coordinator = startCoordinator(0, testDataDir("coordinator"),
				"--margosha.metrics.expected-replica-grace=10m")) {
			FakeCloudWatch cloudWatch = coordinator.bean(FakeCloudWatch.class);

			// Act
			try (InProcessReplicaSet shardA = InProcessReplicaSet.start(SHARD_A, coordinator.address())) {
				awaitAllRead(cloudWatch, shardA);

				// Assert
				await().during(Duration.ofSeconds(2)).atMost(WAIT)
						.untilAsserted(() -> assertThat(cloudWatch.last(UP, "replica", SHARD_B + "-1")).isEmpty());
			}
		}
	}

	private static InProcessNode startCoordinator(int port, Path dataDir, String... extraArgs) {
		List<String> args = Stream.concat(Stream.of("--margosha.metrics.cloudwatch.region=us-east-1",
				"--margosha.metrics.cloudwatch.step=1s", "--margosha.metrics.relay-interval=200ms", FakeCloudWatch.SOURCE),
				Stream.of(extraArgs)).toList();
		return InProcessNode.replicatedCoordinator(port, dataDir, args.toArray(String[]::new));
	}

	private static void awaitAllRead(FakeCloudWatch cloudWatch, InProcessReplicaSet... shards) {
		List<String> replicas = Stream.of(shards).flatMap(set -> set.replicas().stream())
				.map(InProcessNode::replicaId).toList();
		await().atMost(WAIT).until(() -> replicas.stream()
				.allMatch(id -> cloudWatch.last(UP, "replica", id).orElse(0.0) == 1.0));
	}

	private static double leaderCreates(FakeCloudWatch cloudWatch) {
		return cloudWatch.sent().stream()
				.filter(named("margosha.requests.count").and(with("operation", "create")).and(with("role", "leader")))
				.mapToDouble(MetricDatum::value)
				.sum();
	}

	private static Predicate<MetricDatum> named(String name) {
		return datum -> datum.metricName().equals(name);
	}

	private static Predicate<MetricDatum> with(String dimension, String value) {
		return datum -> FakeCloudWatch.has(datum, dimension, value);
	}

	private static void awaitRegistered(InProcessNode coordinator, InProcessNode replica) {
		ShardRegistration registration = replica.registration();
		await().atMost(WAIT).until(() -> coordinator.shardRegistry().replicaSet(registration.shardId())
				.map(set -> set.replicas().stream().map(ReplicaClient::registration).toList().contains(registration))
				.orElse(false));
	}
}
