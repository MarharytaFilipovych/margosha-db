package com.margosha.node.shard.replication.model;

import java.util.Objects;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.PrimaryKey;

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
}
