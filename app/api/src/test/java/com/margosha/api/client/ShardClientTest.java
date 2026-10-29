package com.margosha.api.client;

import static com.margosha.api.util.PathConstants.TABLES;
import static com.margosha.api.data.TestData.BASE_URL;
import static com.margosha.api.data.TestData.ORDERS;
import static com.margosha.api.data.TestData.USERS;
import static com.margosha.api.data.TestData.error;
import static com.margosha.api.data.TestData.json;
import static com.margosha.api.data.TestData.orderAttributes;
import static com.margosha.api.data.TestData.orderKey;
import static com.margosha.api.data.TestData.renamed;
import static com.margosha.api.data.TestData.tree;
import static com.margosha.api.data.TestData.userAttributes;
import static com.margosha.api.data.TestData.userKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.io.IOException;

import com.margosha.api.exception.ApiException;
import com.margosha.api.model.Attributes;
import com.margosha.api.model.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class ShardClientTest {

    private static final String RECORDS = "/records";
    private static final String EXISTS = "/exists";
    private static final String SORT_KEY_QUERY = "?sortKey=";

    private static final String USER_RECORD = TABLES + "/" + USERS + RECORDS + "/" + userKey().partitionKey();
    private static final String USER_EXISTS = USER_RECORD + EXISTS;
    private static final String ORDER_PATH = TABLES + "/" + ORDERS + RECORDS + "/" + orderKey().partitionKey();
    private static final String ORDER_RECORD = ORDER_PATH + SORT_KEY_QUERY + orderKey().sortKey();

    private MockRestServiceServer server;
    private ShardClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ShardClient(builder, BASE_URL);
    }

    @Nested
    class CreateRecord {

        @Test
        void postsOnlyTheAttributesToTheRecordPath() {
            // Arrange
            expectRequest(HttpMethod.POST, USER_RECORD, userAttributes())
                    .andRespond(respond(HttpStatus.CREATED, userAttributes()));

            // Act
            Attributes result = client.createRecord(USERS, userKey(), userAttributes());

            // Assert
            assertThat(result).isEqualTo(userAttributes());
            server.verify();
        }

        @Test
        void sendsTheSortKeyAsQueryParameter() {
            // Arrange
            expectRequest(HttpMethod.POST, ORDER_RECORD, orderAttributes())
                    .andRespond(respond(HttpStatus.CREATED, orderAttributes()));

            // Act
            client.createRecord(ORDERS, orderKey(), orderAttributes());

            // Assert
            server.verify();
        }

        @Test
        void existingRecordThrowsConflict() {
            // Arrange
            expectRequest(HttpMethod.POST, USER_RECORD, userAttributes())
                    .andRespond(respondWithError(HttpStatus.CONFLICT, ErrorCode.RECORD_ALREADY_EXISTS));

            // Act
            Throwable thrown = catchThrowable(() -> client.createRecord(USERS, userKey(), userAttributes()));

            // Assert
            assertApiError(thrown, HttpStatus.CONFLICT, ErrorCode.RECORD_ALREADY_EXISTS);
        }
    }

    @Nested
    class ReadRecord {

        @Test
        void returnsTheStoredAttributes() {
            // Arrange
            expectRequest(HttpMethod.GET, ORDER_RECORD)
                    .andRespond(respond(HttpStatus.OK, orderAttributes()));

            // Act
            Attributes result = client.readRecord(ORDERS, orderKey());

            // Assert
            assertThat(result).isEqualTo(orderAttributes());
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
        void returnsWhatTheShardAnswers() {
            // Arrange
            expectRequest(HttpMethod.GET, USER_EXISTS)
                    .andRespond(respondJson(HttpStatus.OK, "{\"exists\":true}"));

            // Act
            boolean exists = client.recordExists(USERS, userKey());

            // Assert
            assertThat(exists).isTrue();
            server.verify();
        }
    }

    @Nested
    class UpdateRecord {

        @Test
        void putsTheAttributesAndReturnsThem() {
            // Arrange
            expectRequest(HttpMethod.PUT, USER_RECORD, renamed())
                    .andRespond(respond(HttpStatus.OK, renamed()));

            // Act
            Attributes result = client.updateRecord(USERS, userKey(), renamed());

            // Assert
            assertThat(result).isEqualTo(renamed());
            server.verify();
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
    }

    @Nested
    class Errors {

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
