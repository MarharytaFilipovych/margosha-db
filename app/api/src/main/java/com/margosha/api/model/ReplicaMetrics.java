package com.margosha.api.model;

import java.util.List;

public record ReplicaMetrics(List<MetricValue> metrics) {

	public ReplicaMetrics {
		metrics = List.copyOf(metrics);
	}
}
