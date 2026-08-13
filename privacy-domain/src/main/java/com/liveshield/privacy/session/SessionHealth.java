package com.liveshield.privacy.session;

import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.TypedFailure;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable, non-sensitive session health evidence. */
public final class SessionHealth {
    private final SessionState state;
    private final long latestDecisionAgeNanos;
    private final Map<DetectorLane, Long> detectorLaneAgesNanos;
    private final int rawQueueDepth;
    private final long encodedQueueDurationNanos;
    private final long encodedQueueBytes;
    private final double outputFramesPerSecond;
    private final long droppedSanitizedUnits;
    private final long shieldActivationCount;
    private final ThermalState thermalState;
    private final SceneState sceneState;
    private final RendererState rendererState;
    private final RecoveryState recoveryState;
    private final TypedFailure.Code lastFailureCode;

    private SessionHealth(Builder builder) {
        state = builder.state;
        latestDecisionAgeNanos = requireNonNegative(
                builder.latestDecisionAgeNanos, "latestDecisionAgeNanos");
        detectorLaneAgesNanos = immutableAges(builder.detectorLaneAgesNanos);
        rawQueueDepth = Math.toIntExact(requireNonNegative(
                builder.rawQueueDepth, "rawQueueDepth"));
        encodedQueueDurationNanos = requireNonNegative(
                builder.encodedQueueDurationNanos, "encodedQueueDurationNanos");
        encodedQueueBytes = requireNonNegative(builder.encodedQueueBytes, "encodedQueueBytes");
        if (!Double.isFinite(builder.outputFramesPerSecond)
                || builder.outputFramesPerSecond < 0.0) {
            throw new IllegalArgumentException("outputFramesPerSecond must be finite and non-negative");
        }
        outputFramesPerSecond = builder.outputFramesPerSecond;
        droppedSanitizedUnits = requireNonNegative(
                builder.droppedSanitizedUnits, "droppedSanitizedUnits");
        shieldActivationCount = requireNonNegative(
                builder.shieldActivationCount, "shieldActivationCount");
        thermalState = builder.thermalState;
        sceneState = builder.sceneState;
        rendererState = builder.rendererState;
        recoveryState = builder.recoveryState;
        lastFailureCode = builder.lastFailureCode;
    }

    public static Builder builder(SessionState state) {
        return new Builder(state);
    }

    private static Map<DetectorLane, Long> immutableAges(Map<DetectorLane, Long> source) {
        EnumMap<DetectorLane, Long> copy = new EnumMap<>(DetectorLane.class);
        for (Map.Entry<DetectorLane, Long> entry : source.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "detector lane"),
                    requireNonNegative(entry.getValue(), "detector lane age"));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static long requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }

    public SessionState state() {
        return state;
    }

    public long latestDecisionAgeNanos() {
        return latestDecisionAgeNanos;
    }

    public Map<DetectorLane, Long> detectorLaneAgesNanos() {
        return detectorLaneAgesNanos;
    }

    public int rawQueueDepth() {
        return rawQueueDepth;
    }

    public long encodedQueueDurationNanos() {
        return encodedQueueDurationNanos;
    }

    public long encodedQueueBytes() {
        return encodedQueueBytes;
    }

    public double outputFramesPerSecond() {
        return outputFramesPerSecond;
    }

    public long droppedSanitizedUnits() {
        return droppedSanitizedUnits;
    }

    public long shieldActivationCount() {
        return shieldActivationCount;
    }

    public ThermalState thermalState() {
        return thermalState;
    }

    public SceneState sceneState() {
        return sceneState;
    }

    public RendererState rendererState() {
        return rendererState;
    }

    public RecoveryState recoveryState() {
        return recoveryState;
    }

    public Optional<TypedFailure.Code> lastFailureCode() {
        return Optional.ofNullable(lastFailureCode);
    }

    public enum ThermalState {
        NOMINAL,
        WARNING,
        SEVERE
    }

    /** Whether the current scene can safely inherit an earlier analysis result. */
    public enum SceneState {
        STABLE,
        CHANGED
    }

    /** Payload-free renderer availability used by pure privacy policy. */
    public enum RendererState {
        READY,
        FAILED
    }

    /** Whether pre-failure raw work has been proven discarded before recovery. */
    public enum RecoveryState {
        SAFE,
        UNSAFE,
        VERIFIED
    }

    /** Builder that accepts only non-sensitive health metrics. */
    public static final class Builder {
        private final SessionState state;
        private long latestDecisionAgeNanos;
        private final Map<DetectorLane, Long> detectorLaneAgesNanos =
                new EnumMap<>(DetectorLane.class);
        private int rawQueueDepth;
        private long encodedQueueDurationNanos;
        private long encodedQueueBytes;
        private double outputFramesPerSecond;
        private long droppedSanitizedUnits;
        private long shieldActivationCount;
        private ThermalState thermalState = ThermalState.NOMINAL;
        private SceneState sceneState = SceneState.STABLE;
        private RendererState rendererState = RendererState.READY;
        private RecoveryState recoveryState = RecoveryState.SAFE;
        private TypedFailure.Code lastFailureCode;

        private Builder(SessionState state) {
            this.state = Objects.requireNonNull(state, "state");
        }

        public Builder latestDecisionAgeNanos(long value) {
            latestDecisionAgeNanos = value;
            return this;
        }

        public Builder detectorLaneAgeNanos(DetectorLane lane, long value) {
            detectorLaneAgesNanos.put(Objects.requireNonNull(lane, "lane"), value);
            return this;
        }

        public Builder rawQueueDepth(int value) {
            rawQueueDepth = value;
            return this;
        }

        public Builder encodedQueueDurationNanos(long value) {
            encodedQueueDurationNanos = value;
            return this;
        }

        public Builder encodedQueueBytes(long value) {
            encodedQueueBytes = value;
            return this;
        }

        public Builder outputFramesPerSecond(double value) {
            outputFramesPerSecond = value;
            return this;
        }

        public Builder droppedSanitizedUnits(long value) {
            droppedSanitizedUnits = value;
            return this;
        }

        public Builder shieldActivationCount(long value) {
            shieldActivationCount = value;
            return this;
        }

        public Builder thermalState(ThermalState value) {
            thermalState = Objects.requireNonNull(value, "thermalState");
            return this;
        }

        public Builder sceneState(SceneState value) {
            sceneState = Objects.requireNonNull(value, "sceneState");
            return this;
        }

        public Builder rendererState(RendererState value) {
            rendererState = Objects.requireNonNull(value, "rendererState");
            return this;
        }

        public Builder recoveryState(RecoveryState value) {
            recoveryState = Objects.requireNonNull(value, "recoveryState");
            return this;
        }

        public Builder lastFailureCode(TypedFailure.Code value) {
            lastFailureCode = value;
            return this;
        }

        public SessionHealth build() {
            return new SessionHealth(this);
        }
    }
}
