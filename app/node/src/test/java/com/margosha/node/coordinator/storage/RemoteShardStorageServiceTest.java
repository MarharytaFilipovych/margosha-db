package com.margosha.node.coordinator.storage;

import static com.margosha.node.data.TestData.ANN_ATTRIBUTES;
import static com.margosha.node.data.TestData.SHARD_A;
import static com.margosha.node.data.TestData.SHARD_TIMEOUT;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.freePort;
import static com.margosha.node.data.TestData.json;
import static com.margosha.node.data.TestData.shard;
import static com.margosha.node.data.TestData.silentServer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import com.margosha.api.client.ShardClient;
import com.margosha.api.exception.ApiException;
import com.margosha.api.model.ApiError;
import com.margosha.api.model.Attributes;
import com.margosha.api.model.ErrorCode;
import com.margosha.node.exception.RecordAlreadyExistsException;
import com.margosha.node.exception.RecordNotFoundException;
import com.margosha.node.coordinator.exception.ShardUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;

class RemoteShardStorageServiceTest {

	private static final String BASE_URL = "http://shard-a.test:8081";
	private static final String USER_42_RECORD = BASE_URL + "/tables/" + USERS + "/records/" + USER_42.partitionKey();

	@Nested
	class ShardAnswers {

		private MockRestServiceServer server;
		private RemoteShardStorageService remoteShard;

		@BeforeEach
		void setUp() {
			RestClient.Builder builder = RestClient.builder();
			server = MockRestServiceServer.bindTo(builder).build();
			remoteShard = new RemoteShardStorageService(SHARD_A, new ShardClient(builder, BASE_URL));
		}

		@Test
		void readReturnsTheShardsAttributes() {
			// Arrange
			server.expect(requestTo(USER_42_RECORD)).andExpect(method(HttpMethod.GET))
					.andRespond(respond(HttpStatus.OK, ANN_ATTRIBUTES));

			// Act
			Attributes attributes = remoteShard.read(USERS, USER_42);

			// Assert
			assertThat(attributes).isEqualTo(ANN_ATTRIBUTES);
			server.verify();
		}

		@Test
		void recordNotFoundOnTheShardIsRecordNotFound() {
			// Arrange
			server.expect(requestTo(USER_42_RECORD))
					.andRespond(respond(HttpStatus.NOT_FOUND, new ApiError(ErrorCode.RECORD_NOT_FOUND, "missing")));

			// Act & Assert
			assertThatThrownBy(() -> remoteShard.read(USERS, USER_42))
					.isInstanceOf(RecordNotFoundException.class)
					.hasMessage(new RecordNotFoundException(USERS, USER_42).getMessage());
		}

		@Test
		void conflictOnTheShardIsRecordAlreadyExists() {
			// Arrange
			server.expect(requestTo(USER_42_RECORD)).andExpect(method(HttpMethod.POST))
					.andRespond(respond(HttpStatus.CONFLICT, new ApiError(ErrorCode.RECORD_ALREADY_EXISTS, "exists")));

			// Act & Assert
			assertThatThrownBy(() -> remoteShard.create(USERS, USER_42, ANN_ATTRIBUTES))
					.isInstanceOf(RecordAlreadyExistsException.class);
		}

		@Test
		void otherShardErrorIsPassedOnUnchanged() {
			// Arrange
			server.expect(requestTo(USER_42_RECORD))
					.andRespond(respond(HttpStatus.INTERNAL_SERVER_ERROR, new ApiError(ErrorCode.INTERNAL_ERROR, "boom")));

			// Act
			Throwable thrown = catchThrowable(() -> remoteShard.read(USERS, USER_42));

			// Assert
			assertThat(thrown).isInstanceOfSatisfying(ApiException.class,
					e -> assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
		}

		@Test
		void connectionFailureIsShardUnavailable() {
			// Arrange
			server.expect(requestTo(USER_42_RECORD)).andRespond(withException(new IOException("connection reset")));

			// Act & Assert
			assertThatThrownBy(() -> remoteShard.read(USERS, USER_42))
					.isInstanceOf(ShardUnavailableException.class)
					.hasMessage("Shard shard-a did not answer");
		}
	}

	@Nested
	class RealConnection {

		@Test
		void shardThatDoesNotAnswerIsUnavailableAfterTheTimeout() throws IOException {
			try (ServerSocket hangingShard = silentServer()) {
				// Arrange
				RemoteShardStorageService remoteShard = RemoteShardStorageService.connect(shard(SHARD_A, hangingShard.getLocalPort()), SHARD_TIMEOUT);
				long start = System.nanoTime();

				// Act
				Throwable thrown = catchThrowable(() -> remoteShard.read(USERS, USER_42));

				// Assert
				Duration waited = Duration.ofNanos(System.nanoTime() - start);
				assertThat(thrown).isInstanceOf(ShardUnavailableException.class);
				assertThat(waited).isGreaterThanOrEqualTo(SHARD_TIMEOUT).isLessThan(SHARD_TIMEOUT.multipliedBy(4));
			}
		}

		@Test
		void stoppedShardIsUnavailable() {
			// Arrange
			RemoteShardStorageService remoteShard = RemoteShardStorageService.connect(shard(SHARD_A, freePort()), SHARD_TIMEOUT);

			// Act & Assert
			assertThatThrownBy(() -> remoteShard.create(USERS, USER_42, ANN_ATTRIBUTES))
					.isInstanceOf(ShardUnavailableException.class)
					.hasMessage("Shard shard-a did not answer");
		}
	}

	private static DefaultResponseCreator respond(HttpStatus status, Object body) {
		return withStatus(status).contentType(MediaType.APPLICATION_JSON).body(json(body));
	}
}
