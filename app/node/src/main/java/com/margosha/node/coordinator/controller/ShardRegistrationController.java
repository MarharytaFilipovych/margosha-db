package com.margosha.node.coordinator.controller;

import static com.margosha.api.util.PathConstants.SHARDS;

import com.margosha.api.model.ShardRegistration;
import com.margosha.node.coordinator.service.ShardRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("coordinator")
@ConditionalOnProperty(name = "margosha.mode", havingValue = "SHARDED")
@RequiredArgsConstructor
public class ShardRegistrationController {

	private final ShardRegistryService shards;

	@PostMapping(SHARDS)
	public ShardRegistration registerShard(@RequestBody ShardRegistration shard) {
		return shards.register(shard);
	}
}
