package com.margosha.mcp.tools;

import static com.margosha.mcp.tools.Constants.ADDITIONAL_PROPERTIES;
import static com.margosha.mcp.tools.Constants.PROPERTIES;
import static com.margosha.mcp.tools.Constants.RECORD;
import static com.margosha.mcp.tools.Constants.STRING_TYPE;
import static com.margosha.mcp.tools.Constants.TYPE;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;

@UtilityClass
public class RecordToolSpecifications {

	public List<SyncToolSpecification> of(@NonNull RecordTools tools) {
		return new SyncMcpToolProvider(List.of(tools)).getToolSpecifications().stream()
				.map(RecordToolSpecifications::withStringAttributes)
				.toList();
	}

	@SuppressWarnings("unchecked")
	private SyncToolSpecification withStringAttributes(SyncToolSpecification specification) {
		Tool tool = specification.tool();
		Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get(PROPERTIES);
		if (properties == null || !properties.containsKey(RECORD)) {
			return specification;
		}
		Map<String, Object> record = new LinkedHashMap<>((Map<String, Object>) properties.get(RECORD));
		record.put(ADDITIONAL_PROPERTIES, Map.of(TYPE, STRING_TYPE));
		Map<String, Object> newProperties = new LinkedHashMap<>(properties);
		newProperties.put(RECORD, record);
		Map<String, Object> inputSchema = new LinkedHashMap<>(tool.inputSchema());
		inputSchema.put(PROPERTIES, newProperties);
		Tool newTool = new Tool(tool.name(), tool.title(), tool.description(), inputSchema, tool.outputSchema(),
				tool.annotations(), tool.meta(), tool.icons());
		return SyncToolSpecification.builder().tool(newTool).callHandler(specification.callHandler()).build();
	}
}
