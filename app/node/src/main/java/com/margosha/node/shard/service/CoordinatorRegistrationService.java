package com.margosha.node.shard.service;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.model.ShardRegistration;
import com.margosha.node.shard.replication.model.ReplicaIdentity;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;

@Slf4j
@RequiredArgsConstructor
public class CoordinatorRegistrationService implements ApplicationListener<WebServerInitializedEvent>, DisposableBean {

	@NonNull
	private final CoordinatorClient coordinator;

	@NonNull
	private final ReplicaIdentity identity;

	@NonNull
	private final String host;

	@NonNull
	private final RetryBackoff backoff;

	@NonNull
	private final Duration refreshInterval;

	@NonNull
	private final CompletionStage<?> readyToServe;

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
		Thread thread = new Thread(task, "coordinator-registration");
		thread.setDaemon(true);
		return thread;
	});

	@Getter
	private volatile boolean registered;

	@Override
	public void onApplicationEvent(WebServerInitializedEvent event) {
		if (event.getApplicationContext().getServerNamespace() != null) {
			return; // a second server (e.g. a management port) is not where records are served
		}
		int port = event.getWebServer().getPort();
		ShardRegistration registration = new ShardRegistration(identity.shardId(), identity.replicaId(),
				identity.role(), "http://" + host + ":" + port);
		if (!readyToServe.toCompletableFuture().isDone()) {
			log.info("Replica {} registers with the coordinator once it has caught up with its leader",
					identity.replicaId());
		}
		readyToServe.thenRun(() -> {
			try {
				executor.execute(() -> attempt(registration, backoff.first()));
			} catch (RejectedExecutionException stopped) {
				log.info("Replica is stopping; registration of {} ends", identity.replicaId());
			}
		});
	}

	private void attempt(ShardRegistration registration, Duration pause) {
		try {
			coordinator.registerShard(registration);
			if (!registered) {
				log.info("Registered replica {} ({}) of shard {} at {}", registration.replicaId(),
						registration.role().argument(), registration.shardId(), registration.address());
			}
			registered = true;
			schedule(() -> attempt(registration, backoff.first()), refreshInterval);
		} catch (RuntimeException e) {
			registered = false;
			log.warn("Coordinator did not accept replica {} of shard {} yet, retrying in {}: {}",
					registration.replicaId(), registration.shardId(), pause, e.getMessage());
			schedule(() -> attempt(registration, backoff.after(pause)), pause);
		}
	}

	private void schedule(Runnable next, Duration delay) {
		try {
			executor.schedule(next, delay.toMillis(), TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException stopped) {
			log.info("Replica is stopping; registration of {} ends", identity.replicaId());
		}
	}

	@Override
	public void destroy() {
		executor.shutdownNow();
	}
}
