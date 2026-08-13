package com.liveshield.privacy.model;

import java.util.Arrays;

/** An immutable three-by-three homogeneous coordinate transform. */
public final class CoordinateTransform {
    private static final int MATRIX_SIZE = 9;
    private static final double MINIMUM_DETERMINANT = 1.0e-12;
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

    /** Returns the inverse mapping, rejecting transforms that cannot be projected safely. */
    public CoordinateTransform inverse() {
        double a = matrix[0];
        double b = matrix[1];
        double c = matrix[2];
        double d = matrix[3];
        double e = matrix[4];
        double f = matrix[5];
        double g = matrix[6];
        double h = matrix[7];
        double i = matrix[8];
        double determinant = a * (e * i - f * h)
                - b * (d * i - f * g)
                + c * (d * h - e * g);
        if (!Double.isFinite(determinant) || Math.abs(determinant) < MINIMUM_DETERMINANT) {
            throw new IllegalArgumentException("Transform must be invertible");
        }
        double scale = 1.0 / determinant;
        return new CoordinateTransform(new double[]{
            (e * i - f * h) * scale,
            (c * h - b * i) * scale,
            (b * f - c * e) * scale,
            (f * g - d * i) * scale,
            (a * i - c * g) * scale,
            (c * d - a * f) * scale,
            (d * h - e * g) * scale,
            (b * g - a * h) * scale,
            (a * e - b * d) * scale
        });
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
