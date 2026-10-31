package com.margosha.node.shard.controller;

import static com.margosha.api.util.PathConstants.EXISTS;
import static com.margosha.api.util.PathConstants.RECORD;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.ExistsResult;
import com.margosha.api.model.PrimaryKey;
import jakarta.validation.constraints.NotBlank;
import com.margosha.node.common.storage.RecordStorage;
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

	private final RecordStorage recordStorage;

	@PostMapping(RECORD)
	@ResponseStatus(HttpStatus.CREATED)
	public Attributes createRecord(@PathVariable String tableName,
								   @PathVariable @NotBlank(message = "Missing partition key value") String partitionKey,
								   @RequestParam(required = false) String sortKey,
								   @RequestBody Attributes attributes) {
		recordStorage.create(tableName, PrimaryKey.of(partitionKey, sortKey), attributes);
		return attributes;
	}

	@GetMapping(RECORD)
	public Attributes readRecord(@PathVariable String tableName,
								 @PathVariable @NotBlank(message = "Missing partition key value") String partitionKey,
								 @RequestParam(required = false) String sortKey) {
		return recordStorage.read(tableName, PrimaryKey.of(partitionKey, sortKey));
	}

	@GetMapping(EXISTS)
	public ExistsResult recordExists(@PathVariable String tableName,
									 @PathVariable @NotBlank(message = "Missing partition key value") String partitionKey,
									 @RequestParam(required = false) String sortKey) {
		return new ExistsResult(recordStorage.exists(tableName, PrimaryKey.of(partitionKey, sortKey)));
	}

	@PutMapping(RECORD)
	public Attributes updateRecord(@PathVariable String tableName,
								   @PathVariable @NotBlank(message = "Missing partition key value") String partitionKey,
								   @RequestParam(required = false) String sortKey,
								   @RequestBody Attributes attributes) {
		recordStorage.update(tableName, PrimaryKey.of(partitionKey, sortKey), attributes);
		return attributes;
	}

	@DeleteMapping(RECORD)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteRecord(@PathVariable String tableName,
							 @PathVariable @NotBlank(message = "Missing partition key value") String partitionKey,
							 @RequestParam(required = false) String sortKey) {
		recordStorage.delete(tableName, PrimaryKey.of(partitionKey, sortKey));
	}

}
