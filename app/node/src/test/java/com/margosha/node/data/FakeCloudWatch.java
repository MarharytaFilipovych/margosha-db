package com.margosha.node.data;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse;

public class FakeCloudWatch implements CloudWatchAsyncClient {

	public static final String SOURCE = "--spring.main.sources=" + FakeCloudWatch.class.getName();

	private final List<MetricDatum> sent = new CopyOnWriteArrayList<>();

	@Override
	public CompletableFuture<PutMetricDataResponse> putMetricData(PutMetricDataRequest request) {
		sent.addAll(request.metricData());
		return CompletableFuture.completedFuture(PutMetricDataResponse.builder().build());
	}

	public List<MetricDatum> sent() {
		return List.copyOf(sent);
	}

	public Optional<Double> last(String metricName, String dimension, String value) {
		List<MetricDatum> matching = sent.stream()
				.filter(datum -> datum.metricName().equals(metricName) && has(datum, dimension, value))
				.toList();
		return matching.isEmpty() ? Optional.empty() : Optional.of(matching.getLast().value());
	}

	public static boolean has(MetricDatum datum, String dimension, String value) {
		return datum.dimensions().contains(Dimension.builder().name(dimension).value(value).build());
	}

	@Override
	public String serviceName() {
		return SERVICE_NAME;
	}

	@Override
	public void close() { }
}
