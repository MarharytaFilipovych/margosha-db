package com.margosha.node.shard.config;

import java.net.InetAddress;
import java.net.UnknownHostException;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.util.RestClientBuilder;
import com.margosha.node.shard.service.CoordinatorRegistrationService;
import com.margosha.node.shard.service.RetryBackoff;
import com.margosha.node.storage.InMemoryRecordStorage;
import com.margosha.node.storage.RecordStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import static org.springframework.util.StringUtils.hasText;

@Configuration(proxyBeanMethods = false)
@Profile("shard")
@EnableConfigurationProperties(ShardSettings.class)
public class ShardConfig {

	@Bean
	public RecordStorage recordStorage() {
		return new InMemoryRecordStorage();
	}

	@Bean
	@ConditionalOnProperty(name = "margosha.coordinator.url")
	public CoordinatorRegistrationService coordinatorRegistrationService(ShardSettings settings) {
		ShardSettings.Shard shard = settings.shard();
		CoordinatorClient coordinator = new CoordinatorClient(
				RestClientBuilder.withTimeout(shard.registerTimeout()), settings.coordinator().url());
		return new CoordinatorRegistrationService(coordinator, shard.id(),
				hasText(shard.host()) ? shard.host() : localAddress(),
				new RetryBackoff(shard.registerRetryInterval(), shard.registerRetryMaxInterval()),
				shard.registerRefreshInterval());
	}

	private static String localAddress() {
		try {
			return InetAddress.getLocalHost().getHostAddress();
		} catch (UnknownHostException e) {
			throw new IllegalStateException("Cannot find this shard's address; set margosha.shard.host", e);
		}
	}
}
