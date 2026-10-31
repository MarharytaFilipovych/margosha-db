package com.margosha.node.shard.config;

import com.margosha.node.shard.replication.model.ReplicaRole;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("margosha.replica")
public record ReplicaProperties(String id, ReplicaRole role, String leaderUrl) { }
