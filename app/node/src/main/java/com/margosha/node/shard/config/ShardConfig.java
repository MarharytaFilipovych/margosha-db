package com.margosha.node.shard.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.util.RestClientBuilders;
import com.margosha.node.shard.service.CoordinatorRegistrationService;
import com.margosha.node.shard.service.RetryBackoff;
import com.margosha.node.storage.InMemoryRecordStorage;
import com.margosha.node.storage.RecordStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import static org.springframework.util.StringUtils.hasText;

@Configuration(proxyBeanMethods = false)
@Profile("shard")
public class ShardConfig {

	@Bean
	public RecordStorage recordStorage() {
		return new InMemoryRecordStorage();
	}

	@Bean
	@ConditionalOnProperty(name = "margosha.coordinator.url")
	public CoordinatorRegistrationService coordinatorRegistrationService(
			@Value("${margosha.coordinator.url}") String coordinatorUrl,
			@Value("${margosha.shard.id}") String shardId,
			@Value("${margosha.shard.host:}") String host,
			@Value("${margosha.shard.register-timeout}") Duration timeout,
			@Value("${margosha.shard.register-retry-interval}") Duration retryInterval,
			@Value("${margosha.shard.register-retry-max-interval}") Duration maxRetryInterval,
			@Value("${margosha.shard.register-refresh-interval}") Duration refreshInterval) {
		CoordinatorClient coordinator = new CoordinatorClient(RestClientBuilders.withTimeout(timeout), coordinatorUrl);
		return new CoordinatorRegistrationService(coordinator, shardId, hasText(host) ? host : localAddress(),
				new RetryBackoff(retryInterval, maxRetryInterval), refreshInterval);
	}

	private static String localAddress() {
		try {
			return InetAddress.getLocalHost().getHostAddress();
		} catch (UnknownHostException e) {
			throw new IllegalStateException("Cannot find this shard's address; set margosha.shard.host", e);
		}
	}
}
