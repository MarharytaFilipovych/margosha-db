package com.margosha.node.coordinator.storage;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.PrimaryKey;
import com.margosha.node.common.exception.RecordNotFoundException;
import com.margosha.node.common.metrics.RequestMetrics;
import com.margosha.node.common.storage.RecordStorage;
import com.margosha.node.coordinator.model.ReplicaAnswer;
import com.margosha.node.coordinator.replica.QuorumReader;
import com.margosha.node.coordinator.replica.ReplicaSet;
import com.margosha.node.coordinator.service.ShardRegistryService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ShardRoutingService implements RecordStorage {

	@NonNull
	private final ShardRegistryService shardRegistryService;

	@NonNull
	private final QuorumReader quorumReader;

	@NonNull
	private final RequestMetrics metrics;

	@Override
	public void create(String table, PrimaryKey key, Attributes attributes) {
		owner(key).leader().create(table, key, attributes);
	}

	@Override
	public Attributes read(String table, PrimaryKey key) {
		ReplicaAnswer newest = quorumReader.read(owner(key), replica -> replica.read(table, key));
		if (!newest.exists()) {
			throw new RecordNotFoundException(table, key);
		}
		return newest.attributes();
	}

	@Override
	public boolean exists(String table, PrimaryKey key) {
		return quorumReader.read(owner(key), replica -> replica.exists(table, key)).exists();
	}

	@Override
	public void update(String table, PrimaryKey key, Attributes attributes) {
		owner(key).leader().update(table, key, attributes);
	}

	@Override
	public void delete(String table, PrimaryKey key) {
		owner(key).leader().delete(table, key);
	}

	/** The replica set of the shard that owns the key; counts the request for that shard. */
	private ReplicaSet owner(PrimaryKey key) {
		ReplicaSet replicaSet = shardRegistryService.replicaSetFor(key);
		metrics.shardRequest(replicaSet.getShardId());
		return replicaSet;
	}
}
