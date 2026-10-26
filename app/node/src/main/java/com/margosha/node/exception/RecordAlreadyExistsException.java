package com.margosha.node.exception;

import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.PrimaryKey;
import org.springframework.http.HttpStatus;

public class RecordAlreadyExistsException extends NodeException {

	public RecordAlreadyExistsException(String table, PrimaryKey key) {
		super(ErrorCode.RECORD_ALREADY_EXISTS, HttpStatus.CONFLICT,
				"Record " + describe(key) + " already exists in table " + table);
	}
}
