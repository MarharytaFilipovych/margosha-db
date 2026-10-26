package com.margosha.node.exception;

import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.PrimaryKey;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public abstract class NodeException extends RuntimeException {

	private final ErrorCode code;
	private final HttpStatus status;

	protected NodeException(ErrorCode code, HttpStatus status, String message) {
		super(message);
		this.code = code;
		this.status = status;
	}

	protected static String describe(PrimaryKey key) {
		return key.sortKey() == null ? key.partitionKey() : key.partitionKey() + "/" + key.sortKey();
	}
}
