package com.margosha.node.storage;

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
import com.margosha.api.model.PrimaryKey;
import com.margosha.node.exception.RecordAlreadyExistsException;
import com.margosha.node.exception.RecordNotFoundException;
import org.junit.jupiter.api.Test;

class InMemoryRecordStoreTest {

	private static final String USERS = "users";
	private static final String ORDERS = "orders";

	private static final PrimaryKey USER_42 = PrimaryKey.of("42");
	private static final PrimaryKey USER_7 = PrimaryKey.of("7");
	private static final PrimaryKey ORDER_42_1 = new PrimaryKey("42", "order-1");
	private static final PrimaryKey ORDER_42_2 = new PrimaryKey("42", "order-2");

	private static final Attributes ANN_IN_KYIV = Attributes.of(Map.of("name", "Ann", "city", "Kyiv"));
	private static final Attributes BOB = Attributes.of(Map.of("name", "Bob"));

	private final InMemoryRecordStore store = new InMemoryRecordStore();

	@Test
	void createdRecordCanBeRead() {
		// Act
		store.create(USERS, USER_42, ANN_IN_KYIV);

		// Assert
		assertThat(store.read(USERS, USER_42)).isEqualTo(ANN_IN_KYIV);
	}

	@Test
	void createOfExistingKeyFailsAndKeepsTheRecord() {
		// Arrange
		store.create(USERS, USER_42, ANN_IN_KYIV);

		// Act & Assert
		assertThatThrownBy(() -> store.create(USERS, USER_42, BOB))
				.isInstanceOf(RecordAlreadyExistsException.class)
				.hasMessageContaining(USERS);

		// Assert
		assertThat(store.read(USERS, USER_42)).isEqualTo(ANN_IN_KYIV);
	}

	@Test
	void readOfMissingKeyFails() {
		// Act & Assert
		assertThatThrownBy(() -> store.read(USERS, USER_42)).isInstanceOf(RecordNotFoundException.class);
	}

	@Test
	void existsIsTrueForPresentAndFalseForAbsentKey() {
		// Arrange
		store.create(USERS, USER_42, ANN_IN_KYIV);

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
		store.create(USERS, USER_42, ANN_IN_KYIV);

		// Act
		store.update(USERS, USER_42, BOB);

		// Assert
		// kv-api: "a later READ returns only name=Bob" - city is gone
		assertThat(store.read(USERS, USER_42)).isEqualTo(BOB);
	}

	@Test
	void updateOfMissingKeyFailsAndCreatesNothing() {
		// Act & Assert
		assertThatThrownBy(() -> store.update(USERS, USER_42, BOB)).isInstanceOf(RecordNotFoundException.class);

		// Assert
		assertThat(store.exists(USERS, USER_42)).isFalse();
	}

	@Test
	void deletedRecordNoLongerExists() {
		// Arrange
		store.create(USERS, USER_42, ANN_IN_KYIV);

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
	void keyCanBeCreatedAgainAfterDelete() {
		// Arrange
		store.create(USERS, USER_42, ANN_IN_KYIV);
		store.delete(USERS, USER_42);

		// Act
		store.create(USERS, USER_42, BOB);

		// Assert
		assertThat(store.read(USERS, USER_42)).isEqualTo(BOB);
	}

	@Test
	void sortKeyMakesRecordsOfOnePartitionDistinct() {
		// Act
		store.create(ORDERS, ORDER_42_1, ANN_IN_KYIV);
		store.create(ORDERS, ORDER_42_2, BOB);

		// Assert
		assertThat(store.read(ORDERS, ORDER_42_1)).isEqualTo(ANN_IN_KYIV);
		assertThat(store.read(ORDERS, ORDER_42_2)).isEqualTo(BOB);
	}

	@Test
	void tablesAreSeparate() {
		// Arrange
		store.create(USERS, USER_42, ANN_IN_KYIV);

		// Act
		boolean existsInOtherTable = store.exists("customers", USER_42);
		store.create("customers", USER_42, BOB);

		// Assert
		assertThat(existsInOtherTable).isFalse();
		assertThat(store.read(USERS, USER_42)).isEqualTo(ANN_IN_KYIV);
		assertThat(store.read("customers", USER_42)).isEqualTo(BOB);
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
		assertThatThrownBy(() -> store.create(null, USER_42, BOB)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> store.read(USERS, null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> store.create(USERS, USER_42, null)).isInstanceOf(NullPointerException.class);
	}
}
