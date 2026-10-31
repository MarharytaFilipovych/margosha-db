package com.margosha.api;

import static com.margosha.api.util.PathConstants.REPLICATION_LOG;
import static com.margosha.api.util.PathConstants.REPLICATION_STATUS;
import static com.margosha.api.util.PathConstants.SHARDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import com.margosha.api.model.ApiError;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.ReplicaExists;
import com.margosha.api.model.ReplicaRecord;
import com.margosha.api.model.ReplicaRole;
import com.margosha.api.model.ReplicaStatus;
import com.margosha.api.model.ReplicationBatch;
import com.margosha.api.model.ReplicationEntry;
import com.margosha.api.model.ShardRegistration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class OpenApiSpecTest {

	private static final List<String> SPEC = load();

	@ParameterizedTest
	@EnumSource(ErrorCode.class)
	void everyErrorCodeIsListed(ErrorCode code) {
		// Arrange
		List<String> errorSchema = schema("Error");

		// Act
		boolean listed = errorSchema.stream().anyMatch(line -> line.trim().equals("- " + code));

		// Assert
		assertThat(listed).as("%s is listed in the Error schema", code).isTrue();
	}

	@ParameterizedTest
	@EnumSource(ReplicaRole.class)
	void everyReplicaRoleIsListed(ReplicaRole role) {
		// Arrange
		List<String> roleSchema = schema("ReplicaRole");

		// Act
		boolean listed = roleSchema.stream().anyMatch(line -> line.contains(role.argument()));

		// Assert
		assertThat(listed).isTrue();
	}

	static Stream<Arguments> models() {
		return Stream.of(
				Arguments.of(ShardRegistration.class, "ShardRegistration"),
				Arguments.of(ReplicaRecord.class, "ReplicaRecord"),
				Arguments.of(ReplicaExists.class, "ReplicaExists"),
				Arguments.of(ReplicationEntry.class, "ReplicationEntry"),
				Arguments.of(ReplicationBatch.class, "ReplicationBatch"),
				Arguments.of(ReplicaStatus.class, "ReplicaStatus"),
				Arguments.of(ApiError.class, "Error"));
	}

	@ParameterizedTest
	@MethodSource("models")
	void everyFieldOfAModelIsInItsSchema(Class<? extends Record> model, String schemaName) {
		// Arrange
		List<String> schema = schema(schemaName);

		// Act
		List<String> missing = Arrays.stream(model.getRecordComponents())
				.map(RecordComponent::getName)
				.filter(name -> schema.stream().noneMatch(line -> line.equals("        " + name + ":")))
				.toList();

		// Assert
		assertThat(missing).as("fields of %s missing from schema %s", model.getSimpleName(), schemaName).isEmpty();
	}

	@Test
	void replicaRegistrationAndReplicationEndpointsAreDescribed() {
		// Arrange
		List<String> paths = List.of(SHARDS, REPLICATION_LOG, REPLICATION_STATUS);

		// Act
		List<String> missing = paths.stream().filter(path -> !SPEC.contains("  " + path + ":")).toList();

		// Assert
		assertThat(missing).isEmpty();
	}

	private static List<String> schema(String name) {
		int start = SPEC.indexOf("    " + name + ":");
		assertThat(start).as("schema %s exists", name).isNotNegative();
		List<String> lines = new ArrayList<>();
		for (int i = start + 1; i < SPEC.size(); i++) {
			String line = SPEC.get(i);
			if (!line.isBlank() && !line.startsWith("      ")) {
				break;
			}
			lines.add(line);
		}
		return lines;
	}

	private static List<String> load() {
		try (InputStream in = OpenApiSpecTest.class.getResourceAsStream("/openapi.yaml")) {
			assertThat(in).as("openapi.yaml is on the classpath").isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
