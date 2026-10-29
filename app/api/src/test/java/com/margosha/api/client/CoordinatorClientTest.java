package com.margosha.api.client;

import static com.margosha.api.data.TestData.BASE_URL;
import static com.margosha.api.data.TestData.ORDERS;
import static com.margosha.api.data.TestData.USERS;
import static com.margosha.api.data.TestData.error;
import static com.margosha.api.data.TestData.json;
import static com.margosha.api.data.TestData.order;
import static com.margosha.api.data.TestData.orderKey;
import static com.margosha.api.data.TestData.ordersTable;
import static com.margosha.api.data.TestData.renamed;
import static com.margosha.api.data.TestData.renamedUser;
import static com.margosha.api.data.TestData.shardA;
import static com.margosha.api.data.TestData.tree;
import static com.margosha.api.data.TestData.user;
import static com.margosha.api.data.TestData.userKey;
import static com.margosha.api.util.PathConstants.SHARDS;
import static com.margosha.api.util.PathConstants.TABLES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.io.IOException;

import com.margosha.api.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.margosha.api.data.TestData.ErrorResponse;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ShardRegistration;
import com.margosha.api.model.TableDefinition;
import com.margosha.api.model.TableRecord;

class CoordinatorClientTest {

    private static final String RECORDS = "/records";
    private static final String EXISTS = "/exists";
    private static final String SORT_KEY_QUERY = "?sortKey=";

    private static final String USERS_RECORDS = TABLES + "/" + USERS + RECORDS;
    private static final String ORDERS_RECORDS = TABLES + "/" + ORDERS + RECORDS;
    private static final String USER_RECORD = USERS_RECORDS + "/" + userKey().partitionKey();
    private static final String USER_EXISTS = USER_RECORD + EXISTS;
    private static final String ORDER_PATH = ORDERS_RECORDS + "/" + orderKey().partitionKey();
    private static final String ORDER_SORT_KEY = SORT_KEY_QUERY + orderKey().sortKey();
    private static final String ORDER_RECORD = ORDER_PATH + ORDER_SORT_KEY;
    private static final String ORDER_EXISTS = ORDER_PATH + EXISTS + ORDER_SORT_KEY;

