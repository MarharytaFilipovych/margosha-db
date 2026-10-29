package com.margosha.api.client;

import static com.margosha.api.util.ApiCallsUtil.uri;
import static com.margosha.api.util.PathConstants.EXISTS;
import static com.margosha.api.util.PathConstants.RECORD;
import static com.margosha.api.util.PathConstants.RECORDS;
import static com.margosha.api.util.PathConstants.SHARDS;
import static com.margosha.api.util.PathConstants.TABLES;

import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.ExistsResult;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ShardRegistration;
import com.margosha.api.model.TableDefinition;
import com.margosha.api.model.TableRecord;
import com.margosha.api.util.ApiCallsUtil;

public class CoordinatorClient {

    private final ApiCallsUtil calls;

    public CoordinatorClient(RestClient.Builder builder, String baseUrl) {
        this.calls = new ApiCallsUtil(builder, baseUrl);
    }

    public TableDefinition registerTable(TableDefinition table) {
        return calls.call(HttpMethod.POST, uri(TABLES), table, TableDefinition.class);
    }

    public TableRecord createRecord(String tableName, TableRecord record) {
        return calls.call(HttpMethod.POST, uri(RECORDS, tableName), record, TableRecord.class);
    }

    public TableRecord readRecord(String tableName, PrimaryKey key) {
        return calls.call(HttpMethod.GET, uri(RECORD, tableName, key), null, TableRecord.class);
    }

    public boolean recordExists(String tableName, PrimaryKey key) {
        return calls.call(HttpMethod.GET, uri(EXISTS, tableName, key), null, ExistsResult.class).exists();
    }

    public TableRecord updateRecord(String tableName, PrimaryKey key, Attributes attributes) {
        return calls.call(HttpMethod.PUT, uri(RECORD, tableName, key), attributes, TableRecord.class);
    }

    public void deleteRecord(String tableName, PrimaryKey key) {
        calls.call(HttpMethod.DELETE, uri(RECORD, tableName, key), null, Void.class);
    }

    public ShardRegistration registerShard(ShardRegistration registration) {
        return calls.call(HttpMethod.POST, uri(SHARDS), registration, ShardRegistration.class);
    }
}
