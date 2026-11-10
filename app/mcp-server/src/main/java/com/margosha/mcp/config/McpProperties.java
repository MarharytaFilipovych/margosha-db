package com.margosha.mcp.config;

import java.time.Duration;
import java.util.List;

import lombok.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("margosha.mcp")
public record McpProperties(@NonNull String coordinatorUrl, @NonNull Duration timeout,
							@Nullable String agentKey, @NonNull List<String> allowedTables) {

	@NonNull @Override
	public String toString() {
		return "McpProperties{" +
				"coordinatorUrl='" + coordinatorUrl + '\'' +
				", timeout=" + timeout +
				", allowedTables=" + allowedTables +
				'}';
	}
}
