package com.margosha.api.model;

import java.util.Map;

public record ReplicaStatus(String shardId, String replicaId, String role, long lastVersion,
        Map<String, Long> followerPositions, long readsServed, long entriesReceived) {

    public ReplicaStatus {
        followerPositions = Map.copyOf(followerPositions);
    }
}
