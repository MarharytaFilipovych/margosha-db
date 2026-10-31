package com.margosha.node.shard.replication.model;

import com.margosha.api.model.Attributes;

public record VersionedRecord(long version, Attributes attributes) {

	public boolean deleted() {
		return attributes == null;
	}
}
