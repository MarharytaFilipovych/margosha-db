package com.margosha.node.coordinator.storage;

import com.margosha.node.storage.RecordStorage;
import com.margosha.api.model.Attributes;
import com.margosha.api.model.PrimaryKey;
import com.margosha.node.coordinator.service.ShardRegistryService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ShardRoutingService implements RecordStorage {

	@NonNull
	private final ShardRegistryService shardRegistryService;

	@Override
	public void create(String table, PrimaryKey key, Attributes attributes) {
		shardRegistryService.ownerOf(key).create(table, key, attributes);
	}

	@Override
	public Attributes read(String table, PrimaryKey key) {
		return shardRegistryService.ownerOf(key).read(table, key);
	}

	@Override
	public boolean exists(String table, PrimaryKey key) {
		return shardRegistryService.ownerOf(key).exists(table, key);
	}

	@Override
	public void update(String table, PrimaryKey key, Attributes attributes) {
		shardRegistryService.ownerOf(key).update(table, key, attributes);
	}

	@Override
	public void delete(String table, PrimaryKey key) {
		shardRegistryService.ownerOf(key).delete(table, key);
	}
}
