package com.margosha.node.common.config;

import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

import java.util.Objects;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CloudWatchProperties.class)
public class MetricsConfig {

	public static final String PREFIX = "margosha.";

	@Bean
	public MeterFilter onlyStoreMeters() {
		return MeterFilter.denyUnless(id -> id.getName().startsWith(PREFIX));
	}

	@Bean(destroyMethod = "close")
	@ConditionalOnExpression("!'${margosha.metrics.cloudwatch.region:}'.isEmpty()")
	public CloudWatchMeterRegistry cloudWatchMeterRegistry(CloudWatchProperties cloudWatch,
			ObjectProvider<CloudWatchAsyncClient> client) {
		CloudWatchAsyncClient cloudWatchClient = client.getIfAvailable(
				() -> CloudWatchAsyncClient.builder().region(Region.of(Objects.requireNonNull(cloudWatch.region()))).build());
		return new CloudWatchMeterRegistry(cloudWatch, Clock.SYSTEM, new CountAndPercentilesOnly(cloudWatchClient));
	}
}
