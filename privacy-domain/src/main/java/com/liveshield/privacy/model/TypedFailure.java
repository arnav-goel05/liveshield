package com.liveshield.privacy.model;

import java.util.Objects;

/** A non-sensitive failure signal with no pixels, recognized text, or arbitrary message. */
public record TypedFailure(Code code, FrameTimestamp occurredAt) {
    public TypedFailure {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public enum Code {
        ANALYZER_ERROR,
        ANALYZER_CANCELLED,
        STALE_RESULT,
        QUEUE_CAPACITY,
        DEADLINE_EXCEEDED,
        RENDERER_ERROR,
        SURFACE_ERROR,
        ENCODER_ERROR,
        THERMAL_UNSAFE,
        LIFECYCLE_INTERRUPTED
    }
}
