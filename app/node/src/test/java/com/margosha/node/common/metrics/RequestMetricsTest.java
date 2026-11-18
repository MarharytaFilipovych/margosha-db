package com.margosha.node.common.metrics;

import static com.margosha.node.common.config.MetricsConfig.PREFIX;
import static com.margosha.node.data.TestData.ANN;
import static com.margosha.node.data.TestData.BOB_ATTRIBUTES;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.apiError;
import static com.margosha.node.data.TestData.client;
import static com.margosha.node.data.TestData.unique;
import static com.margosha.node.data.TestData.usersTable;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.exception.ApiException;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.TableRecord;
import com.margosha.node.annotation.SingleModeCoordinatorTest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

@SingleModeCoordinatorTest
class RequestMetricsTest {

	@LocalServerPort
	private int port;

	@Autowired
	private MeterRegistry registry;

	private CoordinatorClient client;
	private final String users = unique(USERS);

	@BeforeEach
	void setUp() {
		client = client(port);
		client.registerTable(usersTable(users));
		client.createRecord(users, ANN);
	}

	@Test
	void readAndUpdateLatenciesAreRecordedPerOperation() {
		// Arrange
		long readsBefore = count("read");
		long updatesBefore = count("update");

		// Act
		IntStream.range(0, 3).forEach(i -> client.readRecord(users, USER_42));
		IntStream.range(0, 2).forEach(i -> client.updateRecord(users, USER_42, BOB_ATTRIBUTES));

		// Assert
		assertThat(count(Operation.READ.tag()) - readsBefore).isEqualTo(3);
		assertThat(count(Operation.UPDATE.tag()) - updatesBefore).isEqualTo(2);
		Timer reads = registry.get(RequestMetrics.REQUESTS).tag("operation", Operation.READ.tag()).timer();
		assertThat(reads.getId().getTag("role")).isEqualTo("coordinator");
		assertThat(Arrays.stream(reads.takeSnapshot().percentileValues()).map(ValueAtPercentile::percentile))
				.containsExactly(0.5, 0.95, 0.99);
		assertThat(reads.max(TimeUnit.NANOSECONDS)).isPositive();
	}

	@Test
	void readPercentilesAreKeptForDurationsFromMicrosecondsToSeconds() {
		// Arrange
		client.readRecord(users, USER_42);
		Timer reads = registry.get(RequestMetrics.REQUESTS).tag("operation", Operation.READ.tag()).timer();
		long[] nanos = {1_000L, 600_000L, 3_000_000L, 40_000_000L, 2_500_000_000L};

		// Act
		IntStream.range(0, 1_000).forEach(i -> reads.record(nanos[i % nanos.length], TimeUnit.NANOSECONDS));
		reads.takeSnapshot();
		IntStream.range(0, 1_000).forEach(i -> reads.record(nanos[(i + 2) % nanos.length], TimeUnit.NANOSECONDS));
		ValueAtPercentile[] percentiles = reads.takeSnapshot().percentileValues();

		// Assert
		assertThat(percentiles[2].value(TimeUnit.MILLISECONDS)).isGreaterThan(1_000);
	}

	@Test
	void onlyTheStoresOwnMetersAreKept() {
		// Act
		client.readRecord(users, USER_42);

		// Assert
		assertThat(registry.getMeters()).extracting(Meter::getId).extracting(Meter.Id::getName)
				.isNotEmpty().allSatisfy(name -> assertThat(name).startsWith(PREFIX));
	}

	@ParameterizedTest
	@ValueSource(strings = {"/actuator", "/actuator/health", "/actuator/metrics", "/actuator/prometheus",
			"/actuator/env", "/actuator/info"})
	void noActuatorEndpointAnswers(String path) throws IOException, InterruptedException {
		try (HttpClient http = HttpClient.newHttpClient()) {

			// Act
			HttpResponse<String> response = http.send(
					HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
					HttpResponse.BodyHandlers.ofString());

			// Assert
			assertThat(response.statusCode()).isEqualTo(404);
		}
	}

	@Test
	void notFoundReadIncrementsOnlyTheNotFoundCount() {
		// Arrange
		Map<String, Double> before = errorCounts();

		// Act
		ApiException error = apiError(() -> client.readRecord(users, PrimaryKey.of("nobody")));

		// Assert
		assertThat(error.code()).isEqualTo(ErrorCode.RECORD_NOT_FOUND);
		Map<String, Double> after = errorCounts();
		assertThat(after.get("read/RECORD_NOT_FOUND") - before.getOrDefault("read/RECORD_NOT_FOUND", 0.0)).isEqualTo(1);
		after.forEach((counter, count) -> {
			if (!counter.equals("read/RECORD_NOT_FOUND")) {
				assertThat(count).as(counter).isEqualTo(before.getOrDefault(counter, 0.0));
			}
		});
	}

	@Test
	void errorsAreCountedUnderTheirOperationButNotOutsideRecordOperations() {
		// Arrange
		Map<String, Double> before = errorCounts();

		// Act
		ApiException invalid = apiError(() -> client.createRecord(users, TableRecord.of(Map.of("name", "Ann"))));
		ApiException tableExists = apiError(() -> client.registerTable(usersTable(users)));

		// Assert
		assertThat(invalid.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
		assertThat(tableExists.code()).isEqualTo(ErrorCode.TABLE_ALREADY_EXISTS);
		Map<String, Double> after = errorCounts();
		assertThat(after.get("create/VALIDATION_ERROR") - before.getOrDefault("create/VALIDATION_ERROR", 0.0))
				.isEqualTo(1);
		assertThat(after).doesNotContainKey("create/TABLE_ALREADY_EXISTS")
				.allSatisfy((counter, count) -> assertThat(counter).doesNotContain("TABLE_ALREADY_EXISTS"));
	}

	@Test
	void existsOnAMissingTableIsCountedUnderExists() {
		// Arrange
		Map<String, Double> before = errorCounts();

		// Act
		ApiException error = apiError(() -> client.recordExists(unique("ghost"), USER_42));

		// Assert
		assertThat(error.code()).isEqualTo(ErrorCode.TABLE_NOT_FOUND);
		assertThat(errorCounts().get("exists/TABLE_NOT_FOUND") - before.getOrDefault("exists/TABLE_NOT_FOUND", 0.0))
				.isEqualTo(1);
	}

	@Test
	void clientErrorIsNotCountedAsFailed() {
		// Arrange
		double before = failedCount();

		// Act
		ApiException error = apiError(() -> client.readRecord(users, PrimaryKey.of("nobody")));

		// Assert
		assertThat(error.code()).isEqualTo(ErrorCode.RECORD_NOT_FOUND);
		assertThat(failedCount()).isEqualTo(before);
	}

	private double failedCount() {
		Counter failed = registry.find(RequestMetrics.FAILED).counter();
		return failed == null ? 0 : failed.count();
	}

	private Map<String, Double> errorCounts() {
		return registry.find(RequestMetrics.ERRORS).counters().stream().collect(Collectors.toMap(
				counter -> counter.getId().getTag("operation") + "/" + counter.getId().getTag("code"), Counter::count));
	}

	private long count(String operation) {
		Timer timer = registry.find(RequestMetrics.REQUESTS).tag("operation", operation).timer();
		return timer == null ? 0 : timer.count();
	}
}
