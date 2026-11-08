package com.margosha.node.coordinator.controller;

import static com.margosha.node.data.InProcessNode.registeringWith;
import static com.margosha.node.data.TestData.SHARD_A;
import static com.margosha.node.data.TestData.SHARD_B;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.freePort;
import static com.margosha.node.data.TestData.testDataDir;
import static com.margosha.node.data.TestData.unique;
import static com.margosha.node.data.TestData.user;
import static com.margosha.node.data.TestData.usersTable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.margosha.api.util.TraceIds;
import com.margosha.node.data.InProcessNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A trace ID follows a request from the coordinator to the replicas it calls, on 2 shards x 3 replicas in this
 * JVM. Every node runs on a fixed port, so the Tomcat thread name in a log line ({@code http-nio-<port>-exec-N}) shows
 * which node wrote it: reads have the same path on the coordinator and on a replica.
 */
@ExtendWith(OutputCaptureExtension.class)
class TraceAcrossNodesTest {

	private static final Duration WAIT = Duration.ofSeconds(15);
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final String users = unique(USERS);
	private final List<InProcessNode> nodes = new ArrayList<>();
	private InProcessNode coordinator;
	private RestClient http;

	@BeforeEach
	void setUp() {
		LoggingSystem.get(getClass().getClassLoader()).cleanUp();
		coordinator = InProcessNode.replicatedCoordinator(freePort(), testDataDir("trace-coordinator"),
				"--margosha.shard.read-hedge-delay=5s");
		startReplicaSet(SHARD_A);
		startReplicaSet(SHARD_B);
		await().atMost(WAIT).until(() -> coordinator.shardRegistry().shards().size() == 2
				&& coordinator.shardRegistry().replicaSet(SHARD_A).orElseThrow().replicas().size() == 3
				&& coordinator.shardRegistry().replicaSet(SHARD_B).orElseThrow().replicas().size() == 3);
		coordinator.client().registerTable(usersTable(users));
		http = RestClient.create(coordinator.address());
	}

	@AfterEach
	void tearDown() {
		nodes.reversed().forEach(InProcessNode::close);
		coordinator.close();
	}

	@Test
	void writeIsTracedOnTheCoordinatorAndTheLeader(CapturedOutput output) {
		// Arrange
		InProcessNode leader = node(ownerOf42() + "-1");

		// Act
		http.post().uri("/tables/{table}/records", users).header(TraceIds.HEADER, "trace-of-a-write")
				.contentType(MediaType.APPLICATION_JSON).body(user(USER_42)).retrieve().toBodilessEntity();

		// Assert
		List<Map<String, Object>> lines = linesOf(output, "trace-of-a-write");
		Map<String, Object> request = endLineOn(lines, coordinator);
		Map<String, Object> call = callLineTo(lines, leader);
		Map<String, Object> handled = endLineOn(lines, leader);
		assertThat(request).containsEntry("method", "POST").containsEntry("path", "/tables/" + users + "/records")
				.containsEntry("status", 201);
		assertThat(call).containsEntry("method", "POST").containsEntry("status", 201)
				.containsEntry("path", "/tables/" + users + "/records/42");
		assertThat(handled).containsEntry("method", "POST").containsEntry("path", "/tables/" + users + "/records/42");
		assertThat(durationOf(request)).isGreaterThanOrEqualTo(durationOf(call));
		assertThat(durationOf(call)).isGreaterThanOrEqualTo(durationOf(handled));
	}

	@Test
	void readIsTracedOnTheCoordinatorAndTheReplicasThatAnswered(CapturedOutput output) {
		// Arrange
		http.post().uri("/tables/{table}/records", users).contentType(MediaType.APPLICATION_JSON)
				.body(user(USER_42)).retrieve().toBodilessEntity();

		// Act
		http.get().uri("/tables/{table}/records/42", users).header(TraceIds.HEADER, "trace-of-a-read")
				.retrieve().toBodilessEntity();

		// Assert
		List<Map<String, Object>> lines = linesOf(output, "trace-of-a-read");
		Map<String, Object> request = endLineOn(lines, coordinator);
		List<Map<String, Object>> calls = lines.stream().filter(line -> line.containsKey("toReplica")).toList();
		assertThat(request).containsEntry("method", "GET").containsEntry("status", 200);
		assertThat(calls).as("one call per replica of the R = 2 asked").hasSize(2)
				.allSatisfy(call -> assertThat(call).containsEntry("status", 200)
						.extractingByKey("toReplica").asString().startsWith(ownerOf42() + "-"));
		for (Map<String, Object> call : calls) {
			Map<String, Object> handled = endLineOn(lines, node((String) call.get("toReplica")));
			assertThat(handled).containsEntry("method", "GET").containsEntry("status", 200);
			assertThat(durationOf(request)).isGreaterThanOrEqualTo(durationOf(call));
			assertThat(durationOf(call)).isGreaterThanOrEqualTo(durationOf(handled));
		}
	}

	private void startReplicaSet(String shardId) {
		String[] registration = registeringWith(coordinator.address());
		InProcessNode leader = InProcessNode.leader(shardId, testDataDir(shardId + "-1"), freePort(), registration);
		nodes.add(leader);
		for (int number = 2; number <= 3; number++) {
			nodes.add(InProcessNode.follower(shardId, shardId + "-" + number, testDataDir(shardId + "-" + number),
					leader.address(), freePort(), registration));
		}
	}

	private String ownerOf42() {
		return coordinator.shardRegistry().shardIdFor(USER_42);
	}

	private InProcessNode node(String replicaId) {
		return nodes.stream().filter(node -> node.replicaId().equals(replicaId)).findFirst().orElseThrow();
	}

	private static List<Map<String, Object>> linesOf(CapturedOutput output, String traceId) {
		return output.getOut().lines().filter(line -> line.startsWith("{") && line.contains("\"" + traceId + "\""))
				.map(line -> JSON.readValue(line, new TypeReference<Map<String, Object>>() { }))
				.filter(line -> traceId.equals(line.get("traceId")))
				.toList();
	}

	private static Map<String, Object> endLineOn(List<Map<String, Object>> lines, InProcessNode node) {
		List<Map<String, Object>> found = lines.stream()
				.filter(line -> String.valueOf(line.get("thread_name")).contains("-" + node.port() + "-"))
				.filter(line -> line.containsKey("durationMs") && !line.containsKey("toReplica"))
				.toList();
		assertThat(found).as("end line on port %d", node.port()).hasSize(1);
		return found.getFirst();
	}

	private static Map<String, Object> callLineTo(List<Map<String, Object>> lines, InProcessNode replica) {
		List<Map<String, Object>> found = lines.stream()
				.filter(line -> replica.replicaId().equals(line.get("toReplica"))).toList();
		assertThat(found).as("call line to %s", replica.replicaId()).hasSize(1);
		return found.getFirst();
	}

	private static long durationOf(Map<String, Object> line) {
		return ((Number) line.get("durationMs")).longValue();
	}
}
