package com.margosha.node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


@SpringBootApplication
public class NodeApplication {

	private static final Logger log = LoggerFactory.getLogger(NodeApplication.class);

	private static final String USAGE = """
			Usage:
			  java -jar margosha.jar coordinator --mode=single|sharded
			  java -jar margosha.jar shard""";

	public static void main(String[] args) {
		if (args.length == 0) {
			fail("No role given.");
		}
		Role role = Role.fromArgument(args[0]).orElse(null);
		if (role == null) {
			fail("Unknown role: " + args[0]);
		}

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
			case COORDINATOR -> {
				mode = Mode.fromArgument(modeArgument).orElse(null);
				if (mode == null) {
					fail("The coordinator needs --mode=single or --mode=sharded.");
				}
			}
			case SHARD -> {
				if (modeArgument != null) {
					fail("--mode is only used by the coordinator.");
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
		app.run(springArgs.toArray(String[]::new));

		log.info("Running as {}{}", role.argument(), mode != null ? " (mode: " + mode.argument() + ")" : "");
	}

	private static void fail(String message) {
		System.err.println(message);
		System.err.println(USAGE);
		System.exit(1);
	}
}
