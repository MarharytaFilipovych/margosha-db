package com.margosha.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplicaExists(boolean exists, long logVersion,
                            RecordVersion recordVersion) { }
