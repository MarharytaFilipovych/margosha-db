package com.margosha.node.common.metrics;

import com.margosha.node.shard.replication.leader.ReplicationLeader;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

public final class ReplicationLagMetrics {

	public static final String LAG = "margosha.replication.lag";

	private ReplicationLagMetrics() { }

	public static void register(MeterRegistry registry, ReplicationLeader leader) {
		for (String followerId : leader.followerIds()) {
			Gauge.builder(LAG, leader, l -> l.lagSeconds(followerId))
					.description("How far a follower is behind its leader: the age of the oldest entry it hasn't confirmed")
					.baseUnit("seconds")
					.tag("follower", followerId)
					.strongReference(true)
					.register(registry);
		}
	}
}
