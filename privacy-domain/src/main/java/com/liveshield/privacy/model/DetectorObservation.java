package com.liveshield.privacy.model;

import java.util.Objects;
import java.util.OptionalLong;

/** One non-sensitive detector region plus an optional session-local association hint. */
public final class DetectorObservation {
    private final ProtectedRegion region;
    private final Long detectorTrackingId;

    private DetectorObservation(ProtectedRegion region, Long detectorTrackingId) {
        this.region = Objects.requireNonNull(region, "region");
        if (detectorTrackingId != null && detectorTrackingId < 0) {
            throw new IllegalArgumentException("Detector tracking identifier must be non-negative");
        }
        this.detectorTrackingId = detectorTrackingId;
    }

    public static DetectorObservation withoutTrackingHint(ProtectedRegion region) {
        return new DetectorObservation(region, null);
    }

    public static DetectorObservation withTrackingHint(ProtectedRegion region, long trackingId) {
        return new DetectorObservation(region, trackingId);
    }

    public ProtectedRegion region() {
        return region;
    }

    public OptionalLong detectorTrackingId() {
        return detectorTrackingId == null
                ? OptionalLong.empty() : OptionalLong.of(detectorTrackingId);
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (!(candidate instanceof DetectorObservation other)) {
            return false;
        }
        return region.equals(other.region)
                && Objects.equals(detectorTrackingId, other.detectorTrackingId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(region, detectorTrackingId);
    }
}
