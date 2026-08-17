package com.liveshield.app.session;

import com.liveshield.privacy.session.SessionHealth;
import java.nio.ByteBuffer;

/** Bounded sampled-luminance scene signal; no sample or pixel survives the call. */
final class SceneChangeDetector {
    private static final int GRID = 8;
    private static final int CHANGE_THRESHOLD = 24;
    private static final int REAR_PATTERN_CHANGE_BITS = 24;
    private static final int REAR_PATTERN_SETTLED_BITS = 8;
    private static final int REAR_PATTERN_LUMINANCE_MARGIN = 12;
    private static final int REAR_CHANGE_CONFIRMATIONS = 3;
    private final boolean exposureCompensated;
    private int previousMean = -1;
    private long previousPattern;
    private long pendingRearPattern;
    private int pendingRearChanges;

    SceneChangeDetector() {
        this(false);
    }

    private SceneChangeDetector(boolean exposureCompensated) {
        this.exposureCompensated = exposureCompensated;
    }

    static SceneChangeDetector forRearCamera() {
        return new SceneChangeDetector(true);
    }

    synchronized SessionHealth.SceneState observe(
            ByteBuffer luminance, int width, int height, int rowStride, int pixelStride) {
        if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) {
            throw new IllegalArgumentException("Invalid luminance geometry");
        }
        ByteBuffer sample = luminance.duplicate();
        int base = sample.position();
        long total = 0L;
        int count = 0;
        for (int row = 0; row < GRID; row++) {
            int y = Math.min(height - 1, row * height / GRID);
            for (int column = 0; column < GRID; column++) {
                int x = Math.min(width - 1, column * width / GRID);
                int value = Byte.toUnsignedInt(
                        sample.get(base + y * rowStride + x * pixelStride));
                total += value;
                count++;
            }
        }
        int mean = Math.toIntExact(total / count);
        if (exposureCompensated) {
            return observeRearCamera(mean, luminancePattern(
                    sample, base, width, height, rowStride, pixelStride, mean));
        }
        SessionHealth.SceneState state = previousMean >= 0
                && Math.abs(mean - previousMean) >= CHANGE_THRESHOLD
                ? SessionHealth.SceneState.CHANGED : SessionHealth.SceneState.STABLE;
        previousMean = mean;
        return state;
    }

    private SessionHealth.SceneState observeRearCamera(int mean, long pattern) {
        if (previousMean < 0) {
            previousMean = mean;
            previousPattern = pattern;
            return SessionHealth.SceneState.STABLE;
        }
        boolean structuralChange = Long.bitCount(pattern ^ previousPattern)
                >= REAR_PATTERN_CHANGE_BITS;
        if (!structuralChange) {
            previousMean = mean;
            previousPattern = pattern;
            pendingRearPattern = 0L;
            pendingRearChanges = 0;
            return SessionHealth.SceneState.STABLE;
        }
        if (pendingRearChanges == 0
                || Long.bitCount(pattern ^ pendingRearPattern) > REAR_PATTERN_SETTLED_BITS) {
            pendingRearPattern = pattern;
            pendingRearChanges = 1;
            return SessionHealth.SceneState.STABLE;
        }
        pendingRearChanges++;
        if (pendingRearChanges >= REAR_CHANGE_CONFIRMATIONS) {
            previousMean = mean;
            previousPattern = pattern;
            pendingRearPattern = 0L;
            pendingRearChanges = 0;
            return SessionHealth.SceneState.CHANGED;
        }
        return SessionHealth.SceneState.STABLE;
    }

    private static long luminancePattern(
            ByteBuffer sample,
            int base,
            int width,
            int height,
            int rowStride,
            int pixelStride,
            int mean) {
        long pattern = 0L;
        int index = 0;
        for (int row = 0; row < GRID; row++) {
            int y = Math.min(height - 1, row * height / GRID);
            for (int column = 0; column < GRID; column++) {
                int x = Math.min(width - 1, column * width / GRID);
                int value = Byte.toUnsignedInt(
                        sample.get(base + y * rowStride + x * pixelStride));
                if (value >= mean + REAR_PATTERN_LUMINANCE_MARGIN) {
                    pattern |= 1L << index;
                }
                index++;
            }
        }
        return pattern;
    }

    synchronized void reset() {
        previousMean = -1;
        previousPattern = 0L;
        pendingRearPattern = 0L;
        pendingRearChanges = 0;
    }
}
