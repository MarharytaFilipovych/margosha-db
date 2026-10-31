package com.margosha.api.util;

public final class PathConstants {

    public static final String TABLES = "/tables";
    public static final String SHARDS = "/shards";
    public static final String RECORDS = TABLES + "/{tableName}/records";
    public static final String RECORD = RECORDS + "/{partitionKey}";
    public static final String EXISTS = RECORD + "/exists";

    public static final String REPLICATION = "/replication";
    public static final String REPLICATION_LOG = REPLICATION + "/log";
    public static final String REPLICATION_STATUS = REPLICATION + "/status";

    private PathConstants() { }
}
