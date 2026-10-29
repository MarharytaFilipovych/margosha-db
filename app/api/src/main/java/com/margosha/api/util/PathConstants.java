package com.margosha.api.util;

public final class PathConstants {

    public static final String TABLES = "/tables";
    public static final String SHARDS = "/shards";
    public static final String RECORDS = TABLES + "/{tableName}/records";
    public static final String RECORD = RECORDS + "/{partitionKey}";
    public static final String EXISTS = RECORD + "/exists";

    private PathConstants() { }
}
