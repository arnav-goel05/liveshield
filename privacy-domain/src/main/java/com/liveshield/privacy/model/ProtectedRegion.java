package com.liveshield.privacy.model;

import java.util.List;
import java.util.Objects;

/** Immutable geometry and policy metadata for content that must be protected. */
public record ProtectedRegion(
        FindingCategory category,
        List<NormalizedRect> bounds,
        ConfidenceClass confidenceClass,
        ProtectionAction action) {
    public ProtectedRegion {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(confidenceClass, "confidenceClass");
        Objects.requireNonNull(action, "action");
        bounds = List.copyOf(bounds);
        if (bounds.isEmpty()) {
            throw new IllegalArgumentException("A protected region needs at least one bound");
        }
    }
}
