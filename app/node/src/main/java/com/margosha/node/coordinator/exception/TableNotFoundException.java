package com.margosha.node.coordinator.exception;

import com.margosha.node.exception.NodeException;
import com.margosha.api.model.ErrorCode;
import org.springframework.http.HttpStatus;

public class TableNotFoundException extends NodeException {

	public TableNotFoundException(String table) {
		super(ErrorCode.TABLE_NOT_FOUND, HttpStatus.NOT_FOUND, "Table " + table + " does not exist");
	}
}
