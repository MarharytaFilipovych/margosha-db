package com.margosha.node.exception;

import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.PrimaryKey;
import org.springframework.http.HttpStatus;

public class RecordNotFoundException extends NodeException {

	public RecordNotFoundException(String table, PrimaryKey key) {
		super(ErrorCode.RECORD_NOT_FOUND, HttpStatus.NOT_FOUND,
				"Record " + describe(key) + " not found in table " + table);
	}
}
