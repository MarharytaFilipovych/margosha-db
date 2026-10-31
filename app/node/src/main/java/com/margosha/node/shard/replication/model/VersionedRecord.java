package com.margosha.node.shard.replication.model;

import com.margosha.api.model.Attributes;

public record VersionedRecord(long version, Attributes attributes) {

	public static final VersionedRecord NEVER_WRITTEN = new VersionedRecord(0, null);

	public boolean exists() {
		return attributes != null;
	}

	public boolean deleted() {
		return attributes == null;
	}
}
