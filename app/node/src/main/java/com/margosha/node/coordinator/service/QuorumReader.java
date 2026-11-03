package com.margosha.node.coordinator.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.margosha.node.coordinator.exception.ShardUnavailableException;
import com.margosha.node.coordinator.model.ReplicaAnswer;
import com.margosha.node.coordinator.storage.ReplicaClient;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class QuorumReader implements AutoCloseable {

	/** Well above a replica's usual answer time (the whole read's p99 was about 15 ms in the M3 benchmark). */
	public static final Duration DEFAULT_HEDGE_DELAY = Duration.ofMillis(50);

	private final int readQuorum;
	private final long hedgeNanos;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public QuorumReader(int readQuorum) {
		this(readQuorum, DEFAULT_HEDGE_DELAY);
	}

	public QuorumReader(int readQuorum, Duration hedgeDelay) {
		if (readQuorum < 1) {
			throw new IllegalArgumentException("The read quorum must be 1 or higher, was " + readQuorum);
		}
		if (hedgeDelay.isNegative() || hedgeDelay.isZero()) {
			throw new IllegalArgumentException("The hedge delay must be positive, was " + hedgeDelay);
		}
		this.readQuorum = readQuorum;
		this.hedgeNanos = hedgeDelay.toNanos();
	}

	public ReplicaAnswer read(ReplicaSet replicaSet, Function<ReplicaClient, ReplicaAnswer> request) {
		List<ReplicaClient> order = replicaSet.readOrder(readQuorum);
		CompletionService<ReplicaAnswer> answers = new ExecutorCompletionService<>(executor);
		Asked asked = new Asked(replicaSet, order, answers, request);
		while (asked.count() < Math.min(readQuorum, order.size())) {
			asked.next();
		}
		List<ReplicaAnswer> received = new ArrayList<>(readQuorum);
		long hedgeAt = System.nanoTime() + hedgeNanos;
		try {
			while (received.size() < readQuorum && asked.pending() > 0) {
				Future<ReplicaAnswer> done;
				if (asked.hasMore()) {
					done = answers.poll(Math.max(0, hedgeAt - System.nanoTime()), TimeUnit.NANOSECONDS);
					if (done == null) {
						asked.next();
						hedgeAt = System.nanoTime() + hedgeNanos;
						continue;
					}
				} else {
					done = answers.take();
				}
				asked.finished();
				try {
					received.add(done.get());
				} catch (ExecutionException failed) {
					log.debug("A replica of shard {} did not answer a read: {}", replicaSet.getShardId(),
							failed.getCause().getMessage());
					if (asked.hasMore()) {
						asked.next();
						hedgeAt = System.nanoTime() + hedgeNanos;
					}
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
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

	private static final class Asked {

		private final ReplicaSet replicaSet;
		private final List<ReplicaClient> order;
		private final CompletionService<ReplicaAnswer> answers;
		private final Function<ReplicaClient, ReplicaAnswer> request;
		private int count;
		private int pending;

		private Asked(ReplicaSet replicaSet, List<ReplicaClient> order, CompletionService<ReplicaAnswer> answers,
				Function<ReplicaClient, ReplicaAnswer> request) {
			this.replicaSet = replicaSet;
			this.order = order;
			this.answers = answers;
			this.request = request;
		}

		private void next() {
			ReplicaClient replica = order.get(count++);
			pending++;
			answers.submit(() -> {
				try {
					ReplicaAnswer answer = request.apply(replica);
					replicaSet.answered(replica);
					return answer;
				} catch (RuntimeException e) {
					replicaSet.failed(replica);
					throw e;
				}
			});
		}

		private void finished() {
			pending--;
		}

		private boolean hasMore() {
			return count < order.size();
		}

		private int count() {
			return count;
		}

		private int pending() {
			return pending;
		}
	}
}
