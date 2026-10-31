package com.margosha.node.shard.replication.model;

import java.util.Objects;

import com.margosha.api.model.ReplicaRole;

public record ReplicaIdentity(String shardId, String replicaId, ReplicaRole role) {

	public ReplicaIdentity {
		Objects.requireNonNull(shardId, "shardId");
		Objects.requireNonNull(replicaId, "replicaId");
		Objects.requireNonNull(role, "role");
	}
}
