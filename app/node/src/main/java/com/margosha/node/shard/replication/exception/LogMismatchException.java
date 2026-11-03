package com.margosha.node.shard.replication.exception;

import com.margosha.api.model.ErrorCode;
import com.margosha.node.common.exception.NodeException;
import org.springframework.http.HttpStatus;

public class LogMismatchException extends NodeException {

	private LogMismatchException(String message) {
		super(ErrorCode.LOG_MISMATCH, HttpStatus.CONFLICT, message);
	}

	public static LogMismatchException otherLog(String followerId, String followerLogId, String leaderId,
			String leaderLogId) {
		return new LogMismatchException("Follower " + followerId + " has log " + followerLogId + " but leader "
				+ leaderId + " has log " + leaderLogId + "; the logs are not the same history");
	}

	public static LogMismatchException ahead(String followerId, long after, String leaderId, long leaderLast) {
		return new LogMismatchException("Follower " + followerId + " is at position " + after
				+ ", after the last entry " + leaderLast + " of leader " + leaderId
				+ "; the logs are not the same history");
	}
}
