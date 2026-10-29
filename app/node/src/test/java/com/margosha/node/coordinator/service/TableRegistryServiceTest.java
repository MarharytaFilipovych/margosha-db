package com.margosha.node.coordinator.service;

import static com.margosha.node.data.TestData.CUSTOMER_ID;
import static com.margosha.node.data.TestData.ORDERS_TABLE;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USERS_TABLE;
import static com.margosha.node.data.TestData.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import com.margosha.api.model.TableDefinition;
import com.margosha.node.coordinator.exception.TableAlreadyExistsException;
import com.margosha.node.coordinator.exception.TableNotFoundException;
import com.margosha.node.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TableRegistryServiceTest {

	private final TableRegistryService registry = new TableRegistryService();

	@Test
	void registersTableWithPartitionKeyOnly() {
		// Act
		TableDefinition registered = registry.register(USERS_TABLE);

		// Assert
		assertThat(registered).isEqualTo(USERS_TABLE);
		assertThat(registry.get(USERS)).isEqualTo(USERS_TABLE);
	}

	@Test
	void registersTableWithSortKey() {
		// Act
		registry.register(ORDERS_TABLE);

		// Assert
		assertThat(registry.get(ORDERS_TABLE.name())).isEqualTo(ORDERS_TABLE);
	}

	static Stream<Arguments> invalidDefinitions() {
		return Stream.of(
				Arguments.of(null, "Table definition is required"),
				Arguments.of(TableDefinition.of(null, USER_ID), "Table name must not be empty"),
				Arguments.of(TableDefinition.of("", USER_ID), "Table name must not be empty"),
				Arguments.of(TableDefinition.of("  ", USER_ID), "Table name must not be empty"),
				Arguments.of(TableDefinition.of(USERS, null), "Partition key of table users must not be empty"),
				Arguments.of(TableDefinition.of(USERS, " "), "Partition key of table users must not be empty"),
				Arguments.of(new TableDefinition(USERS, USER_ID, " "), "Sort key of table users must not be empty if given"),
				Arguments.of(new TableDefinition(USERS, USER_ID, USER_ID), "Sort key of table users must differ from the partition key"));
	}

	@ParameterizedTest
	@MethodSource("invalidDefinitions")
	void invalidDefinitionIsRejectedAndNotStored(TableDefinition table, String message) {
		// Act & Assert
		assertThatThrownBy(() -> registry.register(table))
				.isInstanceOf(ValidationException.class)
				.hasMessage(message);

		// Assert
		assertThatThrownBy(() -> registry.get(USERS)).isInstanceOf(TableNotFoundException.class);
	}

	@Test
	void registeringExistingNameFailsAndKeepsTheTable() {
		// Arrange
		registry.register(USERS_TABLE);

		// Act & Assert
		assertThatThrownBy(() -> registry.register(TableDefinition.of(USERS, CUSTOMER_ID)))
				.isInstanceOf(TableAlreadyExistsException.class)
				.hasMessage("Table users already exists");

		// Assert
		assertThat(registry.get(USERS)).isEqualTo(USERS_TABLE);
	}

	@Test
	void getOfUnknownTableFails() {
		// Act & Assert
		assertThatThrownBy(() -> registry.get("ghost"))
				.isInstanceOf(TableNotFoundException.class)
				.hasMessage("Table ghost does not exist");
	}
}
