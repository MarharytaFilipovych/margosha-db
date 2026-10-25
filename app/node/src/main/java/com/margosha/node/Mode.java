package com.margosha.node;

import java.util.Arrays;
import java.util.Optional;

public enum Mode {
	SINGLE,
	SHARDED;

	public String argument() {
		return name().toLowerCase();
	}

	public static Optional<Mode> fromArgument(String argument) {
		return Arrays.stream(values())
				.filter(mode -> mode.argument().equals(argument))
				.findFirst();
	}
}
