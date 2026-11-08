package com.margosha.node.shard.replication.log;


import static com.margosha.node.data.TestData.writeVersion;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.Hlc;
import com.margosha.api.model.RecordVersion;
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
			LogEntry first = log.appendNext(USERS, USER_42, ANN_ATTRIBUTES, writeVersion(1));
			LogEntry second = log.appendNext(USERS, USER_42, BOB_ATTRIBUTES, writeVersion(1));
			LogEntry third = log.appendNext(USERS, USER_42, null, writeVersion(1));

			// Assert
			assertThat(first.logVersion()).isEqualTo(1);
			assertThat(second.logVersion()).isEqualTo(2);
			assertThat(third.logVersion()).isEqualTo(3);
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
			log.appendNext(USERS, USER_42, ANN_ATTRIBUTES, writeVersion(1));
			log.appendNext(ORDERS, ORDER_42_1, null, writeVersion(1));
		}

		// Assert
		List<String> lines = Files.readAllLines(file(), UTF_8);
		assertThat(lines).hasSize(2);
		assertThat(lines.get(0)).matches("[0-9a-f]{8} \\{\"logVersion\":1,\"table\":\"users\",\"partitionKey\":\"42\",.*")
				.contains("\"name\":\"Ann\"")
				.doesNotContain("deleted");
		assertThat(lines.get(1)).matches("[0-9a-f]{8} \\{\"logVersion\":2,.*")
				.contains("\"sortKey\":\"order-1\"", "\"deleted\":true")
				.doesNotContain("attributes");
	}

	@Test
	void recordVersionIsStoredInTheLineAndReadBackAfterAReopen() throws IOException {
		// Arrange
		RecordVersion version = new RecordVersion(Map.of("shard-a-1", 2L), new Hlc(1_700_000_000_000L, 3), "shard-a-1");
		try (ReplicationLog log = open()) {

			// Act
			log.appendNext(USERS, USER_42, BOB_ATTRIBUTES, version);
			log.appendNext(USERS, USER_7, null, version);
		}

		// Assert
		assertThat(Files.readAllLines(file(), UTF_8).getFirst()).contains(
				"\"recordVersion\":{\"vector\":{\"shard-a-1\":2},\"hlc\":{\"l\":1700000000000,\"c\":3},\"writer\":\"shard-a-1\"}");
		assertThat(reopenAndReplay()).extracting(LogEntry::recordVersion).containsExactly(version, version);
	}



	@Test
	void readAfterReturnsOnlyLaterEntriesInOrder() {
		// Arrange
		try (ReplicationLog log = open()) {
			LogEntry first = log.appendNext(USERS, USER_42, ANN_ATTRIBUTES, writeVersion(1));
			LogEntry second = log.appendNext(USERS, USER_7, BOB_ATTRIBUTES, writeVersion(1));
			LogEntry third = log.appendNext(USERS, USER_42, null, writeVersion(1));

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
				log.appendNext(USERS, USER_42, ANN_ATTRIBUTES, writeVersion(1));
			}

			// Act
			List<LogEntry> entries = log.readAfter(3, 4);

			// Assert
			assertThat(entries).extracting(LogEntry::logVersion).containsExactly(4L, 5L, 6L, 7L);
		}
	}

	@Test
	void readAfterTheLastEntryIsEmpty() {
		// Arrange
		try (ReplicationLog log = open()) {
			log.appendNext(USERS, USER_42, ANN_ATTRIBUTES, writeVersion(1));

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
			written.add(log.appendNext(USERS, USER_42, ANN_ATTRIBUTES, writeVersion(1)));
			written.add(log.appendNext(ORDERS, ORDER_42_1, BOB_ATTRIBUTES, writeVersion(1)));
			written.add(log.appendNext(USERS, USER_42, null, writeVersion(1)));
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
			log.appendNext(USERS, USER_42, ANN_ATTRIBUTES, writeVersion(1));
			log.appendNext(USERS, USER_7, BOB_ATTRIBUTES, writeVersion(1));
		}

		// Act
		LogEntry next;
		List<LogEntry> afterFirst;
		try (ReplicationLog log = open()) {
			next = log.appendNext(USERS, USER_42, null, writeVersion(1));
			afterFirst = log.readAfter(1, 100);
		}

		// Assert
		assertThat(next.logVersion()).isEqualTo(3);
		assertThat(afterFirst).extracting(LogEntry::logVersion).containsExactly(2L, 3L);
	}

	@Test
	void lastLineCutOffByACrashIsDroppedOnOpen() throws IOException {
		// Arrange
		try (ReplicationLog log = open()) {
			log.appendNext(USERS, USER_42, ANN_ATTRIBUTES, writeVersion(1));
			log.appendNext(USERS, USER_7, BOB_ATTRIBUTES, writeVersion(1));
		}
		byte[] cutOff = new String(ReplicationLog.encode(LogEntry.put(3, USERS, USER_42, BOB_ATTRIBUTES, writeVersion(3))), UTF_8)
				.substring(0, 30).getBytes(UTF_8);
		Files.write(file(), cutOff, StandardOpenOption.APPEND);

		// Act
		LogEntry next;
		try (ReplicationLog log = open()) {
			next = log.appendNext(USERS, USER_42, null, writeVersion(1));
		}

		// Assert
		assertThat(next.logVersion()).isEqualTo(3);
		assertThat(reopenAndReplay()).extracting(LogEntry::logVersion).containsExactly(1L, 2L, 3L);
		assertThat(Files.readAllLines(file(), UTF_8)).hasSize(3);
	}

	@Test
	void lastLineWithAWrongChecksumIsDroppedOnOpen() throws IOException {
		// Arrange
		try (ReplicationLog log = open()) {
			log.appendNext(USERS, USER_42, ANN_ATTRIBUTES, writeVersion(1));
			log.appendNext(USERS, USER_7, BOB_ATTRIBUTES, writeVersion(1));
		}
		replaceInFile("\"name\":\"Bob\"", "\"name\":\"Bod\"");

		// Act
		List<LogEntry> replayed = reopenAndReplay();

		// Assert
		assertThat(replayed).extracting(LogEntry::logVersion).containsExactly(1L);
		assertThat(Files.readAllLines(file(), UTF_8)).hasSize(1);
	}

	@Test
	void damagedLineThatIsNotTheLastStopsTheOpen() throws IOException {
		// Arrange
		try (ReplicationLog log = open()) {
			log.appendNext(USERS, USER_42, ANN_ATTRIBUTES, writeVersion(1));
			log.appendNext(USERS, USER_7, BOB_ATTRIBUTES, writeVersion(1));
			log.appendNext(USERS, USER_42, null, writeVersion(1));
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
			log.appendNext(USERS, USER_42, ANN_ATTRIBUTES, writeVersion(1));
			log.appendNext(USERS, USER_7, BOB_ATTRIBUTES, writeVersion(1));
		}
		replaceInFile("\"logVersion\":1,", "\"logVersion\":2,");

		// Act
		Throwable thrown = catchThrowable(this::open);

		// Assert
		assertThat(thrown).isInstanceOf(LogCorruptedException.class).hasMessageContaining("line 1");
	}

	@Test
	void followerStoresTheNextEntryFromTheLeader() {
		// Arrange
		LogEntry fromLeader = LogEntry.put(1, USERS, USER_42, ANN_ATTRIBUTES, writeVersion(1));
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
			log.appendReplicated(LogEntry.put(1, USERS, USER_42, ANN_ATTRIBUTES, writeVersion(1)));

			// Act
			Throwable thrown = catchThrowable(() -> log.appendReplicated(LogEntry.put(3, USERS, USER_7, BOB_ATTRIBUTES, writeVersion(3))));

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
