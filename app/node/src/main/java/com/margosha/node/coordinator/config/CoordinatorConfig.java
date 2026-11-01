package com.margosha.node.coordinator.config;

import java.time.Duration;

import com.margosha.node.coordinator.service.RecordService;
import com.margosha.node.coordinator.service.ShardRegistryService;
import com.margosha.node.coordinator.service.TableRegistryService;
import com.margosha.node.coordinator.storage.RemoteShardStorageService;
import com.margosha.node.coordinator.storage.ShardRoutingService;
import com.margosha.node.storage.InMemoryRecordStorage;
import com.margosha.node.storage.RecordStorage;
import com.margosha.ring.RingSettings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("coordinator")
@EnableConfigurationProperties(CoordinatorSettings.class)
public class CoordinatorConfig {

	@Bean
	@ConditionalOnProperty(name = "margosha.mode", havingValue = "SINGLE")
	public RecordStorage localRecordStorage() {
		return new InMemoryRecordStorage();
	}

	@Bean
	@ConditionalOnProperty(name = "margosha.mode", havingValue = "SHARDED")
	public ShardRegistryService shardRegistryService(CoordinatorSettings settings) {
		Duration timeout = settings.shard().timeout();
		return new ShardRegistryService(RingSettings.load().newRing(), shard -> RemoteShardStorageService.connect(shard, timeout));
	}

	@Bean
	@ConditionalOnProperty(name = "margosha.mode", havingValue = "SHARDED")
	public RecordStorage shardRoutingService(ShardRegistryService shardRegistryService) {
		return new ShardRoutingService(shardRegistryService);
	}

	@Bean
	public RecordService recordService(TableRegistryService tableRegistryService, RecordStorage recordStorage) {
		return new RecordService(tableRegistryService, recordStorage);
	}
}
