package com.liveshield.app.setup;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SetupActivityDebugCaptureTest {
    @Test
    public void screenCaptureRequiresDebugBuildAndExplicitRequest() {
        assertTrue(SetupActivity.allowDebugScreenCapture(true, true));
        assertFalse(SetupActivity.allowDebugScreenCapture(true, false));
        assertFalse(SetupActivity.allowDebugScreenCapture(false, true));
        assertFalse(SetupActivity.allowDebugScreenCapture(false, false));
    }
}
