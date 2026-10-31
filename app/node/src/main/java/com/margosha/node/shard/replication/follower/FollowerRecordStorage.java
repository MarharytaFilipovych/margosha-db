package com.margosha.node.shard.replication.follower;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.PrimaryKey;
import com.margosha.node.common.storage.RecordStorage;
import com.margosha.node.shard.replication.exception.NotLeaderException;
import com.margosha.node.shard.replication.model.ReplicaIdentity;
import com.margosha.node.shard.replication.storage.ReplicaStorage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class FollowerRecordStorage implements RecordStorage {

	@NonNull
	private final ReplicaStorage storage;

	@NonNull
	private final ReplicaIdentity identity;

	@Override
	public void create(String table, PrimaryKey key, Attributes attributes) {
		throw notLeader();
	}

	@Override
	public Attributes read(String table, PrimaryKey key) {
		return storage.read(table, key);
	}

	@Override
	public boolean exists(String table, PrimaryKey key) {
		return storage.exists(table, key);
	}

	@Override
	public void update(String table, PrimaryKey key, Attributes attributes) {
		throw notLeader();
	}

	@Override
	public void delete(String table, PrimaryKey key) {
		throw notLeader();
	}

	private NotLeaderException notLeader() {
		return new NotLeaderException(identity.shardId(), identity.replicaId());
	}
}
