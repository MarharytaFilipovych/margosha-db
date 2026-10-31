package com.margosha.node.shard.controller;

import static com.margosha.api.util.PathConstants.EXISTS;
import static com.margosha.api.util.PathConstants.RECORD;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ReplicaExists;
import com.margosha.api.model.ReplicaRecord;
import com.margosha.node.common.storage.RecordStorage;
import com.margosha.node.shard.service.ReplicaReadService;
import jakarta.validation.constraints.NotBlank;
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
	private final ReplicaReadService replicaReadService;

	@PostMapping(RECORD)
	@ResponseStatus(HttpStatus.CREATED)
	public Attributes createRecord(@PathVariable String tableName,
								   @PathVariable @NotBlank(message = "Missing partition key value") String partitionKey,
								   @RequestParam(required = false) String sortKey,
								   @RequestBody Attributes attributes) {
		recordStorage.create(tableName, key(partitionKey, sortKey), attributes);
		return attributes;
	}

	@GetMapping(RECORD)
	public ReplicaRecord readRecord(@PathVariable String tableName,
									@PathVariable @NotBlank(message = "Missing partition key value") String partitionKey,
									@RequestParam(required = false) String sortKey) {
		return replicaReadService.read(tableName, key(partitionKey, sortKey));
	}

	@GetMapping(EXISTS)
	public ReplicaExists recordExists(@PathVariable String tableName,
									  @PathVariable @NotBlank(message = "Missing partition key value") String partitionKey,
									  @RequestParam(required = false) String sortKey) {
		return replicaReadService.exists(tableName, key(partitionKey, sortKey));
	}

	@PutMapping(RECORD)
	public Attributes updateRecord(@PathVariable String tableName,
								   @PathVariable @NotBlank(message = "Missing partition key value") String partitionKey,
								   @RequestParam(required = false) String sortKey,
								   @RequestBody Attributes attributes) {
		recordStorage.update(tableName, key(partitionKey, sortKey), attributes);
		return attributes;
	}

	@DeleteMapping(RECORD)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteRecord(@PathVariable String tableName,
							 @PathVariable @NotBlank(message = "Missing partition key value") String partitionKey,
							 @RequestParam(required = false) String sortKey) {
		recordStorage.delete(tableName, key(partitionKey, sortKey));
	}

	private static PrimaryKey key(String partitionKey, String sortKey) {
		return PrimaryKey.of(partitionKey, sortKey);
	}
}
