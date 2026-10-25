package com.margosha.api.client;

import java.net.URI;
import java.util.Optional;
import java.util.function.Function;

import com.margosha.api.exception.ApiException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import com.margosha.api.model.ApiError;
import com.margosha.api.model.Attributes;
import com.margosha.api.model.ExistsResult;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ShardRegistration;
import com.margosha.api.model.TableDefinition;
import com.margosha.api.model.TableRecord;

public class MargoshaDbClient {

    public static final String TABLES = "/tables";
    public static final String SHARDS = "/shards";
    private static final String RECORDS = TABLES + "/{tableName}/records";
    private static final String RECORD = RECORDS + "/{partitionKey}";
    private static final String EXISTS = RECORD + "/exists";

    private final RestClient restClient;

    public MargoshaDbClient(RestClient.Builder builder, String baseUrl) {
        this.restClient = builder.clone()
                .baseUrl(baseUrl)
                .build();
    }

    public TableDefinition registerTable(TableDefinition table) {
        return call(HttpMethod.POST, uri(TABLES), table, TableDefinition.class);
    }

    public TableRecord createRecord(String tableName, TableRecord record) {
        return call(HttpMethod.POST, uri(RECORDS, tableName), record, TableRecord.class);
    }

    public TableRecord readRecord(String tableName, PrimaryKey key) {
        return call(HttpMethod.GET, uri(RECORD, tableName, key), null, TableRecord.class);
    }

    public boolean recordExists(String tableName, PrimaryKey key) {
        return call(HttpMethod.GET, uri(EXISTS, tableName, key), null, ExistsResult.class).exists();
    }

    public TableRecord updateRecord(String tableName, PrimaryKey key, Attributes attributes) {
        return call(HttpMethod.PUT, uri(RECORD, tableName, key), attributes, TableRecord.class);
    }

    public void deleteRecord(String tableName, PrimaryKey key) {
        call(HttpMethod.DELETE, uri(RECORD, tableName, key), null, Void.class);
    }

    public ShardRegistration registerShard(ShardRegistration registration) {
        return call(HttpMethod.POST, uri(SHARDS), registration, ShardRegistration.class);
    }

    private <T> T call(HttpMethod method, Function<UriBuilder, URI> uri, Object body, Class<T> responseType) {
        RestClient.RequestBodySpec request = restClient.method(method).uri(uri).accept(MediaType.APPLICATION_JSON);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        try {
            return request.retrieve().body(responseType);
        } catch (RestClientResponseException e) {
            throw new ApiException(e.getStatusCode().value(), readError(e), e);
        }
    }

    private static Function<UriBuilder, URI> uri(String template, Object... variables) {
        return builder -> builder.path(template).build(variables);
    }

    private static Function<UriBuilder, URI> uri(String template, String tableName, PrimaryKey key) {
        return builder -> builder.path(template)
                .queryParamIfPresent("sortKey", Optional.ofNullable(key.sortKey()).map(sortKey -> "{sortKey}"))
                .build(tableName, key.partitionKey(), key.sortKey());
    }

    private static ApiError readError(RestClientResponseException e) {
        try {
            return e.getResponseBodyAs(ApiError.class);
        } catch (RestClientException notAnError) {
            return null;
        }
    }
}
