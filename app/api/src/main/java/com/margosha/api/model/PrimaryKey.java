package com.margosha.api.model;

public record PrimaryKey(String partitionKey, String sortKey) {

    public static PrimaryKey of(String partitionKey) {
        return new PrimaryKey(partitionKey, null);
    }
}
