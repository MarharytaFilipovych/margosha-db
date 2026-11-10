package com.margosha.mcp.tools;

import static com.margosha.mcp.tools.Constants.AGENT_KEY_MISSING;
import static com.margosha.mcp.tools.Constants.TABLE_NOT_ALLOWED;

import java.util.List;
import java.util.Optional;

import lombok.NonNull;

public record ToolLimits(@NonNull List<String> allowedTables, boolean agentKeySet) {

	public ToolLimits {
		allowedTables = List.copyOf(allowedTables);
	}

	public Optional<ToolError> refusal(String table) {
		if (!agentKeySet) {
			return Optional.of(new ToolError(AGENT_KEY_MISSING,
					"The MCP server has no agent key, so it sends nothing to the store"));
		}
		if (!allowedTables.contains(table)) {
			return Optional.of(new ToolError(TABLE_NOT_ALLOWED,
					"Table " + table + " is not allowed for agents; allowed tables: " + allowedTables));
		}
		return Optional.empty();
	}
}
