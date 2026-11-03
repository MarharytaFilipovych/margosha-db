package com.margosha.node.coordinator.config;

import java.nio.file.Path;
import java.time.Duration;

import com.margosha.node.common.config.ReplicationProperties;
import com.margosha.node.common.storage.InMemoryRecordStorage;
import com.margosha.node.common.storage.RecordStorage;
import com.margosha.node.coordinator.service.QuorumReader;
import com.margosha.node.coordinator.service.RecordService;
import com.margosha.node.coordinator.service.ShardRegistryService;
import com.margosha.node.coordinator.service.TableRegistryService;
import com.margosha.node.coordinator.storage.RemoteReplica;
import com.margosha.node.coordinator.storage.ShardRoutingService;
import com.margosha.ring.RingSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("coordinator")
@EnableConfigurationProperties(CoordinatorSettings.class)
public class CoordinatorConfig {

	public static final String TABLES_FILE = "tables.log";

	@Bean(destroyMethod = "close")
	@ConditionalOnProperty(name = "margosha.mode", havingValue = "SINGLE")
	public TableRegistryService inMemoryTableRegistry() {
		return new TableRegistryService();
	}

	@Bean(destroyMethod = "close")
	@ConditionalOnProperty(name = "margosha.mode", havingValue = "SHARDED")
	public TableRegistryService durableTableRegistry(@Value("${margosha.data-dir}") String dataDir) {
		return TableRegistryService.durable(Path.of(dataDir, TABLES_FILE));
	}

	@Bean
	@ConditionalOnProperty(name = "margosha.mode", havingValue = "SINGLE")
	public RecordStorage localRecordStorage() {
		return new InMemoryRecordStorage();
	}

	@Bean
	@ConditionalOnProperty(name = "margosha.mode", havingValue = "SHARDED")
	public ShardRegistryService shardRegistryService(CoordinatorSettings settings, ReplicationProperties replication) {
		Duration timeout = settings.shard().timeout();
		return new ShardRegistryService(RingSettings.load().newRing(), replica -> RemoteReplica.connect(replica, timeout),
				settings.shard().skipAfterFailure(), replication.factor());
	}

	@Bean(destroyMethod = "close")
	@ConditionalOnProperty(name = "margosha.mode", havingValue = "SHARDED")
	public QuorumReader quorumReader(ReplicationProperties replication, CoordinatorSettings settings) {
		return new QuorumReader(replication.readQuorum(), settings.shard().readHedgeDelay());
	}

	@Bean
	@ConditionalOnProperty(name = "margosha.mode", havingValue = "SHARDED")
	public RecordStorage shardRoutingService(ShardRegistryService shardRegistryService, QuorumReader quorumReader) {
		return new ShardRoutingService(shardRegistryService, quorumReader);
	}

	@Bean
	public RecordService recordService(TableRegistryService tableRegistryService, RecordStorage recordStorage) {
		return new RecordService(tableRegistryService, recordStorage);
	}
}
