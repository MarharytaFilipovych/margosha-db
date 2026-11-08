package com.margosha.api.model;

public record ReplicaRecord(Attributes attributes, long logVersion,
                            RecordVersion recordVersion) { }
