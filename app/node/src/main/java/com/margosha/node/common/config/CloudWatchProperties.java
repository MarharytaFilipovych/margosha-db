package com.margosha.node.common.config;

import java.time.Duration;

import io.micrometer.cloudwatch2.CloudWatchConfig;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

@NullMarked
@ConfigurationProperties("margosha.metrics.cloudwatch")
public record CloudWatchProperties(boolean enabled, String namespace, Duration step) implements CloudWatchConfig {

	@Override
	public @Nullable String get(String key) {
		return null;
	}
}
