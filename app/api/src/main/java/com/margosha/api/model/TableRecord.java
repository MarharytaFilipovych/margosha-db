package com.margosha.api.model;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public record TableRecord(@JsonValue Map<String, String> values) implements AttributeMap {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public TableRecord {
        values = AttributeMap.copyOf(values);
    }

    public static TableRecord of(Map<String, String> values) {
        return new TableRecord(values);
    }

    public PrimaryKey keyIn(TableDefinition table) {
        return new PrimaryKey(get(table.partitionKey()), table.hasSortKey() ? get(table.sortKey()) : null);
    }

    public Attributes attributesIn(TableDefinition table) {
        Map<String, String> attributes = new HashMap<>(values);
        table.keyAttributes().forEach(attributes.keySet()::remove);
        return new Attributes(attributes);
    }
}
