package com.margosha.api.data;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.http.HttpStatus;

import com.margosha.api.model.ApiError;
import com.margosha.api.model.Attributes;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.ExistsResult;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ShardRegistration;
import com.margosha.api.model.TableDefinition;
import com.margosha.api.model.TableRecord;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class TestData {

    public static final String BASE_URL = "http://localhost:8080";
    public static final String USERS = "users";
    public static final String ORDERS = "orders";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TestData() { }

    public static TableDefinition usersTable() {
        return TableDefinition.of(USERS, "user_id");
    }

    public static TableDefinition ordersTable() {
        return new TableDefinition(ORDERS, "customer_id", "order_id");
    }

    public static PrimaryKey userKey() {
        return PrimaryKey.of("42");
    }

    public static PrimaryKey orderKey() {
        return new PrimaryKey("c1", "o7");
    }

    public static TableRecord user() {
        return TableRecord.of(Map.of("user_id", "42", "name", "Ann", "city", "Kyiv"));
    }

    public static TableRecord order() {
        return TableRecord.of(Map.of("customer_id", "c1", "order_id", "o7", "total", "10"));
    }

    public static Attributes renamed() {
        return Attributes.of(Map.of("name", "Bob"));
    }

    public static TableRecord renamedUser() {
        return TableRecord.of(Map.of("user_id", "42", "name", "Bob"));
    }

    public static ShardRegistration shardA() {
        return new ShardRegistration("shard-a", "http://10.0.1.15:8081");
    }

    public static ApiError error(ErrorCode code) {
        return new ApiError(code, "Test error " + code);
    }

    public static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    public static JsonNode tree(String json) {
        return JSON.readTree(json);
    }

    public static <T> T fromJson(String json, Class<T> type) {
        return JSON.readValue(json, type);
    }

    public static Stream<Arguments> modelsWithJson() {
        Stream<Arguments> models = Stream.of(
                Arguments.of(usersTable(), "{\"name\":\"users\",\"partitionKey\":\"user_id\"}"),
                Arguments.of(ordersTable(), "{\"name\":\"orders\",\"partitionKey\":\"customer_id\",\"sortKey\":\"order_id\"}"),
                Arguments.of(user(), "{\"user_id\":\"42\",\"name\":\"Ann\",\"city\":\"Kyiv\"}"),
                Arguments.of(renamed(), "{\"name\":\"Bob\"}"),
                Arguments.of(Attributes.of(Map.of()), "{}"),
                Arguments.of(new ExistsResult(true), "{\"exists\":true}"),
                Arguments.of(new ExistsResult(false), "{\"exists\":false}"),
                Arguments.of(shardA(), "{\"shardId\":\"shard-a\",\"address\":\"http://10.0.1.15:8081\"}"));
        Stream<Arguments> errors = Arrays.stream(ErrorCode.values()).map(code -> Arguments.of(
                error(code), "{\"code\":\"" + code + "\",\"message\":\"Test error " + code + "\"}"));
        return Stream.concat(models, errors);
    }

    public static Stream<Arguments> recordParts() {
        return Stream.of(
                Arguments.of(usersTable(), user(), userKey(), Attributes.of(Map.of("name", "Ann", "city", "Kyiv"))),
                Arguments.of(ordersTable(), order(), orderKey(), Attributes.of(Map.of("total", "10"))));
    }

    public static Stream<ErrorResponse> errorResponses() {
        return Stream.of(
                new ErrorResponse(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR),
                new ErrorResponse(HttpStatus.NOT_FOUND, ErrorCode.TABLE_NOT_FOUND),
                new ErrorResponse(HttpStatus.NOT_FOUND, ErrorCode.RECORD_NOT_FOUND),
                new ErrorResponse(HttpStatus.CONFLICT, ErrorCode.TABLE_ALREADY_EXISTS),
                new ErrorResponse(HttpStatus.CONFLICT, ErrorCode.RECORD_ALREADY_EXISTS));
    }

    public static Stream<Arguments> keysNeedingEncoding() {
        return Stream.of(
                Arguments.of(new PrimaryKey("a b", "o 1"), "/tables/orders/records/a%20b?sortKey=o%201"),
                Arguments.of(new PrimaryKey("a/b", "o/1"), "/tables/orders/records/a%2Fb?sortKey=o%2F1"),
                Arguments.of(new PrimaryKey("a&b", "o&1=2"), "/tables/orders/records/a%26b?sortKey=o%261%3D2"),
                Arguments.of(new PrimaryKey("a+b", "o?1#"), "/tables/orders/records/a%2Bb?sortKey=o%3F1%23"));
    }


    public record ErrorResponse(HttpStatus status, ErrorCode code) {
        @Override
        public @NonNull String toString() {
            return status.value() + " " + code;
        }
    }
}
