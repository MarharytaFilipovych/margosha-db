package com.margosha.node.shard.replication.leader;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.margosha.node.shard.replication.model.LogEntry;
import com.margosha.node.shard.replication.storage.ReplicaStorage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

@Slf4j
@RequiredArgsConstructor
public class ReplicationLeader implements SmartLifecycle {

	@NonNull
	private final ReplicaStorage storage;

	private final int confirmationsNeeded;

	@NonNull
	private final Duration ackTimeout;

	@NonNull
	private final Duration longPoll;

	private final Object monitor = new Object();
	private final Map<String, Long> followerPositions = new HashMap<>();
	private volatile boolean running;

	public void entryAppended() {
		synchronized (monitor) {
			monitor.notifyAll();
		}
	}

	public List<LogEntry> fetch(String followerId, long after, int maxEntries) {
		synchronized (monitor) {
			long last = storage.lastVersion();
			if (after > last) {
				log.warn("Follower {} asks from position {}, after the leader's last entry {}; not counted as a confirmation",
						followerId, after, last);
			} else {
				followerPositions.put(followerId, after);
				monitor.notifyAll();
			}
			waitForEntryAfter(after);
		}
		return storage.entriesAfter(after, maxEntries);
	}

	public boolean awaitConfirmations(long version) {
		if (confirmationsNeeded <= 0) {
			return true;
		}
		long deadline = System.nanoTime() + ackTimeout.toNanos();
		synchronized (monitor) {
			while (confirmed(version) < confirmationsNeeded) {
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
		entryAppended();
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

	private int confirmed(long version) {
		int count = 0;
		for (long position : followerPositions.values()) {
			if (position >= version) {
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
