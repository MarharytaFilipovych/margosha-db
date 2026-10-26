package com.margosha.node.exception;

import com.margosha.api.model.ErrorCode;
import org.springframework.http.HttpStatus;

public class ValidationException extends NodeException {

	public ValidationException(String message) {
		super(ErrorCode.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, message);
	}
}
