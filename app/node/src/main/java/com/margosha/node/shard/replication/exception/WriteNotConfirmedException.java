package com.margosha.node.shard.replication.exception;

import com.margosha.api.model.ErrorCode;
import com.margosha.node.common.exception.NodeException;
import org.springframework.http.HttpStatus;

public class WriteNotConfirmedException extends NodeException {

	public WriteNotConfirmedException(long version, int confirmationsNeeded) {
		super(ErrorCode.SHARD_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
				"Write " + version + " was stored by the leader but not confirmed by " + confirmationsNeeded
						+ " follower(s) in time; its outcome is unknown");
	}
}
