package com.margosha.mcp.tools;

import static com.margosha.mcp.tools.Constants.COORDINATOR_CANNOT_BE_REACHED;
import static com.margosha.mcp.tools.Constants.COORDINATOR_UNREACHABLE;
import static com.margosha.mcp.tools.Constants.HTTP_STATUS;
import static com.margosha.mcp.tools.Constants.OUTCOME_UNKNOWN;
import static com.margosha.mcp.tools.Constants.STORE_UNAVAILABLE;
import static com.margosha.mcp.tools.config.JsonConfig.JSON_MAPPER;

import java.util.Optional;
import java.util.function.Supplier;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.web.client.ResourceAccessException;

import com.margosha.api.exception.ApiException;
import com.margosha.api.util.TraceIds;

@Slf4j
@RequiredArgsConstructor
class ToolResults {

	@NonNull
	private final ToolLimits limits;

	public CallToolResult read(String tool, String table, Supplier<?> call) {
		return run(tool, table, call, false);
	}

	public CallToolResult write(String tool, String table, Supplier<?> call) {
		return run(tool, table, call, true);
	}

	private CallToolResult run(String tool, String table, Supplier<?> call, boolean write) {
		MDC.put(TraceIds.MDC_KEY, TraceIds.newId());
		try {
			Optional<ToolError> refusal = limits.refusal(table);
			if (refusal.isPresent()) {
				return error(tool, table, refusal.get());
			}
			return success(tool, table, call.get());
		} catch (ApiException e) {
			String message = e.error() != null ? e.error().message() : e.getMessage();
			if (e.isUnavailable()) {
				message = unavailable(message, write);
			}
			return error(tool, table, new ToolError(e.code() != null ? e.code().name() : HTTP_STATUS + e.status(),
					message));
		} catch (ResourceAccessException e) {
			String message = unavailable(COORDINATOR_CANNOT_BE_REACHED + e.getMessage(), write);
			return error(tool, table, new ToolError(COORDINATOR_UNREACHABLE, message));
		} finally {
			MDC.remove(TraceIds.MDC_KEY);
		}
	}

	private static String unavailable(String message, boolean write) {
		return STORE_UNAVAILABLE + message + (write ? OUTCOME_UNKNOWN : "");
	}

	private static CallToolResult success(String tool, String table, Object content) {
		logCall(tool, table, "OK");
		return result(content, false);
	}

	private static CallToolResult error(String tool, String table, ToolError error) {
		logCall(tool, table, error.code());
		return result(error, true);
	}

	private static void logCall(String tool, String table, String result) {
		log.atInfo().addKeyValue("tool", tool).addKeyValue("table", table).addKeyValue("result", result)
				.log("Tool {} on table {}: {}", tool, table, result);
	}

	private static CallToolResult result(Object content, boolean isError) {
		return CallToolResult.builder()
				.addTextContent(JSON_MAPPER.writeValueAsString(content))
				.isError(isError)
				.build();
	}
}
