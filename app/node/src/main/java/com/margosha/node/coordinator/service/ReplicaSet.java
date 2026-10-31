package com.margosha.node.coordinator.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import com.margosha.api.model.ReplicaRole;
import com.margosha.node.common.exception.ValidationException;
import com.margosha.node.coordinator.exception.ShardUnavailableException;
import com.margosha.node.coordinator.storage.ReplicaClient;
import lombok.Getter;

public final class ReplicaSet {

	@Getter
	private final String shardId;

	private final Map<String, ReplicaClient> replicas = new TreeMap<>(); // by replica ID; changed under the registry's lock
	private final AtomicLong nextRead = new AtomicLong();
	private volatile List<ReplicaClient> snapshot = List.of();
	private volatile ReplicaClient leader;

	public ReplicaSet(String shardId) {
		this.shardId = Objects.requireNonNull(shardId, "shardId");
	}

	void checkCanAdd(String replicaId, ReplicaRole role) {
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

	public List<ReplicaClient> readOrder() {
		List<ReplicaClient> current = snapshot;
		if (current.isEmpty()) {
			return current;
		}
		int start = (int) Math.floorMod(nextRead.getAndIncrement(), (long) current.size());
		List<ReplicaClient> order = new ArrayList<>(current.size());
		for (int i = 0; i < current.size(); i++) {
			order.add(current.get((start + i) % current.size()));
		}
		return order;
	}
}
