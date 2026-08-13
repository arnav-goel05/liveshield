package com.liveshield.privacy.model;

/** A point in normalized sensor coordinates. */
public record NormalizedPoint(double x, double y) {
    public NormalizedPoint {
        requireNormalized(x, "x");
        requireNormalized(y, "y");
    }

    private static void requireNormalized(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be finite and in [0, 1]");
        }
    }
}
