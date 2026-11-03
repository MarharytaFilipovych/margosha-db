package com.margosha.node.coordinator.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.margosha.api.model.ReplicaRole;
import com.margosha.node.common.exception.ValidationException;
import com.margosha.node.coordinator.exception.ShardUnavailableException;
import com.margosha.node.coordinator.storage.ReplicaClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ReplicaSet {

	/** How long reads put a replica that did not answer at the end of their order. */
	public static final Duration DEFAULT_SKIP_AFTER_FAILURE = Duration.ofSeconds(5);

	@Getter
	private final String shardId;

	private final Map<String, ReplicaClient> replicas = new TreeMap<>(); // by replica ID; changed under the registry's lock
	private final AtomicLong nextRead = new AtomicLong();
	private final long skipNanos;

	private final int maxReplicas;

	/** Replica ID → {@code System.nanoTime()} until which reads ask it last, after it failed to answer. */
	private final Map<String, Long> skippedUntil = new ConcurrentHashMap<>();
	private volatile List<ReplicaClient> snapshot = List.of();
	private volatile ReplicaClient leader;

	public ReplicaSet(String shardId) {
		this(shardId, DEFAULT_SKIP_AFTER_FAILURE);
	}

	public ReplicaSet(String shardId, Duration skipAfterFailure) {
		this(shardId, skipAfterFailure, Integer.MAX_VALUE);
	}

	public ReplicaSet(String shardId, Duration skipAfterFailure, int maxReplicas) {
		if (maxReplicas < 1) {
			throw new IllegalArgumentException("maxReplicas must be 1 or higher, was " + maxReplicas);
		}
		this.shardId = Objects.requireNonNull(shardId, "shardId");
		this.skipNanos = skipAfterFailure.toNanos();
		this.maxReplicas = maxReplicas;
	}

	void checkCanAdd(String replicaId, ReplicaRole role) {
		if (!replicas.containsKey(replicaId) && replicas.size() >= maxReplicas) {
			throw new ValidationException("Shard " + shardId + " already has " + maxReplicas + " replicas "
					+ replicas.keySet() + "; replica " + replicaId + " cannot register as another one");
		}
		ReplicaClient current = leader;
		if (role == ReplicaRole.LEADER && current != null && !current.registration().replicaId().equals(replicaId)) {
			throw new ValidationException("Shard " + shardId + " already has leader " + current.registration().replicaId()
					+ "; replica " + replicaId + " cannot register as a second leader");
		}
	}

	void put(ReplicaClient replica) {
		String replicaId = replica.registration().replicaId();
		replicas.put(replicaId, replica);
		ReplicaClient current = leader;
		if (replica.registration().role() == ReplicaRole.LEADER) {
			leader = replica;
		} else if (current != null && current.registration().replicaId().equals(replicaId)) {
			leader = null;
		}
		snapshot = List.copyOf(replicas.values());
	}

	ReplicaClient get(String replicaId) {
		return replicas.get(replicaId);
	}

	public ReplicaClient leader() {
		ReplicaClient current = leader;
		if (current == null) {
			throw ShardUnavailableException.noLeader(shardId);
		}
		return current;
	}

	public List<ReplicaClient> replicas() {
		return snapshot;
	}

	public List<ReplicaClient> readOrder(int firstAsked) {
		List<ReplicaClient> current = snapshot;
		if (current.isEmpty()) {
			return current;
		}
		int start = (int) Math.floorMod(nextRead.getAndIncrement(), (long) current.size());
		List<ReplicaClient> order = new ArrayList<>(current.size());
		List<ReplicaClient> skipped = new ArrayList<>(1);
		long now = System.nanoTime();
		for (int i = 0; i < current.size(); i++) {
			ReplicaClient replica = current.get((start + i) % current.size());
			boolean asked = order.size() < firstAsked;
			(isSkipped(replica.registration().replicaId(), now, asked) ? skipped : order).add(replica);
		}
		order.addAll(skipped);
		return order;
	}

	public void failed(ReplicaClient replica) {
		String replicaId = replica.registration().replicaId();
		if (skippedUntil.put(replicaId, System.nanoTime() + skipNanos) == null) {
			log.warn("Replica {} of shard {} did not answer a read; reads ask it last for the next {} ms", replicaId,
					shardId, skipNanos / 1_000_000);
		}
	}

	public void answered(ReplicaClient replica) {
		String replicaId = replica.registration().replicaId();
		if (skippedUntil.remove(replicaId) != null) {
			log.info("Replica {} of shard {} answers reads again", replicaId, shardId);
		}
	}

	private boolean isSkipped(String replicaId, long now, boolean wouldBeAsked) {
		Long until = skippedUntil.get(replicaId);
		if (until == null) {
			return false;
		}
		if (now - until < 0 || !wouldBeAsked) {
			return true;
		}
		return !skippedUntil.replace(replicaId, until, now + skipNanos);
	}
}
