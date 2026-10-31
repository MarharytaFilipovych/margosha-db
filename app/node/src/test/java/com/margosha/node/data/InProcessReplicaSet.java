package com.margosha.node.data;

import static com.margosha.node.data.InProcessNode.registeringWith;
import static com.margosha.node.data.TestData.testDataDir;

import java.nio.file.Path;
import java.util.List;

import lombok.Getter;

@Getter
public final class InProcessReplicaSet implements AutoCloseable {

	private final String shardId;
	private final String coordinatorUrl;
	private final InProcessNode leader;
	private final InProcessNode follower2;
	private InProcessNode follower3;
	private final Path follower3Dir;

	private InProcessReplicaSet(String shardId, String coordinatorUrl) {
		this.shardId = shardId;
		this.coordinatorUrl = coordinatorUrl;
		this.leader = InProcessNode.leader(shardId, testDataDir(shardId + "-1"), registeringWith(coordinatorUrl));
		this.follower2 = InProcessNode.follower(shardId, shardId + "-2", testDataDir(shardId + "-2"), leader,
				registeringWith(coordinatorUrl));
		this.follower3Dir = testDataDir(shardId + "-3");
		this.follower3 = InProcessNode.follower(shardId, shardId + "-3", follower3Dir, leader,
				registeringWith(coordinatorUrl));
	}

	public static InProcessReplicaSet start(String shardId, String coordinatorUrl) {
		return new InProcessReplicaSet(shardId, coordinatorUrl);
	}

	public List<InProcessNode> replicas() {
		return List.of(leader, follower2, follower3);
	}

	/**
	 * Restarts follower 3 on its own data, pointed at a leader that doesn't exist: it keeps what it has and receives
	 * nothing new, like a follower cut off from its leader. It registers with the coordinator again at its new address.
	 */
	public InProcessNode cutOffFollower3() {
		follower3.close();
		follower3 = InProcessNode.follower(shardId, shardId + "-3", follower3Dir,
				TestData.localAddress(TestData.freePort()), registeringWith(coordinatorUrl));
		return follower3;
	}

	@Override
	public void close() {
		follower3.close();
		follower2.close();
		leader.close();
	}
}
