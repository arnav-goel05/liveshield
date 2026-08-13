package com.liveshield.privacy.model;

/** A non-empty rectangle in normalized sensor coordinates. */
public record NormalizedRect(double left, double top, double right, double bottom) {
    public NormalizedRect {
        requireNormalized(left, "left");
        requireNormalized(top, "top");
        requireNormalized(right, "right");
        requireNormalized(bottom, "bottom");
        if (left >= right || top >= bottom) {
            throw new IllegalArgumentException("Rectangle must have positive width and height");
        }
    }

    public boolean contains(NormalizedPoint point) {
        return point.x() >= left && point.x() <= right
                && point.y() >= top && point.y() <= bottom;
    }

    private static void requireNormalized(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be finite and in [0, 1]");
        }
    }
}
