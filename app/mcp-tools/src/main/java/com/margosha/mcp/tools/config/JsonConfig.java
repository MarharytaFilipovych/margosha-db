package com.margosha.mcp.tools.config;

import lombok.experimental.UtilityClass;
import tools.jackson.databind.json.JsonMapper;

@UtilityClass
public class JsonConfig {

	public static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
}
