package com.margosha.api.client;

import static com.margosha.api.util.ApiCallsUtil.uri;
import static com.margosha.api.util.PathConstants.EXISTS;
import static com.margosha.api.util.PathConstants.RECORD;

import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.ExistsResult;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.util.ApiCallsUtil;

public class ShardClient {

    private final ApiCallsUtil calls;

    public ShardClient(RestClient.Builder builder, String baseUrl) {
        this.calls = new ApiCallsUtil(builder, baseUrl);
    }

    public Attributes createRecord(String tableName, PrimaryKey key, Attributes attributes) {
        return calls.call(HttpMethod.POST, uri(RECORD, tableName, key), attributes, Attributes.class);
    }

    public Attributes readRecord(String tableName, PrimaryKey key) {
        return calls.call(HttpMethod.GET, uri(RECORD, tableName, key), null, Attributes.class);
    }

    public boolean recordExists(String tableName, PrimaryKey key) {
        return calls.call(HttpMethod.GET, uri(EXISTS, tableName, key), null, ExistsResult.class).exists();
    }

    public Attributes updateRecord(String tableName, PrimaryKey key, Attributes attributes) {
        return calls.call(HttpMethod.PUT, uri(RECORD, tableName, key), attributes, Attributes.class);
    }

    public void deleteRecord(String tableName, PrimaryKey key) {
        calls.call(HttpMethod.DELETE, uri(RECORD, tableName, key), null, Void.class);
    }
}
