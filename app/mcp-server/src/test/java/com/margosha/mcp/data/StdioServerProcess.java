package com.margosha.mcp.data;

import static com.margosha.mcp.tools.config.JsonConfig.JSON_MAPPER;
import static io.modelcontextprotocol.spec.McpSchema.JSONRPC_VERSION;
import static io.modelcontextprotocol.spec.McpSchema.METHOD_INITIALIZE;
import static io.modelcontextprotocol.spec.McpSchema.METHOD_NOTIFICATION_INITIALIZED;
import static io.modelcontextprotocol.spec.McpSchema.METHOD_TOOLS_LIST;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.margosha.mcp.McpServerApplication;
import tools.jackson.databind.JsonNode;

/**
 * Starts the MCP server as a separate program, the way Claude does, and talks to it over its standard input and output.
 */
public final class StdioServerProcess implements AutoCloseable {

	private static final String ID = "id";
	private static final String RESULT = "result";

	private final Process process;
	private final Writer stdin;
	private final BufferedReader stdout;
	private final List<String> stdoutLines = new ArrayList<>();
	private int nextId = 1;

	private StdioServerProcess(Process process) {
		this.process = process;
		this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
		this.stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
	}

	public static StdioServerProcess start(Path stderrFile) throws IOException {
		String java = ProcessHandle.current().info().command().orElse("java");
		ProcessBuilder builder = new ProcessBuilder(java, McpServerApplication.class.getName())
				.redirectError(stderrFile.toFile());
		builder.environment().put("CLASSPATH", System.getProperty("java.class.path"));
		return new StdioServerProcess(builder.start());
	}

	/** Starts the MCP session as a client does: {@code initialize}, then {@code notifications/initialized}. */
	public JsonNode initialize() throws Exception {
		JsonNode result = request(METHOD_INITIALIZE, Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
				"clientInfo", Map.of("name", "stdio-test", "version", "1.0")));
		send(message(null, METHOD_NOTIFICATION_INITIALIZED, null));
		return result;
	}

	/** The {@code tools} array of the {@code tools/list} answer. */
	public JsonNode listTools() throws Exception {
		return request(METHOD_TOOLS_LIST, null).path("tools");
	}

	/** Every line the server has written to standard output so far. */
	public List<String> stdoutLines() {
		return List.copyOf(stdoutLines);
	}

	private JsonNode request(String method, Map<String, Object> params) throws Exception {
		int id = nextId++;
		send(message(id, method, params));
		String answer = CompletableFuture.supplyAsync(() -> readUntilResponse(id))
				.get(60, TimeUnit.SECONDS);
		return JSON_MAPPER.readTree(answer).path(RESULT);
	}

	private static Map<String, Object> message(Integer id, String method, Map<String, Object> params) {
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("jsonrpc", JSONRPC_VERSION);
		if (id != null) {
			message.put(ID, id);
		}
		message.put("method", method);
		if (params != null) {
			message.put("params", params);
		}
		return message;
	}

	private void send(Map<String, Object> message) throws IOException {
		stdin.write(JSON_MAPPER.writeValueAsString(message));
		stdin.write("\n");
		stdin.flush();
	}

	private String readUntilResponse(int id) {
		try {
			String line;
			while ((line = stdout.readLine()) != null) {
				stdoutLines.add(line);
				if (isResponse(line, id)) {
					return line;
				}
			}
			throw new IllegalStateException("The MCP server closed its standard output");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static boolean isResponse(String line, int id) {
		try {
			JsonNode message = JSON_MAPPER.readTree(line);
			return message.path(ID).asInt(-1) == id && (message.has(RESULT) || message.has("error"));
		} catch (RuntimeException e) {
			return false;
		}
	}

	@Override
	public void close() throws InterruptedException {
		process.destroy();
		if (!process.waitFor(10, TimeUnit.SECONDS)) {
			process.destroyForcibly();
		}
	}
}
