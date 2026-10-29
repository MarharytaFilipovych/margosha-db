package com.margosha.node.shard.service;

import static com.margosha.node.data.TestData.FIRST_RETRY;
import static com.margosha.node.data.TestData.MAX_RETRY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RetryBackoffTest {

	private final RetryBackoff backoff = new RetryBackoff(FIRST_RETRY, MAX_RETRY);

	@Test
	void pauseDoublesAfterEachFailedAttempt() {
		// Arrange
		Duration second = backoff.after(FIRST_RETRY);

		// Act
		Duration third = backoff.after(second);

		// Assert
		assertThat(second).isEqualTo(Duration.ofSeconds(4));
		assertThat(third).isEqualTo(Duration.ofSeconds(8));
	}

	@Test
	void pauseStopsGrowingAtTheMaximum() {
		// Arrange
		Duration almostMax = Duration.ofSeconds(16);

		// Act
		Duration capped = backoff.after(almostMax);

		// Assert
		assertThat(capped).isEqualTo(MAX_RETRY);
		assertThat(backoff.after(capped)).isEqualTo(MAX_RETRY);
	}

	@Test
	void firstPauseMustBePositive() {
		// Arrange
		Duration zero = Duration.ZERO;

		// Act & Assert
		assertThatThrownBy(() -> new RetryBackoff(zero, MAX_RETRY))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("must be positive");
	}

	@Test
	void maximumShorterThanFirstPauseIsRejected() {
		// Arrange
		Duration shorterMax = FIRST_RETRY.minusMillis(1);

		// Act & Assert
		assertThatThrownBy(() -> new RetryBackoff(FIRST_RETRY, shorterMax))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("shorter than the first");
	}
}
