package com.margosha.mcp.config;

import java.util.List;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.util.RestClientBuilder;
import com.margosha.mcp.tools.RecordToolSpecifications;
import com.margosha.mcp.tools.RecordTools;

@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpConfig {

	@Bean
	public CoordinatorClient coordinatorClient(McpProperties properties) {
		return new CoordinatorClient(RestClientBuilder.withTimeout(properties.timeout()), properties.coordinatorUrl());
	}

	@Bean
	public List<SyncToolSpecification> recordTools(CoordinatorClient coordinatorClient) {
		return RecordToolSpecifications.of(new RecordTools(coordinatorClient));
	}
}
