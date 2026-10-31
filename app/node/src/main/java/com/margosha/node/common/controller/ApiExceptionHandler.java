package com.margosha.node.common.controller;

import java.util.List;
import java.util.stream.Collectors;

import com.margosha.api.model.ApiError;
import com.margosha.api.model.ErrorCode;
import com.margosha.node.common.exception.NodeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(NodeException.class)
	public ResponseEntity<ApiError> handleNodeException(NodeException e) {
		log.error("NodeException occurred", e);
		return ResponseEntity.status(e.getStatus()).body(e.toApiError());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleInvalidBody(MethodArgumentNotValidException e) {
		String message = messages(e.getBindingResult().getAllErrors());
		log.warn("Invalid request body: {}", message);
		return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, message);
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ApiError> handleInvalidParameter(HandlerMethodValidationException e) {
		String message = messages(e.getAllErrors());
		log.warn("Invalid request parameter: {}", message);
		return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, message);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException e) {
		log.error("HttpMessageNotReadableException occurred", e);
		return error(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, "Request body is missing or not valid");
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleUnexpected(Exception e) {
		if (e instanceof ErrorResponse response && response.getStatusCode().is4xxClientError()) {
			log.error("Spring Framework exception occurred", e);
			return error(HttpStatus.valueOf(response.getStatusCode().value()), ErrorCode.VALIDATION_ERROR, e.getMessage());
		}
		log.error("Unexpected error while handling a request", e);
		return error(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "Unexpected server error!");
	}

	private static String messages(List<? extends MessageSourceResolvable> errors) {
		return errors.stream()
				.map(MessageSourceResolvable::getDefaultMessage)
				.distinct()
				.sorted()
				.collect(Collectors.joining("; "));
	}

	private static ResponseEntity<ApiError> error(HttpStatus status, ErrorCode code, String message) {
		return ResponseEntity.status(status).body(new ApiError(code, message));
	}
}
