package com.margosha.node.coordinator.agent;

import static com.margosha.node.data.TestData.AGENT_ARGS;
import static com.margosha.node.data.TestData.AGENT_KEY;
import static com.margosha.node.data.TestData.AGENT_NOTES;
import static com.margosha.node.data.TestData.ANN;
import static com.margosha.node.data.TestData.BOB_ATTRIBUTES;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.apiError;
import static com.margosha.node.data.TestData.usersTable;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.margosha.api.client.CoordinatorClient;
import com.margosha.api.exception.ApiException;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.TableRecord;
import com.margosha.mcp.tools.config.AgentCoordinatorClient;
import com.margosha.node.data.InProcessNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentKeyFilterTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private InProcessNode coordinator;
	private CoordinatorClient store;
	private CoordinatorClient agent;

	@BeforeEach
	void setUp() {
		coordinator = InProcessNode.singleModeCoordinator(AGENT_ARGS);
		store = coordinator.client();
		store.registerTable(usersTable(AGENT_NOTES));
		store.registerTable(usersTable(USERS));
		agent = AgentCoordinatorClient.create(coordinator.address(), TIMEOUT, AGENT_KEY);
	}

	@AfterEach
	void tearDown() {
		coordinator.close();
	}

	@Test
	void theAgentKeyMayCreateAndReadARecordInAnAllowedTable() {
		// Arrange
		agent.createRecord(AGENT_NOTES, ANN);

		// Act
		TableRecord read = agent.readRecord(AGENT_NOTES, USER_42);

		// Assert
		assertThat(read).isEqualTo(ANN);
	}

	@Test
	void aRequestWithoutTheAgentKeyWorksAsBefore() {
		// Arrange
		store.createRecord(USERS, ANN);

		// Act
		TableRecord updated = store.updateRecord(USERS, USER_42, BOB_ATTRIBUTES);

		// Assert
		assertThat(updated.get("name")).isEqualTo(BOB_ATTRIBUTES.get("name"));
	}

	@Test
	void aWrongAgentKeyIsRefusedWith401AndCreatesNothing() {
		// Arrange
		CoordinatorClient wrongKey = AgentCoordinatorClient.create(coordinator.address(), TIMEOUT, "not-the-key");

		// Act
		ApiException error = apiError(() -> wrongKey.createRecord(AGENT_NOTES, ANN));

		// Assert
		assertThat(error.status()).isEqualTo(401);
		assertThat(error.code()).isEqualTo(ErrorCode.AGENT_KEY_INVALID);
		assertThat(store.recordExists(AGENT_NOTES, USER_42)).isFalse();
	}

	@Test
	void anyAgentKeyIsRefusedWith401WhenTheCoordinatorHasNone() {
		// Arrange
		try (InProcessNode withoutKey = InProcessNode.singleModeCoordinator()) {
			withoutKey.client().registerTable(usersTable(AGENT_NOTES));
			CoordinatorClient agentWithoutKey = AgentCoordinatorClient.create(withoutKey.address(), TIMEOUT, AGENT_KEY);

			// Act
			ApiException error = apiError(() -> agentWithoutKey.createRecord(AGENT_NOTES, ANN));

			// Assert
			assertThat(error.status()).isEqualTo(401);
			assertThat(withoutKey.client().recordExists(AGENT_NOTES, USER_42)).isFalse();
		}
	}

	@Test
	void theAgentKeyMayNotUseATableThatIsNotAllowed() {
		// Act
		ApiException error = apiError(() -> agent.createRecord(USERS, ANN));

		// Assert
		assertThat(error.status()).isEqualTo(403);
		assertThat(error.code()).isEqualTo(ErrorCode.AGENT_NOT_ALLOWED);
		assertThat(store.recordExists(USERS, USER_42)).isFalse();
	}

	@Test
	void theAgentKeyMayNotUpdateARecord() {
		// Arrange
		store.createRecord(AGENT_NOTES, ANN);

		// Act
		ApiException error = apiError(() -> agent.updateRecord(AGENT_NOTES, USER_42, BOB_ATTRIBUTES));

		// Assert
		assertThat(error.status()).isEqualTo(403);
		assertThat(error.code()).isEqualTo(ErrorCode.AGENT_NOT_ALLOWED);
		assertThat(store.readRecord(AGENT_NOTES, USER_42)).isEqualTo(ANN);
	}

	@Test
	void theAgentKeyMayNotRegisterATable() {
		// Act
		ApiException error = apiError(() -> agent.registerTable(usersTable("agent_extra")));

		// Assert
		assertThat(error.status()).isEqualTo(403);
		assertThat(error.code()).isEqualTo(ErrorCode.AGENT_NOT_ALLOWED);
	}
}
