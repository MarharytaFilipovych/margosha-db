package com.margosha.node.controller;

import static com.margosha.node.data.TestData.ANN;
import static com.margosha.node.data.TestData.BOB;
import static com.margosha.node.data.TestData.BOB_ATTRIBUTES;
import static com.margosha.node.data.TestData.CUSTOMER_ID;
import static com.margosha.node.data.TestData.ORDERS;
import static com.margosha.node.data.TestData.ORDER_1;
import static com.margosha.node.data.TestData.ORDER_2;
import static com.margosha.node.data.TestData.ORDER_42_1;
import static com.margosha.node.data.TestData.ORDER_42_2;
import static com.margosha.node.data.TestData.ORDER_ID;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.USER_7;
import static com.margosha.node.data.TestData.USER_ID;
import static com.margosha.node.data.TestData.apiError;
import static com.margosha.node.data.TestData.client;
import static com.margosha.node.data.TestData.ordersTable;
import static com.margosha.node.data.TestData.unique;
import static com.margosha.node.data.TestData.usersTable;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.margosha.api.client.MargoshaDbClient;
import com.margosha.api.exception.ApiException;
import com.margosha.api.model.Attributes;
import com.margosha.api.model.ErrorCode;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.TableDefinition;
import com.margosha.api.model.TableRecord;
import com.margosha.node.storage.InMemoryRecordStore;
import com.margosha.node.storage.RecordStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

@SingleModeCoordinatorTest
class KvApiScenariosTest {

	@LocalServerPort
	private int port;

	private MargoshaDbClient client;
	private final String users = unique(USERS);
	private final String orders = unique(ORDERS);

	@BeforeEach
	void setUp() {
		client = client(port);
	}

	@Nested
	class SingleMode {

		@Autowired
		private RecordStorage backend;

		@Test
		void singleModeStoresRecordsInTheCoordinatorItself() {
			// Assert
			assertThat(backend).isInstanceOf(InMemoryRecordStore.class);
		}
	}

	@Nested
	class RegisterTable {

		@Test
		void tableWithPartitionKeyOnly() {
			// Arrange
			TableDefinition table = usersTable(users);

			// Act
			TableDefinition registered = client.registerTable(table);

			// Assert
			assertThat(registered).isEqualTo(table);
			client.createRecord(users, ANN);
			assertThat(client.readRecord(users, USER_42)).isEqualTo(ANN);
		}

		@Test
		void tableWithPartitionKeyAndSortKey() {
			// Arrange
			TableDefinition table = ordersTable(orders);

			// Act
			client.registerTable(table);

			// Assert
			client.createRecord(orders, ORDER_1);
			client.createRecord(orders, ORDER_2);
			assertThat(client.readRecord(orders, ORDER_42_1)).isEqualTo(ORDER_1);
			assertThat(client.readRecord(orders, ORDER_42_2)).isEqualTo(ORDER_2);
		}

