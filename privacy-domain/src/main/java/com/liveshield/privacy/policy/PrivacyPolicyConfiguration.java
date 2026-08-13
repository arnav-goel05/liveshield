package com.liveshield.privacy.policy;

import com.liveshield.privacy.model.DetectorLane;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Validated, benchmark-tunable bounds for deterministic fail-private policy. */
public record PrivacyPolicyConfiguration(
        Set<DetectorLane> requiredLanes,
        long maximumDetectorAgeNanos,
        long carryWindowNanos,
        long expansionWindowNanos,
        double expansionFraction,
        int rawQueueCapacity,
        int thermalRecoveryFreshDecisions,
        long decisionValidityNanos) {
    public PrivacyPolicyConfiguration {
        Objects.requireNonNull(requiredLanes, "requiredLanes");
        if (requiredLanes.isEmpty()) {
            throw new IllegalArgumentException("At least one detector lane is required");
        }
        requiredLanes = Set.copyOf(EnumSet.copyOf(requiredLanes));
        requireNonNegative(maximumDetectorAgeNanos, "maximumDetectorAgeNanos");
        requireNonNegative(carryWindowNanos, "carryWindowNanos");
        if (expansionWindowNanos < carryWindowNanos) {
            throw new IllegalArgumentException("Expansion window must include the carry window");
        }
        if (!Double.isFinite(expansionFraction)
                || expansionFraction <= 0.0 || expansionFraction > 1.0) {
            throw new IllegalArgumentException("expansionFraction must be in (0, 1]");
        }
        if (rawQueueCapacity <= 0) {
            throw new IllegalArgumentException("rawQueueCapacity must be positive");
        }
        if (thermalRecoveryFreshDecisions <= 0) {
            throw new IllegalArgumentException("thermalRecoveryFreshDecisions must be positive");
        }
        if (decisionValidityNanos <= 0) {
            throw new IllegalArgumentException("decisionValidityNanos must be positive");
        }
    }

    /** Conservative Phase 4 face-only baseline; device benchmarks may tighten these values. */
    public static PrivacyPolicyConfiguration faceOnlyDefaults() {
        return new PrivacyPolicyConfiguration(
                Set.of(DetectorLane.FACE),
                100_000_000L,
                100_000_000L,
                300_000_000L,
                0.25,
                12,
                3,
                33_333_334L);
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
