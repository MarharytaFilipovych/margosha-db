package com.margosha.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.margosha.mcp.data.StdioServerProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class StdioOutputTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String INITIALIZE = """
			{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",\
			"capabilities":{},"clientInfo":{"name":"stdio-test","version":"1.0"}}}""";
	private static final String INITIALIZED = """
			{"jsonrpc":"2.0","method":"notifications/initialized"}""";
	private static final String LIST_TOOLS = """
			{"jsonrpc":"2.0","id":2,"method":"tools/list"}""";

	@TempDir
	Path folder;

	@Test
	void standardOutputCarriesOnlyProtocolMessages() throws Exception {
		// Arrange
		try (StdioServerProcess server = StdioServerProcess.start(folder.resolve("stderr.log"))) {

			// Act
			server.send(INITIALIZE);
			server.send(INITIALIZED);
			server.send(LIST_TOOLS);
			List<String> lines = server.linesUntilResponse(2, Duration.ofSeconds(60));

			// Assert
			assertThat(lines).allSatisfy(line -> assertThat(JSON.readTree(line).path("jsonrpc").asString())
					.isEqualTo("2.0"));
			assertThat(lines).extracting(line -> JSON.readTree(line).path("id").asInt()).containsExactly(1, 2);
		}
	}
}
