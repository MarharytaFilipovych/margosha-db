package com.margosha.node.data;

import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.margosha.api.client.MargoshaDbClient;
import com.margosha.api.exception.ApiException;
import com.margosha.api.model.Attributes;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.TableDefinition;
import com.margosha.api.model.TableRecord;
import org.junit.jupiter.api.function.Executable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

public final class TestData {

	public static final String USERS = "users";
	public static final String ORDERS = "orders";
	public static final String USER_ID = "user_id";
	public static final String CUSTOMER_ID = "customer_id";
	public static final String ORDER_ID = "order_id";

	public static final TableDefinition USERS_TABLE = usersTable(USERS);
	public static final TableDefinition ORDERS_TABLE = ordersTable(ORDERS);

	public static final PrimaryKey USER_42 = PrimaryKey.of("42");
	public static final PrimaryKey USER_7 = PrimaryKey.of("7");
	public static final PrimaryKey ORDER_42_1 = new PrimaryKey("42", "order-1");
	public static final PrimaryKey ORDER_42_2 = new PrimaryKey("42", "order-2");

	public static final Attributes ANN_ATTRIBUTES = Attributes.of(Map.of("name", "Ann", "city", "Kyiv"));
	public static final Attributes BOB_ATTRIBUTES = Attributes.of(Map.of("name", "Bob"));
	public static final Attributes TOTAL_10 = Attributes.of(Map.of("total", "10"));
	public static final Attributes TOTAL_20 = Attributes.of(Map.of("total", "20"));

	public static final TableRecord ANN = TableRecord.of(Map.of(USER_ID, "42", "name", "Ann", "city", "Kyiv"));
	public static final TableRecord BOB = TableRecord.of(Map.of(USER_ID, "42", "name", "Bob"));
	public static final TableRecord ORDER_1 = order(ORDER_42_1, TOTAL_10);
	public static final TableRecord ORDER_2 = order(ORDER_42_2, TOTAL_20);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private TestData() { }

	public static TableDefinition usersTable(String name) {
		return TableDefinition.of(name, USER_ID);
	}

	public static TableDefinition ordersTable(String name) {
		return new TableDefinition(name, CUSTOMER_ID, ORDER_ID);
	}

	public static TableRecord order(PrimaryKey key, Attributes attributes) {
		Map<String, String> values = new HashMap<>(attributes.values());
		values.put(CUSTOMER_ID, key.partitionKey());
		values.put(ORDER_ID, key.sortKey());
		return TableRecord.of(values);
	}

	public static String unique(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

	public static MargoshaDbClient client(int port) {
		return new MargoshaDbClient(RestClient.builder(), "http://localhost:" + port);
	}

	public static ApiException apiError(Executable call) {
		return catchThrowableOfType(ApiException.class, call::execute);
	}

	public static String json(Object value) {
		return JSON.writeValueAsString(value);
	}
}
