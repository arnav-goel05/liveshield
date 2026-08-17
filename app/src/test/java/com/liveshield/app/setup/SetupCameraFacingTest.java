package com.liveshield.app.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import androidx.camera.core.CameraSelector;
import org.junit.Test;

/** Camera-facing selection stays explicit and reversible. */
public final class SetupCameraFacingTest {
    @Test
    public void frontAndRearToggleWithoutAnIntermediateFacing() {
        assertSame(CameraSelector.DEFAULT_FRONT_CAMERA, SetupCameraFacing.FRONT.selector());
        assertEquals(SetupCameraFacing.REAR, SetupCameraFacing.FRONT.opposite());
        assertSame(CameraSelector.DEFAULT_BACK_CAMERA, SetupCameraFacing.REAR.selector());
        assertEquals(SetupCameraFacing.FRONT, SetupCameraFacing.REAR.opposite());
    }
}
