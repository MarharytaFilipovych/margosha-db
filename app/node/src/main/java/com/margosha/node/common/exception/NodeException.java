package com.margosha.node.common.exception;

import com.margosha.api.model.ApiError;
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

	/** The error body sent to the caller; subclasses add details such as a version. */
	public ApiError toApiError() {
		return new ApiError(code, getMessage());
	}

	protected static String describe(PrimaryKey key) {
		return key.sortKey() == null ? key.partitionKey() : key.partitionKey() + "/" + key.sortKey();
	}
}