		@Test
		void invalidTableDefinitionIsRejected() {
			// Act
			ApiException emptyName = apiError(() -> client.registerTable(TableDefinition.of("", USER_ID)));
			ApiException noPartitionKey = apiError(() -> client.registerTable(TableDefinition.of(users, null)));

			// Assert: validation error and no table is created
			assertThat(emptyName.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
			assertThat(noPartitionKey.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
			assertThat(apiError(() -> client.recordExists(users, USER_42)).code()).isEqualTo(ErrorCode.TABLE_NOT_FOUND);
		}

		@Test
		void tableAlreadyExists() {
			// Arrange
			client.registerTable(usersTable(users));

			// Act
			ApiException error = apiError(() -> client.registerTable(TableDefinition.of(users, CUSTOMER_ID)));

			// Assert
			assertThat(error.isConflict()).isTrue();
			assertThat(error.code()).isEqualTo(ErrorCode.TABLE_ALREADY_EXISTS);
			assertThat(client.createRecord(users, ANN)).isEqualTo(ANN);
		}
	}

	@Nested
	class RecordShape {

		@Test
		void missingKeyAttributeIsRejected() {
			// Arrange
			client.registerTable(ordersTable(orders));

			// Act
			ApiException error = apiError(() -> client.createRecord(orders, TableRecord.of(Map.of(CUSTOMER_ID, "42"))));

			// Assert
			assertThat(error.isValidationError()).isTrue();
			assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
			assertThat(error.error().message()).contains(ORDER_ID);
		}
	}

	@Nested
	class Create {

		@BeforeEach
		void registerUsers() {
			client.registerTable(usersTable(users));
		}

		@Test
		void newRecord() {
			// Act
			client.createRecord(users, ANN);

			// Assert
			assertThat(client.readRecord(users, USER_42).get("name")).isEqualTo("Ann");
		}

		@Test
		void recordAlreadyExists() {
			// Arrange
			client.createRecord(users, ANN);

			// Act
			ApiException error = apiError(() -> client.createRecord(users, BOB));

			// Assert: conflict, and the existing record is unchanged
			assertThat(error.code()).isEqualTo(ErrorCode.RECORD_ALREADY_EXISTS);
			assertThat(client.readRecord(users, USER_42)).isEqualTo(ANN);
		}
	}

	@Nested
	class Read {

		@BeforeEach
		void registerUsers() {
			client.registerTable(usersTable(users));
		}

		@Test
		void recordExists() {
			// Arrange
			client.createRecord(users, ANN);

			// Act
			TableRecord record = client.readRecord(users, USER_42);

			// Assert: key values and attributes
			assertThat(record).isEqualTo(ANN);
		}

		@Test
		void recordDoesNotExist() {
			// Act
			ApiException error = apiError(() -> client.readRecord(users, USER_42));

			// Assert
			assertThat(error.isNotFound()).isTrue();
			assertThat(error.code()).isEqualTo(ErrorCode.RECORD_NOT_FOUND);
		}
	}

	@Nested
	class Exists {

		@Test
		void presentAndAbsentKeys() {
			// Arrange
			client.registerTable(usersTable(users));
			client.createRecord(users, ANN);

			// Act
			boolean present = client.recordExists(users, USER_42);
			boolean absent = client.recordExists(users, USER_7);

			// Assert
			assertThat(present).isTrue();
			assertThat(absent).isFalse();
		}
	}

	@Nested
	class Update {

		@BeforeEach
		void registerUsers() {
			client.registerTable(usersTable(users));
		}

		@Test
		void updateExistingRecord() {
			// Arrange
			client.createRecord(users, ANN);

			// Act
			client.updateRecord(users, USER_42, BOB_ATTRIBUTES);

			// Assert
			assertThat(client.readRecord(users, USER_42)).isEqualTo(BOB);
		}

		@Test
		void updateOfMissingRecord() {
			// Act
			ApiException error = apiError(() -> client.updateRecord(users, USER_42, BOB_ATTRIBUTES));

			// Assert
			assertThat(error.code()).isEqualTo(ErrorCode.RECORD_NOT_FOUND);
			assertThat(client.recordExists(users, USER_42)).isFalse();
		}
	}

	@Nested
	class Delete {

		@BeforeEach
		void registerUsers() {
			client.registerTable(usersTable(users));
		}

		@Test
		void deleteExistingRecord() {
			// Arrange
			client.createRecord(users, ANN);

			// Act
			client.deleteRecord(users, USER_42);

			// Assert
			assertThat(client.recordExists(users, USER_42)).isFalse();
		}

		@Test
		void deleteMissingRecord() {
			// Act
			ApiException error = apiError(() -> client.deleteRecord(users, USER_42));

			// Assert
			assertThat(error.code()).isEqualTo(ErrorCode.RECORD_NOT_FOUND);
		}
	}

	@Nested
	class UnknownTable {

		@Test
		void recordOperationOnUnknownTable() {
			// Arrange
			String ghost = unique("ghost");

			// Act
			ApiException error = apiError(() -> client.createRecord(ghost, ANN));

			// Assert
			assertThat(error.code()).isEqualTo(ErrorCode.TABLE_NOT_FOUND);
			client.registerTable(usersTable(ghost));
			assertThat(client.recordExists(ghost, USER_42)).isFalse();
		}
	}

	@Nested
	class SortKeyParameter {

		@Test
		void sortKeyIsRequiredForTableWithSortKey() {
			// Arrange
			client.registerTable(ordersTable(orders));

			// Act
			ApiException error = apiError(() -> client.readRecord(orders, PrimaryKey.of("42")));

			// Assert
			assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
		}

		@Test
		void sortKeyIsRejectedForTableWithoutSortKey() {
			// Arrange
			client.registerTable(usersTable(users));

			// Act
			ApiException error = apiError(() -> client.readRecord(users, ORDER_42_1));

			// Assert
			assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
		}

		@Test
		void updateMustNotChangeKeyAttributes() {
			// Arrange
			client.registerTable(usersTable(users));
			client.createRecord(users, ANN);

			// Act
			ApiException error = apiError(() -> client.updateRecord(users, USER_42, Attributes.of(Map.of(USER_ID, "43"))));

			// Assert
			assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
			assertThat(client.readRecord(users, USER_42)).isEqualTo(ANN);
		}
	}
}
