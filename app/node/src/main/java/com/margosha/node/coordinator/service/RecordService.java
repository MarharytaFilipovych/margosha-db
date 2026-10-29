package com.margosha.node.coordinator.service;

import java.util.HashMap;
import java.util.Map;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.TableDefinition;
import com.margosha.api.model.TableRecord;
import com.margosha.node.storage.RecordStorage;
import com.margosha.node.exception.ValidationException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import static org.springframework.util.StringUtils.hasText;

@RequiredArgsConstructor
public class RecordService {

	@NonNull
	private final TableRegistryService tables;

	@NonNull
	private final RecordStorage recordStorage;

	public TableRecord create(String tableName, TableRecord record) {
		TableDefinition table = tables.get(tableName);
		PrimaryKey key = record.keyIn(table);
		requireKeyValue(table.partitionKey(), key.partitionKey());
		if (table.hasSortKey()) {
			requireKeyValue(table.sortKey(), key.sortKey());
		}
		Attributes attributes = record.attributesIn(table);
		recordStorage.create(table.name(), key, attributes);
		return record;
	}

	public TableRecord read(String tableName, String partitionKey, String sortKey) {
		TableDefinition table = tables.get(tableName);
		PrimaryKey key = keyFromRequest(table, partitionKey, sortKey);
		return toRecord(table, key, recordStorage.read(table.name(), key));
	}

	public boolean exists(String tableName, String partitionKey, String sortKey) {
		TableDefinition table = tables.get(tableName);
		return recordStorage.exists(table.name(), keyFromRequest(table, partitionKey, sortKey));
	}

	public TableRecord update(String tableName, String partitionKey, String sortKey, Attributes attributes) {
		TableDefinition table = tables.get(tableName);
		PrimaryKey key = keyFromRequest(table, partitionKey, sortKey);
		for (String keyAttribute : table.keyAttributes()) {
			if (attributes.values().containsKey(keyAttribute)) {
				throw new ValidationException("Key attribute " + keyAttribute + " cannot be updated; the key comes from the path");
			}
		}
		recordStorage.update(table.name(), key, attributes);
		return toRecord(table, key, attributes);
	}

	public void delete(String tableName, String partitionKey, String sortKey) {
		TableDefinition table = tables.get(tableName);
		recordStorage.delete(table.name(), keyFromRequest(table, partitionKey, sortKey));
	}

 	private static PrimaryKey keyFromRequest(TableDefinition table, String partitionKey, String sortKey) {
		requireKeyValue(table.partitionKey(), partitionKey);
		if (table.hasSortKey()) {
			requireKeyValue(table.sortKey(), sortKey);
			return new PrimaryKey(partitionKey, sortKey);
		}
		if (hasText(sortKey)) {
			throw new ValidationException("Table " + table.name() + " has no sort key");
		}
		return PrimaryKey.of(partitionKey);
	}

	private static void requireKeyValue(String attribute, String value) {
		if (!hasText(value)) {
			throw new ValidationException("Missing value for key attribute " + attribute);
		}
	}

	private static TableRecord toRecord(TableDefinition table, PrimaryKey key, Attributes attributes) {
		Map<String, String> values = new HashMap<>(attributes.values());
		values.put(table.partitionKey(), key.partitionKey());
		if (table.hasSortKey()) {
			values.put(table.sortKey(), key.sortKey());
		}
		return TableRecord.of(values);
	}
}
