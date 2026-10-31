package com.margosha.node.coordinator.controller;

import static com.margosha.node.data.TestData.json;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Stream;

import com.margosha.api.model.ApiError;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.ReplicaRole;
import com.margosha.api.model.ShardRegistration;
import com.margosha.node.coordinator.service.ShardRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("coordinator")
@TestPropertySource(properties = "margosha.mode=SHARDED")
@WebMvcTest(ShardRegistrationController.class)
class ShardRegistrationControllerTest {

	private static final String SHARDS = "/shards";
	private static final String NOT_AN_HTTP_URL = "Shard address must be an http(s) URL with a host";

	@MockitoBean
	private ShardRegistryService shards;

	@Autowired
	private MockMvc mvc;

	@Test
	void validRegistrationReachesTheRegistry() throws Exception {
		// Arrange
		ShardRegistration shard = new ShardRegistration("shard-a", "shard-a-1", ReplicaRole.LEADER, "http://10.0.1.15:8080");
		when(shards.register(any())).thenReturn(shard);

		// Act & Assert
		mvc.perform(post(SHARDS).contentType(MediaType.APPLICATION_JSON).content(json(shard)))
				.andExpect(status().isOk())
				.andExpect(content().json(json(shard), JsonCompareMode.STRICT));

		// Assert
		verify(shards).register(shard);
	}

	private static final String REPLICA = "\"replicaId\":\"shard-a-1\",\"role\":\"leader\",";

	static Stream<Arguments> invalidRegistrations() {
		return Stream.of(
				Arguments.of("{" + REPLICA + "\"address\":\"http://localhost:8081\"}", "Shard ID must not be empty"),
				Arguments.of("{\"shardId\":\" \"," + REPLICA + "\"address\":\"http://localhost:8081\"}", "Shard ID must not be empty"),
				Arguments.of("{\"shardId\":\"shard-a\",\"replicaId\":\" \",\"role\":\"leader\",\"address\":\"http://localhost:8081\"}",
						"Replica ID must not be empty"),
				Arguments.of("{\"shardId\":\"shard-a\",\"replicaId\":\"shard-a-1\",\"address\":\"http://localhost:8081\"}",
						"Replica role must be leader or follower"),
				Arguments.of("{\"shardId\":\"shard-a\"," + REPLICA.substring(0, REPLICA.length() - 1) + "}", "Shard address must not be empty"),
				Arguments.of("{\"shardId\":\"shard-a\"," + REPLICA + "\"address\":\"\"}", NOT_AN_HTTP_URL),
				Arguments.of("{\"shardId\":\"shard-a\"," + REPLICA + "\"address\":\"localhost:8081\"}", NOT_AN_HTTP_URL),
				Arguments.of("{\"shardId\":\"shard-a\"," + REPLICA + "\"address\":\"ftp://localhost:8081\"}", NOT_AN_HTTP_URL),
				Arguments.of("{\"shardId\":\"shard-a\"," + REPLICA + "\"address\":\"http://bad host\"}", NOT_AN_HTTP_URL),
				Arguments.of("{\"shardId\":\"shard-a\"," + REPLICA + "\"address\":\"http://\"}", NOT_AN_HTTP_URL));
	}

	@ParameterizedTest
	@MethodSource("invalidRegistrations")
	void invalidRegistrationIsRejectedBeforeReachingTheRegistry(String body, String message) throws Exception {
		// Act & Assert
		mvc.perform(post(SHARDS).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(content().json(json(new ApiError(ErrorCode.VALIDATION_ERROR, message)),
						JsonCompareMode.STRICT));

		// Assert
		verifyNoInteractions(shards);
	}
}
