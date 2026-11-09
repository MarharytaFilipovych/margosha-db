package com.margosha.node.coordinator.controller;

import static com.margosha.node.data.TestData.SHARD_A;
import static com.margosha.node.data.TestData.SHARD_B;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.testDataDir;
import static com.margosha.node.data.TestData.unique;
import static com.margosha.node.data.TestData.user;
import static com.margosha.node.data.TestData.userKeys;
import static com.margosha.node.data.TestData.usersTable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ShardRegistration;
import com.margosha.node.common.metrics.ClusterMetrics;
import com.margosha.node.common.metrics.RequestMetrics;
import com.margosha.node.coordinator.replica.ReplicaClient;
import com.margosha.node.data.InProcessNode;
import com.margosha.node.data.InProcessReplicaSet;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;

class ClusterMetricsTest {

	private static final Duration WAIT = Duration.ofSeconds(15);
	private static final int RECORDS = 40;

	@Test
	void twoShardsOfThreeReplicasShowOneLeaderTwoFollowersAndADistributionValueEach() {
		// Arrange
		try (InProcessNode coordinator = InProcessNode.replicatedCoordinator(0, testDataDir("coordinator"));
				InProcessReplicaSet shardA = InProcessReplicaSet.start(SHARD_A, coordinator.address());
				InProcessReplicaSet shardB = InProcessReplicaSet.start(SHARD_B, coordinator.address())) {
			List.of(shardA, shardB).forEach(shard -> shard.replicas().forEach(replica -> awaitRegistered(coordinator, replica)));
			CoordinatorClient client = coordinator.client();
			String users = unique(USERS);
			client.registerTable(usersTable(users));
			List<PrimaryKey> keys = userKeys(RECORDS);

			// Act
			keys.forEach(key -> client.createRecord(users, user(key)));

			// Assert
			MeterRegistry metrics = coordinator.meterRegistry();
			for (String shard : List.of(SHARD_A, SHARD_B)) {
				assertThat(replicas(metrics, shard, "leader")).as("leaders of %s", shard).isEqualTo(1);
				assertThat(replicas(metrics, shard, "follower")).as("followers of %s", shard).isEqualTo(2);
			}

			double requestsA = requests(metrics, SHARD_A);
			double requestsB = requests(metrics, SHARD_B);
			assertThat(requestsA).isPositive();
			assertThat(requestsB).isPositive();
			assertThat(requestsA + requestsB).isEqualTo(RECORDS);

			await().atMost(WAIT).untilAsserted(() -> {
				for (InProcessReplicaSet shard : List.of(shardA, shardB)) {
					double leaderRecords = records(shard.getLeader());
					assertThat(shard.replicas()).allSatisfy(replica ->
							assertThat(records(replica)).isEqualTo(leaderRecords));
				}
			});
			assertThat(records(shardA.getLeader())).isEqualTo(requestsA);
			assertThat(records(shardB.getLeader())).isEqualTo(requestsB);
		}
	}

	private static double replicas(MeterRegistry metrics, String shard, String role) {
		return metrics.get(ClusterMetrics.REPLICAS).tag("shard", shard).tag("role", role).gauge().value();
	}

	private static double requests(MeterRegistry metrics, String shard) {
		return metrics.get(RequestMetrics.SHARD_REQUESTS).tag("shard", shard).counter().count();
	}

	private static double records(InProcessNode replica) {
		return replica.meterRegistry().get(ClusterMetrics.RECORDS).gauge().value();
	}

	private static void awaitRegistered(InProcessNode coordinator, InProcessNode replica) {
		ShardRegistration registration = replica.registration();
		await().atMost(WAIT).until(() -> coordinator.shardRegistry().replicaSet(registration.shardId())
				.map(set -> set.replicas().stream().map(ReplicaClient::registration).toList().contains(registration))
				.orElse(false));
	}
}
