package com.margosha.node.mcp;

import static com.margosha.mcp.tools.Constants.CODE;
import static com.margosha.mcp.tools.Constants.CREATE_RECORD;
import static com.margosha.mcp.tools.Constants.DELETE_RECORD;
import static com.margosha.mcp.tools.Constants.EXISTS;
import static com.margosha.mcp.tools.Constants.EXISTS_RECORD;
import static com.margosha.mcp.tools.Constants.MESSAGE;
import static com.margosha.mcp.tools.Constants.PARTITION_KEY;
import static com.margosha.mcp.tools.Constants.READ_RECORD;
import static com.margosha.mcp.tools.Constants.RECORD;
import static com.margosha.mcp.tools.Constants.TABLE;
import static com.margosha.node.data.InProcessMcpServer.content;
import static com.margosha.node.data.TestData.ANN;
import static com.margosha.node.data.TestData.BOB;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.USER_7;
import static com.margosha.node.data.TestData.apiError;
import static com.margosha.node.data.TestData.client;
import static com.margosha.node.data.TestData.localAddress;
import static com.margosha.node.data.TestData.unique;
import static com.margosha.node.data.TestData.usersTable;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.model.ErrorCode;
import com.margosha.node.annotation.SingleModeCoordinatorTest;
import com.margosha.node.data.InProcessMcpServer;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

@SingleModeCoordinatorTest
class RecordToolsTest {

	@LocalServerPort
	private int port;

	private final String table = unique(USERS);
	private CoordinatorClient store;
	private InProcessMcpServer mcp;

	@BeforeEach
	void setUp() {
		store = client(port);
		store.registerTable(usersTable(table));
		mcp = InProcessMcpServer.start(localAddress(port));
	}

	@AfterEach
	void tearDown() {
		mcp.close();
	}

	@Test
	void readAfterCreateReturnsTheRecord() {
		// Arrange
		mcp.callTool(CREATE_RECORD, Map.of(TABLE, table, RECORD, ANN.values()));

		// Act
		CallToolResult read = mcp.callTool(READ_RECORD, key(USER_42.partitionKey()));

		// Assert
		assertThat(read.isError()).isFalse();
		assertThat(content(read)).isEqualTo(ANN.values());
	}

	@Test
	void existsIsFalseAfterDelete() {
		// Arrange
		mcp.callTool(CREATE_RECORD, Map.of(TABLE, table, RECORD, ANN.values()));
		mcp.callTool(DELETE_RECORD, key(USER_42.partitionKey()));

		// Act
		CallToolResult exists = mcp.callTool(EXISTS_RECORD, key(USER_42.partitionKey()));

		// Assert
		assertThat(exists.isError()).isFalse();
		assertThat(content(exists)).isEqualTo(Map.of(EXISTS, false));
	}

	@Test
	void readOfAMissingRecordReturnsTheNotFoundError() {
		// Arrange
		String storeMessage = apiError(() -> store.readRecord(table, USER_7)).error().message();

		// Act
		CallToolResult read = mcp.callTool(READ_RECORD, key(USER_7.partitionKey()));

		// Assert
		assertThat(read.isError()).isTrue();
		assertThat(content(read)).isEqualTo(Map.of(CODE, ErrorCode.RECORD_NOT_FOUND.name(), MESSAGE, storeMessage));
	}

	@Test
	void createOfAnExistingKeyReturnsTheConflictErrorAndKeepsTheRecord() {
		// Arrange
		mcp.callTool(CREATE_RECORD, Map.of(TABLE, table, RECORD, ANN.values()));

		// Act
		CallToolResult create = mcp.callTool(CREATE_RECORD, Map.of(TABLE, table, RECORD, BOB.values()));

		// Assert
		assertThat(create.isError()).isTrue();
		assertThat(content(create)).containsEntry(CODE, ErrorCode.RECORD_ALREADY_EXISTS.name());
		assertThat(store.readRecord(table, USER_42)).isEqualTo(ANN);
	}

	private Map<String, Object> key(String partitionKey) {
		return Map.of(TABLE, table, PARTITION_KEY, partitionKey);
	}
}
