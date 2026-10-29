package com.margosha.node.coordinator.storage;

import java.time.Duration;
import java.util.function.Supplier;

import com.margosha.api.client.ShardClient;
import com.margosha.api.exception.ApiException;
import com.margosha.api.model.Attributes;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ShardRegistration;
import com.margosha.api.util.RestClientBuilders;
import com.margosha.node.coordinator.exception.ShardUnavailableException;
import com.margosha.node.exception.RecordAlreadyExistsException;
import com.margosha.node.exception.RecordNotFoundException;
import com.margosha.node.storage.RecordStorage;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.client.ResourceAccessException;

@RequiredArgsConstructor
public class RemoteShardStorageService implements RecordStorage {

	@Getter @NonNull
	private final String shardId;

	@NonNull
	private final ShardClient client;

	public static RemoteShardStorageService connect(ShardRegistration shard, Duration timeout) {
		ShardClient client = new ShardClient(RestClientBuilders.withTimeout(timeout), shard.address());
		return new RemoteShardStorageService(shard.shardId(), client);
	}

	@Override
	public void create(String table, PrimaryKey key, Attributes attributes) {
		call(table, key, () -> client.createRecord(table, key, attributes));
	}

	@Override
	public Attributes read(String table, PrimaryKey key) {
		return call(table, key, () -> client.readRecord(table, key));
	}

	@Override
	public boolean exists(String table, PrimaryKey key) {
		return call(table, key, () -> client.recordExists(table, key));
	}

	@Override
	public void update(String table, PrimaryKey key, Attributes attributes) {
		call(table, key, () -> client.updateRecord(table, key, attributes));
	}

	@Override
	public void delete(String table, PrimaryKey key) {
		call(table, key, () -> {
			client.deleteRecord(table, key);
			return null;
		});
	}

	private <T> T call(String table, PrimaryKey key, Supplier<T> request) {
		try {
			return request.get();
		} catch (ResourceAccessException e) {
			throw ShardUnavailableException.didNotAnswer(shardId);
		} catch (ApiException e) {
			if (e.code() == ErrorCode.RECORD_NOT_FOUND) {
				throw new RecordNotFoundException(table, key);
			}
			if (e.code() == ErrorCode.RECORD_ALREADY_EXISTS) {
				throw new RecordAlreadyExistsException(table, key);
			}
			throw e;
		}
	}
}
