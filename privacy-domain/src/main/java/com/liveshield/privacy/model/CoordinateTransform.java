package com.liveshield.privacy.model;

import java.util.Arrays;

/** An immutable three-by-three homogeneous coordinate transform. */
public final class CoordinateTransform {
    private static final int MATRIX_SIZE = 9;
    private final double[] matrix;

    public CoordinateTransform(double[] matrix) {
        if (matrix == null || matrix.length != MATRIX_SIZE) {
            throw new IllegalArgumentException("Transform must contain exactly nine values");
        }
        for (double value : matrix) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Transform values must be finite");
            }
        }
        this.matrix = matrix.clone();
    }

    public static CoordinateTransform identity() {
        return new CoordinateTransform(new double[]{
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
        });
    }

    public double[] matrix() {
        return matrix.clone();
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (!(candidate instanceof CoordinateTransform other)) {
            return false;
        }
        return Arrays.equals(matrix, other.matrix);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(matrix);
    }
}
