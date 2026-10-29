package com.margosha.node.shard.controller;

import static com.margosha.api.util.PathConstants.EXISTS;
import static com.margosha.api.util.PathConstants.RECORD;
import static org.springframework.util.StringUtils.hasText;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.ExistsResult;
import com.margosha.api.model.PrimaryKey;
import com.margosha.node.exception.ValidationException;
import com.margosha.node.storage.RecordStorage;
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
@Profile("shard")
@RequiredArgsConstructor
public class ShardController {

	private final RecordStorage storage;

	@PostMapping(RECORD)
	@ResponseStatus(HttpStatus.CREATED)
	public Attributes createRecord(@PathVariable String tableName,
								   @PathVariable String partitionKey,
								   @RequestParam(required = false) String sortKey,
								   @RequestBody Attributes attributes) {
		storage.create(tableName, key(partitionKey, sortKey), attributes);
		return attributes;
	}

	@GetMapping(RECORD)
	public Attributes readRecord(@PathVariable String tableName,
								 @PathVariable String partitionKey,
								 @RequestParam(required = false) String sortKey) {
		return storage.read(tableName, key(partitionKey, sortKey));
	}

	@GetMapping(EXISTS)
	public ExistsResult recordExists(@PathVariable String tableName,
									 @PathVariable String partitionKey,
									 @RequestParam(required = false) String sortKey) {
		return new ExistsResult(storage.exists(tableName, key(partitionKey, sortKey)));
	}

	@PutMapping(RECORD)
	public Attributes updateRecord(@PathVariable String tableName,
								   @PathVariable String partitionKey,
								   @RequestParam(required = false) String sortKey,
								   @RequestBody Attributes attributes) {
		storage.update(tableName, key(partitionKey, sortKey), attributes);
		return attributes;
	}

	@DeleteMapping(RECORD)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteRecord(@PathVariable String tableName,
							 @PathVariable String partitionKey,
							 @RequestParam(required = false) String sortKey) {
		storage.delete(tableName, key(partitionKey, sortKey));
	}

	private static PrimaryKey key(String partitionKey, String sortKey) {
		if (!hasText(partitionKey)) {
			throw new ValidationException("Missing partition key value");
		}
		return new PrimaryKey(partitionKey, hasText(sortKey) ? sortKey : null);
	}
}
