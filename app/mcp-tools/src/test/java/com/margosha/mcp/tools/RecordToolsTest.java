package com.margosha.mcp.tools;

import static com.margosha.mcp.tools.Constants.AGENT_KEY_MISSING;
import static com.margosha.mcp.tools.Constants.CODE;
import static com.margosha.mcp.tools.Constants.COORDINATOR_CANNOT_BE_REACHED;
import static com.margosha.mcp.tools.Constants.COORDINATOR_UNREACHABLE;
import static com.margosha.mcp.tools.Constants.DELETED;
import static com.margosha.mcp.tools.Constants.EXISTS;
import static com.margosha.mcp.tools.Constants.HTTP_STATUS;
import static com.margosha.mcp.tools.Constants.MESSAGE;
import static com.margosha.mcp.tools.Constants.OUTCOME_UNKNOWN;
import static com.margosha.mcp.tools.Constants.STORE_UNAVAILABLE;
import static com.margosha.mcp.tools.Constants.TABLE_NOT_ALLOWED;
import static com.margosha.mcp.tools.config.JsonConfig.JSON_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.exception.ApiException;
import com.margosha.api.model.ApiError;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.PrimaryKey;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.core.type.TypeReference;

class RecordToolsTest {

	private static final String USERS = "users";
	private static final String OTHER_TABLE = "usertable";
	private static final String PARTITION_KEY = "42";
	private static final PrimaryKey USER_42 = PrimaryKey.of(PARTITION_KEY);
	private static final String SHARD_UNAVAILABLE = ErrorCode.SHARD_UNAVAILABLE.name();
	private static final String STORE_MESSAGE = "Only 1 of the 2 replicas a read needs answered for shard shard-a";
	private static final String CONNECTION_REFUSED = "Connection refused";
	private static final int INTERNAL_ERROR = 500;
	private static final ApiException UNAVAILABLE = new ApiException(503,
			new ApiError(ErrorCode.SHARD_UNAVAILABLE, STORE_MESSAGE), null);

	private final CoordinatorClient coordinator = mock(CoordinatorClient.class);
	private final RecordTools tools = new RecordTools(coordinator, new ToolLimits(List.of(USERS), true));

	@Test
	void tableThatIsNotAllowedIsRefusedWithoutCallingTheStore() {
		// Act
		CallToolResult read = tools.readRecord(OTHER_TABLE, PARTITION_KEY, null);

		// Assert
		assertThat(read.isError()).isTrue();
		assertThat(content(read)).isEqualTo(Map.of(CODE, TABLE_NOT_ALLOWED,
				MESSAGE, "Table " + OTHER_TABLE + " is not allowed for agents; allowed tables: [" + USERS + "]"));
		verifyNoInteractions(coordinator);
	}

	@Test
	void withoutAgentKeyNothingIsSentToTheStore() {
		// Arrange
		RecordTools withoutKey = new RecordTools(coordinator, new ToolLimits(List.of(USERS), false));

		// Act
		CallToolResult delete = withoutKey.deleteRecord(USERS, PARTITION_KEY, null);

		// Assert
		assertThat(delete.isError()).isTrue();
		assertThat(content(delete)).containsEntry(CODE, AGENT_KEY_MISSING);
		verifyNoInteractions(coordinator);
	}

	@Test
	void deleteReturnsDeleted() {
		// Act
		CallToolResult delete = tools.deleteRecord(USERS, PARTITION_KEY, null);

		// Assert
		assertThat(delete.isError()).isFalse();
		assertThat(content(delete)).isEqualTo(Map.of(DELETED, true));
		verify(coordinator).deleteRecord(USERS, USER_42);
	}

	@Test
	void blankSortKeyIsSentAsNoSortKey() {
		// Arrange
		when(coordinator.recordExists(any(), any())).thenReturn(true);

		// Act
		CallToolResult exists = tools.existsRecord(USERS, PARTITION_KEY, " ");

		// Assert
		assertThat(content(exists)).isEqualTo(Map.of(EXISTS, true));
		verify(coordinator).recordExists(USERS, new PrimaryKey(PARTITION_KEY, null));
	}

	@Test
	void errorWithoutBodyUsesTheHttpStatusAsItsCode() {
		// Arrange
		when(coordinator.readRecord(USERS, USER_42)).thenThrow(new ApiException(INTERNAL_ERROR, null, null));

		// Act
		CallToolResult read = tools.readRecord(USERS, PARTITION_KEY, null);

		// Assert
		assertThat(read.isError()).isTrue();
		assertThat(content(read)).isEqualTo(Map.of(CODE, HTTP_STATUS + INTERNAL_ERROR,
				MESSAGE, "HTTP " + INTERNAL_ERROR));
	}

	@Test
	void unavailableReadSaysStoreIsUnavailableButNotThatOutcomeIsUnknown() {
		// Arrange
		when(coordinator.readRecord(USERS, USER_42)).thenThrow(UNAVAILABLE);

		// Act
		CallToolResult read = tools.readRecord(USERS, PARTITION_KEY, null);

		// Assert
		assertThat(read.isError()).isTrue();
		assertThat(content(read)).isEqualTo(Map.of(CODE, SHARD_UNAVAILABLE, MESSAGE, STORE_UNAVAILABLE + STORE_MESSAGE));
	}

	@Test
	void unavailableDeleteSaysOutcomeIsUnknown() {
		// Arrange
		doThrow(UNAVAILABLE).when(coordinator).deleteRecord(USERS, USER_42);

		// Act
		CallToolResult delete = tools.deleteRecord(USERS, PARTITION_KEY, null);

		// Assert
		assertThat(delete.isError()).isTrue();
		assertThat(content(delete)).isEqualTo(Map.of(CODE, SHARD_UNAVAILABLE,
				MESSAGE, STORE_UNAVAILABLE + STORE_MESSAGE + OUTCOME_UNKNOWN));
	}

	@Test
	void readWhenCoordinatorCannotBeReachedSaysStoreIsUnavailableButNotThatOutcomeIsUnknown() {
		// Arrange
		when(coordinator.readRecord(USERS, USER_42)).thenThrow(new ResourceAccessException(CONNECTION_REFUSED));

		// Act
		CallToolResult read = tools.readRecord(USERS, PARTITION_KEY, null);

		// Assert
		assertThat(read.isError()).isTrue();
		assertThat(content(read)).isEqualTo(Map.of(CODE, COORDINATOR_UNREACHABLE,
				MESSAGE, STORE_UNAVAILABLE + COORDINATOR_CANNOT_BE_REACHED + CONNECTION_REFUSED));
	}

	private static Map<String, Object> content(CallToolResult result) {
		String text = ((TextContent) result.content().getFirst()).text();
		return JSON_MAPPER.readValue(text, new TypeReference<>() { });
	}
}
