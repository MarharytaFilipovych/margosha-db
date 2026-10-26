package com.margosha.node.exception;

import com.margosha.api.model.ErrorCode;
import org.springframework.http.HttpStatus;

public class TableAlreadyExistsException extends NodeException {

	public TableAlreadyExistsException(String table) {
		super(ErrorCode.TABLE_ALREADY_EXISTS, HttpStatus.CONFLICT, "Table " + table + " already exists");
	}
}
