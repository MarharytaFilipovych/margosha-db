package com.margosha.api.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplicationEntry(long version, String table, String partitionKey, String sortKey,
        Map<String, String> attributes, boolean deleted) { }
