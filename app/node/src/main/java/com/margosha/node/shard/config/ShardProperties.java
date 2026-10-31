package com.margosha.node.shard.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("margosha.shard")
public record ShardProperties(String id, String host, Duration registerTimeout, Duration registerRetryInterval,
		Duration registerRetryMaxInterval, Duration registerRefreshInterval) { }
