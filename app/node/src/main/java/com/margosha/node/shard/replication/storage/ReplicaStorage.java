package com.margosha.node.shard.replication.storage;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.PrimaryKey;
import com.margosha.node.common.exception.RecordAlreadyExistsException;
import com.margosha.node.common.exception.RecordNotFoundException;
import com.margosha.node.shard.replication.log.ReplicationLog;
import com.margosha.node.shard.replication.model.LogEntry;
import com.margosha.node.shard.replication.model.VersionedRecord;

public final class ReplicaStorage implements AutoCloseable {

	public static final String LOG_FILE = "replication.log";

	private final Map<String, Map<PrimaryKey, VersionedRecord>> tables = new ConcurrentHashMap<>();
	private final Object writeLock = new Object();
	private final ReplicationLog log;

	private ReplicaStorage(Path dataDir) {
		this.log = ReplicationLog.open(dataDir.resolve(LOG_FILE), this::put);
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
			return record(log.appendNext(table, key, attributes));
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
			return record(log.appendNext(table, key, attributes));
		}
	}

	public LogEntry delete(String table, PrimaryKey key) {
		requireArguments(table, key);
		synchronized (writeLock) {
			if (!isPresent(current(table, key))) {
				throw new RecordNotFoundException(table, key);
			}
			return record(log.appendNext(table, key, null));
		}
	}

	public Optional<VersionedRecord> readVersioned(String table, PrimaryKey key) {
		requireArguments(table, key);
		return Optional.ofNullable(current(table, key));
	}

	public void apply(LogEntry entry) {
		Objects.requireNonNull(entry, "entry");
		synchronized (writeLock) {
			if (entry.version() <= log.lastVersion()) {
				return;
			}
			log.appendReplicated(entry);
			put(entry);
		}
	}

	public List<LogEntry> entriesAfter(long position, int maxEntries) {
		return log.readAfter(position, maxEntries);
	}

	public long lastVersion() {
		return log.lastVersion();
	}

	@Override
	public void close() {
		log.close();
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
