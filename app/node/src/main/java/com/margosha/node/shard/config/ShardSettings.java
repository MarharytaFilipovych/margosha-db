package com.margosha.node.shard.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "margosha")
public record ShardSettings(Coordinator coordinator, Shard shard) {

	public record Coordinator(String url) { }

	public record Shard(String id,
						String host,
						Duration registerTimeout,
						Duration registerRetryInterval,
						Duration registerRetryMaxInterval,
						Duration registerRefreshInterval) { }
}
