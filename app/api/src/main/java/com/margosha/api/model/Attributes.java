package com.margosha.api.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public record Attributes(@JsonValue Map<String, String> values) implements AttributeMap {

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public Attributes {
        values = AttributeMap.copyOf(values);
    }

    public static Attributes of(Map<String, String> values) {
        return new Attributes(values);
    }
}
