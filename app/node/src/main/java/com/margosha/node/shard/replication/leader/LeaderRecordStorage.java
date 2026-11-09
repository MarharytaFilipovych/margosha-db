package com.margosha.node.shard.replication.leader;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.PrimaryKey;
import com.margosha.node.common.storage.RecordStorage;
import com.margosha.node.shard.replication.exception.WriteNotConfirmedException;
import com.margosha.node.shard.replication.model.LogEntry;
import com.margosha.node.shard.replication.storage.ReplicaStorage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class LeaderRecordStorage implements RecordStorage {

	@NonNull
	private final ReplicaStorage replicaStorage;

	@NonNull
	private final ReplicationLeader leader;

	private final int confirmationsNeeded;

	@Override
	public void create(String table, PrimaryKey key, Attributes attributes) {
		committed(replicaStorage.create(table, key, attributes));
	}

	@Override
	public Attributes read(String table, PrimaryKey key) {
		return replicaStorage.read(table, key);
	}

	@Override
	public boolean exists(String table, PrimaryKey key) {
		return replicaStorage.exists(table, key);
	}

	@Override
	public void update(String table, PrimaryKey key, Attributes attributes) {
		committed(replicaStorage.update(table, key, attributes));
	}

	@Override
	public void delete(String table, PrimaryKey key) {
		committed(replicaStorage.delete(table, key));
	}

	private void committed(LogEntry entry) {
		leader.entryAppended(entry.logVersion());
		if (!leader.awaitConfirmations(entry.logVersion())) {
			throw new WriteNotConfirmedException(entry.logVersion(), confirmationsNeeded);
		}
	}
}
