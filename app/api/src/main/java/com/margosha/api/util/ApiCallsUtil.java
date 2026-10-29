package com.margosha.api.util;

import java.net.URI;
import java.util.Optional;
import java.util.function.Function;

import com.margosha.api.exception.ApiException;
import com.margosha.api.model.ApiError;
import com.margosha.api.model.PrimaryKey;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

public final class ApiCallsUtil {

    private final RestClient restClient;

    public ApiCallsUtil(RestClient.Builder builder, String baseUrl) {
        this.restClient = builder.clone()
                .baseUrl(baseUrl)
                .build();
    }

    public <T> T call(HttpMethod method, Function<UriBuilder, URI> uri, Object body, Class<T> responseType) {
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

    public static Function<UriBuilder, URI> uri(String template, Object... variables) {
        return builder -> builder.path(template).build(variables);
    }

    public static Function<UriBuilder, URI> uri(String template, String tableName, PrimaryKey key) {
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
