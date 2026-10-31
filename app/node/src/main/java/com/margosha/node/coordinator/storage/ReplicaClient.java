package com.margosha.node.coordinator.storage;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.PrimaryKey;
import com.margosha.api.model.ShardRegistration;
import com.margosha.node.coordinator.model.ReplicaAnswer;

public interface ReplicaClient {

	ShardRegistration registration();

	void create(String table, PrimaryKey key, Attributes attributes);

	void update(String table, PrimaryKey key, Attributes attributes);

	void delete(String table, PrimaryKey key);

	ReplicaAnswer read(String table, PrimaryKey key);

	ReplicaAnswer exists(String table, PrimaryKey key);
}
