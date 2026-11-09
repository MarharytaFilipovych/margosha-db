package com.margosha.node.shard.replication.leader;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentSkipListMap;

import com.margosha.node.common.exception.ValidationException;
import com.margosha.node.shard.replication.exception.LogMismatchException;
import com.margosha.node.shard.replication.model.LogEntry;
import com.margosha.node.shard.replication.storage.ReplicaStorage;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

@Slf4j
public class ReplicationLeader implements SmartLifecycle {

	private final ReplicaStorage storage;
	private final String shardId;
	private final String leaderId;
	private final Set<String> followerIds;
	private final int confirmationsNeeded;
	private final Duration ackTimeout;
	private final Duration longPoll;

	private final Object monitor = new Object();
	private final Map<String, Long> followerPositions = new HashMap<>();
	/** When this leader added each entry (System.nanoTime), until every follower has confirmed it. */
	private final NavigableMap<Long, Long> appendedAt = new ConcurrentSkipListMap<>();
	/** Entries up to this log version were added before this leader started: their time is unknown. */
	private final long lastVersionAtStart;
	private final long startedAt = System.nanoTime();
	private volatile boolean running;

	public ReplicationLeader(@NonNull ReplicaStorage storage, @NonNull String shardId, @NonNull String leaderId,
			@NonNull Set<String> followerIds, int confirmationsNeeded, @NonNull Duration ackTimeout,
			@NonNull Duration longPoll) {
		this.storage = storage;
		this.shardId = shardId;
		this.leaderId = leaderId;
		this.followerIds = followerIds;
		this.confirmationsNeeded = confirmationsNeeded;
		this.ackTimeout = ackTimeout;
		this.longPoll = longPoll;
		this.lastVersionAtStart = storage.lastVersion();
	}

	/** Called after the leader added an entry to its log: notes the time and wakes the waiting fetches. */
	public void entryAppended(long logVersion) {
		appendedAt.put(logVersion, System.nanoTime());
		wakeUp();
	}

	/**
	 * How far behind a follower is, in seconds: the time since this leader added the oldest entry the
	 * follower hasn't confirmed, or 0 when it has every entry. Entries added before this leader started count from its
	 * start, so after a restart the lag looks smaller than it is until the follower catches up.
	 */
	public double lagSeconds(String followerId) {
		long confirmed;
		synchronized (monitor) {
			confirmed = followerPositions.getOrDefault(followerId, 0L);
		}
		long oldestMissing = confirmed + 1;
		if (oldestMissing > storage.lastVersion()) {
			return 0;
		}
		long now = System.nanoTime();
		long since = oldestMissing <= lastVersionAtStart ? startedAt : appendedAt.getOrDefault(oldestMissing, now);
		return Math.max(0, now - since) / 1_000_000_000.0;
	}

	public Set<String> followerIds() {
		return followerIds;
	}

	private void wakeUp() {
		synchronized (monitor) {
			monitor.notifyAll();
		}
	}

	public List<LogEntry> fetch(String followerShardId, String followerId, long after, String followerLogId,
			int maxEntries) {
		if (!shardId.equals(followerShardId)) {
			throw new ValidationException("Replica " + followerId + " of shard " + followerShardId
					+ " fetched from leader " + leaderId + " of shard " + shardId + "; check its leader address");
		}
		if (!followerIds.contains(followerId)) {
			throw new ValidationException("Replica " + followerId + " is not a follower of shard " + shardId
					+ "; its followers are " + new TreeSet<>(followerIds));
		}
		String ownLogId = storage.ensureLogId();
		if (followerLogId != null && !followerLogId.equals(ownLogId)) {
			throw LogMismatchException.otherLog(followerId, followerLogId, leaderId, ownLogId);
		}
		synchronized (monitor) {
			long last = storage.lastVersion();
			if (after > last) {
				throw LogMismatchException.ahead(followerId, after, leaderId, last);
			}
			followerPositions.put(followerId, after);
			forgetConfirmedByAll();
			monitor.notifyAll();
			waitForEntryAfter(after);
		}
		return storage.entriesAfter(after, maxEntries);
	}

	public boolean awaitConfirmations(long logVersion) {
		if (confirmationsNeeded <= 0) {
			return true;
		}
		long deadline = System.nanoTime() + ackTimeout.toNanos();
		synchronized (monitor) {
			while (confirmed(logVersion) < confirmationsNeeded) {
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0 || interruptedWhileWaiting(remaining)) {
					return false;
				}
			}
			return true;
		}
	}

	public Map<String, Long> followerPositions() {
		synchronized (monitor) {
			return Collections.unmodifiableMap(new TreeMap<>(followerPositions));
		}
	}

	@Override
	public void start() {
		running = true;
	}

	@Override
	public void stop() {
		running = false;
		wakeUp();
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	private void waitForEntryAfter(long after) {
		long deadline = System.nanoTime() + longPoll.toNanos();
		while (running && storage.lastVersion() <= after) {
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0 || interruptedWhileWaiting(remaining)) {
				return;
			}
		}
	}

	/** Drops the times of entries every follower has confirmed; they are never needed again. */
	private void forgetConfirmedByAll() {
		long confirmedByAll = Long.MAX_VALUE;
		for (String follower : followerIds) {
			confirmedByAll = Math.min(confirmedByAll, followerPositions.getOrDefault(follower, 0L));
		}
		appendedAt.headMap(confirmedByAll, true).clear();
	}

	private int confirmed(long logVersion) {
		int count = 0;
		for (long position : followerPositions.values()) {
			if (position >= logVersion) {
				count++;
			}
		}
		return count;
	}

	private boolean interruptedWhileWaiting(long nanos) {
		try {
			monitor.wait(Math.max(1, nanos / 1_000_000), (int) (nanos % 1_000_000));
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return true;
		}
	}
}
