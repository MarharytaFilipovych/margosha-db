package com.margosha.node.common.config;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse;

@RequiredArgsConstructor
public class CountAndPercentilesOnly implements CloudWatchAsyncClient {

	private static final List<String> DROPPED = List.of(".sum", ".avg", ".max");

	@NonNull
	private final CloudWatchAsyncClient cloudWatch;

	@Override
	public CompletableFuture<PutMetricDataResponse> putMetricData(PutMetricDataRequest request) {
		List<MetricDatum> kept = request.metricData().stream().filter(CountAndPercentilesOnly::isKept).toList();
		if (kept.isEmpty()) {
			return CompletableFuture.completedFuture(PutMetricDataResponse.builder().build());
		}
		return cloudWatch.putMetricData(request.toBuilder().metricData(kept).build());
	}

	static boolean isKept(MetricDatum datum) {
		return DROPPED.stream().noneMatch(datum.metricName()::endsWith);
	}

	@Override
	public String serviceName() {
		return cloudWatch.serviceName();
	}

	@Override
	public void close() {
		cloudWatch.close();
	}
}
