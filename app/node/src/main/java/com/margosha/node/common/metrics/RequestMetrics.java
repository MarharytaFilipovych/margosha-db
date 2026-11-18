package com.margosha.node.common.metrics;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.margosha.api.model.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RequestMetrics {

	public static final String REQUESTS = "margosha.requests";
	public static final String ERRORS = "margosha.errors";
	public static final String FAILED = "margosha.requests.failed";
	public static final String SHARD_REQUESTS = "margosha.shard.requests";

	private final MeterRegistry registry;
	private final Map<Operation, Timer> timers = new ConcurrentHashMap<>();

	public <T> T time(Operation operation, Supplier<T> request) {
		long start = System.nanoTime();
		try {
			return request.get();
		} finally {
			timer(operation).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
		}
	}

	public void time(Operation operation, Runnable request) {
		time(operation, () -> {
			request.run();
			return null;
		});
	}

	public void error(Operation operation, ErrorCode code) {
		Counter.builder(ERRORS)
				.description("Record requests this node answered with an error")
				.tag("operation", operation.tag())
				.tag("code", code.name())
				.register(registry)
				.increment();
	}

	public void failed() {
		Counter.builder(FAILED)
				.description("Record requests this node answered with a 5xx, all operations together")
				.register(registry)
				.increment();
	}

	public void shardRequest(String shardId) {
		Counter.builder(SHARD_REQUESTS)
				.description("Client requests the coordinator sent to the shard")
				.tag("shard", shardId)
				.register(registry)
				.increment();
	}

	private Timer timer(Operation operation) {
		return timers.computeIfAbsent(operation, op -> Timer.builder(REQUESTS)
				.description("Record requests this node handled")
				.tag("operation", op.tag())
				.publishPercentiles(0.5, 0.95, 0.99)
				.distributionStatisticExpiry(Duration.ofMinutes(1))
				.distributionStatisticBufferLength(2)
				.register(registry));
	}
}
