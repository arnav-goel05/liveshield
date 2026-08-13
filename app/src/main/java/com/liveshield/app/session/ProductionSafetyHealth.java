package com.liveshield.app.session;

import com.liveshield.privacy.session.SessionHealth;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Payload-free production aggregation of renderer and scene safety evidence. */
final class ProductionSafetyHealth implements LiveSessionCoordinator.SafetyHealthProbe {
    private final IntSupplier rawQueueDepth;
    private final Supplier<SessionHealth.RecoveryState> recoveryState;
    private final AtomicReference<SessionHealth.SceneState> sceneState =
            new AtomicReference<>(SessionHealth.SceneState.STABLE);

    ProductionSafetyHealth(
            IntSupplier rawQueueDepth,
            Supplier<SessionHealth.RecoveryState> recoveryState) {
        this.rawQueueDepth = Objects.requireNonNull(rawQueueDepth, "rawQueueDepth");
        this.recoveryState = Objects.requireNonNull(recoveryState, "recoveryState");
    }

    void updateScene(SessionHealth.SceneState state) {
        sceneState.set(Objects.requireNonNull(state, "state"));
    }

    SessionHealth.ThermalState thermalState() {
        return SessionHealth.ThermalState.NOMINAL;
    }

    @Override
    public LiveSessionCoordinator.SafetyHealthSnapshot snapshot() {
        return new LiveSessionCoordinator.SafetyHealthSnapshot(
                rawQueueDepth.getAsInt(),
                recoveryState.get(),
                SessionHealth.ThermalState.NOMINAL,
                sceneState.get());
    }
}
