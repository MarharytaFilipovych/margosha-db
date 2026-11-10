package com.margosha.mcp;

import static com.margosha.mcp.tools.Constants.ADDITIONAL_PROPERTIES;
import static com.margosha.mcp.tools.Constants.CREATE_RECORD;
import static com.margosha.mcp.tools.Constants.DELETE_RECORD;
import static com.margosha.mcp.tools.Constants.EXISTS_RECORD;
import static com.margosha.mcp.tools.Constants.PARTITION_KEY;
import static com.margosha.mcp.tools.Constants.PROPERTIES;
import static com.margosha.mcp.tools.Constants.READ_RECORD;
import static com.margosha.mcp.tools.Constants.RECORD;
import static com.margosha.mcp.tools.Constants.STRING_TYPE;
import static com.margosha.mcp.tools.Constants.TABLE;
import static com.margosha.mcp.tools.Constants.TYPE;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.margosha.mcp.data.StdioServerProcess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class ToolListTest {

	private static final String INPUT_SCHEMA = "inputSchema";

	private static final List<String> KEY_FIELDS = List.of(TABLE, PARTITION_KEY);
	private static final Map<String, String> KEY_TYPES = Map.of(TABLE, STRING_TYPE, PARTITION_KEY, STRING_TYPE,
			"sortKey", STRING_TYPE);

	@TempDir
	static Path folder;

	private static List<JsonNode> tools;

	@BeforeAll
	static void listTools() throws Exception {
		try (StdioServerProcess server = StdioServerProcess.start(folder.resolve("stderr.log"))) {
			server.initialize();
			tools = StreamSupport.stream(server.listTools().spliterator(), false).toList();
		}
	}

	@Test
	void theServerListsTheFourRecordToolsAndNoOthers() {
		// Act
		List<String> names = tools.stream().map(ToolListTest::name).toList();

		// Assert
		assertThat(names).containsExactlyInAnyOrder(EXISTS_RECORD, READ_RECORD, CREATE_RECORD, DELETE_RECORD);
	}

	@Test
	void everyToolHasADescriptionAndAnInputSchemaWithItsRequiredFieldsAndTypes() {
		// Act
		Map<String, List<String>> required = tools.stream().collect(Collectors.toMap(ToolListTest::name,
				tool -> strings(tool.path(INPUT_SCHEMA).path("required"))));

		// Assert
		assertThat(tools).allSatisfy(tool -> assertThat(tool.path("description").asString()).isNotBlank());
		assertThat(required).isEqualTo(Map.of(EXISTS_RECORD, KEY_FIELDS, READ_RECORD, KEY_FIELDS,
				DELETE_RECORD, KEY_FIELDS, CREATE_RECORD, List.of(TABLE, RECORD)));
		assertThat(propertyTypes(EXISTS_RECORD)).isEqualTo(KEY_TYPES);
		assertThat(propertyTypes(READ_RECORD)).isEqualTo(KEY_TYPES);
		assertThat(propertyTypes(DELETE_RECORD)).isEqualTo(KEY_TYPES);
		assertThat(propertyTypes(CREATE_RECORD)).isEqualTo(Map.of(TABLE, STRING_TYPE, RECORD, "object"));
		assertThat(properties(CREATE_RECORD).path(RECORD).path(ADDITIONAL_PROPERTIES).path(TYPE).asString())
				.isEqualTo(STRING_TYPE);
	}

	@Test
	void readAndExistsAreMarkedReadOnlyAndOnlyDeleteIsMarkedDestructive() {
		// Act
		Map<String, Boolean> readOnly = hints("readOnlyHint");
		Map<String, Boolean> destructive = hints("destructiveHint");

		// Assert
		assertThat(readOnly).isEqualTo(Map.of(EXISTS_RECORD, true, READ_RECORD, true,
				CREATE_RECORD, false, DELETE_RECORD, false));
		assertThat(destructive).isEqualTo(Map.of(EXISTS_RECORD, false, READ_RECORD, false,
				CREATE_RECORD, false, DELETE_RECORD, true));
	}

	private static String name(JsonNode tool) {
		return tool.path("name").asString();
	}

	private static JsonNode properties(String toolName) {
		JsonNode tool = tools.stream().filter(candidate -> name(candidate).equals(toolName)).findFirst().orElseThrow();
		return tool.path(INPUT_SCHEMA).path(PROPERTIES);
	}

	private static Map<String, String> propertyTypes(String toolName) {
		return properties(toolName).properties().stream()
				.collect(Collectors.toMap(Map.Entry::getKey, property -> property.getValue().path(TYPE).asString()));
	}

	private static Map<String, Boolean> hints(String hint) {
		return tools.stream().collect(Collectors.toMap(ToolListTest::name,
				tool -> tool.path("annotations").path(hint).asBoolean()));
	}

	private static List<String> strings(JsonNode array) {
		return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asString).toList();
	}
}
