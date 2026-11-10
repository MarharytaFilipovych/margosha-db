package com.margosha.node.coordinator.agent;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.util.StringUtils.hasText;

import java.security.MessageDigest;
import java.util.List;

import lombok.Getter;
import lombok.NonNull;
import org.jspecify.annotations.Nullable;

public final class AgentAccess {

	private final byte @Nullable [] key;

	@Getter
	private final List<String> allowedTables;

	public AgentAccess(@Nullable String key, @NonNull List<String> allowedTables) {
		this.key = !hasText(key) ? null : key.getBytes(UTF_8);
		this.allowedTables = List.copyOf(allowedTables);
	}

	public boolean hasKey() {
		return key != null;
	}

	public boolean isKey(@NonNull String candidate) {
		return key != null && MessageDigest.isEqual(key, candidate.getBytes(UTF_8));
	}

	public boolean allows(@NonNull String table) {
		return allowedTables.contains(table);
	}
}
