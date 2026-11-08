package com.margosha.node.shard.replication.model;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.RecordVersion;

public record VersionedRecord(long logVersion, Attributes attributes, RecordVersion recordVersion) {

	public static final VersionedRecord NEVER_WRITTEN = new VersionedRecord(0, null, null);

	public boolean exists() {
		return attributes != null;
	}

	public boolean deleted() {
		return attributes == null;
	}
}
