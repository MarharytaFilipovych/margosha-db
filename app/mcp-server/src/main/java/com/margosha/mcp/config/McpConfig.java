package com.margosha.mcp.config;

import static org.springframework.util.StringUtils.hasText;

import java.util.List;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.mcp.tools.RecordToolSpecifications;
import com.margosha.mcp.tools.RecordTools;
import com.margosha.mcp.tools.ToolLimits;
import com.margosha.mcp.tools.config.AgentCoordinatorClient;

@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpConfig {

	@Bean
	public CoordinatorClient coordinatorClient(McpProperties properties) {
		return AgentCoordinatorClient.create(properties.coordinatorUrl(), properties.timeout(), properties.agentKey());
	}

	@Bean
	public List<SyncToolSpecification> recordTools(CoordinatorClient coordinatorClient, McpProperties properties) {
		ToolLimits limits = new ToolLimits(properties.allowedTables(), hasText(properties.agentKey()));
		return RecordToolSpecifications.of(new RecordTools(coordinatorClient, limits));
	}
}
