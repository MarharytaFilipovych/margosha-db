package com.margosha.node.coordinator.interceptor;

import java.io.IOException;

import com.margosha.api.util.TraceIds;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

@Slf4j
@RequiredArgsConstructor
public class ReplicaCallInterceptor implements ClientHttpRequestInterceptor {

	@NonNull
	private final String replicaId;

	@NullMarked
	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		String traceId = MDC.get(TraceIds.MDC_KEY);
		if (traceId != null) {
			request.getHeaders().set(TraceIds.HEADER, traceId);
		}
		long start = System.nanoTime();
		try {
			ClientHttpResponse response = execution.execute(request, body);
			int status = response.getStatusCode().value();
			long durationMs = millisSince(start);
			call(traceId, request, durationMs).addKeyValue("status", status)
					.log("Call to {}: {} {} {} in {} ms", replicaId, request.getMethod(),
							path(request), status, durationMs);
			return response;
		} catch (IOException | RuntimeException e) {
			long durationMs = millisSince(start);
			call(traceId, request, durationMs).addKeyValue("error", e.getClass().getSimpleName())
					.log("Call to {}: {} {} failed after {} ms: {}", replicaId, request.getMethod(), path(request),
							durationMs, e.getMessage());
			throw e;
		}
	}

	private LoggingEventBuilder call(@Nullable String traceId, HttpRequest request, long durationMs) {
		return log.atLevel(traceId != null ? Level.INFO : Level.DEBUG)
				.addKeyValue("toReplica", replicaId)
				.addKeyValue("method", request.getMethod().name())
				.addKeyValue("path", path(request))
				.addKeyValue("durationMs", durationMs);
	}

	private static String path(HttpRequest request) {
		return request.getURI().getRawPath();
	}

	private static long millisSince(long start) {
		return (System.nanoTime() - start) / 1_000_000;
	}
}
