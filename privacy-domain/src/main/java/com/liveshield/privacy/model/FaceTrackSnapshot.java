package com.liveshield.privacy.model;

import java.util.Objects;

/** A session-local, non-biometric face track snapshot. */
public record FaceTrackSnapshot(
        long trackId,
        NormalizedRect bounds,
        FrameTimestamp lastDetected,
        ConfidenceState confidenceState,
        Policy policy) {
    public FaceTrackSnapshot {
        if (trackId < 0) {
            throw new IllegalArgumentException("Track identifier must be non-negative");
        }
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(lastDetected, "lastDetected");
        Objects.requireNonNull(confidenceState, "confidenceState");
        Objects.requireNonNull(policy, "policy");
    }

    public enum ConfidenceState {
        FRESH,
        PREDICTED,
        AMBIGUOUS,
        EXPIRED
    }

    public enum Policy {
        HOST_VISIBLE,
        PROTECTED
    }
}
