package com.margosha.mcp.tools;

import static com.margosha.mcp.tools.Constants.COORDINATOR_CANNOT_BE_REACHED;
import static com.margosha.mcp.tools.Constants.COORDINATOR_UNREACHABLE;
import static com.margosha.mcp.tools.Constants.HTTP_STATUS;
import static com.margosha.mcp.tools.Constants.OUTCOME_UNKNOWN;
import static com.margosha.mcp.tools.Constants.STORE_UNAVAILABLE;
import static com.margosha.mcp.tools.config.JsonConfig.JSON_MAPPER;

import java.util.function.Supplier;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.experimental.UtilityClass;
import org.springframework.web.client.ResourceAccessException;

import com.margosha.api.exception.ApiException;

@UtilityClass
class ToolResults {

	public CallToolResult read(Supplier<?> call) {
		return run(call, false);
	}

	public CallToolResult write(Supplier<?> call) {
		return run(call, true);
	}

	private CallToolResult run(Supplier<?> call, boolean write) {
		try {
			return result(call.get(), false);
		} catch (ApiException e) {
			String message = e.error() != null ? e.error().message() : e.getMessage();
			if (e.isUnavailable()) {
				message = unavailable(message, write);
			}
			return result(new ToolError(e.code() != null ? e.code().name() : HTTP_STATUS + e.status(), message), true);
		} catch (ResourceAccessException e) {
			String message = unavailable(COORDINATOR_CANNOT_BE_REACHED + e.getMessage(), write);
			return result(new ToolError(COORDINATOR_UNREACHABLE, message), true);
		}
	}

	private String unavailable(String message, boolean write) {
		return STORE_UNAVAILABLE + message + (write ? OUTCOME_UNKNOWN : "");
	}

	private CallToolResult result(Object content, boolean isError) {
		return CallToolResult.builder()
				.addTextContent(JSON_MAPPER.writeValueAsString(content))
				.isError(isError)
				.build();
	}
}
