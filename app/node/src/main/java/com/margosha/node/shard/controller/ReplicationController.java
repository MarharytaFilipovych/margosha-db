package com.margosha.node.shard.controller;

import static com.margosha.api.util.PathConstants.REPLICATION_LOG;
import static com.margosha.api.util.PathConstants.REPLICATION_STATUS;
import static org.springframework.util.StringUtils.hasText;

import java.util.Map;

import com.margosha.api.model.ReplicaStatus;
import com.margosha.api.model.ReplicationBatch;
import com.margosha.node.shard.replication.exception.NotLeaderException;
import com.margosha.node.shard.replication.follower.FollowerReplicator;
import com.margosha.node.shard.replication.leader.ReplicationLeader;
import com.margosha.node.shard.replication.model.LogEntry;
import com.margosha.node.shard.replication.model.ReplicaIdentity;
import com.margosha.node.shard.replication.storage.ReplicaStorage;
import com.margosha.node.shard.service.ReplicaReadService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
	private final ReplicaStorage replicaStorage;
	private final ReplicaReadService replicaReadService;
	private final ReplicationLeader leader;
	private final FollowerReplicator follower;

	public ReplicationController(ReplicaIdentity identity, ReplicaStorage replicaStorage, ReplicaReadService replicaReadService,
								 ObjectProvider<ReplicationLeader> leader, ObjectProvider<FollowerReplicator> follower) {
		this.identity = identity;
		this.replicaStorage = replicaStorage;
		this.replicaReadService = replicaReadService;
		this.leader = leader.getIfAvailable();
		this.follower = follower.getIfAvailable();
	}

	@GetMapping(REPLICATION_LOG)
	public ReplicationBatch log(@RequestParam @Min(value = 0, message = "after must be 0 or higher") long after,
								@RequestParam @NotBlank(message = "shard must be set") String shard,
								@RequestParam @NotBlank(message = "follower must be set") String follower,
								@RequestParam(required = false) String logId,
								@RequestParam(defaultValue = "500")
								@Min(value = 1, message = "max must be between 1 and " + MAX_ENTRIES_PER_FETCH)
								@Max(value = MAX_ENTRIES_PER_FETCH, message = "max must be between 1 and " + MAX_ENTRIES_PER_FETCH)
								int max) {
		if (leader == null) {
			throw new NotLeaderException(identity.shardId(), identity.replicaId());
		}
		return new ReplicationBatch(
				leader.fetch(shard, follower, after, hasText(logId) ? logId : null, max).stream()
						.map(LogEntry::toReplicationEntry).toList(),
				replicaStorage.lastVersion(), replicaStorage.ensureLogId());
	}

	@GetMapping(REPLICATION_STATUS)
	public ReplicaStatus status() {
		return new ReplicaStatus(identity.shardId(), identity.replicaId(), identity.role().argument(),
				replicaStorage.lastVersion(),
				leader != null ? leader.followerPositions() : Map.of(),
				replicaReadService.readsServed(),
				follower != null ? follower.entriesReceived() : 0);
	}
}
