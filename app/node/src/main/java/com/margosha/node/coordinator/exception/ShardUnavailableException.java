package com.margosha.node.coordinator.exception;

import com.margosha.api.model.ErrorCode;
import com.margosha.node.common.exception.NodeException;
import org.springframework.http.HttpStatus;

public class ShardUnavailableException extends NodeException {

	public ShardUnavailableException(String message) {
		super(ErrorCode.SHARD_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, message);
	}

	public static ShardUnavailableException noShardRegistered() {
		return new ShardUnavailableException("No shard is registered");
	}

	public static ShardUnavailableException didNotAnswer(String shardId, String replicaId) {
		return new ShardUnavailableException("Replica " + replicaId + " of shard " + shardId + " did not answer");
	}

	public static ShardUnavailableException noLeader(String shardId) {
		return new ShardUnavailableException("Shard " + shardId + " has no registered leader");
	}

	public static ShardUnavailableException notEnoughReplicas(String shardId, int answered, int needed) {
		return new ShardUnavailableException("Only " + answered + " of the " + needed + " replicas a read needs answered"
				+ " for shard " + shardId);
	}
}
