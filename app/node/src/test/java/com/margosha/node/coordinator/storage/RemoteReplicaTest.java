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
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.ReplicaExists;
import com.margosha.api.model.ReplicaRecord;
import com.margosha.api.model.ShardRegistration;
import com.margosha.node.common.exception.RecordAlreadyExistsException;
import com.margosha.node.common.exception.RecordNotFoundException;
import com.margosha.node.coordinator.exception.ShardUnavailableException;
import com.margosha.node.coordinator.model.ReplicaAnswer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;

class RemoteReplicaTest {

	private static final String BASE_URL = "http://shard-a.test:8081";
	private static final String USER_42_RECORD = BASE_URL + "/tables/" + USERS + "/records/" + USER_42.partitionKey();
	private static final ShardRegistration LEADER_A = shard(SHARD_A, 8081);
	private static final String DID_NOT_ANSWER = "Replica shard-a-1 of shard shard-a did not answer";

	@Nested
	class ReplicaAnswers {

		private MockRestServiceServer server;
		private RemoteReplica replica;

		@BeforeEach
		void setUp() {
			RestClient.Builder builder = RestClient.builder();
			server = MockRestServiceServer.bindTo(builder).build();
			replica = new RemoteReplica(LEADER_A, new ShardClient(builder, BASE_URL));
		}

		@Test
		void readReturnsTheAttributesWithTheirVersion() {
			// Arrange
			server.expect(requestTo(USER_42_RECORD)).andExpect(method(HttpMethod.GET))
					.andRespond(respond(HttpStatus.OK, new ReplicaRecord(ANN_ATTRIBUTES, 7)));

			// Act
			ReplicaAnswer answer = replica.read(USERS, USER_42);

			// Assert
			assertThat(answer).isEqualTo(ReplicaAnswer.found(7, ANN_ATTRIBUTES));
			server.verify();
		}

		@Test
		void notFoundWithAVersionIsAnAnswerNotAnError() {
			// Arrange
			server.expect(requestTo(USER_42_RECORD))
					.andRespond(respond(HttpStatus.NOT_FOUND, new ApiError(ErrorCode.RECORD_NOT_FOUND, "deleted", 9L)));

			// Act
			ReplicaAnswer answer = replica.read(USERS, USER_42);

			// Assert
			assertThat(answer).isEqualTo(ReplicaAnswer.notFound(9));
		}

		@Test
		void existsReturnsTheAnswerWithItsVersion() {
			// Arrange
			server.expect(requestTo(USER_42_RECORD + "/exists"))
					.andRespond(respond(HttpStatus.OK, new ReplicaExists(true, 4)));

			// Act
			ReplicaAnswer answer = replica.exists(USERS, USER_42);

			// Assert
			assertThat(answer).isEqualTo(new ReplicaAnswer(4, true, null));
		}

		@Test
		void notFoundOnAWriteIsRecordNotFound() {
			// Arrange
			server.expect(requestTo(USER_42_RECORD)).andExpect(method(HttpMethod.PUT))
					.andRespond(respond(HttpStatus.NOT_FOUND, new ApiError(ErrorCode.RECORD_NOT_FOUND, "missing")));

			// Act
			Throwable thrown = catchThrowable(() -> replica.update(USERS, USER_42, ANN_ATTRIBUTES));

			// Assert
			assertThat(thrown).isInstanceOf(RecordNotFoundException.class)
					.hasMessage(new RecordNotFoundException(USERS, USER_42).getMessage());
		}

		@Test
		void conflictIsRecordAlreadyExists() {
			// Arrange
			server.expect(requestTo(USER_42_RECORD)).andExpect(method(HttpMethod.POST))
					.andRespond(respond(HttpStatus.CONFLICT, new ApiError(ErrorCode.RECORD_ALREADY_EXISTS, "exists")));

			// Act
			Throwable thrown = catchThrowable(() -> replica.create(USERS, USER_42, ANN_ATTRIBUTES));

			// Assert
			assertThat(thrown).isInstanceOf(RecordAlreadyExistsException.class);
		}

		@Test
		void writeTheLeaderCouldNotConfirmIsShardUnavailable() {
			// Arrange
			String message = "Write 7 was stored by the leader but not confirmed by 1 follower(s) in time; its outcome is unknown";
			server.expect(requestTo(USER_42_RECORD)).andExpect(method(HttpMethod.POST))
					.andRespond(respond(HttpStatus.SERVICE_UNAVAILABLE, new ApiError(ErrorCode.SHARD_UNAVAILABLE, message)));

			// Act
			Throwable thrown = catchThrowable(() -> replica.create(USERS, USER_42, ANN_ATTRIBUTES));

			// Assert
			assertThat(thrown).isInstanceOf(ShardUnavailableException.class).hasMessage(message);
		}

		@Test
		void otherReplicaErrorIsPassedOnUnchanged() {
			// Arrange
			server.expect(requestTo(USER_42_RECORD))
					.andRespond(respond(HttpStatus.INTERNAL_SERVER_ERROR, new ApiError(ErrorCode.INTERNAL_ERROR, "boom")));

			// Act
			Throwable thrown = catchThrowable(() -> replica.read(USERS, USER_42));

			// Assert
			assertThat(thrown).isInstanceOfSatisfying(ApiException.class,
					e -> assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL_ERROR));
		}

		@Test
		void connectionFailureIsShardUnavailable() {
			// Arrange
			server.expect(requestTo(USER_42_RECORD)).andRespond(withException(new IOException("connection reset")));

			// Act
			Throwable thrown = catchThrowable(() -> replica.read(USERS, USER_42));

			// Assert
			assertThat(thrown).isInstanceOf(ShardUnavailableException.class).hasMessage(DID_NOT_ANSWER);
		}
	}

	@Nested
	class RealConnection {

		@Test
		void replicaThatDoesNotAnswerIsUnavailableAfterTheTimeout() throws IOException {
			try (ServerSocket hangingReplica = silentServer()) {
				// Arrange
				RemoteReplica replica = RemoteReplica.connect(shard(SHARD_A, hangingReplica.getLocalPort()), SHARD_TIMEOUT);
				long start = System.nanoTime();

				// Act
				Throwable thrown = catchThrowable(() -> replica.read(USERS, USER_42));

				// Assert
				Duration waited = Duration.ofNanos(System.nanoTime() - start);
				assertThat(thrown).isInstanceOf(ShardUnavailableException.class);
				assertThat(waited).isGreaterThanOrEqualTo(SHARD_TIMEOUT).isLessThan(SHARD_TIMEOUT.multipliedBy(4));
			}
		}

		@Test
		void stoppedReplicaIsUnavailable() {
			// Arrange
			RemoteReplica replica = RemoteReplica.connect(shard(SHARD_A, freePort()), SHARD_TIMEOUT);

			// Act
			Throwable thrown = catchThrowable(() -> replica.create(USERS, USER_42, ANN_ATTRIBUTES));

			// Assert
			assertThat(thrown).isInstanceOf(ShardUnavailableException.class).hasMessage(DID_NOT_ANSWER);
		}
	}

	private static DefaultResponseCreator respond(HttpStatus status, Object body) {
		return withStatus(status).contentType(MediaType.APPLICATION_JSON).body(json(body));
	}
}
