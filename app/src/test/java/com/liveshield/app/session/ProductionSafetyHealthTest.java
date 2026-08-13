package com.liveshield.app.session;

import static org.junit.Assert.assertEquals;

import com.liveshield.privacy.session.SessionHealth;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class ProductionSafetyHealthTest {
    @Test
    public void productionThermalStateIsAlwaysNominalWithoutPlatformObservation() {
        AtomicInteger rawDepth = new AtomicInteger(2);
        AtomicReference<SessionHealth.RecoveryState> recovery =
                new AtomicReference<>(SessionHealth.RecoveryState.UNSAFE);
        ProductionSafetyHealth health =
                new ProductionSafetyHealth(rawDepth::get, recovery::get);

        assertEquals(SessionHealth.ThermalState.NOMINAL, health.thermalState());
        assertEquals(SessionHealth.ThermalState.NOMINAL, health.snapshot().thermalState());

        rawDepth.set(0);
        recovery.set(SessionHealth.RecoveryState.VERIFIED);
        health.updateScene(SessionHealth.SceneState.CHANGED);

        assertEquals(SessionHealth.ThermalState.NOMINAL, health.snapshot().thermalState());
        assertEquals(SessionHealth.SceneState.CHANGED, health.snapshot().sceneState());
    }
}
