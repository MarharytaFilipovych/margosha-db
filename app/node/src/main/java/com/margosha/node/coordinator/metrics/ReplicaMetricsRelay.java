package com.margosha.node.coordinator.metrics;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;

import com.margosha.api.model.MetricValue;
import com.margosha.api.model.MetricValue.MetricType;
import com.margosha.api.model.ReplicaMetrics;
import com.margosha.api.model.ShardRegistration;
import com.margosha.node.common.metrics.ClusterMetrics;
import com.margosha.node.coordinator.replica.ReplicaClient;
import com.margosha.node.coordinator.service.ShardRegistryService;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Replicas have no internet, so the coordinator reads their metrics and sends them on with its own. Each
 * replica value becomes a meter here with the replica's tags (role, shard, replica), and {@code margosha.replica.up}
 * says whether the last read worked.
 */
@Slf4j
public class ReplicaMetricsRelay implements AutoCloseable {

	private final ShardRegistryService shardRegistryService;
	private final MeterRegistry registry;
	private final ScheduledExecutorService scheduler;

	/** Replica ID → whether its last read worked (1) or not (0). */
	private final Map<String, AtomicInteger> up = new ConcurrentHashMap<>();
	/** Replica ID → what the coordinator keeps for each of its meters. */
	private final Map<String, Map<MeterKey, ReplicaValue>> values = new ConcurrentHashMap<>();

	public ReplicaMetricsRelay(@NonNull ShardRegistryService shardRegistryService, @NonNull MeterRegistry registry,
							   @NonNull Duration interval) {
		this.shardRegistryService = shardRegistryService;
		this.registry = registry;
		this.scheduler = Executors.newSingleThreadScheduledExecutor(
				Thread.ofPlatform().name("metrics-relay").daemon().factory());
		scheduler.scheduleWithFixedDelay(this::readAll, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
	}

	/** Reads every registered replica once; a replica that doesn't answer is marked down. */
	public void readAll() {
		for (ReplicaClient replica : shardRegistryService.replicas()) {
			try {
				read(replica);
			} catch (RuntimeException e) {
				log.warn("Could not relay the metrics of replica {}", replica.registration().replicaId(), e);
			}
		}
	}

	private void read(ReplicaClient replica) {
		ShardRegistration registration = replica.registration();
		AtomicInteger isUp = up.computeIfAbsent(registration.replicaId(), id -> registerUp(registration));
		Map<MeterKey, ReplicaValue> own = values.computeIfAbsent(registration.replicaId(), id -> new ConcurrentHashMap<>());
		ReplicaMetrics metrics;
		try {
			metrics = replica.metrics();
		} catch (RuntimeException e) {
			if (isUp.getAndSet(0) == 1) {
				log.warn("Replica {} of shard {} did not send its metrics: {}", registration.replicaId(),
						registration.shardId(), e.getMessage());
			}
			own.values().forEach(ReplicaValue::noValue);
			return;
		}
		if (isUp.getAndSet(1) == 0) {
			log.info("Replica {} of shard {} sends its metrics", registration.replicaId(), registration.shardId());
		}
		Set<MeterKey> seen = new HashSet<>();
		for (MetricValue value : metrics.metrics()) {
			MeterKey key = new MeterKey(value.name(), value.type(), value.tags());
			seen.add(key);
			own.computeIfAbsent(key, k -> register(value)).update(value.value());
		}
		own.forEach((key, replicaValue) -> {
			if (!seen.contains(key)) {
				replicaValue.noValue();
			}
		});
	}

	private AtomicInteger registerUp(ShardRegistration replica) {
		AtomicInteger value = new AtomicInteger();
		Gauge.builder(ClusterMetrics.UP, value, AtomicInteger::get)
				.description("1 when the coordinator could read the replica's metrics, 0 when it could not")
				.tag("role", replica.role().argument())
				.tag("shard", replica.shardId())
				.tag("replica", replica.replicaId())
				.strongReference(true)
				.register(registry);
		return value;
	}

	private ReplicaValue register(MetricValue value) {
		ReplicaValue replicaValue = new ReplicaValue(value.type());
		Tags tags = Tags.of(value.tags().entrySet().stream().map(tag -> Tag.of(tag.getKey(), tag.getValue())).toList());
		switch (value.type()) {
			case GAUGE -> Gauge.builder(value.name(), replicaValue, ReplicaValue::gauge)
					.tags(tags)
					.baseUnit(value.unit())
					.strongReference(true)
					.register(registry);
			case COUNTER -> FunctionCounter.builder(value.name(), replicaValue, ReplicaValue::total)
					.tags(tags)
					.register(registry);
		}
		return replicaValue;
	}

	@Override
	public void close() {
		scheduler.shutdownNow();
	}

	/** Which replica meter a value belongs to: the same name, type and tags on every read. */
	private record MeterKey(String name, MetricType type, Map<String, String> tags) { }

	/**
	 * What the coordinator keeps for one replica meter between reads: the gauge's latest value, or the counter's total.
	 * The relay thread writes it, and the meter registered for it reads it when metrics are sent.
	 */
	private static final class ReplicaValue {

		private final MetricType type;
		private volatile double gauge = Double.NaN;
		private final DoubleAdder total = new DoubleAdder();
		private double lastSeen = Double.NaN;

		private ReplicaValue(MetricType type) {
			this.type = type;
		}

		void update(double value) {
			if (type == MetricType.GAUGE) {
				gauge = value;
				return;
			}
			if (!Double.isNaN(lastSeen)) {
				total.add(value >= lastSeen ? value - lastSeen : value);
			}
			lastSeen = value;
		}

		void noValue() {
			gauge = Double.NaN;
		}

		double gauge() {
			return gauge;
		}

		double total() {
			return total.sum();
		}
	}
}
