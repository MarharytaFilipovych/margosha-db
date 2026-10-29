package com.margosha.node.controller;

import static com.margosha.node.data.TestData.ORDERS;
import static com.margosha.node.data.TestData.ORDER_42_1;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import com.margosha.api.model.ApiError;
import com.margosha.api.model.ErrorCode;
import com.margosha.node.exception.NodeException;
import com.margosha.node.exception.RecordAlreadyExistsException;
import com.margosha.node.exception.RecordNotFoundException;
import com.margosha.node.coordinator.exception.TableAlreadyExistsException;
import com.margosha.node.coordinator.exception.TableNotFoundException;
import com.margosha.node.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

class ApiExceptionHandlerTest {

	private static final String UNEXPECTED_MESSAGE = "Unexpected server error!";

	private final ApiExceptionHandler handler = new ApiExceptionHandler();

	static Stream<Arguments> nodeExceptions() {
		return Stream.of(
				Arguments.of(new ValidationException("Table name must not be empty"), HttpStatus.BAD_REQUEST,
						ErrorCode.VALIDATION_ERROR, "Table name must not be empty"),
				Arguments.of(new TableNotFoundException("ghost"), HttpStatus.NOT_FOUND,
						ErrorCode.TABLE_NOT_FOUND, "Table ghost does not exist"),
				Arguments.of(new TableAlreadyExistsException(USERS), HttpStatus.CONFLICT,
						ErrorCode.TABLE_ALREADY_EXISTS, "Table users already exists"),
				Arguments.of(new RecordNotFoundException(USERS, USER_42), HttpStatus.NOT_FOUND,
						ErrorCode.RECORD_NOT_FOUND, "Record 42 not found in table users"),
				Arguments.of(new RecordAlreadyExistsException(ORDERS, ORDER_42_1), HttpStatus.CONFLICT,
						ErrorCode.RECORD_ALREADY_EXISTS, "Record 42/order-1 already exists in table orders"));
	}

	@ParameterizedTest
	@MethodSource("nodeExceptions")
	void nodeExceptionKeepsItsStatusCodeAndMessage(NodeException exception,
												   HttpStatus status,
												   ErrorCode code, String message) {
		// Act
		ResponseEntity<ApiError> response = handler.handleNodeException(exception);

		// Assert
		assertThat(response.getStatusCode()).isEqualTo(status);
		assertThat(response.getBody()).isEqualTo(new ApiError(code, message));
	}

	@Test
	void unreadableBodyIsValidationError() {
		// Arrange
		HttpMessageNotReadableException exception =
				new HttpMessageNotReadableException("JSON parse error", new MockHttpInputMessage(new byte[0]));

		// Act
		ResponseEntity<ApiError> response = handler.handleUnreadableBody(exception);

		// Assert
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isEqualTo(new ApiError(ErrorCode.VALIDATION_ERROR,
				"Request body is missing or not valid"));
	}

	@Test
	void springClientErrorKeepsItsStatus() {
		// Arrange
		HttpRequestMethodNotSupportedException exception = new HttpRequestMethodNotSupportedException("PATCH");

		// Act
		ResponseEntity<ApiError> response = handler.handleUnexpected(exception);

		// Assert
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
		assertThat(response.getBody()).isEqualTo(new ApiError(ErrorCode.VALIDATION_ERROR, exception.getMessage()));
	}

	@Test
	void springServerErrorIsInternalError() {
		// Arrange
		ErrorResponseException exception = new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE);

		// Act
		ResponseEntity<ApiError> response = handler.handleUnexpected(exception);

		// Assert
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody()).isEqualTo(new ApiError(ErrorCode.INTERNAL_ERROR, UNEXPECTED_MESSAGE));
	}

	@Test
	void unexpectedExceptionIsInternalErrorWithoutDetails() {
		// Arrange
		IllegalStateException exception = new IllegalStateException("store is broken");

		// Act
		ResponseEntity<ApiError> response = handler.handleUnexpected(exception);

		// Assert
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody()).isEqualTo(new ApiError(ErrorCode.INTERNAL_ERROR, UNEXPECTED_MESSAGE));
	}
}
