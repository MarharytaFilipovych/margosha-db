package com.margosha.node.shard.service;

import java.util.concurrent.atomic.AtomicLong;

import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ReplicaExists;
import com.margosha.api.model.ReplicaRecord;
import com.margosha.node.shard.replication.exception.ReplicaRecordNotFoundException;
import com.margosha.node.shard.replication.model.VersionedRecord;
import com.margosha.node.shard.replication.storage.ReplicaStorage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ReplicaReadService {

	@NonNull
	private final ReplicaStorage storage;

	private final AtomicLong readsServed = new AtomicLong();

	public ReplicaRecord read(String table, PrimaryKey key) {
		VersionedRecord record = current(table, key);
		if (!record.exists()) {
			throw new ReplicaRecordNotFoundException(table, key, record.version());
		}
		return new ReplicaRecord(record.attributes(), record.version());
	}

	public ReplicaExists exists(String table, PrimaryKey key) {
		VersionedRecord record = current(table, key);
		return new ReplicaExists(record.exists(), record.version());
	}

	public long readsServed() {
		return readsServed.get();
	}

	private VersionedRecord current(String table, PrimaryKey key) {
		readsServed.incrementAndGet();
		return storage.readVersioned(table, key).orElse(VersionedRecord.NEVER_WRITTEN);
	}
}
