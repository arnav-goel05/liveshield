package com.liveshield.video.output;

import java.util.Arrays;
import java.util.Objects;

/** Immutable copied H.264 output that can only originate after sanitized rendering. */
public final class SanitizedH264AccessUnit {
    private final byte[] payload;
    private final long presentationTimeUs;
    private final int codecFlags;

    SanitizedH264AccessUnit(byte[] payload, long presentationTimeUs, int codecFlags) {
        this.payload = Objects.requireNonNull(payload, "payload").clone();
        if (presentationTimeUs < 0) {
            throw new IllegalArgumentException("presentationTimeUs must be non-negative");
        }
        this.presentationTimeUs = presentationTimeUs;
        this.codecFlags = codecFlags;
    }

    public byte[] payload() {
        return payload.clone();
    }

    public int size() {
        return payload.length;
    }

    public long presentationTimeUs() {
        return presentationTimeUs;
    }

    public int codecFlags() {
        return codecFlags;
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (!(candidate instanceof SanitizedH264AccessUnit other)) {
            return false;
        }
        return presentationTimeUs == other.presentationTimeUs
                && codecFlags == other.codecFlags
                && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(payload), presentationTimeUs, codecFlags);
    }
}
