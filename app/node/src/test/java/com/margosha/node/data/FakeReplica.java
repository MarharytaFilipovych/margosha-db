package com.margosha.node.data;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ShardRegistration;
import com.margosha.node.coordinator.exception.ShardUnavailableException;
import com.margosha.node.coordinator.model.ReplicaAnswer;
import com.margosha.node.coordinator.storage.ReplicaClient;


public final class FakeReplica implements ReplicaClient {

	private final ShardRegistration registration;
	private final AtomicInteger reads = new AtomicInteger();
	private final List<String> writes = new ArrayList<>();
	private volatile ReplicaAnswer answer = ReplicaAnswer.notFound(0);
	private volatile boolean down;

	public FakeReplica(ShardRegistration registration) {
		this.registration = registration;
	}

	public FakeReplica answers(ReplicaAnswer answer) {
		this.answer = answer;
		return this;
	}

	public FakeReplica down() {
		this.down = true;
		return this;
	}

	public int reads() {
		return reads.get();
	}

	public List<String> writes() {
		return List.copyOf(writes);
	}

	@Override
	public ShardRegistration registration() {
		return registration;
	}

	@Override
	public void create(String table, PrimaryKey key, Attributes attributes) {
		write("create " + key.partitionKey());
	}

	@Override
	public void update(String table, PrimaryKey key, Attributes attributes) {
		write("update " + key.partitionKey());
	}

	@Override
	public void delete(String table, PrimaryKey key) {
		write("delete " + key.partitionKey());
	}

	@Override
	public ReplicaAnswer read(String table, PrimaryKey key) {
		return answer();
	}

	@Override
	public ReplicaAnswer exists(String table, PrimaryKey key) {
		ReplicaAnswer current = answer();
		return new ReplicaAnswer(current.version(), current.exists(), null);
	}

	private synchronized void write(String write) {
		failIfDown();
		writes.add(write);
	}

	private ReplicaAnswer answer() {
		reads.incrementAndGet();
		failIfDown();
		return answer;
	}

	private void failIfDown() {
		if (down) {
			throw ShardUnavailableException.didNotAnswer(registration.shardId(), registration.replicaId());
		}
	}
}
