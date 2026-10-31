package com.margosha.node.coordinator.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import com.margosha.node.coordinator.exception.ShardUnavailableException;
import com.margosha.node.coordinator.model.ReplicaAnswer;
import com.margosha.node.coordinator.storage.ReplicaClient;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class QuorumReader implements AutoCloseable {

	private final int readQuorum;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public QuorumReader(int readQuorum) {
		if (readQuorum < 1) {
			throw new IllegalArgumentException("The read quorum must be 1 or higher, was " + readQuorum);
		}
		this.readQuorum = readQuorum;
	}

	public ReplicaAnswer read(ReplicaSet replicaSet, Function<ReplicaClient, ReplicaAnswer> request) {
		List<ReplicaClient> order = replicaSet.readOrder();
		CompletionService<ReplicaAnswer> answers = new ExecutorCompletionService<>(executor);
		int asked = 0;
		int pending = 0;
		while (asked < Math.min(readQuorum, order.size())) {
			ReplicaClient replica = order.get(asked++);
			answers.submit(() -> request.apply(replica));
			pending++;
		}
		List<ReplicaAnswer> received = new ArrayList<>(readQuorum);
		while (received.size() < readQuorum && pending > 0) {
			try {
				received.add(answers.take().get());
			} catch (ExecutionException failed) {
				log.debug("A replica of shard {} did not answer a read: {}", replicaSet.getShardId(),
						failed.getCause().getMessage());
				if (asked < order.size()) {
					ReplicaClient replica = order.get(asked++);
					answers.submit(() -> request.apply(replica));
					pending++;
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} finally {
				pending--;
			}
		}
		if (received.size() < readQuorum) {
			throw ShardUnavailableException.notEnoughReplicas(replicaSet.getShardId(), received.size(), readQuorum);
		}
		return received.stream().max(Comparator.comparingLong(ReplicaAnswer::version)).orElseThrow();
	}

	@Override
	public void close() {
		executor.close();
	}
}
