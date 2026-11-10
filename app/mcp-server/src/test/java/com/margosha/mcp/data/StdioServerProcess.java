package com.margosha.mcp.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.margosha.mcp.McpServerApplication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts the MCP server as a separate program, the way Claude does, and talks to it over its standard input and output.
 */
public final class StdioServerProcess implements AutoCloseable {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final Process process;
	private final Writer stdin;
	private final BufferedReader stdout;

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

	public void send(String message) throws IOException {
		stdin.write(message);
		stdin.write("\n");
		stdin.flush();
	}

	public List<String> linesUntilResponse(int id, Duration timeout) throws Exception {
		return CompletableFuture.supplyAsync(() -> readUntilResponse(id)).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
	}

	private List<String> readUntilResponse(int id) {
		List<String> lines = new ArrayList<>();
		try {
			String line;
			while ((line = stdout.readLine()) != null) {
				lines.add(line);
				if (isResponse(line, id)) {
					break;
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return lines;
	}

	private static boolean isResponse(String line, int id) {
		try {
			JsonNode message = JSON.readTree(line);
			return message.path("id").asInt(-1) == id && (message.has("result") || message.has("error"));
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
