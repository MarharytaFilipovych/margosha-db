package com.margosha.node.shard.service;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.model.ShardRegistration;
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
	private final String shardId;

	@NonNull
	private final String host;

	@NonNull
	private final RetryBackoff backoff;

	@NonNull
	private final Duration refreshInterval;

	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
		Thread thread = new Thread(task, "coordinator-registration");
		thread.setDaemon(true);
		return thread;
	});

	@Getter
	private volatile boolean registered;

	/**
	 * Called once the embedded web server is listening, so its real port is known
	 * (also when it was started on a random port, {@code server.port=0}, as in the tests).
	 */
	@Override
	public void onApplicationEvent(WebServerInitializedEvent event) {
		if (event.getApplicationContext().getServerNamespace() != null) {
			return; // a second server (e.g. a management port) is not where records are served
		}
		int port = event.getWebServer().getPort();
		ShardRegistration registration = new ShardRegistration(shardId, "http://" + host + ":" + port);
		executor.execute(() -> attempt(registration, backoff.first()));
	}

	private void attempt(ShardRegistration registration, Duration pause) {
		try {
			coordinator.registerShard(registration);
			if (!registered) {
				log.info("Registered shard {} at {}", registration.shardId(), registration.address());
			}
			registered = true;
			schedule(() -> attempt(registration, backoff.first()), refreshInterval);
		} catch (RuntimeException e) {
			registered = false;
			log.warn("Coordinator did not accept shard {} yet, retrying in {}: {}",
					registration.shardId(), pause, e.getMessage());
			schedule(() -> attempt(registration, backoff.after(pause)), pause);
		}
	}

	private void schedule(Runnable next, Duration delay) {
		try {
			executor.schedule(next, delay.toMillis(), TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException stopped) {
			log.info("Shard is stopping; registration of {} ends", shardId);
		}
	}

	@Override
	public void destroy() {
		executor.shutdownNow();
	}
}
