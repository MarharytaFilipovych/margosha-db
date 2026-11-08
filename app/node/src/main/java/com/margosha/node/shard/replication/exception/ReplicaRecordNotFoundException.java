package com.margosha.node.shard.replication.exception;

import com.margosha.api.model.ApiError;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.RecordVersion;
import com.margosha.node.common.exception.RecordNotFoundException;
import lombok.Getter;

/** A replica's 404: the key was deleted (with the delete's logVersion), or never written (logVersion 0, no record version). */
@Getter
public class ReplicaRecordNotFoundException extends RecordNotFoundException {

	private final long logVersion;
	private final RecordVersion recordVersion;

	public ReplicaRecordNotFoundException(String table, PrimaryKey key, long logVersion, RecordVersion recordVersion) {
		super(table, key);
		this.logVersion = logVersion;
		this.recordVersion = recordVersion;
	}

	@Override
	public ApiError toApiError() {
		return new ApiError(getCode(), getMessage(), logVersion, recordVersion);
	}
}
