package com.margosha.node.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("margosha.replication")
public record ReplicationProperties(int factor, int writeQuorum, int readQuorum, Duration ackTimeout,
		Duration longPoll, int fetchMaxEntries, Duration retryInterval, Duration retryMaxInterval) {

	public ReplicationProperties {
		if (factor < 1) {
			throw new IllegalArgumentException("margosha.replication.factor (N) must be 1 or higher, was " + factor);
		}
		if (writeQuorum < 1 || writeQuorum > factor) {
			throw new IllegalArgumentException("margosha.replication.write-quorum (W) must be between 1 and N = " + factor
					+ ", was " + writeQuorum);
		}
		if (readQuorum < 1 || readQuorum > factor) {
			throw new IllegalArgumentException("margosha.replication.read-quorum (R) must be between 1 and N = " + factor
					+ ", was " + readQuorum);
		}
		if (readQuorum + writeQuorum <= factor) {
			throw new IllegalArgumentException("R + W must be greater than N, so every read meets the latest write; got R = "
					+ readQuorum + ", W = " + writeQuorum + ", N = " + factor);
		}
	}

	/** How many followers must confirm a write before the leader answers: W − 1. */
	public int followerConfirmations() {
		return writeQuorum - 1;
	}
}
