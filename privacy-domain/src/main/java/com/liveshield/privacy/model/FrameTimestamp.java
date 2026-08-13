package com.liveshield.privacy.model;

/** A validated monotonic camera timestamp. */
public record FrameTimestamp(long nanos) implements Comparable<FrameTimestamp> {
    public FrameTimestamp {
        if (nanos < 0) {
            throw new IllegalArgumentException("Timestamp must be non-negative");
        }
    }

    public static FrameTimestamp ofNanos(long nanos) {
        return new FrameTimestamp(nanos);
    }

    public FrameTimestamp plusNanos(long increment) {
        if (increment < 0) {
            throw new IllegalArgumentException("Timestamp increment must be non-negative");
        }
        return new FrameTimestamp(Math.addExact(nanos, increment));
    }

    @Override
    public int compareTo(FrameTimestamp other) {
        return Long.compare(nanos, other.nanos);
    }
}
