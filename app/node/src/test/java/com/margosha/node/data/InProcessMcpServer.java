package com.margosha.node.data;

import static com.margosha.mcp.tools.config.JsonConfig.JSON_MAPPER;
import static io.modelcontextprotocol.spec.McpSchema.JSONRPC_VERSION;
import static io.modelcontextprotocol.spec.McpSchema.METHOD_INITIALIZE;
import static io.modelcontextprotocol.spec.McpSchema.METHOD_NOTIFICATION_INITIALIZED;
import static io.modelcontextprotocol.spec.McpSchema.METHOD_TOOLS_CALL;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.mcp.tools.RecordToolSpecifications;
import com.margosha.mcp.tools.RecordTools;
import com.margosha.mcp.tools.ToolLimits;
import com.margosha.mcp.tools.config.AgentCoordinatorClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;

/**
 * A real MCP server with the record tools, running in this JVM. The test talks to it as an MCP client does: JSON-RPC
 * lines over the server's stdio transport, connected through pipes instead of a process's standard input and output.
 */
public final class InProcessMcpServer implements AutoCloseable {

	private static final McpJsonMapper MCP_JSON = McpJsonDefaults.getMapper();
	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

	private static final String JSONRPC = "jsonrpc";
	private static final String ID = "id";
	private static final String METHOD = "method";
	private static final String ERROR = "error";
	private static final String NAME = "name";

	private final McpSyncServer server;
	private final Writer requests;
	private final BufferedReader answers;
	private int nextId = 1;

	private InProcessMcpServer(McpSyncServer server, Writer requests, BufferedReader answers) {
		this.server = server;
		this.requests = requests;
		this.answers = answers;
	}

	/** An MCP server with the test agent key that allows only {@link TestData#AGENT_NOTES}. */
	public static InProcessMcpServer start(String coordinatorUrl) {
		return start(coordinatorUrl, TestData.AGENT_KEY, List.of(TestData.AGENT_NOTES));
	}

	public static InProcessMcpServer start(String coordinatorUrl, @Nullable String agentKey,
			List<String> allowedTables) {
		try {
			Pipe toServer = Pipe.open();
			Pipe fromServer = Pipe.open();
			CoordinatorClient coordinator = AgentCoordinatorClient.create(coordinatorUrl, Duration.ofSeconds(5),
					agentKey);
			ToolLimits limits = new ToolLimits(allowedTables, agentKey != null);
			StdioServerTransportProvider transport = new StdioServerTransportProvider(MCP_JSON,
					Channels.newInputStream(toServer.source()), Channels.newOutputStream(fromServer.sink()));
			McpSyncServer server = McpServer.sync(transport)
					.serverInfo("margosha-db", "test")
					.capabilities(ServerCapabilities.builder().tools(false).build())
					.tools(RecordToolSpecifications.of(new RecordTools(coordinator, limits)))
					.build();
			InProcessMcpServer mcp = new InProcessMcpServer(server,
					new OutputStreamWriter(Channels.newOutputStream(toServer.sink()), UTF_8),
					new BufferedReader(new InputStreamReader(Channels.newInputStream(fromServer.source()), UTF_8)));
			mcp.initialize();
			return mcp;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public CallToolResult callTool(String name, Map<String, Object> arguments) {
		Map<String, Object> result = request(METHOD_TOOLS_CALL, Map.of(NAME, name, "arguments", arguments));
		return MCP_JSON.convertValue(result, CallToolResult.class);
	}

	/** The text the tool returned. */
	public static String text(CallToolResult result) {
		return ((TextContent) result.content().getFirst()).text();
	}

	/** The JSON object the tool returned as its text. */
	public static Map<String, Object> content(CallToolResult result) {
		return JSON_MAPPER.readValue(text(result), MAP);
	}

	private void initialize() {
		request(METHOD_INITIALIZE, Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
				"clientInfo", Map.of(NAME, "in-process-test", "version", "1.0")));
		send(Map.of(JSONRPC, JSONRPC_VERSION, METHOD, METHOD_NOTIFICATION_INITIALIZED));
	}

	private Map<String, Object> request(String method, Map<String, Object> params) {
		int id = nextId++;
		Map<String, Object> message = new LinkedHashMap<>();
		message.put(JSONRPC, JSONRPC_VERSION);
		message.put(ID, id);
		message.put(METHOD, method);
		message.put("params", params);
		send(message);
		try {
			return CompletableFuture.supplyAsync(() -> answerTo(id)).get(20, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException("No answer to " + method + " from the MCP server", e);
		}
	}

	private void send(Map<String, Object> message) {
		try {
			requests.write(JSON_MAPPER.writeValueAsString(message));
			requests.write("\n");
			requests.flush();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> answerTo(int id) {
		try {
			String line;
			while ((line = answers.readLine()) != null) {
				Map<String, Object> message = JSON_MAPPER.readValue(line, MAP);
				if (Integer.valueOf(id).equals(message.get(ID))) {
					if (message.containsKey(ERROR)) {
						throw new IllegalStateException("MCP error: " + message.get(ERROR));
					}
					return (Map<String, Object>) message.get("result");
				}
			}
			throw new IllegalStateException("The MCP server closed its output");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public void close() {
		server.close();
		try {
			requests.close();
		} catch (IOException ignored) {
			// the server already closed its end
		}
	}
}
