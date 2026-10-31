package com.margosha.node.data;

import static com.margosha.node.data.TestData.localAddress;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.client.ShardClient;
import com.margosha.api.model.ShardRegistration;
import com.margosha.node.NodeApplication;
import com.margosha.node.common.storage.RecordStorage;
import com.margosha.node.coordinator.service.ShardRegistryService;
import com.margosha.node.shard.replication.follower.FollowerReplicator;
import com.margosha.node.shard.replication.leader.ReplicationLeader;
import com.margosha.node.shard.replication.storage.ReplicaStorage;
import com.margosha.node.shard.service.CoordinatorRegistrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ConfigurableApplicationContext;

@RequiredArgsConstructor
public final class InProcessNode implements AutoCloseable {

	public static final Duration ACK_TIMEOUT = Duration.ofSeconds(1);

	private static final String REGISTER_TIMEOUT = "--margosha.shard.register-timeout=" + TestData.SHARD_TIMEOUT;
	private static final String RETRY_INTERVAL = "--margosha.shard.register-retry-interval=200ms";
	private static final String RETRY_MAX_INTERVAL = "--margosha.shard.register-retry-max-interval=400ms";
	private static final String REFRESH_INTERVAL = "--margosha.shard.register-refresh-interval=300ms";

	private static final List<String> SINGLE_REPLICA = List.of("--margosha.replication.factor=1",
			"--margosha.replication.write-quorum=1", "--margosha.replication.read-quorum=1");

	private static final List<String> REPLICA_SET = List.of("--margosha.replication.ack-timeout=" + ACK_TIMEOUT.toMillis() + "ms",
			"--margosha.replication.long-poll=1s", "--margosha.replication.retry-interval=100ms",
			"--margosha.replication.retry-max-interval=200ms");

	private final String shardId;
	private final ConfigurableApplicationContext context;

	public static InProcessNode shard(String shardId) {
		return startShard(shardId, List.of());
	}

	public static InProcessNode shardRegisteringWith(String shardId, String coordinatorUrl) {
		return startShard(shardId, List.of("--margosha.coordinator.url=" + coordinatorUrl,
				REGISTER_TIMEOUT, RETRY_INTERVAL, RETRY_MAX_INTERVAL, REFRESH_INTERVAL));
	}

	public static InProcessNode shardedCoordinator(int port) {
		return new InProcessNode(null, NodeApplication.run("coordinator", "--mode=sharded", "--server.port=" + port));
	}

	public static InProcessNode leader(String shardId, Path dataDir) {
		return leader(shardId, dataDir, 0);
	}

	public static InProcessNode leader(String shardId, Path dataDir, int port) {
		List<String> args = new ArrayList<>(List.of("--margosha.replica.id=" + shardId + "-1",
				"--margosha.replica.role=leader", "--margosha.data-dir=" + dataDir));
		args.addAll(REPLICA_SET);
		return start(shardId, port, args);
	}

	public static InProcessNode follower(String shardId, String replicaId, Path dataDir, InProcessNode leader) {
		return follower(shardId, replicaId, dataDir, leader.address());
	}

	public static InProcessNode follower(String shardId, String replicaId, Path dataDir, String leaderUrl) {
		List<String> args = new ArrayList<>(List.of("--margosha.replica.id=" + replicaId,
				"--margosha.replica.role=follower", "--margosha.data-dir=" + dataDir,
				"--margosha.replica.leader-url=" + leaderUrl));
		args.addAll(REPLICA_SET);
		return start(shardId, 0, args);
	}

	private static InProcessNode startShard(String shardId, List<String> extraArgs) {
		List<String> args = new ArrayList<>(SINGLE_REPLICA);
		args.add("--margosha.data-dir=target/test-data/" + TestData.unique(shardId));
		args.addAll(extraArgs);
		return start(shardId, 0, args);
	}

	private static InProcessNode start(String shardId, int port, List<String> extraArgs) {
		List<String> args = new ArrayList<>(List.of("shard", "--server.port=" + port,
				"--margosha.shard.id=" + shardId, "--margosha.shard.host=localhost"));
		args.addAll(extraArgs);
		return new InProcessNode(shardId, NodeApplication.run(args.toArray(String[]::new)));
	}

	public int port() {
		return context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
	}

	public String address() {
		return localAddress(port());
	}

	public ShardRegistration registration() {
		return new ShardRegistration(shardId, address());
	}

	public RecordStorage storage() {
		return context.getBean(RecordStorage.class);
	}

	public ReplicaStorage replica() {
		return context.getBean(ReplicaStorage.class);
	}

	public ReplicationLeader replicationLeader() {
		return context.getBean(ReplicationLeader.class);
	}

	public FollowerReplicator followerReplicator() {
		return context.getBean(FollowerReplicator.class);
	}

	public ShardClient shardClient() {
		return TestData.shardClient(port());
	}

	public CoordinatorRegistrationService coordinatorRegistration() {
		return context.getBean(CoordinatorRegistrationService.class);
	}

	public ShardRegistryService shardRegistry() {
		return context.getBean(ShardRegistryService.class);
	}

	public CoordinatorClient client() {
		return TestData.client(port());
	}

	public boolean isRunning() {
		return context.isActive();
	}

	@Override
	public void close() {
		context.close();
	}
}
