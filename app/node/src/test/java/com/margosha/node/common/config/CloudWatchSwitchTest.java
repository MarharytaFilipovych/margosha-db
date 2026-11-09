package com.margosha.node.common.config;

import static com.margosha.node.data.TestData.testDataDir;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.margosha.node.data.FakeCloudWatch;
import com.margosha.node.data.InProcessNode;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CloudWatchSwitchTest {

	@TempDir
	Path folder;

	@Test
	void stackEnvFileWithARegionTurnsTheCloudWatchPushOn() throws IOException {
		// Arrange
		Path stackEnv = Files.writeString(folder.resolve("stack.env"),
				"BUCKET=bucket\nPORT=8080\nREGION=us-east-1\nDATA_DIR=/var/lib/margosha\n");

		// Act
		try (InProcessNode coordinator = InProcessNode.replicatedCoordinator(0, testDataDir("coordinator"),
				"--spring.config.import=optional:file:" + stackEnv + "[.properties]", FakeCloudWatch.SOURCE)) {

			// Assert
			assertThat(coordinator.meterRegistry()).isInstanceOf(CloudWatchMeterRegistry.class);
			assertThat(coordinator.bean(CloudWatchProperties.class).region()).isEqualTo("us-east-1");
		}
	}

	@Test
	void withoutTheStackEnvFileNothingIsSent() {
		// Act
		try (InProcessNode coordinator = InProcessNode.replicatedCoordinator(0, testDataDir("coordinator"))) {

			// Assert
			assertThat(coordinator.meterRegistry()).isNotInstanceOf(CloudWatchMeterRegistry.class);
		}
	}
}
