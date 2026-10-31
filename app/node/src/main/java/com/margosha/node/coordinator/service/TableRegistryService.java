package com.margosha.node.coordinator.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.margosha.api.model.TableDefinition;
import com.margosha.node.coordinator.exception.TableAlreadyExistsException;
import com.margosha.node.coordinator.exception.TableNotFoundException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("coordinator")
public class TableRegistryService {

	private final Map<String, TableDefinition> tables = new ConcurrentHashMap<>();

	public TableDefinition register(TableDefinition table) {
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
}
