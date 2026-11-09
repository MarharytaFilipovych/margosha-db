package com.margosha.node.coordinator.controller;

import static com.margosha.node.data.TestData.ANN;
import static com.margosha.node.data.TestData.BOB;
import static com.margosha.node.data.TestData.BOB_ATTRIBUTES;
import static com.margosha.node.data.TestData.ORDERS;
import static com.margosha.node.data.TestData.ORDER_1;
import static com.margosha.node.data.TestData.ORDER_42_1;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USERS_TABLE;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.LogLines.lineWith;
import static com.margosha.node.data.TestData.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.stream.Stream;

import com.margosha.api.model.ApiError;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.ExistsResult;
import com.margosha.api.util.TraceIds;
import com.margosha.node.common.exception.RecordNotFoundException;
import com.margosha.node.coordinator.service.RecordService;
import com.margosha.node.coordinator.service.TableRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import com.margosha.node.common.metrics.RequestMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("coordinator")
@WebMvcTest(CoordinatorController.class)
@Import({RequestMetrics.class, SimpleMeterRegistry.class})
@ExtendWith(OutputCaptureExtension.class)
class CoordinatorControllerTest {

	private static final String TABLES = "/tables";
	private static final String RECORDS = "/records";
	private static final String EXISTS = "/exists";
	private static final String SORT_KEY = "sortKey";

	private static final String KEY_42 = USER_42.partitionKey();
	private static final String ORDER_ID_1 = ORDER_42_1.sortKey();

	private static final String USERS_RECORDS = TABLES + "/" + USERS + RECORDS;
	private static final String ORDERS_RECORDS = TABLES + "/" + ORDERS + RECORDS;
	private static final String USER_42_RECORD = USERS_RECORDS + "/" + KEY_42;
	private static final String ORDER_42_RECORD = ORDERS_RECORDS + "/" + KEY_42;
	private static final String USER_42_EXISTS = USER_42_RECORD + EXISTS;

	@MockitoBean
	private TableRegistryService tables;

	@MockitoBean
	private RecordService records;

	@Autowired
	private MockMvc mvc;

	@Test
	void registerTableReturns201WithTheTable() throws Exception {
		// Arrange
		when(tables.register(USERS_TABLE)).thenReturn(USERS_TABLE);

		// Act & Assert
		mvc.perform(post(TABLES).contentType(MediaType.APPLICATION_JSON).content(json(USERS_TABLE)))
				.andExpect(status().isCreated())
				.andExpect(content().json(json(USERS_TABLE), JsonCompareMode.STRICT));
	}

	@Test
	void createRecordReturns201WithTheRecord() throws Exception {
		// Arrange
		when(records.create(USERS, ANN)).thenReturn(ANN);

		// Act & Assert
		mvc.perform(post(USERS_RECORDS).contentType(MediaType.APPLICATION_JSON).content(json(ANN)))
				.andExpect(status().isCreated())
				.andExpect(content().json(json(ANN), JsonCompareMode.STRICT));
	}

	@Test
	void readRecordWithoutSortKeyPassesNull() throws Exception {
		// Arrange
		when(records.read(USERS, KEY_42, null)).thenReturn(ANN);

		// Act & Assert
		mvc.perform(get(USER_42_RECORD))
				.andExpect(status().isOk())
				.andExpect(content().json(json(ANN), JsonCompareMode.STRICT));
	}

	@Test
	void readRecordPassesSortKeyFromQueryParameter() throws Exception {
		// Arrange
		when(records.read(ORDERS, KEY_42, ORDER_ID_1)).thenReturn(ORDER_1);

		// Act & Assert
		mvc.perform(get(ORDER_42_RECORD).queryParam(SORT_KEY, ORDER_ID_1))
				.andExpect(status().isOk())
				.andExpect(content().json(json(ORDER_1), JsonCompareMode.STRICT));
	}

	@Test
	void recordExistsReturnsExistsResult() throws Exception {
		// Arrange
		when(records.exists(USERS, KEY_42, null)).thenReturn(true);

		// Act & Assert
		mvc.perform(get(USER_42_EXISTS))
				.andExpect(status().isOk())
				.andExpect(content().json(json(new ExistsResult(true)), JsonCompareMode.STRICT));
	}

	@Test
	void updateRecordReturns200WithTheUpdatedRecord() throws Exception {
		// Arrange
		when(records.update(USERS, KEY_42, null, BOB_ATTRIBUTES)).thenReturn(BOB);

		// Act & Assert
		mvc.perform(put(USER_42_RECORD).contentType(MediaType.APPLICATION_JSON).content(json(BOB_ATTRIBUTES)))
				.andExpect(status().isOk())
				.andExpect(content().json(json(BOB), JsonCompareMode.STRICT));
	}

	@Test
	void deleteRecordReturns204WithoutBody() throws Exception {
		// Act & Assert
		mvc.perform(delete(USER_42_RECORD))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		// Assert
		verify(records).delete(USERS, KEY_42, null);
	}

	@Test
	void serviceErrorIsReturnedInSharedFormat() throws Exception {
		// Arrange
		RecordNotFoundException notFound = new RecordNotFoundException(USERS, USER_42);
		when(records.read(USERS, KEY_42, null)).thenThrow(notFound);

		// Act & Assert
		mvc.perform(get(USER_42_RECORD))
				.andExpect(status().isNotFound())
				.andExpect(content().json(json(new ApiError(ErrorCode.RECORD_NOT_FOUND, notFound.getMessage())),
						JsonCompareMode.STRICT));
	}

