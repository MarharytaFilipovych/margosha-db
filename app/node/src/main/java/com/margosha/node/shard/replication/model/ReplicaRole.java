package com.margosha.node.shard.replication.model;

import java.util.Locale;

public enum ReplicaRole {
	LEADER,
	FOLLOWER;

	public String argument() {
		return name().toLowerCase(Locale.ROOT);
	}
}
