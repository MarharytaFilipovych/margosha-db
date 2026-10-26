package com.margosha.node.exception;

import com.margosha.api.model.PrimaryKey;

public class RecordNotFoundException extends RuntimeException {

	public RecordNotFoundException(String table, PrimaryKey key) {
		super("Record " + key + " not found in table " + table);
	}
}
