package com.margosha.api.client;

import static com.margosha.api.util.ApiCallsUtil.uri;
import static com.margosha.api.util.PathConstants.EXISTS;
import static com.margosha.api.util.PathConstants.RECORD;
import static com.margosha.api.util.PathConstants.REPLICATION_LOG;
import static com.margosha.api.util.PathConstants.REPLICATION_STATUS;

import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ReplicaExists;
import com.margosha.api.model.ReplicaRecord;
import com.margosha.api.model.ReplicaStatus;
import com.margosha.api.model.ReplicationBatch;
import com.margosha.api.util.ApiCallsUtil;

public class ShardClient {

    private final ApiCallsUtil calls;

    public ShardClient(RestClient.Builder builder, String baseUrl) {
        this.calls = new ApiCallsUtil(builder, baseUrl);
    }

    public Attributes createRecord(String tableName, PrimaryKey key, Attributes attributes) {
        return calls.call(HttpMethod.POST, uri(RECORD, tableName, key), attributes, Attributes.class);
    }

    public ReplicaRecord readVersioned(String tableName, PrimaryKey key) {
        return calls.call(HttpMethod.GET, uri(RECORD, tableName, key), null, ReplicaRecord.class);
    }

    public Attributes readRecord(String tableName, PrimaryKey key) {
        return readVersioned(tableName, key).attributes();
    }

    public ReplicaExists existsVersioned(String tableName, PrimaryKey key) {
        return calls.call(HttpMethod.GET, uri(EXISTS, tableName, key), null, ReplicaExists.class);
    }

    public boolean recordExists(String tableName, PrimaryKey key) {
        return existsVersioned(tableName, key).exists();
    }

    public Attributes updateRecord(String tableName, PrimaryKey key, Attributes attributes) {
        return calls.call(HttpMethod.PUT, uri(RECORD, tableName, key), attributes, Attributes.class);
    }

    public void deleteRecord(String tableName, PrimaryKey key) {
        calls.call(HttpMethod.DELETE, uri(RECORD, tableName, key), null, Void.class);
    }

    public ReplicationBatch fetchLog(long after, String followerId, int maxEntries) {
        return calls.call(HttpMethod.GET, builder -> builder.path(REPLICATION_LOG)
                .queryParam("after", after)
                .queryParam("follower", "{follower}")
                .queryParam("max", maxEntries)
                .build(followerId), null, ReplicationBatch.class);
    }

    public ReplicaStatus status() {
        return calls.call(HttpMethod.GET, uri(REPLICATION_STATUS), null, ReplicaStatus.class);
    }
}
