package com.margosha.node.shard.replication.storage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.PrimaryKey;
import com.margosha.node.common.exception.RecordAlreadyExistsException;
import com.margosha.node.common.exception.RecordNotFoundException;
import com.margosha.node.shard.replication.log.ReplicationLog;
import com.margosha.node.shard.replication.model.LogEntry;
import com.margosha.node.shard.replication.model.VersionedRecord;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ReplicaStorage implements AutoCloseable {

	public static final String LOG_FILE = "replication.log";
	public static final String LOG_ID_FILE = "replication.log.id";

	private final Map<String, Map<PrimaryKey, VersionedRecord>> tables = new ConcurrentHashMap<>();
	private final Object writeLock = new Object();
	private final ReplicationLog replicationLog;
	private final Path idFile;
	private volatile String logId;

	private ReplicaStorage(Path dataDir) {
		long start = System.nanoTime();
		this.replicationLog = ReplicationLog.open(dataDir.resolve(LOG_FILE), this::put);
		this.idFile = dataDir.resolve(LOG_ID_FILE);
		this.logId = loadLogId();
		// Rebuild time on start (read and check the whole log, replay it into memory).
		log.info("Rebuilt the replica from its log: {} entries in {} ms (log ID {})", replicationLog.lastVersion(),
				(System.nanoTime() - start) / 1_000_000, logId == null ? "not set yet" : logId);
	}

	public static ReplicaStorage open(Path dataDir) {
		return new ReplicaStorage(Objects.requireNonNull(dataDir, "dataDir"));
	}

	public LogEntry create(String table, PrimaryKey key, Attributes attributes) {
		requireArguments(table, key);
		Objects.requireNonNull(attributes, "attributes");
		synchronized (writeLock) {
			if (isPresent(current(table, key))) {
				throw new RecordAlreadyExistsException(table, key);
			}
			return record(replicationLog.appendNext(table, key, attributes));
		}
	}

	public Attributes read(String table, PrimaryKey key) {
		requireArguments(table, key);
		VersionedRecord record = current(table, key);
		if (!isPresent(record)) {
			throw new RecordNotFoundException(table, key);
		}
		return record.attributes();
	}

	public boolean exists(String table, PrimaryKey key) {
		requireArguments(table, key);
		return isPresent(current(table, key));
	}

	public LogEntry update(String table, PrimaryKey key, Attributes attributes) {
		requireArguments(table, key);
		Objects.requireNonNull(attributes, "attributes");
		synchronized (writeLock) {
			if (!isPresent(current(table, key))) {
				throw new RecordNotFoundException(table, key);
			}
			return record(replicationLog.appendNext(table, key, attributes));
		}
	}

	public LogEntry delete(String table, PrimaryKey key) {
		requireArguments(table, key);
		synchronized (writeLock) {
			if (!isPresent(current(table, key))) {
				throw new RecordNotFoundException(table, key);
			}
			return record(replicationLog.appendNext(table, key, null));
		}
	}

	public Optional<VersionedRecord> readVersioned(String table, PrimaryKey key) {
		requireArguments(table, key);
		return Optional.ofNullable(current(table, key));
	}

	public void apply(LogEntry entry) {
		Objects.requireNonNull(entry, "entry");
		synchronized (writeLock) {
			if (entry.version() <= replicationLog.lastVersion()) {
				return;
			}
			replicationLog.appendReplicated(entry);
			put(entry);
		}
	}

	public List<LogEntry> entriesAfter(long position, int maxEntries) {
		return replicationLog.readAfter(position, maxEntries);
	}

	public long lastVersion() {
		return replicationLog.lastVersion();
	}

	public Optional<String> logId() {
		return Optional.ofNullable(logId);
	}

	public String ensureLogId() {
		synchronized (writeLock) {
			if (logId == null) {
				storeLogId(UUID.randomUUID().toString());
				log.info("This log's ID is {}", logId);
			}
			return logId;
		}
	}

	public void adoptLogId(String leaderLogId) {
		Objects.requireNonNull(leaderLogId, "leaderLogId");
		synchronized (writeLock) {
			if (leaderLogId.equals(logId)) {
				return;
			}
			if (logId != null && replicationLog.lastVersion() > 0) {
				throw new IllegalStateException("This log has " + replicationLog.lastVersion() + " entries with log ID " + logId
						+ "; it cannot take log ID " + leaderLogId);
			}
			storeLogId(leaderLogId);
			log.info("Took the leader's log ID {}", leaderLogId);
		}
	}

	@Override
	public void close() {
		replicationLog.close();
	}

	private String loadLogId() {
		try {
			if (replicationLog.lastVersion() == 0) {
				Files.deleteIfExists(idFile);
				return null;
			}
			if (!Files.exists(idFile)) {
				return null;
			}
			String id = Files.readString(idFile, UTF_8).trim();
			return id.isEmpty() ? null : id;
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read the log ID file " + idFile, e);
		}
	}

	private void storeLogId(String id) {
		Path temporary = idFile.resolveSibling(LOG_ID_FILE + ".tmp");
		try {
			try (FileChannel channel = FileChannel.open(temporary, CREATE, WRITE, TRUNCATE_EXISTING)) {
				ByteBuffer bytes = ByteBuffer.wrap((id + "\n").getBytes(UTF_8));
				while (bytes.hasRemaining()) {
					channel.write(bytes);
				}
				channel.force(true);
			}
			Files.move(temporary, idFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			forceDirectory(idFile.toAbsolutePath().getParent());
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write the log ID file " + idFile, e);
		}
		logId = id;
	}

	private static void forceDirectory(Path dir) {
		try (FileChannel channel = FileChannel.open(dir, READ)) {
			channel.force(true);
		} catch (IOException | UnsupportedOperationException ignored) {
		}
	}

	private LogEntry record(LogEntry entry) {
		put(entry);
		return entry;
	}

	private void put(LogEntry entry) {
		tables.computeIfAbsent(entry.table(), name -> new ConcurrentHashMap<>())
				.put(entry.key(), new VersionedRecord(entry.version(), entry.attributes()));
	}

	private VersionedRecord current(String table, PrimaryKey key) {
		return tables.getOrDefault(table, Map.of()).get(key);
	}

	private static boolean isPresent(VersionedRecord record) {
		return record != null && !record.deleted();
	}

	private static void requireArguments(String table, PrimaryKey key) {
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
	}
}
