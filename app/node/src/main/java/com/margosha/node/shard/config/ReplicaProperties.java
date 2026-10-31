package com.margosha.node.shard.config;

import com.margosha.api.model.ReplicaRole;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("margosha.replica")
public record ReplicaProperties(String id, ReplicaRole role, String leaderUrl) { }
