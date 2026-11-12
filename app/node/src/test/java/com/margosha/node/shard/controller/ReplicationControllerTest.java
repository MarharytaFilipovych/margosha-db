package com.margosha.node.shard.controller;

import static com.margosha.api.util.PathConstants.REPLICATION_LOG;
import static com.margosha.node.data.TestData.json;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Stream;

import com.margosha.api.model.ApiError;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.ReplicationBatch;
import com.margosha.node.shard.replication.leader.ReplicationLeader;
import com.margosha.node.shard.replication.model.ReplicaIdentity;
import com.margosha.node.shard.replication.storage.ReplicaStorage;
import com.margosha.node.shard.service.ReplicaReadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("shard")
@WebMvcTest(ReplicationController.class)
class ReplicationControllerTest {

	private static final String MAX_OUT_OF_RANGE = "max must be between 1 and 1000";

	@MockitoBean
	private ReplicaIdentity identity;

	@MockitoBean
	private ReplicaStorage replicaStorage;

	@MockitoBean
	private ReplicaReadService replicaReadService;

	@MockitoBean
	private ReplicationLeader leader;

	@Autowired
	private MockMvc mvc;

	@Test
	void validFetchReachesTheLeader() throws Exception {
		// Arrange
		when(leader.fetch(anyString(), anyString(), anyLong(), isNull(), anyInt())).thenReturn(List.of());
		when(replicaStorage.lastVersion()).thenReturn(7L);
		when(replicaStorage.ensureLogId()).thenReturn("log-1");

		// Act & Assert
		mvc.perform(get(REPLICATION_LOG).param("after", "7").param("shard", "shard-a").param("follower", "shard-a-2"))
				.andExpect(status().isOk())
				.andExpect(content().json(json(new ReplicationBatch(List.of(), 7, "log-1")), JsonCompareMode.STRICT));
	}

	static Stream<Arguments> invalidFetches() {
		return Stream.of(
				Arguments.of("-1", "shard-a", "shard-a-2", "500", "after must be 0 or higher"),
				Arguments.of("0", " ", "shard-a-2", "500", "shard must be set"),
				Arguments.of("0", "shard-a", " ", "500", "follower must be set"),
				Arguments.of("0", "shard-a", "shard-a-2", "0", MAX_OUT_OF_RANGE),
				Arguments.of("0", "shard-a", "shard-a-2", "1001", MAX_OUT_OF_RANGE));
	}

	@ParameterizedTest
	@MethodSource("invalidFetches")
	void invalidFetchIsRejectedBeforeReachingTheLeader(String after, String shard, String follower, String max,
			String message) throws Exception {
		// Act & Assert
		mvc.perform(get(REPLICATION_LOG).param("after", after).param("shard", shard).param("follower", follower)
						.param("max", max))
				.andExpect(status().isBadRequest())
				.andExpect(content().json(json(new ApiError(ErrorCode.VALIDATION_ERROR, message)),
						JsonCompareMode.STRICT));

		// Assert
		verify(leader, never()).fetch(any(), any(), anyLong(), any(), anyInt());
	}
}
