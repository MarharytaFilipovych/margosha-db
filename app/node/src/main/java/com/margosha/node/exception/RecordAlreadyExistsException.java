package com.margosha.node.exception;

import com.margosha.api.model.PrimaryKey;

public class RecordAlreadyExistsException extends RuntimeException {

	public RecordAlreadyExistsException(String table, PrimaryKey key) {
		super("Record " + key + " already exists in table " + table);
	}
}
