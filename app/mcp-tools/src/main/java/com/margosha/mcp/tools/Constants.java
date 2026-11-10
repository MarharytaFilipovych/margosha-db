package com.margosha.mcp.tools;

import lombok.experimental.UtilityClass;

@UtilityClass
public class Constants {

	/** Tool names. */
	public static final String EXISTS_RECORD = "exists_record";
	public static final String READ_RECORD = "read_record";
	public static final String CREATE_RECORD = "create_record";
	public static final String DELETE_RECORD = "delete_record";

	/** Argument names. They are the names of the tool methods' parameters, from which the input schemas are made. */
	public static final String TABLE = "table";
	public static final String PARTITION_KEY = "partitionKey";
	public static final String RECORD = "record";

	/** Result fields of exists_record and delete_record. */
	public static final String EXISTS = "exists";
	public static final String DELETED = "deleted";

	/** The JSON fields of a tool error. */
	public static final String CODE = "code";
	public static final String MESSAGE = "message";

	/** The code when the coordinator did not answer at all; otherwise the code is the store's own. */
	public static final String COORDINATOR_UNREACHABLE = "COORDINATOR_UNREACHABLE";

	/** The codes of the limits the MCP server checks before it calls the store. */
	public static final String AGENT_KEY_MISSING = "AGENT_KEY_MISSING";
	public static final String TABLE_NOT_ALLOWED = "TABLE_NOT_ALLOWED";

	/** The code prefix, followed by the HTTP status, when the store's answer had no error body. */
	public static final String HTTP_STATUS = "HTTP_";

	/** How a message starts when the store is unavailable. */
	public static final String STORE_UNAVAILABLE = "The store is unavailable. ";
	public static final String COORDINATOR_CANNOT_BE_REACHED = "The coordinator cannot be reached: ";

	/** How a message ends when the store is unavailable during a write. */
	public static final String OUTCOME_UNKNOWN = " The outcome of this write is unknown: it may or may not have been "
			+ "applied. Check the record before trying again.";

	/** JSON schema keywords. */
	public static final String PROPERTIES = "properties";
	public static final String ADDITIONAL_PROPERTIES = "additionalProperties";
	public static final String TYPE = "type";
	public static final String STRING_TYPE = "string";
}
