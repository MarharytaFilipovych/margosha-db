package com.margosha.node.shard.replication.log;

import static com.margosha.node.data.TestData.ANN_ATTRIBUTES;
import static com.margosha.node.data.TestData.BOB_ATTRIBUTES;
import static com.margosha.node.data.TestData.ORDERS;
import static com.margosha.node.data.TestData.ORDER_42_1;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.USER_7;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import com.margosha.node.shard.replication.exception.LogCorruptedException;
import com.margosha.node.shard.replication.model.LogEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplicationLogTest {

	@TempDir
	Path dir;

	private Path file() {
		return dir.resolve("replication.log");
	}

	private ReplicationLog open() {
		return ReplicationLog.open(file(), entry -> { });
	}

	private List<LogEntry> reopenAndReplay() {
		List<LogEntry> replayed = new ArrayList<>();
		ReplicationLog.open(file(), replayed::add).close();
		return replayed;
	}

	@Test
	void appendGivesEachEntryTheNextVersion() {
		// Arrange
		try (ReplicationLog log = open()) {

			// Act
			LogEntry first = log.appendNext(USERS, USER_42, ANN_ATTRIBUTES);
			LogEntry second = log.appendNext(USERS, USER_42, BOB_ATTRIBUTES);
			LogEntry third = log.appendNext(USERS, USER_42, null);

			// Assert
			assertThat(first.version()).isEqualTo(1);
			assertThat(second.version()).isEqualTo(2);
			assertThat(third.version()).isEqualTo(3);
			assertThat(third.deleted()).isTrue();
			assertThat(log.lastVersion()).isEqualTo(3);
		}
	}

	@Test
	void emptyLogHasPositionZero() {
		// Arrange
		try (ReplicationLog log = open()) {

			// Act
			long lastVersion = log.lastVersion();

			// Assert
			assertThat(lastVersion).isZero();
			assertThat(file()).exists();
		}
	}

	@Test
	void entriesAreStoredAsChecksummedJsonLines() throws IOException {
		// Arrange
		try (ReplicationLog log = open()) {

			// Act
			log.appendNext(USERS, USER_42, ANN_ATTRIBUTES);
			log.appendNext(ORDERS, ORDER_42_1, null);
		}

		// Assert
		List<String> lines = Files.readAllLines(file(), UTF_8);
		assertThat(lines).hasSize(2);
		assertThat(lines.get(0)).matches("[0-9a-f]{8} \\{\"version\":1,\"table\":\"users\",\"partitionKey\":\"42\",.*")
				.contains("\"name\":\"Ann\"")
				.doesNotContain("deleted");
		assertThat(lines.get(1)).matches("[0-9a-f]{8} \\{\"version\":2,.*")
				.contains("\"sortKey\":\"order-1\"", "\"deleted\":true")
				.doesNotContain("attributes");
	}

	@Test
	void readAfterReturnsOnlyLaterEntriesInOrder() {
		// Arrange
		try (ReplicationLog log = open()) {
			LogEntry first = log.appendNext(USERS, USER_42, ANN_ATTRIBUTES);
			LogEntry second = log.appendNext(USERS, USER_7, BOB_ATTRIBUTES);
			LogEntry third = log.appendNext(USERS, USER_42, null);

			// Act
			List<LogEntry> all = log.readAfter(0, 100);
			List<LogEntry> afterFirst = log.readAfter(1, 100);

			// Assert
			assertThat(all).containsExactly(first, second, third);
			assertThat(afterFirst).containsExactly(second, third);
		}
	}

	@Test
	void readAfterReturnsAtMostTheRequestedNumberOfEntries() {
		// Arrange
		try (ReplicationLog log = open()) {
			for (int i = 0; i < 10; i++) {
				log.appendNext(USERS, USER_42, ANN_ATTRIBUTES);
			}

			// Act
			List<LogEntry> entries = log.readAfter(3, 4);

			// Assert
			assertThat(entries).extracting(LogEntry::version).containsExactly(4L, 5L, 6L, 7L);
		}
	}

	@Test
	void readAfterTheLastEntryIsEmpty() {
		// Arrange
		try (ReplicationLog log = open()) {
			log.appendNext(USERS, USER_42, ANN_ATTRIBUTES);

			// Act
			List<LogEntry> atEnd = log.readAfter(1, 100);
			List<LogEntry> pastEnd = log.readAfter(5, 100);

			// Assert
			assertThat(atEnd).isEmpty();
			assertThat(pastEnd).isEmpty();
		}
	}

	@Test
	void reopenReplaysEveryEntryInOrder() {
		// Arrange
		List<LogEntry> written = new ArrayList<>();
		try (ReplicationLog log = open()) {
			written.add(log.appendNext(USERS, USER_42, ANN_ATTRIBUTES));
			written.add(log.appendNext(ORDERS, ORDER_42_1, BOB_ATTRIBUTES));
			written.add(log.appendNext(USERS, USER_42, null));
		}

		// Act
		List<LogEntry> replayed = reopenAndReplay();

		// Assert
		assertThat(replayed).containsExactlyElementsOf(written);
	}

	@Test
	void reopenedLogContinuesTheVersions() {
		// Arrange
		try (ReplicationLog log = open()) {
			log.appendNext(USERS, USER_42, ANN_ATTRIBUTES);
			log.appendNext(USERS, USER_7, BOB_ATTRIBUTES);
		}

		// Act
		LogEntry next;
		List<LogEntry> afterFirst;
		try (ReplicationLog log = open()) {
			next = log.appendNext(USERS, USER_42, null);
			afterFirst = log.readAfter(1, 100);
		}

		// Assert
		assertThat(next.version()).isEqualTo(3);
		assertThat(afterFirst).extracting(LogEntry::version).containsExactly(2L, 3L);
	}

	@Test
	void lastLineCutOffByACrashIsDroppedOnOpen() throws IOException {
		// Arrange
		try (ReplicationLog log = open()) {
			log.appendNext(USERS, USER_42, ANN_ATTRIBUTES);
			log.appendNext(USERS, USER_7, BOB_ATTRIBUTES);
		}
		byte[] cutOff = new String(ReplicationLog.encode(LogEntry.put(3, USERS, USER_42, BOB_ATTRIBUTES)), UTF_8)
				.substring(0, 30).getBytes(UTF_8);
		Files.write(file(), cutOff, StandardOpenOption.APPEND);

		// Act
		LogEntry next;
		try (ReplicationLog log = open()) {
			next = log.appendNext(USERS, USER_42, null);
		}

		// Assert
		assertThat(next.version()).isEqualTo(3);
		assertThat(reopenAndReplay()).extracting(LogEntry::version).containsExactly(1L, 2L, 3L);
		assertThat(Files.readAllLines(file(), UTF_8)).hasSize(3);
	}

	@Test
	void lastLineWithAWrongChecksumIsDroppedOnOpen() throws IOException {
		// Arrange
		try (ReplicationLog log = open()) {
			log.appendNext(USERS, USER_42, ANN_ATTRIBUTES);
			log.appendNext(USERS, USER_7, BOB_ATTRIBUTES);
		}
		replaceInFile("\"name\":\"Bob\"", "\"name\":\"Bod\"");

		// Act
		List<LogEntry> replayed = reopenAndReplay();

		// Assert
		assertThat(replayed).extracting(LogEntry::version).containsExactly(1L);
		assertThat(Files.readAllLines(file(), UTF_8)).hasSize(1);
	}

	@Test
	void damagedLineThatIsNotTheLastStopsTheOpen() throws IOException {
		// Arrange
		try (ReplicationLog log = open()) {
			log.appendNext(USERS, USER_42, ANN_ATTRIBUTES);
			log.appendNext(USERS, USER_7, BOB_ATTRIBUTES);
			log.appendNext(USERS, USER_42, null);
		}
		replaceInFile("\"name\":\"Bob\"", "\"name\":\"Bod\"");

		// Act
		Throwable thrown = catchThrowable(this::open);

		// Assert
		assertThat(thrown).isInstanceOf(LogCorruptedException.class).hasMessageContaining("line 2");
		assertThat(Files.readAllLines(file(), UTF_8)).hasSize(3);
	}

	@Test
	void damagedLineInTheMiddleIsFoundEvenWhenItIsStillValidJson() throws IOException {
		// Arrange
		try (ReplicationLog log = open()) {
			log.appendNext(USERS, USER_42, ANN_ATTRIBUTES);
			log.appendNext(USERS, USER_7, BOB_ATTRIBUTES);
		}
		replaceInFile("\"version\":1,", "\"version\":2,");

		// Act
		Throwable thrown = catchThrowable(this::open);

		// Assert
		assertThat(thrown).isInstanceOf(LogCorruptedException.class).hasMessageContaining("line 1");
	}

	@Test
	void followerStoresTheNextEntryFromTheLeader() {
		// Arrange
		LogEntry fromLeader = LogEntry.put(1, USERS, USER_42, ANN_ATTRIBUTES);
		try (ReplicationLog log = open()) {

			// Act
			log.appendReplicated(fromLeader);

			// Assert
			assertThat(log.readAfter(0, 10)).containsExactly(fromLeader);
		}
	}

	@Test
	void followerRefusesAnEntryThatWouldSkipAVersion() {
		// Arrange
		try (ReplicationLog log = open()) {
			log.appendReplicated(LogEntry.put(1, USERS, USER_42, ANN_ATTRIBUTES));

			// Act
			Throwable thrown = catchThrowable(() -> log.appendReplicated(LogEntry.put(3, USERS, USER_7, BOB_ATTRIBUTES)));

			// Assert
			assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("version 2");
			assertThat(log.lastVersion()).isEqualTo(1);
		}
	}

	private void replaceInFile(String target, String replacement) throws IOException {
		String content = Files.readString(file(), UTF_8);
		assertThat(content).contains(target);
		Files.writeString(file(), content.replace(target, replacement), UTF_8);
	}
}
