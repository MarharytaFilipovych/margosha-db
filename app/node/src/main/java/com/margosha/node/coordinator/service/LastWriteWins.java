package com.margosha.node.coordinator.service;

import java.util.Comparator;

import com.margosha.api.model.RecordVersion;
import com.margosha.node.coordinator.model.ReplicaAnswer;

/**
 * Orders two replicas' copies of a record so that the Last-Write-Wins winner is the greater one:
 * <ol>
 * <li>a copy whose vector clock is greater than or equal in every entry (and not equal) is newer;</li>
 * <li>if the vectors are equal or concurrent, the later HLC wins;</li>
 * <li>if the HLCs are equal too, the higher log position wins, then the higher replica ID of the writer.</li>
 * </ol>
 * A winning delete means the record does not exist. An answer from a replica that never received the record has no
 * record version and loses to any copy.
 */
public final class LastWriteWins implements Comparator<ReplicaAnswer> {

	public static final LastWriteWins INSTANCE = new LastWriteWins();

	private LastWriteWins() { }

	@Override
	public int compare(ReplicaAnswer first, ReplicaAnswer second) {
		RecordVersion a = first.recordVersion();
		RecordVersion b = second.recordVersion();
		if (a == null || b == null) {
			return Boolean.compare(a != null, b != null);
		}
		if (a.dominates(b)) {
			return 1;
		}
		if (b.dominates(a)) {
			return -1;
		}
		int byHlc = a.hlc().compareTo(b.hlc());
		if (byHlc != 0) {
			return byHlc;
		}
		int byLogPosition = Long.compare(first.logVersion(), second.logVersion());
		if (byLogPosition != 0) {
			return byLogPosition;
		}
		return a.writer().compareTo(b.writer());
	}
}
