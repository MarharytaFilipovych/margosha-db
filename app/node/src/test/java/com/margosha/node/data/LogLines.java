package com.margosha.node.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

	public static Optional<Map<String, Object>> fileLineWith(Path file, String text) throws IOException {
		if (!Files.exists(file)) {
			return Optional.empty();
		}
		return Files.readAllLines(file).stream()
				.filter(line -> line.contains(text))
				.findFirst()
				.map(line -> JSON.readValue(line, new TypeReference<>() { }));
	}
}
