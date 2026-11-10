package com.margosha.mcp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.util.RestClientBuilder;

@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpConfig {

	@Bean
	public CoordinatorClient coordinatorClient(McpProperties properties) {
		return new CoordinatorClient(RestClientBuilder.withTimeout(properties.timeout()), properties.coordinatorUrl());
	}
}
