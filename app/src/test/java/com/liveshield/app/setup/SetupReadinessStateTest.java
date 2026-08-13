package com.liveshield.app.setup;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SetupReadinessStateTest {
    @Test
    public void startIsDisabledByDefault() {
        assertFalse(SetupReadinessState.initial().canStart());
    }

    @Test
    public void allSafetyPreconditionsEnableStart() {
        SetupReadinessState state = SetupReadinessState.initial()
                .withCameraPermission(true)
                .withFreshHostSelection(true)
                .withPrivacyReady(true)
                .withDestinationConfigured(true);

        assertTrue(state.canStart());
    }

    @Test
    public void staleHostImmediatelyDisablesStart() {
        SetupReadinessState state = ready().withFreshHostSelection(false);

        assertFalse(state.canStart());
    }

    @Test
    public void permissionRevocationImmediatelyDisablesStart() {
        assertFalse(ready().withCameraPermission(false).canStart());
    }

    @Test
    public void privacyDegradationImmediatelyDisablesStart() {
        assertFalse(ready().withPrivacyReady(false).canStart());
    }

    @Test
    public void missingDestinationKeepsStartDisabled() {
        assertFalse(ready().withDestinationConfigured(false).canStart());
    }

    private static SetupReadinessState ready() {
        return SetupReadinessState.initial()
                .withCameraPermission(true)
                .withFreshHostSelection(true)
                .withPrivacyReady(true)
                .withDestinationConfigured(true);
    }
}
