package com.margosha.node.coordinator.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "margosha")
public record CoordinatorSettings(Shard shard, Metrics metrics) {

	public record Shard(Duration timeout, Duration readHedgeDelay, Duration skipAfterFailure) { }

	public record Metrics(Duration relayInterval, @DefaultValue List<String> expectedShards,
			Duration expectedReplicaGrace) { }
}
