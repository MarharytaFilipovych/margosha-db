package com.margosha.ring;

import com.margosha.api.model.PrimaryKey;

public final class RoutingKeys {

    private RoutingKeys() { }

    public static String of(PrimaryKey primaryKey) {
        if (primaryKey == null || primaryKey.partitionKey() == null || primaryKey.partitionKey().isBlank()) {
            throw new IllegalArgumentException("Primary key must have a partition key");
        }
        return primaryKey.partitionKey();
    }
}
