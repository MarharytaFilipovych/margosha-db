package com.margosha.node;

import java.util.Arrays;
import java.util.Optional;

public enum Role {

	COORDINATOR,
	SHARD;

	public String argument() {
		return name().toLowerCase();
	}

	public static Optional<Role> fromArgument(String argument) {
		return Arrays.stream(values())
				.filter(role -> role.argument().equals(argument))
				.findFirst();
	}
}
