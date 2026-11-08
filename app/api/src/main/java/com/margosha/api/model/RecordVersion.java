package com.margosha.api.model;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The version of one write of a record: a vector clock (a counter per leader that wrote the record), the HLC timestamp
 * of the write, and the replica ID of the leader that wrote it.
 */
public record RecordVersion(Map<String, Long> vector, Hlc hlc, String writer) {

	public RecordVersion {
		vector = Map.copyOf(Objects.requireNonNull(vector, "vector"));
		Objects.requireNonNull(hlc, "hlc");
		Objects.requireNonNull(writer, "writer");
	}

	public static RecordVersion first(String leaderId, Hlc hlc) {
		return new RecordVersion(Map.of(leaderId, 1L), hlc, leaderId);
	}

	public RecordVersion next(String leaderId, Hlc nextHlc) {
		Map<String, Long> nextVector = new TreeMap<>(vector);
		nextVector.merge(leaderId, 1L, Long::sum);
		return new RecordVersion(nextVector, nextHlc, leaderId);
	}

	/** True if this vector is greater than or equal to the other in every entry, and greater in at least one. */
	public boolean dominates(RecordVersion other) {
		boolean greater = false;
		for (Map.Entry<String, Long> entry : vector.entrySet()) {
			long theirs = other.vector.getOrDefault(entry.getKey(), 0L);
			if (entry.getValue() < theirs) {
				return false;
			}
			if (entry.getValue() > theirs) {
				greater = true;
			}
		}
		for (Map.Entry<String, Long> entry : other.vector.entrySet()) {
			if (vector.getOrDefault(entry.getKey(), 0L) < entry.getValue()) {
				return false;
			}
		}
		return greater;
	}
}
