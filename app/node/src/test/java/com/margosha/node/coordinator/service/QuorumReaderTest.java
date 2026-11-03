package com.margosha.node.coordinator.service;

import static com.margosha.node.data.TestData.ANN_ATTRIBUTES;
import static com.margosha.node.data.TestData.BOB_ATTRIBUTES;
import static com.margosha.node.data.TestData.SHARD_A;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.follower;
import static com.margosha.node.data.TestData.shard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import com.margosha.node.coordinator.exception.ShardUnavailableException;
import com.margosha.node.coordinator.model.ReplicaAnswer;
import com.margosha.node.data.FakeReplica;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class QuorumReaderTest {

	private final FakeReplica leader = new FakeReplica(shard(SHARD_A, 8081));
	private final FakeReplica follower2 = new FakeReplica(follower(SHARD_A, SHARD_A + "-2", 8082));
	private final FakeReplica follower3 = new FakeReplica(follower(SHARD_A, SHARD_A + "-3", 8083));
	private final QuorumReader reader = new QuorumReader(2);

	@AfterEach
	void tearDown() {
		reader.close();
	}

	@Test
	void newestVersionWinsWhenAReplicaIsBehind() {
		// Arrange
		leader.answers(ReplicaAnswer.found(5, BOB_ATTRIBUTES));
		follower2.answers(ReplicaAnswer.found(5, BOB_ATTRIBUTES));
		follower3.answers(ReplicaAnswer.found(4, ANN_ATTRIBUTES));
		ReplicaSet replicaSet = replicaSet(leader, follower2, follower3);

		// Act
		List<ReplicaAnswer> answers = IntStream.range(0, 30).mapToObj(i -> read(replicaSet)).toList();

		// Assert
		assertThat(answers).containsOnly(ReplicaAnswer.found(5, BOB_ATTRIBUTES));
		assertThat(follower3.reads()).isGreaterThan(0);
	}

	@Test
	void deleteWithTheHighestVersionWinsOverAnOldValue() {
		// Arrange
		leader.answers(ReplicaAnswer.notFound(6));
		follower2.answers(ReplicaAnswer.notFound(6));
		follower3.answers(ReplicaAnswer.found(5, ANN_ATTRIBUTES));
		ReplicaSet replicaSet = replicaSet(leader, follower2, follower3);

		// Act
		List<ReplicaAnswer> answers = IntStream.range(0, 30).mapToObj(i -> read(replicaSet)).toList();

		// Assert
		assertThat(answers).allSatisfy(answer -> {
			assertThat(answer.exists()).isFalse();
			assertThat(answer.version()).isEqualTo(6);
		});
	}

	@Test
	void replicaThatNeverReceivedTheRecordLosesToOneThatHasIt() {
		// Arrange
		leader.answers(ReplicaAnswer.found(3, ANN_ATTRIBUTES));
		follower2.answers(ReplicaAnswer.found(3, ANN_ATTRIBUTES));
		follower3.answers(ReplicaAnswer.notFound(0));
		ReplicaSet replicaSet = replicaSet(leader, follower2, follower3);

		// Act
		List<ReplicaAnswer> answers = IntStream.range(0, 30).mapToObj(i -> read(replicaSet)).toList();

		// Assert
		assertThat(answers).containsOnly(ReplicaAnswer.found(3, ANN_ATTRIBUTES));
	}

	@Test
	void readsStillWorkWithOneFollowerDown() {
		// Arrange
		leader.answers(ReplicaAnswer.found(2, BOB_ATTRIBUTES));
		follower2.answers(ReplicaAnswer.found(2, BOB_ATTRIBUTES));
		follower3.down();
		ReplicaSet replicaSet = replicaSet(leader, follower2, follower3);

		// Act
		List<ReplicaAnswer> answers = IntStream.range(0, 30).mapToObj(i -> read(replicaSet)).toList();

		// Assert
		assertThat(answers).containsOnly(ReplicaAnswer.found(2, BOB_ATTRIBUTES));
		assertThat(follower3.reads()).isGreaterThan(0);
	}

	@Test
	void readsFailWithTwoReplicasDown() {
		// Arrange
		leader.answers(ReplicaAnswer.found(2, BOB_ATTRIBUTES));
		follower2.down();
		follower3.down();
		ReplicaSet replicaSet = replicaSet(leader, follower2, follower3);

		// Act
		Throwable thrown = catchThrowable(() -> read(replicaSet));

		// Assert
		assertThat(thrown).isInstanceOf(ShardUnavailableException.class)
				.hasMessage("Only 1 of the 2 replicas a read needs answered for shard shard-a");
	}

	@Test
	void readsStillWorkWithTheLeaderDown() {
		// Arrange
		leader.down();
		follower2.answers(ReplicaAnswer.found(2, BOB_ATTRIBUTES));
		follower3.answers(ReplicaAnswer.found(2, BOB_ATTRIBUTES));
		ReplicaSet replicaSet = replicaSet(leader, follower2, follower3);

		// Act
		ReplicaAnswer answer = read(replicaSet);

		// Assert
		assertThat(answer).isEqualTo(ReplicaAnswer.found(2, BOB_ATTRIBUTES));
	}

	@Test
	void readsAreSpreadEvenlyOverAllReplicas() {
		// Arrange
		leader.answers(ReplicaAnswer.found(1, ANN_ATTRIBUTES));
		follower2.answers(ReplicaAnswer.found(1, ANN_ATTRIBUTES));
		follower3.answers(ReplicaAnswer.found(1, ANN_ATTRIBUTES));
		ReplicaSet replicaSet = replicaSet(leader, follower2, follower3);

		// Act
		IntStream.range(0, 3000).forEach(i -> read(replicaSet));

		// Assert
		assertThat(List.of(leader.reads(), follower2.reads(), follower3.reads()))
				.allSatisfy(reads -> assertThat(reads).isBetween(1800, 2200));
	}

	@Test
	void readAsksOnlyRReplicasWhenTheyAnswer() {
		// Arrange
		leader.answers(ReplicaAnswer.found(1, ANN_ATTRIBUTES));
		follower2.answers(ReplicaAnswer.found(1, ANN_ATTRIBUTES));
		follower3.answers(ReplicaAnswer.found(1, ANN_ATTRIBUTES));
		ReplicaSet replicaSet = replicaSet(leader, follower2, follower3);

		// Act
		read(replicaSet);

		// Assert
		assertThat(leader.reads() + follower2.reads() + follower3.reads()).isEqualTo(2);
	}

	@Test
	void replicaThatDoesNotAnswerDelaysAReadByTheHedgeDelayNotTheTimeout() {
		// Arrange
		leader.answers(ReplicaAnswer.found(2, BOB_ATTRIBUTES));
		follower2.answers(ReplicaAnswer.found(2, BOB_ATTRIBUTES));
		follower3.answers(ReplicaAnswer.found(2, BOB_ATTRIBUTES)).slow(Duration.ofSeconds(1));
		ReplicaSet replicaSet = replicaSet(leader, follower2, follower3);

		// Act
		List<Duration> durations = IntStream.range(0, 9).mapToObj(i -> {
			long start = System.nanoTime();
			assertThat(read(replicaSet)).isEqualTo(ReplicaAnswer.found(2, BOB_ATTRIBUTES));
			return Duration.ofNanos(System.nanoTime() - start);
		}).toList();

		// Assert
		assertThat(follower3.reads()).isGreaterThan(0);
		assertThat(durations).allSatisfy(duration -> assertThat(duration).isLessThan(Duration.ofMillis(500)));
	}

	@Test
	void replicaThatIsDownIsAskedLastUntilItsSkipTimeIsOver() throws InterruptedException {
		// Arrange
		leader.answers(ReplicaAnswer.found(2, BOB_ATTRIBUTES));
		follower2.answers(ReplicaAnswer.found(2, BOB_ATTRIBUTES));
		follower3.down();
		ReplicaSet replicaSet = replicaSet(Duration.ofMillis(300), leader, follower2, follower3);
		IntStream.range(0, 30).forEach(i -> read(replicaSet));
		int readsWhileSkipped = follower3.reads();

		// Act
		Thread.sleep(400);
		IntStream.range(0, 30).forEach(i -> read(replicaSet));

		// Assert: asked once until it failed, then once more when its skip time was over
		assertThat(readsWhileSkipped).isEqualTo(1);
		assertThat(follower3.reads()).isEqualTo(2);
	}

	@Test
	void replicaThatAnswersAgainIsBackInTheRotation() throws InterruptedException {
		// Arrange
		leader.answers(ReplicaAnswer.found(2, BOB_ATTRIBUTES));
		follower2.answers(ReplicaAnswer.found(2, BOB_ATTRIBUTES));
		follower3.answers(ReplicaAnswer.found(2, BOB_ATTRIBUTES)).down();
		ReplicaSet replicaSet = replicaSet(Duration.ofMillis(300), leader, follower2, follower3);
		IntStream.range(0, 30).forEach(i -> read(replicaSet));
		follower3.up();

		// Act
		Thread.sleep(400);
		IntStream.range(0, 300).forEach(i -> read(replicaSet));

		// Assert
		assertThat(follower3.reads()).isGreaterThan(180);
	}

	private ReplicaAnswer read(ReplicaSet replicaSet) {
		return reader.read(replicaSet, replica -> replica.read(USERS, USER_42));
	}

	private static ReplicaSet replicaSet(FakeReplica... replicas) {
		return replicaSet(ReplicaSet.DEFAULT_SKIP_AFTER_FAILURE, replicas);
	}

	private static ReplicaSet replicaSet(Duration skipAfterFailure, FakeReplica... replicas) {
		ReplicaSet replicaSet = new ReplicaSet(SHARD_A, skipAfterFailure);
		for (FakeReplica replica : replicas) {
			replicaSet.put(replica);
		}
		return replicaSet;
	}
}
