package com.margosha.mcp.tools.config;

import static org.springframework.util.StringUtils.hasText;

import java.time.Duration;

import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.util.AgentKeys;
import com.margosha.api.util.RestClientBuilder;
import com.margosha.api.util.TraceIds;

@UtilityClass
public class AgentCoordinatorClient {

	public CoordinatorClient create(@NonNull String coordinatorUrl, @NonNull Duration timeout,
			@Nullable String agentKey) {
		RestClient.Builder builder = RestClientBuilder.withTimeout(timeout)
				.requestInterceptor((request, body, execution) -> {
					String traceId = MDC.get(TraceIds.MDC_KEY);
					if (traceId != null) {
						request.getHeaders().set(TraceIds.HEADER, traceId);
					}
					return execution.execute(request, body);
				});
		if (hasText(agentKey)) {
			builder.defaultHeader(AgentKeys.HEADER, agentKey);
		}
		return new CoordinatorClient(builder, coordinatorUrl);
	}
}
