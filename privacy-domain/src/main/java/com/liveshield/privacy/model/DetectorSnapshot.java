package com.liveshield.privacy.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable findings or a typed failure keyed to exactly one analysis timestamp. */
public final class DetectorSnapshot {
    private final DetectorLane lane;
    private final FrameTimestamp sourceTimestamp;
    private final FrameTimestamp validUntil;
    private final List<DetectorObservation> observations;
    private final List<ProtectedRegion> findings;
    private final TypedFailure failure;

    private DetectorSnapshot(
            DetectorLane lane,
            FrameTimestamp sourceTimestamp,
            FrameTimestamp validUntil,
            List<DetectorObservation> observations,
            TypedFailure failure) {
        this.lane = Objects.requireNonNull(lane, "lane");
        this.sourceTimestamp = Objects.requireNonNull(sourceTimestamp, "sourceTimestamp");
        this.validUntil = Objects.requireNonNull(validUntil, "validUntil");
        if (validUntil.compareTo(sourceTimestamp) < 0) {
            throw new IllegalArgumentException("validUntil must not precede sourceTimestamp");
        }
        this.observations = List.copyOf(Objects.requireNonNull(observations, "observations"));
        this.findings = this.observations.stream().map(DetectorObservation::region).toList();
        this.failure = failure;
        if (failure != null && !this.observations.isEmpty()) {
            throw new IllegalArgumentException("A failed snapshot cannot contain findings");
        }
    }

    public static DetectorSnapshot success(
            DetectorLane lane,
            FrameTimestamp sourceTimestamp,
            FrameTimestamp validUntil,
            List<ProtectedRegion> findings) {
        Objects.requireNonNull(findings, "findings");
        return new DetectorSnapshot(
                lane,
                sourceTimestamp,
                validUntil,
                findings.stream().map(DetectorObservation::withoutTrackingHint).toList(),
                null);
    }

    public static DetectorSnapshot successWithObservations(
            DetectorLane lane,
            FrameTimestamp sourceTimestamp,
            FrameTimestamp validUntil,
            List<DetectorObservation> observations) {
        return new DetectorSnapshot(lane, sourceTimestamp, validUntil, observations, null);
    }

    public static DetectorSnapshot failure(
            DetectorLane lane, FrameTimestamp timestamp, TypedFailure failure) {
        Objects.requireNonNull(failure, "failure");
        if (!failure.occurredAt().equals(timestamp)) {
            throw new IllegalArgumentException("Failure timestamp must match snapshot timestamp");
        }
        return new DetectorSnapshot(lane, timestamp, timestamp, List.of(), failure);
    }

    public DetectorLane lane() {
        return lane;
    }

    public FrameTimestamp sourceTimestamp() {
        return sourceTimestamp;
    }

    public FrameTimestamp validUntil() {
        return validUntil;
    }

    public List<ProtectedRegion> findings() {
        return findings;
    }

    public List<DetectorObservation> observations() {
        return observations;
    }

    public Optional<TypedFailure> failure() {
        return Optional.ofNullable(failure);
    }

    public boolean isFreshAt(FrameTimestamp timestamp) {
        return failure == null && timestamp.compareTo(sourceTimestamp) >= 0
                && timestamp.compareTo(validUntil) <= 0;
    }
}
