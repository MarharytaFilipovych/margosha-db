package com.margosha.node.common.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.margosha.api.model.MetricValue;
import com.margosha.api.model.MetricValue.MetricType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;

public final class MetricsSnapshot {

	public static final String MILLISECONDS = "milliseconds";

	private MetricsSnapshot() { }

	public static List<MetricValue> of(MeterRegistry registry) {
		List<MetricValue> values = new ArrayList<>();
		for (Meter meter : registry.getMeters()) {
			Meter.Id id = meter.getId();
			switch (meter) {
				case TimeGauge gauge -> gauge(values, id, gauge.value(TimeUnit.MILLISECONDS), MILLISECONDS);
				case Gauge gauge -> gauge(values, id, gauge.value(), id.getBaseUnit());
				case Counter counter -> values.add(value(id, MetricType.COUNTER, counter.count(), null));
				case Timer timer -> values.add(value(id, MetricType.COUNTER, timer.count(), null));
				default -> { }
			}
		}
		return values;
	}

	private static void gauge(List<MetricValue> values, Meter.Id id, double value, String unit) {
		if (Double.isFinite(value)) {
			values.add(value(id, MetricType.GAUGE, value, unit));
		}
	}

	private static MetricValue value(Meter.Id id, MetricType type, double value, String unit) {
		Map<String, String> tags = id.getTags().stream().collect(Collectors.toMap(Tag::getKey, Tag::getValue));
		return new MetricValue(id.getName(), type, tags, value, unit);
	}
}
