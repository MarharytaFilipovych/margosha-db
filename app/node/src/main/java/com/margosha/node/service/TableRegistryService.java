package com.margosha.node.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.margosha.api.model.TableDefinition;
import com.margosha.node.exception.TableAlreadyExistsException;
import com.margosha.node.exception.TableNotFoundException;
import com.margosha.node.exception.ValidationException;
import org.springframework.util.StringUtils;

import static org.springframework.util.StringUtils.hasText;

public class TableRegistryService {

	private final Map<String, TableDefinition> tables = new ConcurrentHashMap<>();

	public TableDefinition register(TableDefinition table) {
		validate(table);
		if (tables.putIfAbsent(table.name(), table) != null) {
			throw new TableAlreadyExistsException(table.name());
		}
		return table;
	}

	public TableDefinition get(String name) {
		TableDefinition table = tables.get(name);
		if (table == null) {
			throw new TableNotFoundException(name);
		}
		return table;
	}

	private static void validate(TableDefinition table) {
		if (table == null) {
			throw new ValidationException("Table definition is required");
		}
		if (!hasText(table.name())) {
			throw new ValidationException("Table name must not be empty");
		}
		if (!hasText(table.partitionKey())) {
			throw new ValidationException("Partition key of table " + table.name() + " must not be empty");
		}
		if (table.sortKey() != null && table.sortKey().isBlank()) {
			throw new ValidationException("Sort key of table " + table.name() + " must not be empty if given");
		}
		if (table.partitionKey().equals(table.sortKey())) {
			throw new ValidationException("Sort key of table " + table.name() + " must differ from the partition key");
		}
	}
}
