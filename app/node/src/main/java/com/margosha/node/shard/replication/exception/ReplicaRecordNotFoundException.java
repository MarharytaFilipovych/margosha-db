package com.margosha.node.shard.replication.exception;

import com.margosha.api.model.ApiError;
import com.margosha.api.model.PrimaryKey;
import com.margosha.node.common.exception.RecordNotFoundException;
import lombok.Getter;

@Getter
public class ReplicaRecordNotFoundException extends RecordNotFoundException {

	private final long version;

	public ReplicaRecordNotFoundException(String table, PrimaryKey key, long version) {
		super(table, key);
		this.version = version;
	}

	@Override
	public ApiError toApiError() {
		return new ApiError(getCode(), getMessage(), version);
	}
}
