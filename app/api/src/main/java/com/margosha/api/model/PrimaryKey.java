package com.margosha.api.model;

public record PrimaryKey(String partitionKey, String sortKey) {

    public static PrimaryKey of(String partitionKey) {
        return new PrimaryKey(partitionKey, null);
    }

    /** A blank sort key (e.g. {@code ?sortKey=}) means "no sort key". */
    public static PrimaryKey of(String partitionKey, String sortKey) {
        return new PrimaryKey(partitionKey, sortKey == null || sortKey.isBlank() ? null : sortKey);
    }
}
