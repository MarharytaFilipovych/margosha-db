package com.margosha.node.coordinator.agent;

import static com.margosha.api.util.PathConstants.EXISTS;
import static com.margosha.api.util.PathConstants.RECORD;
import static com.margosha.api.util.PathConstants.RECORDS;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.margosha.api.model.ApiError;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.util.AgentKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class AgentKeyFilter extends OncePerRequestFilter {

	private static final List<RecordOperation> RECORD_OPERATIONS = List.of(
			new RecordOperation(HttpMethod.POST, RECORDS),
			new RecordOperation(HttpMethod.GET, RECORD),
			new RecordOperation(HttpMethod.GET, EXISTS),
			new RecordOperation(HttpMethod.DELETE, RECORD));

	@NonNull
	private final AgentAccess access;

	@NonNull
	private final JsonMapper json;

	@Override
	protected void doFilterInternal(HttpServletRequest request,
									@NonNull HttpServletResponse response,
									@NonNull FilterChain chain)
			throws ServletException, IOException {
		String key = request.getHeader(AgentKeys.HEADER);
		if (key == null) {
			chain.doFilter(request, response);
			return;
		}
		if (!access.isKey(key)) {
			refuse(request, response, HttpStatus.UNAUTHORIZED, ErrorCode.AGENT_KEY_INVALID,
					"The agent key is wrong, or the coordinator has none");
			return;
		}
		Optional<String> table = recordTable(request);
		if (table.isEmpty()) {
			refuse(request, response, HttpStatus.FORBIDDEN, ErrorCode.AGENT_NOT_ALLOWED,
					"Agents may only create, read, check and delete records");
			return;
		}
		if (!access.allows(table.get())) {
			refuse(request, response, HttpStatus.FORBIDDEN, ErrorCode.AGENT_NOT_ALLOWED,
					"Agents may not use table " + table.get() + "; allowed tables: " + access.getAllowedTables());
			return;
		}
		chain.doFilter(request, response);
	}

	private static Optional<String> recordTable(HttpServletRequest request) {
		PathContainer path = PathContainer.parsePath(request.getRequestURI());
		return RECORD_OPERATIONS.stream()
				.filter(operation -> operation.method().matches(request.getMethod()))
				.map(operation -> operation.pattern().matchAndExtract(path))
				.filter(Objects::nonNull)
				.map(match -> UriUtils.decode(match.getUriVariables().get("tableName"), UTF_8))
				.findFirst();
	}

	private void refuse(HttpServletRequest request, HttpServletResponse response, HttpStatus status, ErrorCode code,
			String message) throws IOException {
		log.warn("Refused agent request {} {}: {} {}", request.getMethod(), request.getRequestURI(), status.value(),
				message);
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(UTF_8.name());
		response.getWriter().write(json.writeValueAsString(new ApiError(code, message)));
	}

	private record RecordOperation(HttpMethod method, PathPattern pattern) {

		RecordOperation(HttpMethod method, String path) {
			this(method, PathPatternParser.defaultInstance.parse(path));
		}
	}
}