    private MockRestServiceServer server;
    private CoordinatorClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CoordinatorClient(builder, BASE_URL);
    }

    @Nested
    class RegisterTable {

        @Test
        void postsTheDefinitionAndReturnsTheRegisteredTable() {
            // Arrange
            expectRequest(HttpMethod.POST, TABLES, ordersTable())
                    .andRespond(respond(HttpStatus.CREATED, ordersTable()));

            // Act
            TableDefinition result = client.registerTable(ordersTable());

            // Assert
            assertThat(result).isEqualTo(ordersTable());
            server.verify();
        }

        @Test
        void existingTableThrowsConflict() {
            // Arrange
            expectRequest(HttpMethod.POST, TABLES, ordersTable())
                    .andRespond(respondWithError(HttpStatus.CONFLICT, ErrorCode.TABLE_ALREADY_EXISTS));

            // Act
            Throwable thrown = catchThrowable(() -> client.registerTable(ordersTable()));

            // Assert
            assertApiError(thrown, HttpStatus.CONFLICT, ErrorCode.TABLE_ALREADY_EXISTS);
        }
    }

    @Nested
    class CreateRecord {

        @Test
        void postsTheRecordAndReturnsIt() {
            // Arrange
            expectRequest(HttpMethod.POST, USERS_RECORDS, user())
                    .andRespond(respond(HttpStatus.CREATED, user()));

            // Act
            TableRecord result = client.createRecord(USERS, user());

            // Assert
            assertThat(result).isEqualTo(user());
            server.verify();
        }

        @Test
        void unknownTableThrowsTableNotFound() {
            // Arrange
            expectRequest(HttpMethod.POST, USERS_RECORDS, user())
                    .andRespond(respondWithError(HttpStatus.NOT_FOUND, ErrorCode.TABLE_NOT_FOUND));

            // Act
            Throwable thrown = catchThrowable(() -> client.createRecord(USERS, user()));

            // Assert
            assertApiError(thrown, HttpStatus.NOT_FOUND, ErrorCode.TABLE_NOT_FOUND);
        }

        @Test
        void existingRecordThrowsConflict() {
            // Arrange
            expectRequest(HttpMethod.POST, USERS_RECORDS, user())
                    .andRespond(respondWithError(HttpStatus.CONFLICT, ErrorCode.RECORD_ALREADY_EXISTS));

            // Act
            Throwable thrown = catchThrowable(() -> client.createRecord(USERS, user()));

            // Assert
            assertApiError(thrown, HttpStatus.CONFLICT, ErrorCode.RECORD_ALREADY_EXISTS);
        }
    }

    @Nested
    class ReadRecord {

        @Test
        void getsTheRecordByPartitionKey() {
            // Arrange
            expectRequest(HttpMethod.GET, USER_RECORD)
                    .andRespond(respond(HttpStatus.OK, user()));

            // Act
            TableRecord result = client.readRecord(USERS, userKey());

            // Assert
            assertThat(result).isEqualTo(user());
            server.verify();
        }

        @Test
        void sendsTheSortKeyAsQueryParameter() {
            // Arrange
            expectRequest(HttpMethod.GET, ORDER_RECORD)
                    .andRespond(respond(HttpStatus.OK, order()));

            // Act
            TableRecord result = client.readRecord(ORDERS, orderKey());

            // Assert
            assertThat(result).isEqualTo(order());
            server.verify();
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @MethodSource("com.margosha.api.data.TestData#keysNeedingEncoding")
        void encodesKeysInTheUri(PrimaryKey key, String expectedPath) {
            // Arrange
            expectRequest(HttpMethod.GET, expectedPath)
                    .andRespond(respond(HttpStatus.OK, order()));

            // Act
            client.readRecord(ORDERS, key);

            // Assert
            server.verify();
        }

        @Test
        void missingRecordThrowsRecordNotFound() {
            // Arrange
            expectRequest(HttpMethod.GET, USER_RECORD)
                    .andRespond(respondWithError(HttpStatus.NOT_FOUND, ErrorCode.RECORD_NOT_FOUND));

            // Act
            Throwable thrown = catchThrowable(() -> client.readRecord(USERS, userKey()));

            // Assert
            assertApiError(thrown, HttpStatus.NOT_FOUND, ErrorCode.RECORD_NOT_FOUND);
        }
    }

    @Nested
    class RecordExists {

        @Test
        void existingRecordReturnsTrue() {
            // Arrange
            expectRequest(HttpMethod.GET, USER_EXISTS)
                    .andRespond(respondJson(HttpStatus.OK, "{\"exists\":true}"));

            // Act
            boolean exists = client.recordExists(USERS, userKey());

            // Assert
            assertThat(exists).isTrue();
            server.verify();
        }

        @Test
        void missingRecordReturnsFalse() {
            // Arrange
            expectRequest(HttpMethod.GET, ORDER_EXISTS)
                    .andRespond(respondJson(HttpStatus.OK, "{\"exists\":false}"));

            // Act
            boolean exists = client.recordExists(ORDERS, orderKey());

            // Assert
            assertThat(exists).isFalse();
            server.verify();
        }

        @Test
        void unknownTableThrowsTableNotFound() {
            // Arrange
            expectRequest(HttpMethod.GET, USER_EXISTS)
                    .andRespond(respondWithError(HttpStatus.NOT_FOUND, ErrorCode.TABLE_NOT_FOUND));

            // Act
            Throwable thrown = catchThrowable(() -> client.recordExists(USERS, userKey()));

            // Assert
            assertApiError(thrown, HttpStatus.NOT_FOUND, ErrorCode.TABLE_NOT_FOUND);
        }
    }

    @Nested
    class UpdateRecord {

        @Test
        void putsOnlyTheAttributesAndReturnsTheUpdatedRecord() {
            // Arrange
            expectRequest(HttpMethod.PUT, USER_RECORD, renamed())
                    .andRespond(respond(HttpStatus.OK, renamedUser()));

            // Act
            TableRecord result = client.updateRecord(USERS, userKey(), renamed());

            // Assert
            assertThat(result).isEqualTo(renamedUser());
            server.verify();
        }

        @Test
        void missingRecordThrowsRecordNotFound() {
            // Arrange
            expectRequest(HttpMethod.PUT, USER_RECORD, renamed())
                    .andRespond(respondWithError(HttpStatus.NOT_FOUND, ErrorCode.RECORD_NOT_FOUND));

            // Act
            Throwable thrown = catchThrowable(() -> client.updateRecord(USERS, userKey(), renamed()));

            // Assert
            assertApiError(thrown, HttpStatus.NOT_FOUND, ErrorCode.RECORD_NOT_FOUND);
        }
    }

    @Nested
    class DeleteRecord {

        @Test
        void sendsDeleteForTheKey() {
            // Arrange
            expectRequest(HttpMethod.DELETE, ORDER_RECORD)
                    .andRespond(withStatus(HttpStatus.NO_CONTENT));

            // Act
            client.deleteRecord(ORDERS, orderKey());

            // Assert
            server.verify();
        }

        @Test
        void missingRecordThrowsRecordNotFound() {
            // Arrange
            expectRequest(HttpMethod.DELETE, USER_RECORD)
                    .andRespond(respondWithError(HttpStatus.NOT_FOUND, ErrorCode.RECORD_NOT_FOUND));

            // Act
            Throwable thrown = catchThrowable(() -> client.deleteRecord(USERS, userKey()));

            // Assert
            assertApiError(thrown, HttpStatus.NOT_FOUND, ErrorCode.RECORD_NOT_FOUND);
        }
    }

    @Nested
    class RegisterShard {

        @Test
        void postsTheRegistrationAndReturnsIt() {
            // Arrange
            expectRequest(HttpMethod.POST, SHARDS, shardA())
                    .andRespond(respond(HttpStatus.OK, shardA()));

            // Act
            ShardRegistration result = client.registerShard(shardA());

            // Assert
            assertThat(result).isEqualTo(shardA());
            server.verify();
        }

        @Test
        void invalidRegistrationThrowsValidationError() {
            // Arrange
            ShardRegistration invalid = new ShardRegistration("", "");
            expectRequest(HttpMethod.POST, SHARDS, invalid)
                    .andRespond(respondWithError(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR));

            // Act
            Throwable thrown = catchThrowable(() -> client.registerShard(invalid));

            // Assert
            assertApiError(thrown, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR);
        }
    }

    @Nested
    class Errors {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.margosha.api.data.TestData#errorResponses")
        void everyErrorResponseIsThrownAsApiException(ErrorResponse response) {
            // Arrange
            expectRequest(HttpMethod.GET, USER_RECORD)
                    .andRespond(respondWithError(response.status(), response.code()));

            // Act
            Throwable thrown = catchThrowable(() -> client.readRecord(USERS, userKey()));

            // Assert
            assertApiError(thrown, response.status(), response.code());
        }

        @Test
        void errorWithoutErrorBodyHasNoError() {
            // Arrange
            expectRequest(HttpMethod.GET, USER_RECORD)
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("oops"));

            // Act
            Throwable thrown = catchThrowable(() -> client.readRecord(USERS, userKey()));

            // Assert
            assertThat(thrown).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
                assertThat(e.error()).isNull();
            });
        }

        @Test
        void connectionFailureIsResourceAccessException() {
            // Arrange
            expectRequest(HttpMethod.GET, USER_RECORD)
                    .andRespond(withException(new IOException("connection refused")));

            // Act
            Throwable thrown = catchThrowable(() -> client.readRecord(USERS, userKey()));

            // Assert
            assertThat(thrown).isInstanceOf(ResourceAccessException.class);
        }
    }

    private ResponseActions expectRequest(HttpMethod httpMethod, String path) {
        return expectRequest(httpMethod, path, null);
    }

    private ResponseActions expectRequest(HttpMethod httpMethod, String path, Object body) {
        return server.expect(requestTo(BASE_URL + path))
                .andExpect(method(httpMethod))
                .andExpect(request -> {
                    String sent = ((MockClientHttpRequest) request).getBodyAsString();
                    if (body == null) {
                        assertThat(sent).isEmpty();
                    } else {
                        assertThat(tree(sent)).isEqualTo(tree(json(body)));
                    }
                });
    }

    private static DefaultResponseCreator respond(HttpStatus status, Object body) {
        return respondJson(status, json(body));
    }

    private static DefaultResponseCreator respondWithError(HttpStatus status, ErrorCode code) {
        return respond(status, error(code));
    }

    private static DefaultResponseCreator respondJson(HttpStatus status, String body) {
        return withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static void assertApiError(Throwable thrown, HttpStatus status, ErrorCode code) {
        assertThat(thrown).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status.value());
            assertThat(e.error()).isEqualTo(error(code));
        });
    }
}
