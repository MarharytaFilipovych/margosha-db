package com.margosha.node.shard.replication.follower;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import com.margosha.api.client.ShardClient;
import com.margosha.api.exception.ApiException;
import com.margosha.api.model.ErrorCode;
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
	private final String shardId;

	@NonNull
	private final String replicaId;

	private final int maxEntries;

	@NonNull
	private final RetryBackoff backoff;

	private final AtomicLong entriesReceived = new AtomicLong();
	private final CompletableFuture<Long> caughtUp = new CompletableFuture<>();
	private volatile boolean running;
	private volatile Thread thread;

	/** The leader's last entry when this follower started with an empty log; -1 until it is known. */
	private long catchUpTarget = -1;

	public long entriesReceived() {
		return entriesReceived.get();
	}

	/**
	 * Completes, with this follower's position, once it may serve reads. A follower that starts with entries in its
	 * log is ready at once: it still has every entry it confirmed. A follower that starts empty (a new one, or one
	 * that lost its log) is ready only once it has the entries its leader had when it started, so a quorum read
	 * never counts it as a replica that holds writes it confirmed before.
	 */
	public CompletableFuture<Long> caughtUp() {
		return caughtUp;
	}

	@Override
	public void start() {
		running = true;
		if (storage.lastVersion() > 0) {
			caughtUp.complete(storage.lastVersion());
		}
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
				learnCatchUpTarget();
				ReplicationBatch batch = leader.fetchLog(storage.lastVersion(), shardId, replicaId, logIdToSend(),
						maxEntries);
				takeLeadersLogId(batch);
				for (ReplicationEntry entry : batch.entries()) {
					storage.apply(LogEntry.from(entry));
					entriesReceived.incrementAndGet();
				}
				checkCaughtUp();
				if (pause != null) {
					log.info("Replica {} reaches its leader again, at position {}", replicaId, storage.lastVersion());
					pause = null;
				}
			} catch (RuntimeException e) {
				if (!running) {
					break;
				}
				pause = pause == null ? backoff.first() : backoff.after(pause);
				if (e instanceof ApiException api && api.code() == ErrorCode.LOG_MISMATCH) {
					log.error("Replica {} and its leader hold different logs; nothing is copied until an operator "
							+ "decides which one is right. Retrying in {}: {}",
							replicaId, pause, api.error().message());
				} else if (e instanceof ApiException api && api.code() == ErrorCode.VALIDATION_ERROR) {
					log.error("The leader refuses replica {} (check its shard ID, replica ID and leader address). "
							+ "Retrying in {}: {}", replicaId, pause, api.error().message());
				} else {
					log.warn("Replica {} could not replicate from its leader, retrying in {}: {}", replicaId, pause,
							e.getMessage());
				}
				if (!sleep(pause)) {
					break;
				}
			}
		}
		log.info("Replica {} stops replicating at position {}", replicaId, storage.lastVersion());
	}

	/** Its log ID once it has entries; an empty log sends none and takes the leader's from the answer. */
	private String logIdToSend() {
		return storage.lastVersion() > 0 ? storage.logId().orElse(null) : null;
	}

	private void takeLeadersLogId(ReplicationBatch batch) {
		String leaderLogId = batch.logId();
		if (leaderLogId != null && !leaderLogId.equals(storage.logId().orElse(null))) {
			storage.adoptLogId(leaderLogId);
		}
	}

	private void learnCatchUpTarget() {
		if (caughtUp.isDone() || catchUpTarget >= 0) {
			return;
		}
		catchUpTarget = leader.status().lastVersion();
		log.info("Replica {} started with an empty log; it registers for reads once it has its leader's {} entries",
				replicaId, catchUpTarget);
		checkCaughtUp();
	}

	private void checkCaughtUp() {
		if (!caughtUp.isDone() && catchUpTarget >= 0 && storage.lastVersion() >= catchUpTarget) {
			log.info("Replica {} has caught up with its leader at position {}", replicaId, storage.lastVersion());
			caughtUp.complete(storage.lastVersion());
		}
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
