package com.margosha.node.coordinator.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "margosha")
public record CoordinatorSettings(Shard shard, Metrics metrics) {

	public record Shard(Duration timeout, Duration readHedgeDelay, Duration skipAfterFailure) { }

	public record Metrics(Duration relayInterval) { }
}
