package com.margosha.node.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.springframework.boot.test.system.CapturedOutput;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public final class LogLines {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private LogLines() { }

	public static Map<String, Object> lineWith(CapturedOutput output, String text) {
		List<String> lines = output.getOut().lines().filter(line -> line.contains(text)).toList();
		assertThat(lines).as("console lines with \"%s\"", text).hasSize(1);
		assertThat(lines.getFirst()).startsWith("{").endsWith("}");
		return JSON.readValue(lines.getFirst(), new TypeReference<>() { });
	}
}
