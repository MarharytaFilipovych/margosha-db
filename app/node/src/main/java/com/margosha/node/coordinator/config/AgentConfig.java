package com.margosha.node.coordinator.config;

import com.margosha.node.coordinator.agent.AgentAccess;
import com.margosha.node.coordinator.agent.AgentKeyFilter;
import com.margosha.node.coordinator.agent.AgentKeyReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.util.StringUtils.hasText;

@Slf4j
@Configuration(proxyBeanMethods = false)
@Profile("coordinator")
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfig {

	@Bean
	public AgentAccess agentAccess(AgentProperties agent, ObjectProvider<SsmAsyncClient> ssm) {
		String key = agent.key();
		if (hasText(agent.region())) {
			SsmAsyncClient provided = ssm.getIfAvailable();
			if (provided != null) {
				key = new AgentKeyReader(provided).read(agent.keyParameter());
			} else {
				try (SsmAsyncClient client = SsmAsyncClient.builder().region(Region.of(agent.region())).build()) {
					key = new AgentKeyReader(client).read(agent.keyParameter());
				}
			}
		}
		AgentAccess access = new AgentAccess(key, agent.allowedTables());
		log.info("Agent requests: {}; allowed tables: {}", access.hasKey() ? "key set" : "no key, all refused",
				access.getAllowedTables());
		return access;
	}

	@Bean
	public AgentKeyFilter agentKeyFilter(AgentAccess agentAccess, JsonMapper jsonMapper) {
		return new AgentKeyFilter(agentAccess, jsonMapper);
	}
}
