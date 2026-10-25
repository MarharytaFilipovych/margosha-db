package com.margosha.api.model;

import java.util.Map;
import java.util.Objects;

public interface AttributeMap {

    Map<String, String> values();

    default String get(String attribute) {
        return values().get(attribute);
    }

    static Map<String, String> copyOf(Map<String, String> values) {
        return Map.copyOf(Objects.requireNonNull(values, "values"));
    }
}
