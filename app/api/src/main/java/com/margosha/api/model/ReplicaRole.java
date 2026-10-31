package com.margosha.api.model;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ReplicaRole {
    @JsonProperty("leader")
    LEADER,
    @JsonProperty("follower")
    FOLLOWER;

    public String argument() {
        return name().toLowerCase(Locale.ROOT);
    }
}
