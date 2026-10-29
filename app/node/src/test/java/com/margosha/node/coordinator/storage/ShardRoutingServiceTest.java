package com.margosha.node.coordinator.storage;

import static com.margosha.node.data.TestData.ANN_ATTRIBUTES;
import static com.margosha.node.data.TestData.BOB_ATTRIBUTES;
import static com.margosha.node.data.TestData.USERS;
import static com.margosha.node.data.TestData.USER_42;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.margosha.node.storage.RecordStorage;
import com.margosha.api.model.Attributes;
import com.margosha.node.coordinator.exception.ShardUnavailableException;
import com.margosha.node.coordinator.service.ShardRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShardRoutingServiceTest {

	@Mock
	private ShardRegistryService shards;

	@Mock
	private RecordStorage owner;

	private ShardRoutingService storage;

	@BeforeEach
	void setUp() {
		storage = new ShardRoutingService(shards);
	}

	@Test
	void writesGoToTheOwningShard() {
		// Arrange
		when(shards.ownerOf(USER_42)).thenReturn(owner);

		// Act
		storage.create(USERS, USER_42, ANN_ATTRIBUTES);
		storage.update(USERS, USER_42, BOB_ATTRIBUTES);
		storage.delete(USERS, USER_42);

		// Assert
		verify(owner).create(USERS, USER_42, ANN_ATTRIBUTES);
		verify(owner).update(USERS, USER_42, BOB_ATTRIBUTES);
		verify(owner).delete(USERS, USER_42);
	}

	@Test
	void readsComeFromTheOwningShard() {
		// Arrange
		when(shards.ownerOf(USER_42)).thenReturn(owner);
		when(owner.read(USERS, USER_42)).thenReturn(ANN_ATTRIBUTES);
		when(owner.exists(USERS, USER_42)).thenReturn(true);

		// Act
		Attributes read = storage.read(USERS, USER_42);
		boolean exists = storage.exists(USERS, USER_42);

		// Assert
		assertThat(read).isEqualTo(ANN_ATTRIBUTES);
		assertThat(exists).isTrue();
	}

	@Test
	void noOwnerIsPassedOnAsShardUnavailable() {
		// Arrange
		when(shards.ownerOf(USER_42)).thenThrow(ShardUnavailableException.noShardRegistered());

		// Act & Assert
		assertThatThrownBy(() -> storage.create(USERS, USER_42, ANN_ATTRIBUTES))
				.isInstanceOf(ShardUnavailableException.class);
	}
}
