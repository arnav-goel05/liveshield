package com.liveshield.privacy.decision;

import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.ProtectedRegion;
import java.util.List;
import java.util.Objects;

/** The immutable, explicit privacy decision for one exact output-frame timestamp. */
public final class FramePrivacyDecision {
    private final FrameTimestamp timestamp;
    private final Status status;
    private final List<ProtectedRegion> regions;
    private final Basis basis;
    private final FrameTimestamp expiresAt;

    /** Constructs the unconditional fail-private default for a frame. */
    public FramePrivacyDecision(FrameTimestamp timestamp) {
        this(timestamp, Status.FULL_SHIELD, List.of(), Basis.MISSING, timestamp);
    }

    private FramePrivacyDecision(
            FrameTimestamp timestamp,
            Status status,
            List<ProtectedRegion> regions,
            Basis basis,
            FrameTimestamp expiresAt) {
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.status = Objects.requireNonNull(status, "status");
        this.regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
        this.basis = Objects.requireNonNull(basis, "basis");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (expiresAt.compareTo(timestamp) < 0) {
            throw new IllegalArgumentException("expiresAt must not precede the frame timestamp");
        }
        if (status == Status.FULL_SHIELD && !this.regions.isEmpty()) {
            throw new IllegalArgumentException("A full-shield decision cannot retain regions");
        }
    }

    public static FramePrivacyDecision fullShield(FrameTimestamp timestamp, Basis basis) {
        return new FramePrivacyDecision(
                timestamp, Status.FULL_SHIELD, List.of(), basis, timestamp);
    }

    public static FramePrivacyDecision regionalSafe(
            FrameTimestamp timestamp,
            List<ProtectedRegion> regions,
            Basis basis,
            FrameTimestamp expiresAt) {
        return new FramePrivacyDecision(
                timestamp, Status.REGIONAL_SAFE, regions, basis, expiresAt);
    }

    public FrameTimestamp timestamp() {
        return timestamp;
    }

    public Status status() {
        return status;
    }

    public List<ProtectedRegion> regions() {
        return regions;
    }

    public Basis basis() {
        return basis;
    }

    public FrameTimestamp expiresAt() {
        return expiresAt;
    }

    public boolean isExpiredAt(FrameTimestamp now) {
        return status == Status.REGIONAL_SAFE && now.compareTo(expiresAt) > 0;
    }

    public enum Status {
        REGIONAL_SAFE,
        FULL_SHIELD
    }

    public enum Basis {
        FRESH,
        CARRIED,
        EXPANDED,
        TIMEOUT,
        ERROR,
        MISSING,
        FUTURE,
        STALE,
        EXPIRED,
        EVICTED
    }
}
