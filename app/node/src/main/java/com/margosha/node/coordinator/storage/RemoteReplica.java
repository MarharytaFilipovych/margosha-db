package com.margosha.node.coordinator.storage;

import java.time.Duration;
import java.util.function.Supplier;

import com.margosha.api.client.ShardClient;
import com.margosha.api.exception.ApiException;
import com.margosha.api.model.Attributes;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ReplicaExists;
import com.margosha.api.model.ReplicaRecord;
import com.margosha.api.model.ShardRegistration;
import com.margosha.api.util.RestClientBuilder;
import com.margosha.node.common.exception.RecordAlreadyExistsException;
import com.margosha.node.common.exception.RecordNotFoundException;
import com.margosha.node.coordinator.exception.ShardUnavailableException;
import com.margosha.node.coordinator.model.ReplicaAnswer;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.client.ResourceAccessException;

@RequiredArgsConstructor
public class RemoteReplica implements ReplicaClient {

	@NonNull
	private final ShardRegistration registration;

	@NonNull
	private final ShardClient client;

	public static RemoteReplica connect(ShardRegistration replica, Duration timeout) {
		return new RemoteReplica(replica, new ShardClient(RestClientBuilder.withTimeout(timeout), replica.address()));
	}

	@Override
	public ShardRegistration registration() {
		return registration;
	}

	@Override
	public void create(String table, PrimaryKey key, Attributes attributes) {
		call(table, key, () -> client.createRecord(table, key, attributes));
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

	@Override
	public ReplicaAnswer read(String table, PrimaryKey key) {
		try {
			ReplicaRecord record = call(table, key, () -> client.readVersioned(table, key));
			return ReplicaAnswer.found(record.version(), record.attributes());
		} catch (VersionedNotFound notFound) {
			return ReplicaAnswer.notFound(notFound.version);
		}
	}

	@Override
	public ReplicaAnswer exists(String table, PrimaryKey key) {
		ReplicaExists exists = call(table, key, () -> client.existsVersioned(table, key));
		return new ReplicaAnswer(exists.version(), exists.exists(), null);
	}

	private <T> T call(String table, PrimaryKey key, Supplier<T> request) {
		try {
			return request.get();
		} catch (ResourceAccessException e) {
			throw ShardUnavailableException.didNotAnswer(registration.shardId(), registration.replicaId());
		} catch (ApiException e) {
			if (e.code() == ErrorCode.RECORD_NOT_FOUND) {
				Long version = e.error().version();
				if (version != null) {
					throw new VersionedNotFound(version);
				}
				throw new RecordNotFoundException(table, key);
			}
			if (e.code() == ErrorCode.RECORD_ALREADY_EXISTS) {
				throw new RecordAlreadyExistsException(table, key);
			}
			if (e.code() == ErrorCode.SHARD_UNAVAILABLE) {
				throw new ShardUnavailableException(e.error().message());
			}
			throw e;
		}
	}

	private static final class VersionedNotFound extends RuntimeException {

		private final long version;

		private VersionedNotFound(long version) {
			super(null, null, false, false);
			this.version = version;
		}
	}
}
