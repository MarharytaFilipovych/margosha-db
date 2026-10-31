package com.margosha.node.shard.controller;

import static com.margosha.api.util.PathConstants.REPLICATION_LOG;
import static com.margosha.api.util.PathConstants.REPLICATION_STATUS;
import static org.springframework.util.StringUtils.hasText;

import java.util.Map;

import com.margosha.api.model.ReplicaStatus;
import com.margosha.api.model.ReplicationBatch;
import com.margosha.node.common.exception.ValidationException;
import com.margosha.node.shard.replication.exception.NotLeaderException;
import com.margosha.node.shard.replication.follower.FollowerReplicator;
import com.margosha.node.shard.replication.leader.ReplicationLeader;
import com.margosha.node.shard.replication.model.LogEntry;
import com.margosha.node.shard.replication.model.ReplicaIdentity;
import com.margosha.node.shard.replication.storage.ReplicaStorage;
import com.margosha.node.shard.service.ReplicaReadService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("shard")
public class ReplicationController {

	private static final int MAX_ENTRIES_PER_FETCH = 1000;

	private final ReplicaIdentity identity;
	private final ReplicaStorage storage;
	private final ReplicaReadService reads;
	private final ReplicationLeader leader;
	private final FollowerReplicator follower;

	public ReplicationController(ReplicaIdentity identity, ReplicaStorage storage, ReplicaReadService reads,
			ObjectProvider<ReplicationLeader> leader, ObjectProvider<FollowerReplicator> follower) {
		this.identity = identity;
		this.storage = storage;
		this.reads = reads;
		this.leader = leader.getIfAvailable();
		this.follower = follower.getIfAvailable();
	}

	@GetMapping(REPLICATION_LOG)
	public ReplicationBatch log(@RequestParam long after,
								@RequestParam String follower,
								@RequestParam(defaultValue = "500") int max) {
		if (leader == null) {
			throw new NotLeaderException(identity.shardId(), identity.replicaId());
		}
		if (after < 0 || !hasText(follower) || max < 1 || max > MAX_ENTRIES_PER_FETCH) {
			throw new ValidationException("after must be 0 or higher, follower must be set, and max between 1 and "
					+ MAX_ENTRIES_PER_FETCH);
		}
		return new ReplicationBatch(
				leader.fetch(follower, after, max).stream().map(LogEntry::toReplicationEntry).toList(),
				storage.lastVersion());
	}

	@GetMapping(REPLICATION_STATUS)
	public ReplicaStatus status() {
		return new ReplicaStatus(identity.shardId(), identity.replicaId(), identity.role().argument(),
				storage.lastVersion(),
				leader != null ? leader.followerPositions() : Map.of(),
				reads.readsServed(),
				follower != null ? follower.entriesReceived() : 0);
	}
}
