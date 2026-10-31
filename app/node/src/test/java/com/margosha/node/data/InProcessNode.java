package com.margosha.node.data;

import static com.margosha.node.data.TestData.localAddress;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.client.ShardClient;
import com.margosha.api.model.ShardRegistration;
import com.margosha.node.NodeApplication;
import com.margosha.node.common.storage.RecordStorage;
import com.margosha.node.coordinator.service.ShardRegistryService;
import com.margosha.node.coordinator.service.TableRegistryService;
import com.margosha.node.shard.replication.follower.FollowerReplicator;
import com.margosha.node.shard.replication.leader.ReplicationLeader;
import com.margosha.node.shard.replication.model.ReplicaIdentity;
import com.margosha.node.shard.replication.storage.ReplicaStorage;
import com.margosha.node.shard.service.CoordinatorRegistrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ConfigurableApplicationContext;

/** A coordinator or a replica running in this JVM, started like the real jar. */
@RequiredArgsConstructor
public final class InProcessNode implements AutoCloseable {

	public static final Duration ACK_TIMEOUT = Duration.ofSeconds(1);

	/** The coordinator waits this long for a replica: longer than the leader's ack timeout, as in production. */
	public static final Duration REPLICA_TIMEOUT = Duration.ofSeconds(2);

	private static final String REGISTER_TIMEOUT = "--margosha.shard.register-timeout=" + TestData.SHARD_TIMEOUT;
	private static final String RETRY_INTERVAL = "--margosha.shard.register-retry-interval=200ms";
	private static final String RETRY_MAX_INTERVAL = "--margosha.shard.register-retry-max-interval=400ms";
	private static final String REFRESH_INTERVAL = "--margosha.shard.register-refresh-interval=300ms";

	/** A shard on its own, as in M2: a leader in a replica set of one, so every write is confirmed at once. */
	private static final List<String> SINGLE_REPLICA = List.of("--margosha.replication.factor=1",
			"--margosha.replication.write-quorum=1", "--margosha.replication.read-quorum=1");

	/** Replica sets in tests: N = 3, W = 2, R = 2 (the defaults), with short waits so tests stay fast. */
	private static final List<String> REPLICA_SET = List.of(
			"--margosha.replication.ack-timeout=" + ACK_TIMEOUT.toMillis() + "ms",
			"--margosha.replication.long-poll=1s", "--margosha.replication.retry-interval=100ms",
			"--margosha.replication.retry-max-interval=200ms");

	private final ConfigurableApplicationContext context;

	// Shards on their own (M2 style)

	/** A single-replica shard: leader {@code <shardId>-1}, N = W = R = 1. */
	public static InProcessNode shard(String shardId) {
		return startShard(shardId, List.of());
	}

	public static InProcessNode shardRegisteringWith(String shardId, String coordinatorUrl) {
		return startShard(shardId, registeringWith(coordinatorUrl));
	}

	/** A coordinator for single-replica shards (N = W = R = 1), with its own data folder. */
	public static InProcessNode shardedCoordinator(int port) {
		return startCoordinator(port, TestData.testDataDir("coordinator"), SINGLE_REPLICA);
	}

	// Replica sets (M3)

	/** A coordinator for replica sets: N = 3, W = 2, R = 2. */
	public static InProcessNode replicatedCoordinator(int port, Path dataDir) {
		return startCoordinator(port, dataDir, List.of());
	}

	/** The leader {@code <shardId>-1} of a replica set with N = 3, W = 2, R = 2. */
	public static InProcessNode leader(String shardId, Path dataDir, String... extraArgs) {
		return leader(shardId, dataDir, 0, extraArgs);
	}

	public static InProcessNode leader(String shardId, Path dataDir, int port, String... extraArgs) {
		List<String> args = new ArrayList<>(List.of("--margosha.replica.id=" + shardId + "-1",
				"--margosha.replica.role=leader", "--margosha.data-dir=" + dataDir));
		args.addAll(REPLICA_SET);
		args.addAll(Arrays.asList(extraArgs));
		return start(shardId, port, args);
	}

	public static InProcessNode follower(String shardId, String replicaId, Path dataDir, InProcessNode leader,
			String... extraArgs) {
		return follower(shardId, replicaId, dataDir, leader.address(), extraArgs);
	}

	public static InProcessNode follower(String shardId, String replicaId, Path dataDir, String leaderUrl,
			String... extraArgs) {
		List<String> args = new ArrayList<>(List.of("--margosha.replica.id=" + replicaId,
				"--margosha.replica.role=follower", "--margosha.data-dir=" + dataDir,
				"--margosha.replica.leader-url=" + leaderUrl));
		args.addAll(REPLICA_SET);
		args.addAll(Arrays.asList(extraArgs));
		return start(shardId, 0, args);
	}

	public static String[] registeringWith(String coordinatorUrl) {
		return new String[] {"--margosha.coordinator.url=" + coordinatorUrl,
				REGISTER_TIMEOUT, RETRY_INTERVAL, RETRY_MAX_INTERVAL, REFRESH_INTERVAL};
	}

	private static InProcessNode startShard(String shardId, String[] extraArgs) {
		return startShard(shardId, Arrays.asList(extraArgs));
	}

	private static InProcessNode startShard(String shardId, List<String> extraArgs) {
		List<String> args = new ArrayList<>(SINGLE_REPLICA);
		args.add("--margosha.replica.id=" + shardId + "-1");
		args.add("--margosha.data-dir=" + TestData.testDataDir(shardId));
		args.addAll(extraArgs);
		return start(shardId, 0, args);
	}

	private static InProcessNode start(String shardId, int port, List<String> extraArgs) {
		List<String> args = new ArrayList<>(List.of("shard", "--server.port=" + port,
				"--margosha.shard.id=" + shardId, "--margosha.shard.host=localhost"));
		args.addAll(extraArgs);
		return new InProcessNode(NodeApplication.run(args.toArray(String[]::new)));
	}

	private static InProcessNode startCoordinator(int port, Path dataDir, List<String> extraArgs) {
		List<String> args = new ArrayList<>(List.of("coordinator", "--mode=sharded", "--server.port=" + port,
				"--margosha.data-dir=" + dataDir, "--margosha.shard.timeout=" + REPLICA_TIMEOUT.toMillis() + "ms"));
		args.addAll(extraArgs);
		return new InProcessNode(NodeApplication.run(args.toArray(String[]::new)));
	}

	public int port() {
		return context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
	}

	public String address() {
		return localAddress(port());
	}

	public ShardRegistration registration() {
		ReplicaIdentity identity = context.getBean(ReplicaIdentity.class);
		return new ShardRegistration(identity.shardId(), identity.replicaId(), identity.role(), address());
	}

	public String replicaId() {
		return context.getBean(ReplicaIdentity.class).replicaId();
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

	public TableRegistryService tableRegistry() {
		return context.getBean(TableRegistryService.class);
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
