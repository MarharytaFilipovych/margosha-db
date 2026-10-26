package com.margosha.node.config;

import com.margosha.node.service.RecordService;
import com.margosha.node.service.TableRegistryService;
import com.margosha.node.storage.RecordStorage;
import com.margosha.node.storage.InMemoryRecordStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("coordinator")
public class CoordinatorConfiguration {

	@Bean
	public TableRegistryService tableRegistry() {
		return new TableRegistryService();
	}

	@Bean
	@ConditionalOnProperty(name = "margosha.mode", havingValue = "SINGLE")
	public RecordStorage recordStorage() {
		return new InMemoryRecordStore();
	}

	@Bean
	public RecordService recordService(TableRegistryService tableRegistryService, RecordStorage recordStorage) {
		return new RecordService(tableRegistryService, recordStorage);
	}
}
