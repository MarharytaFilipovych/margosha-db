package com.margosha.api.model;

/**
 * Hybrid logical clock timestamp: {@code l} is the physical time in milliseconds (the larger of the clock and the last
 * {@code l}), and {@code c} counts writes that got the same {@code l}.
 */
public record Hlc(long l, long c) implements Comparable<Hlc> {

	public static final Hlc ZERO = new Hlc(0, 0);

	public Hlc {
		if (l < 0 || c < 0) {
			throw new IllegalArgumentException("l and c must not be negative, were " + l + " and " + c);
		}
	}

	public Hlc next(long physicalTime) {
		long nextL = Math.max(l, physicalTime);
		return nextL == l ? new Hlc(l, c + 1) : new Hlc(nextL, 0);
	}

	@Override
	public int compareTo(Hlc other) {
		int byTime = Long.compare(l, other.l);
		return byTime != 0 ? byTime : Long.compare(c, other.c);
	}
}
