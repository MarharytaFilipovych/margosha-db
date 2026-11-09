package com.margosha.node.common.config;

import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CloudWatchProperties.class)
public class MetricsConfig {

	public static final String PREFIX = "margosha.";

	@Bean
	public MeterFilter onlyStoreMeters() {
		return MeterFilter.denyUnless(id -> id.getName().startsWith(PREFIX));
	}

	@Bean(destroyMethod = "close")
	@ConditionalOnProperty(name = "margosha.metrics.cloudwatch.enabled", havingValue = "true")
	public CloudWatchMeterRegistry cloudWatchMeterRegistry(CloudWatchProperties cloudWatch) {
		return new CloudWatchMeterRegistry(cloudWatch, Clock.SYSTEM, CloudWatchAsyncClient.create());
	}
}