	@Test
	void requestWithoutATraceIdGetsANewOneInTheResponse() throws Exception {
		// Arrange
		when(records.read(USERS, KEY_42, null)).thenReturn(ANN);

		// Act & Assert
		mvc.perform(get(USER_42_RECORD))
				.andExpect(status().isOk())
				.andExpect(header().string(TraceIds.HEADER, matchesPattern("[0-9a-f]{32}")));
	}

	@Test
	void validTraceIdFromTheClientIsKept() throws Exception {
		// Arrange
		when(records.read(USERS, KEY_42, null)).thenReturn(ANN);

		// Act & Assert
		mvc.perform(get(USER_42_RECORD).header(TraceIds.HEADER, "client-trace-42"))
				.andExpect(status().isOk())
				.andExpect(header().string(TraceIds.HEADER, "client-trace-42"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "has space", "under_score", "quote\"", "a1234567890123456789012345678901234567890123456789012345678901234"})
	void invalidTraceIdFromTheClientIsReplacedByANewOne(String invalid) throws Exception {
		// Arrange
		when(records.read(USERS, KEY_42, null)).thenReturn(ANN);

		// Act & Assert
		mvc.perform(get(USER_42_RECORD).header(TraceIds.HEADER, invalid))
				.andExpect(status().isOk())
				.andExpect(header().string(TraceIds.HEADER, matchesPattern("[0-9a-f]{32}")));
	}

	@Test
	void errorResponseCarriesTheTraceId() throws Exception {
		// Arrange
		when(records.read(USERS, KEY_42, null)).thenThrow(new RecordNotFoundException(USERS, USER_42));

		// Act & Assert
		mvc.perform(get(USER_42_RECORD).header(TraceIds.HEADER, "trace-of-a-404"))
				.andExpect(status().isNotFound())
				.andExpect(header().string(TraceIds.HEADER, "trace-of-a-404"));
	}

	@Test
	void finishedRequestIsLoggedOnceWithItsTraceIdStatusAndDuration(CapturedOutput output) throws Exception {
		// Arrange
		when(records.read(USERS, KEY_42, null)).thenThrow(new RecordNotFoundException(USERS, USER_42));

		// Act
		mvc.perform(get(USER_42_RECORD).header(TraceIds.HEADER, "trace-of-the-end-line"));

		// Assert
		Map<String, Object> line = lineWith(output, "\"GET " + USER_42_RECORD + " 404 in ");
		assertThat(line).containsEntry("traceId", "trace-of-the-end-line")
				.containsEntry("method", "GET")
				.containsEntry("path", USER_42_RECORD)
				.containsEntry("status", 404)
				.containsEntry("level", "INFO");
		assertThat(line.get("durationMs")).isInstanceOf(Integer.class);
		assertThat(MDC.get(TraceIds.MDC_KEY)).as("trace ID removed from the log context after the request").isNull();
	}

	static Stream<Arguments> invalidTableDefinitions() {
		return Stream.of(
				Arguments.of("{\"partitionKey\":\"user_id\"}", "Table name must not be empty"),
				Arguments.of("{\"name\":\"\",\"partitionKey\":\"user_id\"}", "Table name must not be empty"),
				Arguments.of("{\"name\":\"  \",\"partitionKey\":\"user_id\"}", "Table name must not be empty"),
				Arguments.of("{\"name\":\"users\"}", "Partition key must not be empty"),
				Arguments.of("{\"name\":\"users\",\"partitionKey\":\" \"}", "Partition key must not be empty"),
				Arguments.of("{\"name\":\"users\",\"partitionKey\":\"user_id\",\"sortKey\":\" \"}",
						"Sort key must not be empty if given"),
				Arguments.of("{\"name\":\"users\",\"partitionKey\":\"user_id\",\"sortKey\":\"user_id\"}",
						"Sort key must differ from the partition key"),
				Arguments.of("{}", "Partition key must not be empty; Table name must not be empty"));
	}

	@ParameterizedTest
	@MethodSource("invalidTableDefinitions")
	void invalidTableDefinitionIsRejectedBeforeReachingTheService(String body, String message) throws Exception {
		// Act & Assert
		mvc.perform(post(TABLES).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(content().json(json(new ApiError(ErrorCode.VALIDATION_ERROR, message)),
						JsonCompareMode.STRICT));

		// Assert
		verifyNoInteractions(tables);
	}

	@Test
	void blankPartitionKeyInThePathIsRejectedBeforeReachingTheService() throws Exception {
		// Act & Assert
		mvc.perform(get(USERS_RECORDS + "/{partitionKey}", " "))
				.andExpect(status().isBadRequest())
				.andExpect(content().json(json(new ApiError(ErrorCode.VALIDATION_ERROR, "Missing partition key value")),
						JsonCompareMode.STRICT));

		// Assert
		verifyNoInteractions(records);
	}

	@Test
	void invalidJsonBodyIsRejectedBeforeReachingTheService() throws Exception {
		// Act & Assert
		mvc.perform(post(USERS_RECORDS).contentType(MediaType.APPLICATION_JSON).content("{not json"))
				.andExpect(status().isBadRequest())
				.andExpect(content().json(json(new ApiError(ErrorCode.VALIDATION_ERROR,
								"Request body is missing or not valid")),
						JsonCompareMode.STRICT));

		// Assert
		verifyNoInteractions(records);
	}
}
