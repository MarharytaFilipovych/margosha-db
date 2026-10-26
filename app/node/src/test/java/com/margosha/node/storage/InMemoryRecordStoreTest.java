package com.margosha.node.storage;

import static com.margosha.node.data.TestData.ANN_ATTRIBUTES;
import static com.margosha.node.data.TestData.BOB_ATTRIBUTES;
import static com.margosha.node.data.TestData.ORDERS;
import static com.margosha.node.data.TestData.ORDER_42_1;
import static com.margosha.node.data.TestData.ORDER_42_2;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static com.margosha.node.data.TestData.USER_7;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.margosha.api.model.Attributes;
import com.margosha.node.exception.RecordAlreadyExistsException;
import com.margosha.node.exception.RecordNotFoundException;
import org.junit.jupiter.api.Test;

class InMemoryRecordStoreTest {

	private static final String CUSTOMERS = "customers";

	private final InMemoryRecordStore store = new InMemoryRecordStore();

	@Test
	void createdRecordCanBeRead() {
		// Act
		store.create(USERS, USER_42, ANN_ATTRIBUTES);

		// Assert
		assertThat(store.read(USERS, USER_42)).isEqualTo(ANN_ATTRIBUTES);
	}

	@Test
	void createOfExistingKeyFailsAndKeepsTheRecord() {
		// Arrange
		store.create(USERS, USER_42, ANN_ATTRIBUTES);

		// Act & Assert
		assertThatThrownBy(() -> store.create(USERS, USER_42, BOB_ATTRIBUTES))
				.isInstanceOf(RecordAlreadyExistsException.class)
				.hasMessageContaining(USERS);

		// Assert
		assertThat(store.read(USERS, USER_42)).isEqualTo(ANN_ATTRIBUTES);
	}

	@Test
	void readOfMissingKeyFails() {
		// Act & Assert
		assertThatThrownBy(() -> store.read(USERS, USER_42)).isInstanceOf(RecordNotFoundException.class);
	}

	@Test
	void existsIsTrueForPresentAndFalseForAbsentKey() {
		// Arrange
		store.create(USERS, USER_42, ANN_ATTRIBUTES);

		// Act
		boolean present = store.exists(USERS, USER_42);
		boolean absent = store.exists(USERS, USER_7);

		// Assert
		assertThat(present).isTrue();
		assertThat(absent).isFalse();
	}

	@Test
	void existsOnTableWithoutRecordsIsFalse() {
		// Act
		boolean exists = store.exists(ORDERS, ORDER_42_1);

		// Assert
		assertThat(exists).isFalse();
	}

	@Test
	void updateReplacesAllAttributes() {
		// Arrange
		store.create(USERS, USER_42, ANN_ATTRIBUTES);

		// Act
		store.update(USERS, USER_42, BOB_ATTRIBUTES);

		// Assert
		// kv-api: "a later READ returns only name=Bob" - city is gone
		assertThat(store.read(USERS, USER_42)).isEqualTo(BOB_ATTRIBUTES);
	}

	@Test
	void updateOfMissingKeyFailsAndCreatesNothing() {
		// Act & Assert
		assertThatThrownBy(() -> store.update(USERS, USER_42, BOB_ATTRIBUTES)).isInstanceOf(RecordNotFoundException.class);

		// Assert
		assertThat(store.exists(USERS, USER_42)).isFalse();
	}

	@Test
	void updateOfMissingKeyInExistingTableFails() {
		// Arrange
		store.create(USERS, USER_42, ANN_ATTRIBUTES);

		// Act & Assert
		assertThatThrownBy(() -> store.update(USERS, USER_7, BOB_ATTRIBUTES)).isInstanceOf(RecordNotFoundException.class);

		// Assert
		assertThat(store.exists(USERS, USER_7)).isFalse();
	}

	@Test
	void deletedRecordNoLongerExists() {
		// Arrange
		store.create(USERS, USER_42, ANN_ATTRIBUTES);

		// Act
		store.delete(USERS, USER_42);

		// Assert
		assertThat(store.exists(USERS, USER_42)).isFalse();
		assertThatThrownBy(() -> store.read(USERS, USER_42)).isInstanceOf(RecordNotFoundException.class);
	}

	@Test
	void deleteOfMissingKeyFails() {
		// Act & Assert
		assertThatThrownBy(() -> store.delete(USERS, USER_42)).isInstanceOf(RecordNotFoundException.class);
	}

	@Test
	void deleteOfMissingKeyInExistingTableFails() {
		// Arrange
		store.create(USERS, USER_42, ANN_ATTRIBUTES);

		// Act & Assert
		assertThatThrownBy(() -> store.delete(USERS, USER_7)).isInstanceOf(RecordNotFoundException.class);

		// Assert
		assertThat(store.exists(USERS, USER_42)).isTrue();
	}

	@Test
	void keyCanBeCreatedAgainAfterDelete() {
		// Arrange
		store.create(USERS, USER_42, ANN_ATTRIBUTES);
		store.delete(USERS, USER_42);

		// Act
		store.create(USERS, USER_42, BOB_ATTRIBUTES);

		// Assert
		assertThat(store.read(USERS, USER_42)).isEqualTo(BOB_ATTRIBUTES);
	}

	@Test
	void sortKeyMakesRecordsOfOnePartitionDistinct() {
		// Act
		store.create(ORDERS, ORDER_42_1, ANN_ATTRIBUTES);
		store.create(ORDERS, ORDER_42_2, BOB_ATTRIBUTES);

		// Assert
		assertThat(store.read(ORDERS, ORDER_42_1)).isEqualTo(ANN_ATTRIBUTES);
		assertThat(store.read(ORDERS, ORDER_42_2)).isEqualTo(BOB_ATTRIBUTES);
	}

	@Test
	void tablesAreSeparate() {
		// Arrange
		store.create(USERS, USER_42, ANN_ATTRIBUTES);

		// Act
		boolean existsInOtherTable = store.exists(CUSTOMERS, USER_42);
		store.create(CUSTOMERS, USER_42, BOB_ATTRIBUTES);

		// Assert
		assertThat(existsInOtherTable).isFalse();
		assertThat(store.read(USERS, USER_42)).isEqualTo(ANN_ATTRIBUTES);
		assertThat(store.read(CUSTOMERS, USER_42)).isEqualTo(BOB_ATTRIBUTES);
	}

	@Test
	void concurrentCreatesOfSameKeyLetExactlyOneWin() throws Exception {
		// Arrange
		int threads = 16;
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				Attributes attributes = Attributes.of(Map.of("writer", String.valueOf(i)));
				results.add(executor.submit(() -> {
					if (!start.await(10, TimeUnit.SECONDS)) {
						throw new IllegalStateException("start signal not given");
					}
					try {
						store.create(USERS, USER_42, attributes);
						return true;
					} catch (RecordAlreadyExistsException e) {
						return false;
					}
				}));
			}

			// Act
			start.countDown();
			int created = 0;
			for (Future<Boolean> result : results) {
				if (result.get()) {
					created++;
				}
			}

			// Assert
			assertThat(created).isEqualTo(1);
		}
	}

	@Test
	void tableAndKeyAreRequired() {
		// Act & Assert
		assertThatThrownBy(() -> store.create(null, USER_42, BOB_ATTRIBUTES)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> store.read(USERS, null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> store.create(USERS, USER_42, null)).isInstanceOf(NullPointerException.class);
	}
}
