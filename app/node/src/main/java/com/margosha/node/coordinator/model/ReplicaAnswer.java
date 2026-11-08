package com.margosha.node.coordinator.model;

import com.margosha.api.model.Attributes;
import com.margosha.api.model.RecordVersion;

/**
 * One replica's answer to a read or exists check. {@code logVersion} is the log position of the record's last write;
 * {@code recordVersion} is that write's vector clock and HLC, missing when the replica never received the record.
 */
public record ReplicaAnswer(long logVersion, boolean exists, Attributes attributes, RecordVersion recordVersion) {

	public static ReplicaAnswer found(long logVersion, RecordVersion recordVersion, Attributes attributes) {
		return new ReplicaAnswer(logVersion, true, attributes, recordVersion);
	}

	public static ReplicaAnswer notFound(long logVersion, RecordVersion recordVersion) {
		return new ReplicaAnswer(logVersion, false, null, recordVersion);
	}
}
