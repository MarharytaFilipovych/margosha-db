package com.margosha.node.shard.config;

import static com.margosha.api.util.RestClientBuilder.withTimeout;
import static org.springframework.util.StringUtils.hasText;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.client.ShardClient;
import com.margosha.node.common.config.ReplicationProperties;
import com.margosha.node.common.storage.RecordStorage;
import com.margosha.node.shard.service.CoordinatorRegistrationService;
import com.margosha.node.shard.service.RetryBackoff;
import com.margosha.node.shard.replication.follower.FollowerRecordStorage;
import com.margosha.node.shard.replication.follower.FollowerReplicator;
import com.margosha.node.shard.replication.leader.LeaderRecordStorage;
import com.margosha.node.shard.replication.leader.ReplicationLeader;
import com.margosha.node.shard.replication.model.ReplicaIdentity;
import com.margosha.node.shard.replication.storage.ReplicaStorage;
import com.margosha.node.shard.service.ReplicaReadService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("shard")
@EnableConfigurationProperties({ShardProperties.class, ReplicaProperties.class})
public class ShardConfig {

	private static final Duration FETCH_TIMEOUT_MARGIN = Duration.ofSeconds(5);

	@Bean
	public ReplicaIdentity replicaIdentity(ShardProperties shard, ReplicaProperties replica) {
		return new ReplicaIdentity(shard.id(), replica.id(), replica.role());
	}

	@Bean(destroyMethod = "close")
	public ReplicaStorage replicaStorage(@Value("${margosha.data-dir}") String dataDir) {
		return ReplicaStorage.open(Path.of(dataDir));
	}

	@Bean
	public ReplicaReadService replicaReadService(ReplicaStorage storage) {
		return new ReplicaReadService(storage);
	}

	@Bean
	@ConditionalOnProperty(name = "margosha.replica.role", havingValue = "leader")
	public ReplicationLeader replicationLeader(ReplicaStorage storage, ReplicaIdentity identity,
			ReplicationProperties replication) {
		storage.ensureLogId();
		return new ReplicationLeader(storage, identity.shardId(), identity.replicaId(),
				followerIds(identity, replication.factor()), replication.followerConfirmations(),
				replication.ackTimeout(), replication.longPoll());
	}

	static Set<String> followerIds(ReplicaIdentity leader, int factor) {
		return IntStream.rangeClosed(1, factor)
				.mapToObj(number -> leader.shardId() + "-" + number)
				.filter(id -> !id.equals(leader.replicaId()))
				.collect(Collectors.toUnmodifiableSet());
	}

	@Bean
	@ConditionalOnProperty(name = "margosha.replica.role", havingValue = "follower")
	public FollowerReplicator followerReplicator(ReplicaStorage storage, ReplicaIdentity identity,
			ReplicaProperties replica, ReplicationProperties replication) {
		if (!hasText(replica.leaderUrl())) {
			throw new IllegalStateException("A follower needs margosha.replica.leader-url");
		}
		ShardClient leader = new ShardClient(
				withTimeout(replication.longPoll().plus(FETCH_TIMEOUT_MARGIN)), replica.leaderUrl());
		return new FollowerReplicator(storage, leader, identity.shardId(), identity.replicaId(),
				replication.fetchMaxEntries(),
				new RetryBackoff(replication.retryInterval(), replication.retryMaxInterval()));
	}

	@Bean
	public RecordStorage recordStorage(ReplicaIdentity identity, ReplicaStorage storage,
			ReplicationProperties replication, ObjectProvider<ReplicationLeader> leader) {
		return switch (identity.role()) {
			case LEADER -> new LeaderRecordStorage(storage, leader.getObject(), replication.followerConfirmations());
			case FOLLOWER -> new FollowerRecordStorage(storage, identity);
		};
	}

	@Bean
	@ConditionalOnProperty(name = "margosha.coordinator.url")
	public CoordinatorRegistrationService coordinatorRegistrationService(
			@Value("${margosha.coordinator.url}") String coordinatorUrl,
			ShardProperties shard, ReplicaIdentity identity, ObjectProvider<FollowerReplicator> follower) {
		CoordinatorClient coordinator = new CoordinatorClient(
				withTimeout(shard.registerTimeout()), coordinatorUrl);
		FollowerReplicator replicator = follower.getIfAvailable();
		return new CoordinatorRegistrationService(coordinator, identity,
				hasText(shard.host()) ? shard.host() : localAddress(),
				new RetryBackoff(shard.registerRetryInterval(), shard.registerRetryMaxInterval()),
				shard.registerRefreshInterval(),
				replicator != null ? replicator.caughtUp() : CompletableFuture.completedFuture(null));
	}

	private static String localAddress() {
		try {
			return InetAddress.getLocalHost().getHostAddress();
		} catch (UnknownHostException e) {
			throw new IllegalStateException("Cannot find this shard's address; set margosha.shard.host", e);
		}
	}
}
