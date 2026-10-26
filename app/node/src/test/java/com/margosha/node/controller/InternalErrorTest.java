package com.margosha.node.controller;

import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USERS_TABLE;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.apiError;
import static com.margosha.node.data.TestData.client;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.margosha.api.client.MargoshaDbClient;
import com.margosha.api.exception.ApiException;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.PrimaryKey;
import com.margosha.node.storage.RecordStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SingleModeCoordinatorTest
class InternalErrorTest {

	private static final String INTERNAL_DETAIL = "store is broken";

	@LocalServerPort
	private int port;

	@MockitoBean
	private RecordStorage backend;

	@Test
	void unexpectedFailureReturnsInternalErrorInSharedFormat() {
		// Arrange
		MargoshaDbClient client = client(port);
		client.registerTable(USERS_TABLE);
		when(backend.read(any(), any(PrimaryKey.class))).thenThrow(new IllegalStateException(INTERNAL_DETAIL));

		// Act
		ApiException error = apiError(() -> client.readRecord(USERS, USER_42));

		// Assert
		assertThat(error.status()).isEqualTo(500);
		assertThat(error.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);

		assertThat(error.getCause()).isInstanceOf(RestClientResponseException.class);
		String rawBody = ((RestClientResponseException) error.getCause()).getResponseBodyAsString();
		JsonNode body = JsonMapper.builder().build().readTree(rawBody);
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
		assertThat(rawBody).doesNotContain(INTERNAL_DETAIL);
	}
}
