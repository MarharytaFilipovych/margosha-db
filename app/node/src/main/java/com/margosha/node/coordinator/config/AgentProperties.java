package com.margosha.node.coordinator.config;

import java.util.List;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

@NullMarked
@ConfigurationProperties("margosha.agent")
public record AgentProperties(@Nullable String key, @Nullable String region, String keyParameter,
		List<String> allowedTables) {

	@Override
	public String toString() {
		return "AgentProperties[region=" + region + ", keyParameter=" + keyParameter + ", allowedTables="
				+ allowedTables + "]";
	}
}
