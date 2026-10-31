package com.margosha.node.shard.replication.exception;

public class LogCorruptedException extends IllegalStateException {

	public LogCorruptedException(String message) {
		super(message);
	}
}
