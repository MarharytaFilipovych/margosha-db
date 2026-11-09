package com.margosha.node.common.metrics;

import com.margosha.api.model.ReplicaRole;
import com.margosha.node.coordinator.service.ShardRegistryService;
import com.margosha.node.shard.replication.storage.ReplicaStorage;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

public final class ClusterMetrics {

	public static final String REPLICAS = "margosha.cluster.replicas";
	public static final String RECORDS = "margosha.replica.records";
	public static final String UP = "margosha.replica.up";

	private ClusterMetrics() { }

	public static void registerShard(MeterRegistry registry, ShardRegistryService shardRegistryService, String shardId) {
		for (ReplicaRole role : ReplicaRole.values()) {
			Gauge.builder(REPLICAS, shardRegistryService, s -> s.replicaCount(shardId, role))
					.description("Replicas registered with the coordinator")
					.tag("shard", shardId)
					.tag("role", role.argument())
					.strongReference(true)
					.register(registry);
		}
	}

	public static void registerRecords(MeterRegistry registry, ReplicaStorage storage) {
		Gauge.builder(RECORDS, storage, ReplicaStorage::recordCount)
				.description("Records this replica holds")
				.strongReference(true)
				.register(registry);
	}
}
