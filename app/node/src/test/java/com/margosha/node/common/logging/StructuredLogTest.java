package com.margosha.node.common.logging;

import static com.margosha.node.data.TestData.testDataDir;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.margosha.node.NodeApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class StructuredLogTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void replicaLogLineIsJsonWithRoleShardAndReplica(CapturedOutput output) {
		// Arrange
		try (ConfigurableApplicationContext replica = start("shard", "--server.port=0",
				"--margosha.shard.id=shard-a", "--margosha.replica.id=shard-a-2", "--margosha.replica.role=follower",
				"--margosha.replica.leader-url=http://localhost:1",
				"--margosha.data-dir=" + testDataDir("log-replica"))) {

			// Act
			LoggerFactory.getLogger(StructuredLogTest.class).info("Structured log check on a replica");

			// Assert
			assertThat(lineWith(output, "Structured log check on a replica"))
					.containsEntry("message", "Structured log check on a replica")
					.containsEntry("level", "INFO")
					.containsEntry("logger_name", StructuredLogTest.class.getName())
					.containsEntry("role", "follower")
					.containsEntry("shard", "shard-a")
					.containsEntry("replica", "shard-a-2")
					.containsKeys("@timestamp", "thread_name")
					.doesNotContainKeys("@version", "level_value");
		}
	}

	@Test
	void coordinatorLogLineIsJsonWithItsRoleOnly(CapturedOutput output) {
		// Arrange
		try (ConfigurableApplicationContext coordinator = start("coordinator", "--mode=single", "--server.port=0")) {

			// Act
			LoggerFactory.getLogger(StructuredLogTest.class).warn("Structured log check on the coordinator");

			// Assert
			assertThat(lineWith(output, "Structured log check on the coordinator"))
					.containsEntry("message", "Structured log check on the coordinator")
					.containsEntry("level", "WARN")
					.containsEntry("role", "coordinator")
					.doesNotContainKeys("shard", "replica");
			assertThat(lineWith(output, "Running as coordinator")).containsEntry("role", "coordinator");
		}
	}

	private static ConfigurableApplicationContext start(String... args) {
		LoggingSystem.get(StructuredLogTest.class.getClassLoader()).cleanUp();
		return NodeApplication.run(args);
	}

	private static Map<String, Object> lineWith(CapturedOutput output, String message) {
		List<String> lines = output.getOut().lines().filter(line -> line.contains(message)).toList();
		assertThat(lines).as("console lines with \"%s\"", message).hasSize(1);
		assertThat(lines.getFirst()).startsWith("{").endsWith("}");
		return JSON.readValue(lines.getFirst(), new TypeReference<>() { });
	}
}
