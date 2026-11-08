package com.margosha.node.coordinator.service;


import static com.margosha.node.data.TestData.ANN_ATTRIBUTES;
import static com.margosha.node.data.TestData.BOB_ATTRIBUTES;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Stream;

import com.margosha.api.model.Hlc;
import com.margosha.api.model.RecordVersion;
import com.margosha.node.coordinator.model.ReplicaAnswer;
import org.junit.jupiter.api.Test;

class LastWriteWinsTest {

	private static final String LEADER = "shard-a-1";
	private static final String OTHER_LEADER = "shard-a-2";

	@Test
	void laterVectorWins() {
		// Arrange
		ReplicaAnswer older = ReplicaAnswer.found(1, version(Map.of(LEADER, 1L), 1_000, 0, LEADER), ANN_ATTRIBUTES);
		ReplicaAnswer newer = ReplicaAnswer.found(2, version(Map.of(LEADER, 2L), 1_000, 1, LEADER), BOB_ATTRIBUTES);

		// Act & Assert
		assertThat(winner(older, newer)).isEqualTo(newer);
		assertThat(winner(newer, older)).isEqualTo(newer);
	}

	@Test
	void newerVectorWinsEvenWithAnEarlierHlc() {
		// Arrange
		ReplicaAnswer older = ReplicaAnswer.found(1, version(Map.of(LEADER, 1L), 5_000, 0, LEADER), ANN_ATTRIBUTES);
		ReplicaAnswer newer = ReplicaAnswer.found(2,
				version(Map.of(LEADER, 1L, OTHER_LEADER, 1L), 1_000, 0, OTHER_LEADER), BOB_ATTRIBUTES);

		// Act & Assert
		assertThat(winner(older, newer)).isEqualTo(newer);
	}

	@Test
	void winningDeleteMeansTheRecordDoesNotExist() {
		// Arrange
		ReplicaAnswer value = ReplicaAnswer.found(1, version(Map.of(LEADER, 1L), 1_000, 0, LEADER), ANN_ATTRIBUTES);
		ReplicaAnswer delete = ReplicaAnswer.notFound(2, version(Map.of(LEADER, 2L), 1_000, 1, LEADER));

		// Act
		ReplicaAnswer won = winner(value, delete);

		// Assert
		assertThat(won.exists()).isFalse();
	}

	@Test
	void concurrentWritesAreDecidedByTheLaterHlc() {
		// Arrange
		ReplicaAnswer first = ReplicaAnswer.found(1, version(Map.of(LEADER, 1L), 1_000, 0, LEADER), ANN_ATTRIBUTES);
		ReplicaAnswer second = ReplicaAnswer.found(1,
				version(Map.of(OTHER_LEADER, 1L), 1_200, 0, OTHER_LEADER), BOB_ATTRIBUTES);

		// Act & Assert
		assertThat(winner(first, second)).isEqualTo(second);
		assertThat(winner(second, first)).isEqualTo(second);
	}

	@Test
	void equalVersionsPickTheSameCopyInBothOrders() {
		// Arrange
		ReplicaAnswer byLeader = ReplicaAnswer.found(4, version(Map.of(LEADER, 1L), 1_000, 0, LEADER), ANN_ATTRIBUTES);
		ReplicaAnswer byOther = ReplicaAnswer.found(4,
				version(Map.of(OTHER_LEADER, 1L), 1_000, 0, OTHER_LEADER), BOB_ATTRIBUTES);
		ReplicaAnswer laterInLog = ReplicaAnswer.found(5, version(Map.of(LEADER, 1L), 1_000, 0, LEADER), BOB_ATTRIBUTES);

		// Act & Assert
		assertThat(winner(byLeader, byOther)).isEqualTo(byOther);
		assertThat(winner(byOther, byLeader)).isEqualTo(byOther);
		assertThat(winner(byOther, laterInLog)).isEqualTo(laterInLog);
		assertThat(winner(laterInLog, byOther)).isEqualTo(laterInLog);
	}

	@Test
	void writtenCopyWinsOverReplicaThatNeverReceivedTheRecord() {
		// Arrange
		ReplicaAnswer neverReceived = ReplicaAnswer.notFound(0, null);
		ReplicaAnswer written = ReplicaAnswer.found(3, version(Map.of(LEADER, 1L), 1_000, 0, LEADER), ANN_ATTRIBUTES);

		// Act & Assert
		assertThat(winner(neverReceived, written)).isEqualTo(written);
	}

	private static ReplicaAnswer winner(ReplicaAnswer first, ReplicaAnswer second) {
		return Stream.of(first, second).max(LastWriteWins.INSTANCE).orElseThrow();
	}

	private static RecordVersion version(Map<String, Long> vector, long l, long c, String writer) {
		return new RecordVersion(vector, new Hlc(l, c), writer);
	}
}
