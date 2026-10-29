package com.margosha.node.coordinator.controller;

import static com.margosha.api.util.PathConstants.EXISTS;
import static com.margosha.api.util.PathConstants.RECORD;
import static com.margosha.api.util.PathConstants.RECORDS;
import static com.margosha.api.util.PathConstants.TABLES;

import com.margosha.node.coordinator.service.RecordService;
import com.margosha.node.coordinator.service.TableRegistryService;
import com.margosha.api.model.Attributes;
import com.margosha.api.model.ExistsResult;
import com.margosha.api.model.TableDefinition;
import com.margosha.api.model.TableRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("coordinator")
@RequiredArgsConstructor
public class CoordinatorController {

	private final TableRegistryService tables;
	private final RecordService records;

	@PostMapping(TABLES)
	@ResponseStatus(HttpStatus.CREATED)
	public TableDefinition registerTable(@RequestBody TableDefinition table) {
		return tables.register(table);
	}

	@PostMapping(RECORDS)
	@ResponseStatus(HttpStatus.CREATED)
	public TableRecord createRecord(@PathVariable String tableName,
									@RequestBody TableRecord record) {
		return records.create(tableName, record);
	}

	@GetMapping(RECORD)
	public TableRecord readRecord(@PathVariable String tableName,
			@PathVariable String partitionKey,
			@RequestParam(required = false) String sortKey) {
		return records.read(tableName, partitionKey, sortKey);
	}

	@GetMapping(EXISTS)
	public ExistsResult recordExists(@PathVariable String tableName,
			@PathVariable String partitionKey,
			@RequestParam(required = false) String sortKey) {
		return new ExistsResult(records.exists(tableName, partitionKey, sortKey));
	}

	@PutMapping(RECORD)
	public TableRecord updateRecord(@PathVariable String tableName,
			@PathVariable String partitionKey,
			@RequestParam(required = false) String sortKey,
			@RequestBody Attributes attributes) {
		return records.update(tableName, partitionKey, sortKey, attributes);
	}

	@DeleteMapping(RECORD)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteRecord(@PathVariable String tableName,
			@PathVariable String partitionKey,
			@RequestParam(required = false) String sortKey) {
		records.delete(tableName, partitionKey, sortKey);
	}
}
