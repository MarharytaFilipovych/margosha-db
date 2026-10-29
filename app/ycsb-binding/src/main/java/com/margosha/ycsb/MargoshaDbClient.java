package com.margosha.ycsb;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.exception.ApiException;
import com.margosha.api.model.Attributes;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.TableRecord;
import com.margosha.api.util.RestClientBuilders;
import org.springframework.web.client.RestClientException;
import site.ycsb.ByteIterator;
import site.ycsb.DB;
import site.ycsb.DBException;
import site.ycsb.Status;
import site.ycsb.StringByteIterator;

public class MargoshaDbClient extends DB {

    public static final String URL_PROPERTY = "margosha.url";
    public static final String PARTITION_KEY_PROPERTY = "margosha.partitionkey";
    public static final String TIMEOUT_PROPERTY = "margosha.timeout";

    public static final String DEFAULT_URL = "http://localhost:8080";
    public static final String DEFAULT_PARTITION_KEY = "ycsb_key";
    public static final String DEFAULT_TIMEOUT_MS = "10000";

    private static final int MAX_PRINTED_ERRORS = 10;
    private static final AtomicInteger PRINTED_ERRORS = new AtomicInteger();

    private CoordinatorClient client;
    private String partitionKey;

    @Override
    public void init() throws DBException {
        Properties properties = getProperties();
        String url = properties.getProperty(URL_PROPERTY, DEFAULT_URL);
        partitionKey = properties.getProperty(PARTITION_KEY_PROPERTY, DEFAULT_PARTITION_KEY);
        Duration timeout;
        try {
            timeout = Duration.ofMillis(Long.parseLong(properties.getProperty(TIMEOUT_PROPERTY, DEFAULT_TIMEOUT_MS)));
        } catch (NumberFormatException e) {
            throw new DBException(TIMEOUT_PROPERTY + " must be a number of milliseconds", e);
        }
        client = new CoordinatorClient(RestClientBuilders.withTimeout(timeout), url);
    }

    @Override
    public Status read(String table, String key, Set<String> fields, Map<String, ByteIterator> result) {
        try {
            Map<String, String> values = new HashMap<>(client.readRecord(table, PrimaryKey.of(key)).values());
            values.remove(partitionKey);
            if (fields != null) {
                values.keySet().retainAll(fields);
            }
            StringByteIterator.putAllAsByteIterators(result, values);
            return Status.OK;
        } catch (RuntimeException e) {
            return failure("read", table, key, e);
        }
    }

    @Override
    public Status insert(String table, String key, Map<String, ByteIterator> values) {
        try {
            Map<String, String> record = StringByteIterator.getStringMap(values);
            record.put(partitionKey, key);
            client.createRecord(table, TableRecord.of(record));
            return Status.OK;
        } catch (RuntimeException e) {
            return failure("insert", table, key, e);
        }
    }

    @Override
    public Status update(String table, String key, Map<String, ByteIterator> values) {
        try {
            client.updateRecord(table, PrimaryKey.of(key), Attributes.of(StringByteIterator.getStringMap(values)));
            return Status.OK;
        } catch (RuntimeException e) {
            return failure("update", table, key, e);
        }
    }

    @Override
    public Status delete(String table, String key) {
        try {
            client.deleteRecord(table, PrimaryKey.of(key));
            return Status.OK;
        } catch (RuntimeException e) {
            return failure("delete", table, key, e);
        }
    }

    @Override
    public Status scan(String table, String startKey, int recordCount, Set<String> fields,
                       Vector<HashMap<String, ByteIterator>> result) {
        return Status.NOT_IMPLEMENTED;
    }

    private static Status failure(String operation, String table, String key, RuntimeException e) {
        Status status = statusFor(e);
        if (PRINTED_ERRORS.incrementAndGet() <= MAX_PRINTED_ERRORS) {
            System.err.println("margosha: " + operation + " " + table + "/" + key + " -> " + status.getName()
                    + ": " + e.getMessage());
        }
        return status;
    }

    static Status statusFor(RuntimeException e) {
        if (e instanceof ApiException api) {
            if (api.isNotFound()) {
                return Status.NOT_FOUND;
            }
            if (api.isValidationError()) {
                return Status.BAD_REQUEST;
            }
            if (api.isUnavailable()) {
                return Status.SERVICE_UNAVAILABLE;
            }
            return Status.ERROR;
        }
        if (e instanceof RestClientException) {
            return Status.SERVICE_UNAVAILABLE;
        }
        return Status.ERROR;
    }
}
