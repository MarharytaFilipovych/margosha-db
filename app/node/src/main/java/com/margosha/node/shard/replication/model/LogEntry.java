package com.margosha.node.shard.replication.model;

import java.util.Objects;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ReplicationEntry;

public record LogEntry(long version, String table, PrimaryKey key, Attributes attributes) {

	public LogEntry {
		if (version < 1) {
			throw new IllegalArgumentException("version must be 1 or higher, was " + version);
		}
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
	}

	public static LogEntry put(long version, String table, PrimaryKey key, Attributes attributes) {
		return new LogEntry(version, table, key, Objects.requireNonNull(attributes, "attributes"));
	}

	public static LogEntry delete(long version, String table, PrimaryKey key) {
		return new LogEntry(version, table, key, null);
	}

	public boolean deleted() {
		return attributes == null;
	}

	public static LogEntry from(ReplicationEntry entry) {
		PrimaryKey key = new PrimaryKey(entry.partitionKey(), entry.sortKey());
		return entry.deleted()
				? delete(entry.version(), entry.table(), key)
				: put(entry.version(), entry.table(), key, Attributes.of(entry.attributes()));
	}

	public ReplicationEntry toReplicationEntry() {
		return new ReplicationEntry(version, table, key.partitionKey(), key.sortKey(),
				deleted() ? null : attributes.values(), deleted());
	}
}
