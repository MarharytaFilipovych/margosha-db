package com.margosha.node.coordinator.service;

import static com.margosha.node.data.TestData.ANN;
import static com.margosha.node.data.TestData.ANN_ATTRIBUTES;
import static com.margosha.node.data.TestData.BOB;
import static com.margosha.node.data.TestData.BOB_ATTRIBUTES;
import static com.margosha.node.data.TestData.CUSTOMER_ID;
import static com.margosha.node.data.TestData.ORDERS;
import static com.margosha.node.data.TestData.ORDERS_TABLE;
import static com.margosha.node.data.TestData.ORDER_1;
import static com.margosha.node.data.TestData.ORDER_42_1;
import static com.margosha.node.data.TestData.ORDER_ID;
import static com.margosha.node.data.TestData.TOTAL_10;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USERS_TABLE;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.TableRecord;
import com.margosha.node.exception.RecordAlreadyExistsException;
import com.margosha.node.exception.RecordNotFoundException;
import com.margosha.node.coordinator.exception.TableNotFoundException;
import com.margosha.node.exception.ValidationException;
import com.margosha.node.storage.RecordStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecordServiceTest {

	@Mock
	private RecordStorage storage;

	private RecordService service;

	@BeforeEach
	void setUp() {
		TableRegistryService tables = new TableRegistryService();
		tables.register(USERS_TABLE);
		tables.register(ORDERS_TABLE);
		service = new RecordService(tables, storage);
	}

	@Nested
	class Create {

		@Test
		void storesKeyAndAttributesSeparately() {
			// Act
			TableRecord created = service.create(USERS, ANN);

			// Assert
			verify(storage).create(USERS, USER_42, ANN_ATTRIBUTES);
			assertThat(created).isEqualTo(ANN);
		}

		@Test
		void storesRecordWithSortKey() {
			// Act
			service.create(ORDERS, ORDER_1);

			// Assert
			verify(storage).create(ORDERS, ORDER_42_1, TOTAL_10);
		}

		@Test
		void missingPartitionKeyIsRejected() {
			// Arrange
			TableRecord withoutKey = TableRecord.of(Map.of("name", "Ann"));

			// Act & Assert
			assertThatThrownBy(() -> service.create(USERS, withoutKey))
					.isInstanceOf(ValidationException.class)
					.hasMessage("Missing value for key attribute " + USER_ID);
			verifyNoInteractions(storage);
		}

		@Test
		void blankPartitionKeyIsRejected() {
			// Arrange
			TableRecord blankKey = TableRecord.of(Map.of(USER_ID, " ", "name", "Ann"));

			// Act & Assert
			assertThatThrownBy(() -> service.create(USERS, blankKey)).isInstanceOf(ValidationException.class);
			verifyNoInteractions(storage);
		}

		@Test
		void missingSortKeyIsRejected() {
			// Arrange
			TableRecord withoutSortKey = TableRecord.of(Map.of(CUSTOMER_ID, "42", "total", "10"));

			// Act & Assert
			assertThatThrownBy(() -> service.create(ORDERS, withoutSortKey))
					.isInstanceOf(ValidationException.class)
					.hasMessage("Missing value for key attribute " + ORDER_ID);
			verifyNoInteractions(storage);
		}

		@Test
		void unknownTableIsRejected() {
			// Act & Assert
			assertThatThrownBy(() -> service.create("ghost", ANN)).isInstanceOf(TableNotFoundException.class);
			verifyNoInteractions(storage);
		}

		@Test
		void conflictFromStorageIsPassedOn() {
			// Arrange
			doThrow(new RecordAlreadyExistsException(USERS, USER_42)).when(storage).create(USERS, USER_42, ANN_ATTRIBUTES);

			// Act & Assert
			assertThatThrownBy(() -> service.create(USERS, ANN)).isInstanceOf(RecordAlreadyExistsException.class);
		}
	}

	@Nested
	class Read {

		@Test
		void addsKeyAttributesToStoredAttributes() {
			// Arrange
			when(storage.read(USERS, USER_42)).thenReturn(ANN_ATTRIBUTES);

			// Act
			TableRecord record = service.read(USERS, "42", null);

			// Assert
			assertThat(record).isEqualTo(ANN);
		}

		@Test
		void addsPartitionAndSortKeyForTableWithSortKey() {
			// Arrange
			when(storage.read(ORDERS, ORDER_42_1)).thenReturn(TOTAL_10);

			// Act
			TableRecord record = service.read(ORDERS, "42", "order-1");

			// Assert
			assertThat(record).isEqualTo(ORDER_1);
		}

		@Test
		void emptySortKeyParameterIsIgnoredForTableWithoutSortKey() {
			// Arrange
			when(storage.read(USERS, USER_42)).thenReturn(ANN_ATTRIBUTES);

			// Act
			TableRecord record = service.read(USERS, "42", "");

			// Assert
			assertThat(record).isEqualTo(ANN);
		}

		@Test
		void sortKeyIsRequiredForTableWithSortKey() {
			// Act & Assert
			assertThatThrownBy(() -> service.read(ORDERS, "42", null))
					.isInstanceOf(ValidationException.class)
					.hasMessage("Missing value for key attribute " + ORDER_ID);
			verifyNoInteractions(storage);
		}

		@Test
		void sortKeyIsRejectedForTableWithoutSortKey() {
			// Act & Assert
			assertThatThrownBy(() -> service.read(USERS, "42", "order-1"))
					.isInstanceOf(ValidationException.class)
					.hasMessage("Table users has no sort key");
			verifyNoInteractions(storage);
		}

		@Test
		void emptyPartitionKeyIsRejected() {
			// Act & Assert
			assertThatThrownBy(() -> service.read(USERS, "", null)).isInstanceOf(ValidationException.class);
			verifyNoInteractions(storage);
		}

		@Test
		void notFoundFromStorageIsPassedOn() {
			// Arrange
			when(storage.read(USERS, USER_42)).thenThrow(new RecordNotFoundException(USERS, USER_42));

			// Act & Assert
			assertThatThrownBy(() -> service.read(USERS, "42", null)).isInstanceOf(RecordNotFoundException.class);
		}
	}

	@Nested
	class Exists {

		@Test
		void returnsWhatStorageAnswers() {
			// Arrange
			when(storage.exists(USERS, USER_42)).thenReturn(true);
			when(storage.exists(ORDERS, ORDER_42_1)).thenReturn(false);

			// Act
			boolean user = service.exists(USERS, "42", null);
			boolean order = service.exists(ORDERS, "42", "order-1");

			// Assert
			assertThat(user).isTrue();
			assertThat(order).isFalse();
		}

		@Test
		void invalidKeyIsRejected() {
			// Act & Assert
			assertThatThrownBy(() -> service.exists(ORDERS, "42", null)).isInstanceOf(ValidationException.class);
			verifyNoInteractions(storage);
		}
	}

	@Nested
	class Update {

		@Test
		void replacesAttributesAndReturnsWholeRecord() {
			// Act
			TableRecord updated = service.update(USERS, "42", null, BOB_ATTRIBUTES);

			// Assert
			verify(storage).update(USERS, USER_42, BOB_ATTRIBUTES);
			assertThat(updated).isEqualTo(BOB);
		}

		@Test
		void updatesRecordWithSortKey() {
			// Act
			TableRecord updated = service.update(ORDERS, "42", "order-1", TOTAL_10);

			// Assert
			verify(storage).update(ORDERS, ORDER_42_1, TOTAL_10);
			assertThat(updated).isEqualTo(ORDER_1);
		}

		@Test
		void partitionKeyAttributeInBodyIsRejected() {
			// Arrange
			Attributes changesKey = Attributes.of(Map.of(USER_ID, "43"));

			// Act & Assert
			assertThatThrownBy(() -> service.update(USERS, "42", null, changesKey))
					.isInstanceOf(ValidationException.class)
					.hasMessageContaining(USER_ID);
			verifyNoInteractions(storage);
		}

		@Test
		void sortKeyAttributeInBodyIsRejected() {
			// Arrange
			Attributes changesSortKey = Attributes.of(Map.of(ORDER_ID, "order-9"));

			// Act & Assert
			assertThatThrownBy(() -> service.update(ORDERS, "42", "order-1", changesSortKey))
					.isInstanceOf(ValidationException.class)
					.hasMessageContaining(ORDER_ID);
			verifyNoInteractions(storage);
		}

		@Test
		void notFoundFromStorageIsPassedOn() {
			// Arrange
			doThrow(new RecordNotFoundException(USERS, USER_42)).when(storage).update(USERS, USER_42, BOB_ATTRIBUTES);

			// Act & Assert
			assertThatThrownBy(() -> service.update(USERS, "42", null, BOB_ATTRIBUTES))
					.isInstanceOf(RecordNotFoundException.class);
		}
	}

	@Nested
	class Delete {

		@Test
		void deletesRecord() {
			// Act
			service.delete(USERS, "42", null);
			service.delete(ORDERS, "42", "order-1");

			// Assert
			verify(storage).delete(USERS, USER_42);
			verify(storage).delete(ORDERS, ORDER_42_1);
		}

		@Test
		void invalidKeyIsRejected() {
			// Act & Assert
			assertThatThrownBy(() -> service.delete(USERS, "42", "order-1")).isInstanceOf(ValidationException.class);
			verifyNoInteractions(storage);
		}

		@Test
		void unknownTableIsRejected() {
			// Act & Assert
			assertThatThrownBy(() -> service.delete("ghost", "42", null)).isInstanceOf(TableNotFoundException.class);
			verifyNoInteractions(storage);
		}
	}
}
