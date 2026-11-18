package com.margosha.node.coordinator.metrics;

import java.util.List;
import java.util.stream.IntStream;

import com.margosha.api.model.ReplicaRole;
import lombok.NonNull;

public record ExpectedReplica(@NonNull String shardId, @NonNull String replicaId, @NonNull ReplicaRole role) {

	public static List<ExpectedReplica> of(@NonNull List<String> shardIds, int replicationFactor) {
		return shardIds.stream()
				.flatMap(shardId -> IntStream.rangeClosed(1, replicationFactor)
						.mapToObj(number -> new ExpectedReplica(shardId, shardId + "-" + number,
								number == 1 ? ReplicaRole.LEADER : ReplicaRole.FOLLOWER)))
				.toList();
	}
}
