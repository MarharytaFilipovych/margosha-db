package com.margosha.node.common.filter;

import static com.margosha.api.util.PathConstants.REPLICATION_LOG;
import static com.margosha.api.util.PathConstants.REPLICATION_STATUS;
import static com.margosha.api.util.PathConstants.SHARDS;

import java.io.IOException;
import java.util.Set;

import com.margosha.api.util.TraceIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

	/**
	 * Requests the nodes send each other all the time (followers polling the log, replicas re-registering)
	 */
	private static final Set<String> BACKGROUND_PATHS = Set.of(REPLICATION_LOG, REPLICATION_STATUS, SHARDS);

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long start = System.nanoTime();
		String traceId = TraceIds.fromHeader(request.getHeader(TraceIds.HEADER));
		MDC.put(TraceIds.MDC_KEY, traceId);
		response.setHeader(TraceIds.HEADER, traceId);
		try {
			chain.doFilter(request, response);
		} finally {
			long durationMs = (System.nanoTime() - start) / 1_000_000;
			String path = request.getRequestURI();
			log.atLevel(BACKGROUND_PATHS.contains(path) ? Level.DEBUG : Level.INFO)
					.addKeyValue("method", request.getMethod())
					.addKeyValue("path", path)
					.addKeyValue("status", response.getStatus())
					.addKeyValue("durationMs", durationMs)
					.log("{} {} {} in {} ms", request.getMethod(), path, response.getStatus(), durationMs);
			MDC.remove(TraceIds.MDC_KEY);
		}
	}
}
