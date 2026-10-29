package com.margosha.node.shard.service;

import java.time.Duration;
import java.util.Objects;

public record RetryBackoff(Duration first, Duration max) {

	public RetryBackoff {
		Objects.requireNonNull(first, "first");
		Objects.requireNonNull(max, "max");
		if (first.isZero() || first.isNegative()) {
			throw new IllegalArgumentException("The first retry interval must be positive, was " + first);
		}
		if (max.compareTo(first) < 0) {
			throw new IllegalArgumentException("The maximum retry interval " + max + " is shorter than the first one " + first);
		}
	}

	public Duration after(Duration previous) {
		Duration doubled = previous.multipliedBy(2);
		return doubled.compareTo(max) > 0 ? max : doubled;
	}
}
