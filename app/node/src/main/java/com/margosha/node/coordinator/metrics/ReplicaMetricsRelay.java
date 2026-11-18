package com.margosha.node.coordinator.metrics;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.DoubleAdder;

import com.margosha.api.model.MetricValue;
import com.margosha.api.model.MetricValue.MetricType;
import com.margosha.api.model.ReplicaMetrics;
import com.margosha.api.model.ReplicaRole;
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

	/** Replica ID → whether its last read worked (1) or not (0), or no value yet. */
	private final Map<String, UpValue> up = new ConcurrentHashMap<>();
	/** Replica ID → what the coordinator keeps for each of its meters. */
	private final Map<String, Map<MeterKey, ReplicaValue>> values = new ConcurrentHashMap<>();
	/** The replicas this coordinator has read at least once since it started. */
	private final Set<String> readBefore = ConcurrentHashMap.newKeySet();
	/** Until then an expected replica that hasn't registered yet has no value. After it, it counts as down. */
	private final long graceEndsAt;

	public ReplicaMetricsRelay(@NonNull ShardRegistryService shardRegistryService, @NonNull MeterRegistry registry,
							   @NonNull Duration interval, @NonNull List<ExpectedReplica> expected,
							   @NonNull Duration grace) {
		this.shardRegistryService = shardRegistryService;
		this.registry = registry;
		this.graceEndsAt = System.nanoTime() + grace.toNanos();
		expected.forEach(replica -> up.computeIfAbsent(replica.replicaId(),
				id -> registerUp(replica.shardId(), replica.replicaId(), replica.role())));
		this.scheduler = Executors.newSingleThreadScheduledExecutor(
				Thread.ofPlatform().name("metrics-relay").daemon().factory());
		scheduler.scheduleWithFixedDelay(this::readAll, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
	}

	/**
	 * Reads every registered replica once. A replica that doesn't answer is marked down, and so is an expected replica
	 * that still hasn't registered once the grace time is over.
	 */
	public void readAll() {
		for (ReplicaClient replica : shardRegistryService.replicas()) {
			try {
				read(replica);
			} catch (RuntimeException e) {
				log.warn("Could not relay the metrics of replica {}", replica.registration().replicaId(), e);
			}
		}
		if (System.nanoTime() - graceEndsAt >= 0) {
			up.forEach((replicaId, value) -> {
				if (value.downIfUnknown()) {
					log.warn("Replica {} has not registered since the coordinator started", replicaId);
				}
			});
		}
	}

	private void read(ReplicaClient replica) {
		ShardRegistration registration = replica.registration();
		UpValue isUp = up.computeIfAbsent(registration.replicaId(),
				id -> registerUp(registration.shardId(), registration.replicaId(), registration.role()));
		Map<MeterKey, ReplicaValue> own = values.computeIfAbsent(registration.replicaId(), id -> new ConcurrentHashMap<>());
		ReplicaMetrics metrics;
		try {
			metrics = replica.metrics();
		} catch (RuntimeException e) {
			if (isUp.set(0) != 0) {
				log.warn("Replica {} of shard {} did not send its metrics: {}", registration.replicaId(),
						registration.shardId(), e.getMessage());
			}
			own.values().forEach(ReplicaValue::noValue);
			return;
		}
		if (isUp.set(1) != 1) {
			log.info("Replica {} of shard {} sends its metrics", registration.replicaId(), registration.shardId());
		}
		// A counter that was not there at an earlier read started at 0 since then, so all of its value is new. On the
		// first read of a replica (after the coordinator starts), a counter may hold the replica's whole history, which
		// was already counted before, so only its later growth is counted.
		boolean newCountersStartedAtZero = !readBefore.add(registration.replicaId());
		Set<MeterKey> seen = new HashSet<>();
		for (MetricValue value : metrics.metrics()) {
			MeterKey key = new MeterKey(value.name(), value.type(), value.tags());
			seen.add(key);
			own.computeIfAbsent(key, k -> register(value, newCountersStartedAtZero)).update(value.value());
		}
		own.forEach((key, replicaValue) -> {
			if (!seen.contains(key)) {
				replicaValue.noValue();
			}
		});
	}

	private UpValue registerUp(String shardId, String replicaId, ReplicaRole role) {
		UpValue value = new UpValue();
		Gauge.builder(ClusterMetrics.UP, value, UpValue::get)
				.description("1 when the coordinator could read the replica's metrics, 0 when it could not")
				.tag("role", role.argument())
				.tag("shard", shardId)
				.tag("replica", replicaId)
				.strongReference(true)
				.register(registry);
		return value;
	}

	private ReplicaValue register(MetricValue value, boolean startedAtZero) {
		ReplicaValue replicaValue = new ReplicaValue(value.type(), startedAtZero);
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

	private static final class UpValue {

		private volatile double value = Double.NaN;

		double get() {
			return value;
		}

		synchronized double set(double newValue) {
			double before = value;
			value = newValue;
			return before;
		}

		synchronized boolean downIfUnknown() {
			if (!Double.isNaN(value)) {
				return false;
			}
			value = 0;
			return true;
		}
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
		/** The counter's value at the previous read: unknown (NaN) on a first read, 0 when it started at 0. */
		private double lastSeen;

		private ReplicaValue(MetricType type, boolean startedAtZero) {
			this.type = type;
			this.lastSeen = startedAtZero ? 0 : Double.NaN;
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
