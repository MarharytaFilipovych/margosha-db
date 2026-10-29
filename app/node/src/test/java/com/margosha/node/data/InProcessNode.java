package com.margosha.node.data;

import static com.margosha.node.data.TestData.localAddress;

import java.util.ArrayList;
import java.util.List;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.model.ShardRegistration;
import com.margosha.node.NodeApplication;
import com.margosha.node.shard.service.CoordinatorRegistrationService;
import com.margosha.node.coordinator.service.ShardRegistryService;
import com.margosha.node.storage.RecordStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ConfigurableApplicationContext;

@RequiredArgsConstructor
public final class InProcessNode implements AutoCloseable {

	private static final String REGISTER_TIMEOUT = "--margosha.shard.register-timeout=" + TestData.SHARD_TIMEOUT;
	private static final String RETRY_INTERVAL = "--margosha.shard.register-retry-interval=200ms";
	private static final String RETRY_MAX_INTERVAL = "--margosha.shard.register-retry-max-interval=400ms";

	private final String shardId;
	private final ConfigurableApplicationContext context;

	public static InProcessNode shard(String shardId) {
		return startShard(shardId, List.of());
	}

	public static InProcessNode shardRegisteringWith(String shardId, String coordinatorUrl) {
		return startShard(shardId, List.of("--margosha.coordinator.url=" + coordinatorUrl,
				REGISTER_TIMEOUT, RETRY_INTERVAL, RETRY_MAX_INTERVAL));
	}

	public static InProcessNode shardedCoordinator(int port) {
		return new InProcessNode(null, NodeApplication.run("coordinator", "--mode=sharded", "--server.port=" + port));
	}

	private static InProcessNode startShard(String shardId, List<String> extraArgs) {
		List<String> args = new ArrayList<>(List.of("shard", "--server.port=0",
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
