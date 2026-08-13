package com.liveshield.privacy.host;

import java.util.OptionalLong;

/** A non-biometric host-selection result. */
public record HostSelectionResult(Status status, OptionalLong selectedTrackId) {
    public HostSelectionResult {
        if (status == null || selectedTrackId == null) {
            throw new NullPointerException("Selection result fields must not be null");
        }
        if ((status == Status.SELECTED) != selectedTrackId.isPresent()) {
            throw new IllegalArgumentException("Only SELECTED may contain a track identifier");
        }
    }

    public enum Status {
        SELECTED,
        NO_FACE_AT_TAP,
        STALE_FACE,
        AMBIGUOUS
    }
}
