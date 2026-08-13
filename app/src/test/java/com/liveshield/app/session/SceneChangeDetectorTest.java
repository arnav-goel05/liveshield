package com.liveshield.app.session;

import static org.junit.Assert.assertEquals;

import com.liveshield.privacy.session.SessionHealth;
import java.nio.ByteBuffer;
import org.junit.Test;

public final class SceneChangeDetectorTest {
    @Test
    public void stableThenSubstantialLuminanceChangeEmitsChangedWithoutRetainingPixels() {
        SceneChangeDetector detector = new SceneChangeDetector();

        assertEquals(SessionHealth.SceneState.STABLE, detector.observe(
                filled(16, 10), 4, 4, 4, 1));
        assertEquals(SessionHealth.SceneState.STABLE, detector.observe(
                filled(16, 20), 4, 4, 4, 1));
        assertEquals(SessionHealth.SceneState.CHANGED, detector.observe(
                filled(16, 100), 4, 4, 4, 1));
        assertEquals(SessionHealth.SceneState.STABLE, detector.observe(
                filled(16, 100), 4, 4, 4, 1));
    }

    @Test
    public void resetRequiresNewBaselineAndStrideGeometryIsHonored() {
        SceneChangeDetector detector = new SceneChangeDetector();
        ByteBuffer padded = filled(32, 20);
        detector.observe(padded, 4, 4, 8, 1);
        detector.reset();

        assertEquals(SessionHealth.SceneState.STABLE,
                detector.observe(filled(32, 120), 4, 4, 8, 1));
    }

    private static ByteBuffer filled(int count, int value) {
        byte[] bytes = new byte[count];
        java.util.Arrays.fill(bytes, (byte) value);
        return ByteBuffer.wrap(bytes);
    }
}
