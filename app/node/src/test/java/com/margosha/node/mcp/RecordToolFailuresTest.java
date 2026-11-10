package com.margosha.node.mcp;

import static com.margosha.mcp.tools.Constants.CODE;
import static com.margosha.mcp.tools.Constants.COORDINATOR_UNREACHABLE;
import static com.margosha.mcp.tools.Constants.CREATE_RECORD;
import static com.margosha.mcp.tools.Constants.MESSAGE;
import static com.margosha.mcp.tools.Constants.OUTCOME_UNKNOWN;
import static com.margosha.mcp.tools.Constants.RECORD;
import static com.margosha.mcp.tools.Constants.STORE_UNAVAILABLE;
import static com.margosha.mcp.tools.Constants.TABLE;
import static com.margosha.node.data.InProcessMcpServer.content;
import static com.margosha.node.data.InProcessMcpServer.text;
import static com.margosha.node.data.TestData.AGENT_ARGS;
import static com.margosha.node.data.TestData.AGENT_NOTES;
import static com.margosha.node.data.TestData.ANN;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.USER_ID;
import static com.margosha.node.data.TestData.freePort;
import static com.margosha.node.data.TestData.localAddress;
import static com.margosha.node.data.TestData.testDataDir;
import static com.margosha.node.data.TestData.usersTable;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.margosha.api.model.ErrorCode;
import com.margosha.node.data.HangingServer;
import com.margosha.node.data.InProcessMcpServer;
import com.margosha.node.data.InProcessNode;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.Test;

class RecordToolFailuresTest {

	private static final String INPUT_VALIDATION_FAILED = "input validation failed";
	private static final String AGE = "age";
	private static final Map<String, Object> CREATE_ANN = Map.of(TABLE, AGENT_NOTES, RECORD, ANN.values());

	@Test
	void createWhenTheCoordinatorCannotBeReachedSaysTheOutcomeIsUnknown() {
		// Arrange
		try (InProcessMcpServer mcp = InProcessMcpServer.start(localAddress(freePort()))) {

			// Act
			CallToolResult create = mcp.callTool(CREATE_RECORD, CREATE_ANN);

			// Assert
			assertThat(create.isError()).isTrue();
			assertThat(content(create)).containsEntry(CODE, COORDINATOR_UNREACHABLE);
			assertThat((String) content(create).get(MESSAGE)).startsWith(STORE_UNAVAILABLE).endsWith(OUTCOME_UNKNOWN);
		}
	}

	@Test
	void createWhenTheStoreAnswersUnavailableSaysTheOutcomeIsUnknown() {
		// Arrange
		try (InProcessNode coordinator = InProcessNode.replicatedCoordinator(0, testDataDir("coordinator"), AGENT_ARGS);
				InProcessMcpServer mcp = InProcessMcpServer.start(coordinator.address())) {
			coordinator.client().registerTable(usersTable(AGENT_NOTES));

			// Act
			CallToolResult create = mcp.callTool(CREATE_RECORD, CREATE_ANN);

			// Assert
			assertThat(create.isError()).isTrue();
			assertThat(content(create)).containsEntry(CODE, ErrorCode.SHARD_UNAVAILABLE.name());
			assertThat((String) content(create).get(MESSAGE)).startsWith(STORE_UNAVAILABLE).endsWith(OUTCOME_UNKNOWN);
		}
	}

	@Test
	void createWithoutATableNameIsRefusedBeforeTheStore() {
		// Arrange
		try (HangingServer store = HangingServer.start();
				InProcessMcpServer mcp = InProcessMcpServer.start(store.address())) {

			// Act
			CallToolResult create = mcp.callTool(CREATE_RECORD, Map.of(RECORD, ANN.values()));

			// Assert
			assertThat(create.isError()).isTrue();
			assertThat(text(create)).contains(INPUT_VALIDATION_FAILED, TABLE);
			assertThat(store.connections()).isZero();
		}
	}

	@Test
	void createWithAValueThatIsNotAStringIsRefusedBeforeTheStore() {
		// Arrange
		try (HangingServer store = HangingServer.start();
				InProcessMcpServer mcp = InProcessMcpServer.start(store.address())) {

			// Act
			CallToolResult create = mcp.callTool(CREATE_RECORD,
					Map.of(TABLE, AGENT_NOTES, RECORD, Map.of(USER_ID, USER_42.partitionKey(), AGE, 30)));

			// Assert
			assertThat(create.isError()).isTrue();
			assertThat(text(create)).contains(INPUT_VALIDATION_FAILED, "/" + RECORD + "/" + AGE);
			assertThat(store.connections()).isZero();
		}
	}
}
