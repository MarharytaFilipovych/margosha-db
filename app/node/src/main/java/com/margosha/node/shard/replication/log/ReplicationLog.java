package com.margosha.node.shard.replication.log;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.CRC32;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.margosha.api.model.Attributes;
import com.margosha.api.model.PrimaryKey;
import com.margosha.node.shard.replication.exception.LogCorruptedException;
import com.margosha.node.shard.replication.model.LogEntry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

public final class ReplicationLog implements AutoCloseable {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final HexFormat HEX = HexFormat.of();
	private static final int CHECKSUM_DIGITS = 8;
	private static final byte NEWLINE = '\n';

	private final Path file;
	private final FileChannel channel;
	private final Object appendLock = new Object();
	private final Object indexLock = new Object();

	private long[] offsets;
	private int count;
	private long end;

	private ReplicationLog(Path file, FileChannel channel, Index index) {
		this.file = file;
		this.channel = channel;
		this.offsets = index.offsets;
		this.count = index.count;
		this.end = index.end;
	}

	public static ReplicationLog open(Path file, Consumer<LogEntry> replay) {
		try {
			Path parent = file.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			FileChannel channel = FileChannel.open(file, CREATE, READ, WRITE);
			try {
				Index index = scan(file, replay);
				if (index.end < channel.size()) {
					channel.truncate(index.end);
					channel.force(true);
				}
				return new ReplicationLog(file, channel, index);
			} catch (RuntimeException | IOException e) {
				channel.close();
				throw e;
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot open the replication log " + file, e);
		}
	}

	public LogEntry appendNext(String table, PrimaryKey key, Attributes attributes) {
		synchronized (appendLock) {
			LogEntry entry = new LogEntry(lastVersion() + 1, table, key, attributes);
			write(entry);
			return entry;
		}
	}

	public void appendReplicated(LogEntry entry) {
		synchronized (appendLock) {
			long expected = lastVersion() + 1;
			if (entry.version() != expected) {
				throw new IllegalArgumentException(
						"Expected the entry with version " + expected + " but got version " + entry.version());
			}
			write(entry);
		}
	}

	public List<LogEntry> readAfter(long position, int maxEntries) {
		if (position < 0) {
			throw new IllegalArgumentException("position must not be negative, was " + position);
		}
		if (maxEntries < 1) {
			throw new IllegalArgumentException("maxEntries must be 1 or higher, was " + maxEntries);
		}
		int first;
		int size;
		long from;
		long to;
		synchronized (indexLock) {
			if (position >= count) {
				return List.of();
			}
			first = (int) position;
			size = Math.min(maxEntries, count - first);
			from = offsets[first];
			to = first + size < count ? offsets[first + size] : end;
		}
		byte[] bytes = readBytes(from, to);
		List<LogEntry> entries = new ArrayList<>(size);
		int lineStart = 0;
		for (int i = 0; i < bytes.length; i++) {
			if (bytes[i] == NEWLINE) {
				long version = first + entries.size() + 1L;
				LogEntry entry = parse(Arrays.copyOfRange(bytes, lineStart, i), version);
				if (entry == null) {
					throw new LogCorruptedException("Entry " + version + " in " + file + " changed after it was stored");
				}
				entries.add(entry);
				lineStart = i + 1;
			}
		}
		return entries;
	}

	public long lastVersion() {
		synchronized (indexLock) {
			return count;
		}
	}

	public Path file() {
		return file;
	}

	@Override
	public void close() {
		try {
			channel.close();
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot close the replication log " + file, e);
		}
	}

	static byte[] encode(LogEntry entry) {
		byte[] json = JSON.writeValueAsBytes(LogLine.of(entry));
		byte[] checksum = (checksum(json) + " ").getBytes(UTF_8);
		byte[] line = new byte[checksum.length + json.length + 1];
		System.arraycopy(checksum, 0, line, 0, checksum.length);
		System.arraycopy(json, 0, line, checksum.length, json.length);
		line[line.length - 1] = NEWLINE;
		return line;
	}

	private void write(LogEntry entry) {
		byte[] line = encode(entry);
		long start = end;
		ByteBuffer buffer = ByteBuffer.wrap(line);
		try {
			while (buffer.hasRemaining()) {
				channel.write(buffer, start + buffer.position());
			}
			channel.force(false);
		} catch (IOException e) {
			undo(start);
			throw new UncheckedIOException("Cannot write to the replication log " + file, e);
		}
		synchronized (indexLock) {
			if (count == offsets.length) {
				offsets = Arrays.copyOf(offsets, offsets.length * 2);
			}
			offsets[count++] = start;
			end = start + line.length;
		}
	}

	private void undo(long start) {
		try {
			channel.truncate(start);
		} catch (IOException ignored) {
		}
	}

	private byte[] readBytes(long from, long to) {
		ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(to - from));
		try {
			while (buffer.hasRemaining()) {
				if (channel.read(buffer, from + buffer.position()) < 0) {
					throw new LogCorruptedException("The replication log " + file + " is shorter than expected");
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read the replication log " + file, e);
		}
		return buffer.array();
	}

	private static Index scan(Path file, Consumer<LogEntry> replay) throws IOException {
		Index index = new Index();
		long offset = 0;
		long badLineNumber = 0;
		try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 64 * 1024)) {
			ByteArrayOutputStream line = new ByteArrayOutputStream(256);
			int next;
			while ((next = in.read()) != -1) {
				if (next != NEWLINE) {
					line.write(next);
					continue;
				}
				if (badLineNumber > 0) {
					throw damaged(file, badLineNumber);
				}
				byte[] bytes = line.toByteArray();
				line.reset();
				LogEntry entry = parse(bytes, index.count + 1L);
				if (entry == null) {
					badLineNumber = index.count + 1L;
				} else {
					replay.accept(entry);
					index.add(offset);
					index.end = offset + bytes.length + 1;
				}
				offset += bytes.length + 1;
			}
			if (badLineNumber > 0 && line.size() > 0) {
				throw damaged(file, badLineNumber);
			}
		}
		return index;
	}

