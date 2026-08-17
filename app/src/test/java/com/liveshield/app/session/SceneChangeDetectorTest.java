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

    @Test
    public void rearCameraIgnoresUniformExposureShift() {
        SceneChangeDetector detector = SceneChangeDetector.forRearCamera();

        assertEquals(SessionHealth.SceneState.STABLE,
                detector.observe(checkerboard(20, 80), 8, 8, 8, 1));
        assertEquals(SessionHealth.SceneState.STABLE,
                detector.observe(checkerboard(60, 120), 8, 8, 8, 1));
        assertEquals(SessionHealth.SceneState.STABLE,
                detector.observe(checkerboard(70, 130), 8, 8, 8, 1));
    }

    @Test
    public void rearCameraRequiresPersistentStructuralChange() {
        SceneChangeDetector detector = SceneChangeDetector.forRearCamera();

        assertEquals(SessionHealth.SceneState.STABLE,
                detector.observe(checkerboard(20, 80), 8, 8, 8, 1));
        assertEquals(SessionHealth.SceneState.STABLE,
                detector.observe(checkerboard(80, 20), 8, 8, 8, 1));
        assertEquals(SessionHealth.SceneState.STABLE,
                detector.observe(checkerboard(80, 20), 8, 8, 8, 1));
        assertEquals(SessionHealth.SceneState.CHANGED,
                detector.observe(checkerboard(80, 20), 8, 8, 8, 1));
        assertEquals(SessionHealth.SceneState.STABLE,
                detector.observe(checkerboard(80, 20), 8, 8, 8, 1));
    }

    @Test
    public void rearCameraRejectsSingleStructuralSpike() {
        SceneChangeDetector detector = SceneChangeDetector.forRearCamera();

        assertEquals(SessionHealth.SceneState.STABLE,
                detector.observe(checkerboard(20, 80), 8, 8, 8, 1));
        assertEquals(SessionHealth.SceneState.STABLE,
                detector.observe(checkerboard(80, 20), 8, 8, 8, 1));
        assertEquals(SessionHealth.SceneState.STABLE,
                detector.observe(checkerboard(20, 80), 8, 8, 8, 1));
    }

    @Test
    public void rearCameraDoesNotConfirmAnUnsettledMovingView() {
        SceneChangeDetector detector = SceneChangeDetector.forRearCamera();

        assertEquals(SessionHealth.SceneState.STABLE,
                detector.observe(checkerboard(20, 80), 8, 8, 8, 1));
        assertEquals(SessionHealth.SceneState.STABLE,
                detector.observe(checkerboard(80, 20), 8, 8, 8, 1));
        assertEquals(SessionHealth.SceneState.STABLE,
                detector.observe(verticalSplit(20, 80), 8, 8, 8, 1));
        assertEquals(SessionHealth.SceneState.STABLE,
                detector.observe(checkerboard(80, 20), 8, 8, 8, 1));
        assertEquals(SessionHealth.SceneState.STABLE,
                detector.observe(verticalSplit(20, 80), 8, 8, 8, 1));
    }

    private static ByteBuffer filled(int count, int value) {
        byte[] bytes = new byte[count];
        java.util.Arrays.fill(bytes, (byte) value);
        return ByteBuffer.wrap(bytes);
    }

    private static ByteBuffer checkerboard(int even, int odd) {
        byte[] bytes = new byte[64];
        for (int index = 0; index < bytes.length; index++) {
            int row = index / 8;
            int column = index % 8;
            bytes[index] = (byte) (((row + column) & 1) == 0 ? even : odd);
        }
        return ByteBuffer.wrap(bytes);
    }

    private static ByteBuffer verticalSplit(int left, int right) {
        byte[] bytes = new byte[64];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index % 8 < 4 ? left : right);
        }
        return ByteBuffer.wrap(bytes);
    }
}
