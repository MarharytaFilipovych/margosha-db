package com.margosha.node.shard.replication.exception;

import com.margosha.api.model.ErrorCode;
import com.margosha.node.common.exception.NodeException;
import org.springframework.http.HttpStatus;

public class NotLeaderException extends NodeException {

	public NotLeaderException(String shardId, String replicaId) {
		super(ErrorCode.NOT_LEADER, HttpStatus.MISDIRECTED_REQUEST,
				"Replica " + replicaId + " is a follower of shard " + shardId + "; send this to the shard's leader");
	}
}