	private static LogCorruptedException damaged(Path file, long lineNumber) {
		return new LogCorruptedException("The replication log " + file + " is damaged at line " + lineNumber
				+ ", which is not the last line; the replica cannot start");
	}

	private static LogEntry parse(byte[] line, long expectedVersion) {
		if (line.length <= CHECKSUM_DIGITS + 1 || line[CHECKSUM_DIGITS] != ' ') {
			return null;
		}
		byte[] json = Arrays.copyOfRange(line, CHECKSUM_DIGITS + 1, line.length);
		String stored = new String(line, 0, CHECKSUM_DIGITS, UTF_8);
		if (!stored.equals(checksum(json))) {
			return null;
		}
		try {
			LogEntry entry = JSON.readValue(json, LogLine.class).toEntry();
			return entry.version() == expectedVersion ? entry : null;
		} catch (JacksonException | IllegalArgumentException | NullPointerException e) {
			return null;
		}
	}

	private static String checksum(byte[] json) {
		CRC32 crc = new CRC32();
		crc.update(json);
		return HEX.toHexDigits((int) crc.getValue());
	}

	private static final class Index {
		private long[] offsets = new long[1024];
		private int count;
		private long end;

		private void add(long offset) {
			if (count == offsets.length) {
				offsets = Arrays.copyOf(offsets, offsets.length * 2);
			}
			offsets[count++] = offset;
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record LogLine(long version, String table, String partitionKey, String sortKey, Map<String, String> attributes,
			Boolean deleted) {

		static LogLine of(LogEntry entry) {
			PrimaryKey key = entry.key();
			return entry.deleted()
					? new LogLine(entry.version(), entry.table(), key.partitionKey(), key.sortKey(), null, true)
					: new LogLine(entry.version(), entry.table(), key.partitionKey(), key.sortKey(),
							entry.attributes().values(), null);
		}

		LogEntry toEntry() {
			PrimaryKey key = new PrimaryKey(partitionKey, sortKey);
			if (Boolean.TRUE.equals(deleted)) {
				return LogEntry.delete(version, table, key);
			}
			return LogEntry.put(version, table, key, Attributes.of(attributes));
		}
	}
}
