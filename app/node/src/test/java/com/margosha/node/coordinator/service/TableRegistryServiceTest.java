package com.margosha.node.coordinator.service;

import static com.margosha.node.data.TestData.CUSTOMER_ID;
import static com.margosha.node.data.TestData.ORDERS_TABLE;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USERS_TABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.margosha.api.model.TableDefinition;
import com.margosha.node.coordinator.exception.TableAlreadyExistsException;
import com.margosha.node.coordinator.exception.TableNotFoundException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

	@Nested
	class Durable {

		@TempDir
		Path dataDir;

		private Path file() {
			return dataDir.resolve("tables.log");
		}

		@Test
		void tablesAreLoadedAgainAfterARestart() {
			// Arrange
			try (TableRegistryService before = TableRegistryService.durable(file())) {
				before.register(USERS_TABLE);
				before.register(ORDERS_TABLE);
			}

			// Act
			try (TableRegistryService after = TableRegistryService.durable(file())) {

				// Assert
				assertThat(after.get(USERS)).isEqualTo(USERS_TABLE);
				assertThat(after.get(ORDERS_TABLE.name())).isEqualTo(ORDERS_TABLE);
			}
		}

		@Test
		void tableLoadedFromTheFileCannotBeRegisteredAgain() throws IOException {
			// Arrange
			try (TableRegistryService before = TableRegistryService.durable(file())) {
				before.register(USERS_TABLE);
			}

			// Act
			Throwable thrown;
			try (TableRegistryService after = TableRegistryService.durable(file())) {
				thrown = catchThrowable(() -> after.register(USERS_TABLE));
			}

			// Assert
			assertThat(thrown).isInstanceOf(TableAlreadyExistsException.class);
			assertThat(Files.readAllLines(file()).size()).isEqualTo(1);
		}

		@Test
		void definitionCutOffByACrashIsDropped() throws IOException {
			// Arrange
			try (TableRegistryService before = TableRegistryService.durable(file())) {
				before.register(USERS_TABLE);
			}
			Files.writeString(file(), "{\"name\":\"ord", StandardOpenOption.APPEND);

			// Act
			try (TableRegistryService after = TableRegistryService.durable(file())) {
				after.register(ORDERS_TABLE);

				// Assert
				assertThat(after.get(USERS)).isEqualTo(USERS_TABLE);
				assertThat(after.get(ORDERS_TABLE.name())).isEqualTo(ORDERS_TABLE);
			}
			assertThat(Files.readAllLines(file())).hasSize(2);
		}

		@Test
		void damagedDefinitionStopsTheStart() throws IOException {
			// Arrange
			Files.writeString(file(), "not json\n{\"name\":\"users\",\"partitionKey\":\"user_id\"}\n");

			// Act
			Throwable thrown = catchThrowable(() -> TableRegistryService.durable(file()).close());

			// Assert
			assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("damaged at line 1");
		}
	}
}
