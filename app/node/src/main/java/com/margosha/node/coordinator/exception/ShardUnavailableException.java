package com.margosha.node.coordinator.exception;

import com.margosha.node.exception.NodeException;
import com.margosha.api.model.ErrorCode;
import org.springframework.http.HttpStatus;

public class ShardUnavailableException extends NodeException {

	public ShardUnavailableException(String message) {
		super(ErrorCode.SHARD_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, message);
	}

	public static ShardUnavailableException noShardRegistered() {
		return new ShardUnavailableException("No shard is registered");
	}

	public static ShardUnavailableException didNotAnswer(String shardId) {
		return new ShardUnavailableException("Shard " + shardId + " did not answer");
	}
}
