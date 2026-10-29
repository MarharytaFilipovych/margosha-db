package com.margosha.node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@Slf4j
@SpringBootApplication
public class NodeApplication {

	private static final String USAGE = """
			Usage:
			  java -jar margosha.jar coordinator --mode=single|sharded
			  java -jar margosha.jar shard --margosha.shard.id=<id> --margosha.coordinator.url=<url>""";

	public static void main(String[] args) {
		try {
			run(args);
		} catch (IllegalArgumentException e) {
			log.error(e.getMessage());
			log.error(USAGE);
			System.exit(1);
		}
	}

	public static ConfigurableApplicationContext run(String... args) {
		if (args.length == 0) {
			throw new IllegalArgumentException("No role given.");
		}
		Role role = Role.fromArgument(args[0])
				.orElseThrow(() -> new IllegalArgumentException("Unknown role: " + args[0]));

		String modeArgument = null;
		List<String> springArgs = new ArrayList<>();
		for (int i = 1; i < args.length; i++) {
			if (args[i].startsWith("--mode=")) {
				modeArgument = args[i].substring("--mode=".length());
			} else {
				springArgs.add(args[i]);
			}
		}

		Mode mode = null;
		switch (role) {
			case COORDINATOR -> mode = Mode.fromArgument(modeArgument).orElseThrow(
					() -> new IllegalArgumentException("The coordinator needs --mode=single or --mode=sharded."));
			case SHARD -> {
				if (modeArgument != null) {
					throw new IllegalArgumentException("--mode is only used by the coordinator.");
				}
			}
		}

		Map<String, Object> properties = new HashMap<>();
		properties.put("margosha.role", role.name());
		if (mode != null) {
			properties.put("margosha.mode", mode.name());
		}

		SpringApplication app = new SpringApplication(NodeApplication.class);
		app.setAdditionalProfiles(role.argument());
		app.setDefaultProperties(properties);
		ConfigurableApplicationContext context = app.run(springArgs.toArray(String[]::new));

		log.info("Running as {}{} (deployed by the pipeline)", role.argument(), mode != null ? " (mode: " + mode.argument() + ")" : "");
		log.info("Running as {}{}", role.argument(), mode != null ? " (mode: " + mode.argument() + ")" : "");
		return context;
	}
}
