package com.margosha.node.storage;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.PrimaryKey;
import com.margosha.node.exception.RecordAlreadyExistsException;
import com.margosha.node.exception.RecordNotFoundException;

public class InMemoryRecordStorage implements RecordStorage {

	private final Map<String, Map<PrimaryKey, Attributes>> tables = new ConcurrentHashMap<>();

	@Override
	public void create(String table, PrimaryKey key, Attributes attributes) {
		requireArguments(table, key);
		Objects.requireNonNull(attributes, "attributes");
		Attributes existing = tables.computeIfAbsent(table, name -> new ConcurrentHashMap<>()).putIfAbsent(key, attributes);
		if (existing != null) {
			throw new RecordAlreadyExistsException(table, key);
		}
	}

	@Override
	public Attributes read(String table, PrimaryKey key) {
		requireArguments(table, key);
		Attributes attributes = records(table).get(key);
		if (attributes == null) {
			throw new RecordNotFoundException(table, key);
		}
		return attributes;
	}

	@Override
	public boolean exists(String table, PrimaryKey key) {
		requireArguments(table, key);
		return records(table).containsKey(key);
	}

	@Override
	public void update(String table, PrimaryKey key, Attributes attributes) {
		requireArguments(table, key);
		Objects.requireNonNull(attributes, "attributes");
		Map<PrimaryKey, Attributes> records = tables.get(table);
		if (records == null || records.replace(key, attributes) == null) {
			throw new RecordNotFoundException(table, key);
		}
	}

	@Override
	public void delete(String table, PrimaryKey key) {
		requireArguments(table, key);
		Map<PrimaryKey, Attributes> records = tables.get(table);
		if (records == null || records.remove(key) == null) {
			throw new RecordNotFoundException(table, key);
		}
	}

	private Map<PrimaryKey, Attributes> records(String table) {
		return tables.getOrDefault(table, Map.of());
	}

	private static void requireArguments(String table, PrimaryKey key) {
		Objects.requireNonNull(table, "table");
		Objects.requireNonNull(key, "key");
	}
}
