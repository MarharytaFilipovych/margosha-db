package com.margosha.node.coordinator.model;

import com.margosha.api.model.Attributes;

public record ReplicaAnswer(long version, boolean exists, Attributes attributes) {

	public static ReplicaAnswer found(long version, Attributes attributes) {
		return new ReplicaAnswer(version, true, attributes);
	}

	public static ReplicaAnswer notFound(long version) {
		return new ReplicaAnswer(version, false, null);
	}
}
