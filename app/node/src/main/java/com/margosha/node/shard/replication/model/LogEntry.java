package com.margosha.node.shard.replication.model;

import java.util.Objects;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.RecordVersion;
import com.margosha.api.model.ReplicationEntry;

public record LogEntry(long logVersion, String table, PrimaryKey key,
					   Attributes attributes, RecordVersion recordVersion) {

	public LogEntry {
		if (logVersion < 1) {
			throw new IllegalArgumentException("logVersion must be 1 or higher, was " + logVersion);
		}
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(recordVersion, "recordVersion");
	}

	public static LogEntry put(long logVersion, String table, PrimaryKey key, Attributes attributes,
			RecordVersion recordVersion) {
		return new LogEntry(logVersion, table, key, Objects.requireNonNull(attributes, "attributes"), recordVersion);
	}

	public static LogEntry delete(long logVersion, String table, PrimaryKey key, RecordVersion recordVersion) {
		return new LogEntry(logVersion, table, key, null, recordVersion);
	}

	public boolean deleted() {
		return attributes == null;
	}

	public static LogEntry from(ReplicationEntry entry) {
		PrimaryKey key = new PrimaryKey(entry.partitionKey(), entry.sortKey());
		return entry.deleted()
				? delete(entry.logVersion(), entry.table(), key, entry.recordVersion())
				: put(entry.logVersion(), entry.table(), key, Attributes.of(entry.attributes()), entry.recordVersion());
	}

	public ReplicationEntry toReplicationEntry() {
		return new ReplicationEntry(logVersion, table, key.partitionKey(), key.sortKey(),
				deleted() ? null : attributes.values(), deleted(), recordVersion);
	}
}
