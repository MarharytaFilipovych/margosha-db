package com.margosha.node.shard.replication.follower;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import com.margosha.api.client.ShardClient;
import com.margosha.api.model.ReplicationBatch;
import com.margosha.api.model.ReplicationEntry;
import com.margosha.node.shard.replication.model.LogEntry;
import com.margosha.node.shard.replication.storage.ReplicaStorage;
import com.margosha.node.shard.service.RetryBackoff;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

@Slf4j
@RequiredArgsConstructor
public class FollowerReplicator implements SmartLifecycle {

	private static final Duration STOP_WAIT = Duration.ofSeconds(5);

	@NonNull
	private final ReplicaStorage storage;

	@NonNull
	private final ShardClient leader;

	@NonNull
	private final String replicaId;

	private final int maxEntries;

	@NonNull
	private final RetryBackoff backoff;

	private final AtomicLong entriesReceived = new AtomicLong();
	private volatile boolean running;
	private volatile Thread thread;

	public long entriesReceived() {
		return entriesReceived.get();
	}

	@Override
	public void start() {
		running = true;
		thread = Thread.ofPlatform().name("follower-replication").daemon().start(this::replicate);
	}

	@Override
	public void stop() {
		running = false;
		Thread current = thread;
		if (current != null) {
			current.interrupt();
			try {
				current.join(STOP_WAIT.toMillis());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	private void replicate() {
		log.info("Replica {} starts replicating from its leader after position {}", replicaId, storage.lastVersion());
		Duration pause = null;
		while (running) {
			try {
				ReplicationBatch batch = leader.fetchLog(storage.lastVersion(), replicaId, maxEntries);
				for (ReplicationEntry entry : batch.entries()) {
					storage.apply(LogEntry.from(entry));
					entriesReceived.incrementAndGet();
				}
				if (pause != null) {
					log.info("Replica {} reaches its leader again, at position {}", replicaId, storage.lastVersion());
					pause = null;
				}
			} catch (RuntimeException e) {
				if (!running) {
					break;
				}
				pause = pause == null ? backoff.first() : backoff.after(pause);
				log.warn("Replica {} could not replicate from its leader, retrying in {}: {}", replicaId, pause,
						e.getMessage());
				if (!sleep(pause)) {
					break;
				}
			}
		}
		log.info("Replica {} stops replicating at position {}", replicaId, storage.lastVersion());
	}

	private boolean sleep(Duration pause) {
		try {
			Thread.sleep(pause);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}
}
