package com.margosha.node.mcp;

import static com.margosha.mcp.tools.Constants.AGENT_KEY_MISSING;
import static com.margosha.mcp.tools.Constants.CODE;
import static com.margosha.mcp.tools.Constants.CREATE_RECORD;
import static com.margosha.mcp.tools.Constants.PARTITION_KEY;
import static com.margosha.mcp.tools.Constants.READ_RECORD;
import static com.margosha.mcp.tools.Constants.RECORD;
import static com.margosha.mcp.tools.Constants.TABLE;
import static com.margosha.mcp.tools.Constants.TABLE_NOT_ALLOWED;
import static com.margosha.node.data.InProcessMcpServer.content;
import static com.margosha.node.data.TestData.AGENT_ARGS;
import static com.margosha.node.data.TestData.AGENT_KEY;
import static com.margosha.node.data.TestData.AGENT_NOTES;
import static com.margosha.node.data.TestData.ANN;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.usersTable;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.margosha.api.model.ErrorCode;
import com.margosha.node.data.HangingServer;
import com.margosha.node.data.InProcessMcpServer;
import com.margosha.node.data.InProcessNode;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class RecordToolLimitsTest {

	private static final Map<String, Object> CREATE_ANN = Map.of(TABLE, AGENT_NOTES, RECORD, ANN.values());

	private InProcessNode coordinator;

	@BeforeEach
	void setUp() {
		coordinator = InProcessNode.singleModeCoordinator(AGENT_ARGS);
		coordinator.client().registerTable(usersTable(AGENT_NOTES));
	}

	@AfterEach
	void tearDown() {
		coordinator.close();
	}

	@Test
	void aTableThatIsNotAllowedIsRefusedAndNothingIsSentToTheStore() {
		// Arrange
		try (HangingServer store = HangingServer.start();
				InProcessMcpServer mcp = InProcessMcpServer.start(store.address())) {

			// Act
			CallToolResult create = mcp.callTool(CREATE_RECORD, Map.of(TABLE, USERS, RECORD, ANN.values()));

			// Assert
			assertThat(create.isError()).isTrue();
			assertThat(content(create)).containsEntry(CODE, TABLE_NOT_ALLOWED);
			assertThat(store.connections()).isZero();
		}
	}

	@Test
	void withoutAnAgentKeyCreateIsRefusedAndNothingIsCreated() {
		// Arrange
		try (InProcessMcpServer mcp = InProcessMcpServer.start(coordinator.address(), null, List.of(AGENT_NOTES))) {

			// Act
			CallToolResult create = mcp.callTool(CREATE_RECORD, CREATE_ANN);

			// Assert
			assertThat(create.isError()).isTrue();
			assertThat(content(create)).containsEntry(CODE, AGENT_KEY_MISSING);
			assertThat(coordinator.client().recordExists(AGENT_NOTES, USER_42)).isFalse();
		}
	}

	@Test
	void withAWrongAgentKeyCreateIsRefusedAndNothingIsCreated() {
		// Arrange
		try (InProcessMcpServer mcp = InProcessMcpServer.start(coordinator.address(), "not-the-key",
				List.of(AGENT_NOTES))) {

			// Act
			CallToolResult create = mcp.callTool(CREATE_RECORD, CREATE_ANN);

			// Assert
			assertThat(create.isError()).isTrue();
			assertThat(content(create)).containsEntry(CODE, ErrorCode.AGENT_KEY_INVALID.name());
			assertThat(coordinator.client().recordExists(AGENT_NOTES, USER_42)).isFalse();
		}
	}

	@Test
	void noLogLineOfTheMcpServerOrTheCoordinatorContainsTheAgentKey(CapturedOutput output) {
		// Arrange
		try (InProcessMcpServer mcp = InProcessMcpServer.start(coordinator.address())) {

			// Act
			mcp.callTool(CREATE_RECORD, CREATE_ANN);
			mcp.callTool(READ_RECORD, Map.of(TABLE, AGENT_NOTES, PARTITION_KEY, USER_42.partitionKey()));

			// Assert
			assertThat(output.getAll()).contains("Tool " + CREATE_RECORD + " on table " + AGENT_NOTES + ": OK",
					"Tool " + READ_RECORD + " on table " + AGENT_NOTES + ": OK", "POST /tables/" + AGENT_NOTES);
			assertThat(output.getAll()).doesNotContain(AGENT_KEY);
		}
	}
}
