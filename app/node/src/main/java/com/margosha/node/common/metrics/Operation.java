package com.margosha.node.common.metrics;

import static com.margosha.api.util.PathConstants.RECORD;
import static com.margosha.api.util.PathConstants.RECORDS;

import java.util.Optional;

import com.margosha.api.util.PathConstants;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.tomcat.util.http.Method;
import org.springframework.web.servlet.HandlerMapping;

public enum Operation {

	CREATE, READ, EXISTS, UPDATE, DELETE;

	public String tag() {
		return name().toLowerCase();
	}

	public static Optional<Operation> of(HttpServletRequest request) {
		Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		if (pattern == null) {
			return Optional.empty();
		}
		String route = pattern.toString();
		return Optional.ofNullable(switch (request.getMethod()) {
			case Method.POST -> route.equals(RECORDS) || route.equals(RECORD) ? CREATE : null;
			case Method.GET -> route.equals(RECORD) ? READ : route.equals(PathConstants.EXISTS) ? EXISTS : null;
			case Method.PUT -> route.equals(RECORD) ? UPDATE : null;
			case Method.DELETE -> route.equals(RECORD) ? DELETE : null;
			default -> null;
		});
	}
}
