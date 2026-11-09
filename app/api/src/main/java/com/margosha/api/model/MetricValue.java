package com.margosha.api.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetricValue(String name, MetricType type, Map<String, String> tags, double value, String unit) {

	public MetricValue {
		tags = Map.copyOf(tags);
	}

	public enum MetricType { COUNTER, GAUGE }
}
