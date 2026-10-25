package com.margosha.api.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TableDefinition(String name, String partitionKey, String sortKey) {

    public static TableDefinition of(String name, String partitionKey) {
        return new TableDefinition(name, partitionKey, null);
    }

    public boolean hasSortKey() {
        return sortKey != null && !sortKey.isBlank();
    }

    public List<String> keyAttributes() {
        return hasSortKey() ? List.of(partitionKey, sortKey) : List.of(partitionKey);
    }
}
