package com.margosha.node.coordinator.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.margosha.api.model.TableDefinition;
import com.margosha.node.coordinator.exception.TableAlreadyExistsException;
import com.margosha.node.coordinator.exception.TableNotFoundException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
public class TableRegistryService implements AutoCloseable {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final byte NEWLINE = '\n';

	private final Map<String, TableDefinition> tables = new ConcurrentHashMap<>();
	private final Path file;
	private final FileChannel channel;

	public TableRegistryService() {
		this.file = null;
		this.channel = null;
	}

	TableRegistryService(@NonNull Path file, @NonNull FileChannel channel) {
		this.file = file;
		this.channel = channel;
		try {
			load();
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read the table file " + file, e);
		}
	}

	public static TableRegistryService durable(Path file) {
		try {
			Path parent = file.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			return new TableRegistryService(file, FileChannel.open(file, CREATE, WRITE));
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot open the table file " + file, e);
		}
	}

	public synchronized TableDefinition register(TableDefinition table) {
		if (tables.containsKey(table.name())) {
			throw new TableAlreadyExistsException(table.name());
		}
		if (channel != null) {
			append(table);
		}
		tables.put(table.name(), table);
		return table;
	}

	public TableDefinition get(String name) {
		TableDefinition table = tables.get(name);
		if (table == null) {
			throw new TableNotFoundException(name);
		}
		return table;
	}


	@Override
	public void close() {
		if (channel != null) {
			try {
				channel.close();
			} catch (IOException e) {
				throw new UncheckedIOException("Cannot close the table file " + file, e);
			}
		}
	}

	private void load() throws IOException {
		byte[] content = Files.readAllBytes(file);
		int lineStart = 0;
		int lineNumber = 0;
		for (int i = 0; i < content.length; i++) {
			if (content[i] != NEWLINE) {
				continue;
			}
			lineNumber++;
			String line = new String(content, lineStart, i - lineStart, UTF_8);
			try {
				TableDefinition table = JSON.readValue(line, TableDefinition.class);
				tables.put(table.name(), table);
			} catch (JacksonException e) {
				throw new IllegalStateException("The table file " + file + " is damaged at line " + lineNumber, e);
			}
			lineStart = i + 1;
		}
		if (lineStart < content.length) {
			log.warn("Dropping a table definition cut off by a crash at the end of {}", file);
			channel.truncate(lineStart);
			channel.force(true);
		}
		channel.position(lineStart);
		log.info("Loaded {} table definition(s) from {}", tables.size(), file);
	}

	private void append(TableDefinition table) {
		byte[] json = JSON.writeValueAsBytes(table);
		ByteBuffer line = ByteBuffer.allocate(json.length + 1).put(json).put(NEWLINE).flip();
		try {
			long start = channel.position();
			try {
				while (line.hasRemaining()) {
					channel.write(line);
				}
				channel.force(false);
			} catch (IOException e) {
				undo(start, e);
				throw e;
			}
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot write to the table file " + file, e);
		}
	}

	private void undo(long start, IOException cause) {
		try {
			channel.truncate(start);
			channel.position(start);
		} catch (IOException e) {
			cause.addSuppressed(e);
		}
	}
}
