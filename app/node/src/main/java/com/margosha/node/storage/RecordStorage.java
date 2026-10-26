package com.margosha.node.storage;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.PrimaryKey;

public interface RecordStorage {

	void create(String table, PrimaryKey key, Attributes attributes);

	Attributes read(String table, PrimaryKey key);

	boolean exists(String table, PrimaryKey key);

	void update(String table, PrimaryKey key, Attributes attributes);

	void delete(String table, PrimaryKey key);
}
