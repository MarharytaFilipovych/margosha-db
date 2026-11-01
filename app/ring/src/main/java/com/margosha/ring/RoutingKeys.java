package com.margosha.ring;

import com.margosha.api.model.PrimaryKey;

import static org.springframework.util.StringUtils.hasText;

public final class RoutingKeys {

    private RoutingKeys() { }

    public static String of(PrimaryKey primaryKey) {
        if (primaryKey == null || !hasText(primaryKey.partitionKey())) {
            throw new IllegalArgumentException("Primary key must have a partition key");
        }
        return primaryKey.partitionKey();
    }
}
