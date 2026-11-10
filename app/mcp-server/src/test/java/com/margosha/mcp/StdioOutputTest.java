package com.margosha.mcp;

import static com.margosha.mcp.tools.config.JsonConfig.JSON_MAPPER;
import static io.modelcontextprotocol.spec.McpSchema.JSONRPC_VERSION;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import com.margosha.mcp.data.StdioServerProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StdioOutputTest {

	@TempDir
	Path folder;

	@Test
	void standardOutputCarriesOnlyProtocolMessages() throws Exception {
		// Arrange
		try (StdioServerProcess server = StdioServerProcess.start(folder.resolve("stderr.log"))) {

			// Act
			server.initialize();
			server.listTools();
			List<String> lines = server.stdoutLines();

			// Assert
			assertThat(lines).allSatisfy(line -> assertThat(JSON_MAPPER.readTree(line).path("jsonrpc").asString())
					.isEqualTo(JSONRPC_VERSION));
			assertThat(lines).extracting(line -> JSON_MAPPER.readTree(line).path("id").asInt()).containsExactly(1, 2);
		}
	}
}
