package com.margosha.node.ycsb;

import static com.margosha.node.data.TestData.SHARD_A;
import static com.margosha.node.data.TestData.SHARD_B;
import static com.margosha.node.data.TestData.testDataDir;
import static com.margosha.node.data.TestData.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.Vector;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.TableDefinition;
import com.margosha.node.coordinator.service.ShardRegistryService;
import com.margosha.node.data.InProcessNode;
import com.margosha.node.data.InProcessReplicaSet;
import com.margosha.ycsb.MargoshaDbClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.ycsb.ByteIterator;
import site.ycsb.DB;
import site.ycsb.Status;
import site.ycsb.StringByteIterator;

class YcsbBindingRoundTripTest {

	private static final String KEY_ATTRIBUTE = "ycsb_key";
	private static final int RECORD_COUNT = 200;
	private static final int FIELD_COUNT = 10;
	private static final int FIELD_LENGTH = 100;
	private static final Duration REGISTRATION_WAIT = Duration.ofSeconds(15);

	private final String table = unique("usertable");
	private final Random random = new Random(42);
	private InProcessNode coordinator;
	private ShardRegistryService shardRegistry;
	private InProcessReplicaSet shardA;
	private InProcessReplicaSet shardB;
	private DB db;

	@BeforeEach
	void setUp() throws Exception {
		coordinator = InProcessNode.replicatedCoordinator(0, testDataDir("coordinator"));
		shardRegistry = coordinator.shardRegistry();
		shardA = InProcessReplicaSet.start(SHARD_A, coordinator.address());
		shardB = InProcessReplicaSet.start(SHARD_B, coordinator.address());
		await().atMost(REGISTRATION_WAIT).until(() -> Stream.of(SHARD_A, SHARD_B).allMatch(shardId ->
				shardRegistry.replicaSet(shardId).map(set -> set.replicas().size() == 3).orElse(false)));
		coordinator.client().registerTable(TableDefinition.of(table, KEY_ATTRIBUTE));

		Properties properties = new Properties();
		properties.setProperty(MargoshaDbClient.URL_PROPERTY, coordinator.address());
		properties.setProperty(MargoshaDbClient.PARTITION_KEY_PROPERTY, KEY_ATTRIBUTE);
		db = new MargoshaDbClient();
		db.setProperties(properties);
		db.init();
	}

	@AfterEach
	void tearDown() throws Exception {
		db.cleanup();
		shardB.close();
		shardA.close();
		coordinator.close();
	}

	@Test
	void everyInsertedRecordReadsBackWithTheSameFields() {
		// Arrange
		Map<String, Map<String, String>> records = new LinkedHashMap<>();
		IntStream.range(0, RECORD_COUNT).forEach(i -> records.put("user" + random.nextLong(Long.MAX_VALUE), fields()));

		// Act
		List<Status> inserts = records.entrySet().stream()
				.map(record -> db.insert(table, record.getKey(), asByteIterators(record.getValue())))
				.toList();

		// Assert
		assertThat(inserts).containsOnly(Status.OK);
		records.forEach((key, fields) -> {
			Map<String, ByteIterator> result = new HashMap<>();
			assertThat(db.read(table, key, null, result)).isEqualTo(Status.OK);
			assertThat(StringByteIterator.getStringMap(result)).isEqualTo(fields);
		});
		Set<String> owners = new HashSet<>();
		records.keySet().forEach(key -> owners.add(shardRegistry.shardIdFor(PrimaryKey.of(key))));
		assertThat(owners).as("the records are spread over both shards").containsExactlyInAnyOrder(SHARD_A, SHARD_B);
	}

	@Test
	void readReturnsOnlyTheRequestedFields() {
		// Arrange
		Map<String, String> fields = fields();
		db.insert(table, "user1", asByteIterators(fields));

		// Act
		Map<String, ByteIterator> result = new HashMap<>();
		Status status = db.read(table, "user1", Set.of("field3", "field7"), result);

		// Assert
		assertThat(status).isEqualTo(Status.OK);
		assertThat(StringByteIterator.getStringMap(result))
				.isEqualTo(Map.of("field3", fields.get("field3"), "field7", fields.get("field7")));
	}

	@Test
	void updateReplacesTheRecordsFields() {
		// Arrange
		db.insert(table, "user2", asByteIterators(fields()));

		// Act
		Status status = db.update(table, "user2", asByteIterators(Map.of("field0", "new value")));

		// Assert
		assertThat(status).isEqualTo(Status.OK);
		Map<String, ByteIterator> result = new HashMap<>();
		db.read(table, "user2", null, result);
		assertThat(StringByteIterator.getStringMap(result)).isEqualTo(Map.of("field0", "new value"));
	}

	@Test
	void deletedRecordIsNotFound() {
		// Arrange
		db.insert(table, "user3", asByteIterators(fields()));

		// Act
		Status status = db.delete(table, "user3");

		// Assert
		assertThat(status).isEqualTo(Status.OK);
		assertThat(db.read(table, "user3", null, new HashMap<>())).isEqualTo(Status.NOT_FOUND);
	}

	@Test
	void failedOperationsReturnAnErrorStatus() {
		// Arrange
		db.insert(table, "user4", asByteIterators(fields()));

		// Act / Assert
		assertThat(db.insert(table, "user4", asByteIterators(fields()))).isEqualTo(Status.ERROR);
		assertThat(db.update(table, "missing", asByteIterators(fields()))).isEqualTo(Status.NOT_FOUND);
		assertThat(db.delete(table, "missing")).isEqualTo(Status.NOT_FOUND);
		assertThat(db.read("no-such-table", "user4", null, new HashMap<>())).isEqualTo(Status.NOT_FOUND);
	}

	@Test
	void scanIsNotImplemented() {
		// Act
		Status status = db.scan(table, "user1", 10, null, new Vector<>());

		// Assert
		assertThat(status).isEqualTo(Status.NOT_IMPLEMENTED);
	}

	private Map<String, String> fields() {
		Map<String, String> fields = new HashMap<>();
		for (int i = 0; i < FIELD_COUNT; i++) {
			StringBuilder value = new StringBuilder(FIELD_LENGTH);
			for (int c = 0; c < FIELD_LENGTH; c++) {
				value.append((char) (' ' + random.nextInt(95)));
			}
			fields.put("field" + i, value.toString());
		}
		return fields;
	}

	private static Map<String, ByteIterator> asByteIterators(Map<String, String> values) {
		Map<String, ByteIterator> result = new HashMap<>();
		StringByteIterator.putAllAsByteIterators(result, values);
		return result;
	}
}
