package com.margosha.node.coordinator.service;

import static com.margosha.node.data.TestData.CUSTOMER_ID;
import static com.margosha.node.data.TestData.ORDERS_TABLE;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USERS_TABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.margosha.api.model.TableDefinition;
import com.margosha.node.coordinator.exception.TableAlreadyExistsException;
import com.margosha.node.coordinator.exception.TableNotFoundException;
import org.junit.jupiter.api.Test;

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

	// Invalid definitions are rejected by @Valid in the controller: CoordinatorControllerTest.

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
