package com.margosha.node.common.controller;

import java.util.List;
import java.util.stream.Collectors;

import com.margosha.api.model.ApiError;
import com.margosha.api.model.ErrorCode;
import com.margosha.node.common.exception.NodeException;
import com.margosha.node.common.metrics.Operation;
import com.margosha.node.common.metrics.RequestMetrics;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class ApiExceptionHandler {

	private final RequestMetrics metrics;

	@ExceptionHandler(NodeException.class)
	public ResponseEntity<ApiError> handleNodeException(NodeException e, HttpServletRequest request) {
		if (e.getStatus().is4xxClientError()) {
			log.warn("{} {}: {}", e.getStatus().value(), e.getCode(), e.getMessage());
		} else {
			log.error("NodeException occurred", e);
		}
		return answer(request, e.getStatus(), e.toApiError());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleInvalidBody(MethodArgumentNotValidException e, HttpServletRequest request) {
		String message = messages(e.getBindingResult().getAllErrors());
		log.warn("Invalid request body: {}", message);
		return answer(request, HttpStatus.BAD_REQUEST, new ApiError(ErrorCode.VALIDATION_ERROR, message));
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ApiError> handleInvalidParameter(HandlerMethodValidationException e,
			HttpServletRequest request) {
		String message = messages(e.getAllErrors());
		log.warn("Invalid request parameter: {}", message);
		return answer(request, HttpStatus.BAD_REQUEST, new ApiError(ErrorCode.VALIDATION_ERROR, message));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException e,
			HttpServletRequest request) {
		log.warn("Unreadable request body: {}", e.getMessage());
		return answer(request, HttpStatus.BAD_REQUEST,
				new ApiError(ErrorCode.VALIDATION_ERROR, "Request body is missing or not valid"));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest request) {
		if (e instanceof ErrorResponse response && response.getStatusCode().is4xxClientError()) {
			log.warn("Rejected request: {}", e.getMessage());
			return answer(request, HttpStatus.valueOf(response.getStatusCode().value()),
					new ApiError(ErrorCode.VALIDATION_ERROR, e.getMessage()));
		}
		log.error("Unexpected error while handling a request", e);
		return answer(request, HttpStatus.INTERNAL_SERVER_ERROR,
				new ApiError(ErrorCode.INTERNAL_ERROR, "Unexpected server error!"));
	}

	private static String messages(List<? extends MessageSourceResolvable> errors) {
		return errors.stream()
				.map(MessageSourceResolvable::getDefaultMessage)
				.distinct()
				.sorted()
				.collect(Collectors.joining("; "));
	}

	private ResponseEntity<ApiError> answer(HttpServletRequest request, HttpStatus status, ApiError body) {
		Operation.of(request).ifPresent(operation -> {
			metrics.error(operation, body.code());
			if (status.is5xxServerError()) {
				metrics.failed();
			}
		});
		return ResponseEntity.status(status).body(body);
	}
}
