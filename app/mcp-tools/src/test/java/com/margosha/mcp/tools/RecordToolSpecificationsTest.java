package com.margosha.mcp.tools;

import static com.margosha.mcp.tools.Constants.ADDITIONAL_PROPERTIES;
import static com.margosha.mcp.tools.Constants.CREATE_RECORD;
import static com.margosha.mcp.tools.Constants.DELETE_RECORD;
import static com.margosha.mcp.tools.Constants.EXISTS_RECORD;
import static com.margosha.mcp.tools.Constants.PROPERTIES;
import static com.margosha.mcp.tools.Constants.READ_RECORD;
import static com.margosha.mcp.tools.Constants.RECORD;
import static com.margosha.mcp.tools.Constants.STRING_TYPE;
import static com.margosha.mcp.tools.Constants.TABLE;
import static com.margosha.mcp.tools.Constants.TYPE;
import static com.margosha.mcp.tools.config.JsonConfig.JSON_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.model.TableRecord;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;
import tools.jackson.core.type.TypeReference;

class RecordToolSpecificationsTest {

	private static final String USERS = "users";
	private static final Map<String, String> ANN = Map.of("user_id", "42", "name", "Ann");

	private final CoordinatorClient coordinator = mock(CoordinatorClient.class);
	private final RecordTools tools = new RecordTools(coordinator, new ToolLimits(List.of(USERS), true));

	@Test
	void recordAttributeValuesMustBeStrings() {
		// Act
		SyncToolSpecification create = specification(RecordToolSpecifications.of(tools), CREATE_RECORD);

		// Assert
		assertThat(property(create)).containsEntry(ADDITIONAL_PROPERTIES, Map.of(TYPE, STRING_TYPE));
	}

	@Test
	void toolsWithoutARecordKeepTheGeneratedSchema() {
		// Arrange
		List<SyncToolSpecification> generated = new SyncMcpToolProvider(List.of(tools)).getToolSpecifications();

		// Act
		List<SyncToolSpecification> specifications = RecordToolSpecifications.of(tools);

		// Assert
		assertThat(specifications).hasSameSizeAs(generated);
		for (String name : List.of(EXISTS_RECORD, READ_RECORD, DELETE_RECORD)) {
			assertThat(specification(specifications, name).tool()).isEqualTo(specification(generated, name).tool());
		}
	}

	@Test
	void changedCreateToolStillCallsTheStore() {
		// Arrange
		when(coordinator.createRecord(USERS, TableRecord.of(ANN))).thenReturn(TableRecord.of(ANN));
		SyncToolSpecification create = specification(RecordToolSpecifications.of(tools), CREATE_RECORD);

		// Act
		CallToolResult result = create.callHandler().apply(null,
				CallToolRequest.builder(CREATE_RECORD).arguments(Map.of(TABLE, USERS, RECORD, ANN)).build());

		// Assert
		assertThat(result.isError()).isFalse();
		assertThat(content(result)).isEqualTo(ANN);
	}

	private static SyncToolSpecification specification(List<SyncToolSpecification> specifications, String name) {
		return specifications.stream().filter(spec -> spec.tool().name().equals(name)).findFirst().orElseThrow();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> property(SyncToolSpecification specification) {
		Map<String, Object> properties = (Map<String, Object>) specification.tool().inputSchema().get(PROPERTIES);
		return (Map<String, Object>) properties.get(Constants.RECORD);
	}

	private static Map<String, Object> content(CallToolResult result) {
		String text = ((TextContent) result.content().getFirst()).text();
		return JSON_MAPPER.readValue(text, new TypeReference<>() { });
	}
}
