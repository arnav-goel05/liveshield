package com.liveshield.app.session;

import com.liveshield.privacy.session.SessionHealth;
import java.nio.ByteBuffer;

/** Ephemeral sampled-luminance scene signal; no sample or pixel survives the call. */
final class SceneChangeDetector {
    private static final int GRID = 8;
    private static final int CHANGE_THRESHOLD = 24;
    private int previousMean = -1;

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
                total += Byte.toUnsignedInt(sample.get(base + y * rowStride + x * pixelStride));
                count++;
            }
        }
        int mean = Math.toIntExact(total / count);
        SessionHealth.SceneState state = previousMean >= 0
                && Math.abs(mean - previousMean) >= CHANGE_THRESHOLD
                ? SessionHealth.SceneState.CHANGED : SessionHealth.SceneState.STABLE;
        previousMean = mean;
        return state;
    }

    synchronized void reset() {
        previousMean = -1;
    }
}
