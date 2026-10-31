package com.margosha.api.model;

import java.util.List;

public record ReplicationBatch(List<ReplicationEntry> entries, long leaderLastVersion) {

    public ReplicationBatch {
        entries = List.copyOf(entries);
    }
}
