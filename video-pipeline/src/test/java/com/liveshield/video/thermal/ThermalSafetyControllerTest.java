package com.liveshield.video.thermal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.liveshield.privacy.session.SessionHealth;
import com.liveshield.privacy.session.SessionState;
import com.liveshield.privacy.telemetry.PrivacySafeTelemetry;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class ThermalSafetyControllerTest {
    @Test
    public void platformStatusesMapToTypedPolicyStatesWithoutControllerHysteresis() {
        FakeThermalStatusSource source = new FakeThermalStatusSource();
        PrivacySafeTelemetry telemetry = new PrivacySafeTelemetry(8);
        List<SessionHealth.ThermalState> changes = new ArrayList<>();
        ThermalSafetyController controller =
                new ThermalSafetyController(source, telemetry, changes::add);

        source.emit(ThermalSafetyController.PLATFORM_STATUS_NONE);
        source.emit(ThermalSafetyController.PLATFORM_STATUS_MODERATE);
        source.emit(ThermalSafetyController.PLATFORM_STATUS_SEVERE);
        source.emit(ThermalSafetyController.PLATFORM_STATUS_NONE);

        assertEquals(List.of(
                SessionHealth.ThermalState.NOMINAL,
                SessionHealth.ThermalState.WARNING,
                SessionHealth.ThermalState.SEVERE,
                SessionHealth.ThermalState.NOMINAL), changes);
        assertEquals(SessionHealth.ThermalState.NOMINAL, controller.currentState());
        assertEquals(SessionHealth.ThermalState.NOMINAL,
                telemetry.snapshot().latestThermalState());
    }

    @Test
    public void lightIsNominalModerateIsWarningAndAllDangerousOrUnknownStatesAreSevere() {
        assertEquals(SessionHealth.ThermalState.NOMINAL,
                ThermalSafetyController.mapPlatformStatus(
                        ThermalSafetyController.PLATFORM_STATUS_LIGHT));
        assertEquals(SessionHealth.ThermalState.WARNING,
                ThermalSafetyController.mapPlatformStatus(
                        ThermalSafetyController.PLATFORM_STATUS_MODERATE));
        for (int status = ThermalSafetyController.PLATFORM_STATUS_SEVERE;
                status <= ThermalSafetyController.PLATFORM_STATUS_SHUTDOWN;
                status++) {
            assertEquals(SessionHealth.ThermalState.SEVERE,
                    ThermalSafetyController.mapPlatformStatus(status));
        }
        assertEquals(SessionHealth.ThermalState.SEVERE,
                ThermalSafetyController.mapPlatformStatus(-1));
        assertEquals(SessionHealth.ThermalState.SEVERE,
                ThermalSafetyController.mapPlatformStatus(99));
    }

    @Test
    public void currentTypedStateIsAppliedDirectlyToSessionHealth() {
        FakeThermalStatusSource source = new FakeThermalStatusSource();
        ThermalSafetyController controller = new ThermalSafetyController(
                source, new PrivacySafeTelemetry(4), state -> { });

        source.emit(ThermalSafetyController.PLATFORM_STATUS_MODERATE);
        SessionHealth warning = controller.applyTo(
                SessionHealth.builder(SessionState.LIVE)).build();
        source.emit(ThermalSafetyController.PLATFORM_STATUS_CRITICAL);
        SessionHealth severe = controller.applyTo(
                SessionHealth.builder(SessionState.LIVE)).build();

        assertEquals(SessionHealth.ThermalState.WARNING, warning.thermalState());
        assertEquals(SessionHealth.ThermalState.SEVERE, severe.thermalState());
    }

    @Test
    public void duplicateStatusDoesNotDuplicateTelemetryOrSignals() {
        FakeThermalStatusSource source = new FakeThermalStatusSource();
        PrivacySafeTelemetry telemetry = new PrivacySafeTelemetry(8);
        List<SessionHealth.ThermalState> changes = new ArrayList<>();
        new ThermalSafetyController(source, telemetry, changes::add);

        source.emit(ThermalSafetyController.PLATFORM_STATUS_MODERATE);
        source.emit(ThermalSafetyController.PLATFORM_STATUS_MODERATE);

        assertEquals(List.of(SessionHealth.ThermalState.WARNING), changes);
        assertEquals(1, telemetry.snapshot().events().size());
    }

    @Test
    public void closeUnregistersOnceAndIgnoresLateCallbacks() {
        FakeThermalStatusSource source = new FakeThermalStatusSource();
        List<SessionHealth.ThermalState> changes = new ArrayList<>();
        ThermalSafetyController controller = new ThermalSafetyController(
                source, new PrivacySafeTelemetry(8), changes::add);
        source.emit(ThermalSafetyController.PLATFORM_STATUS_NONE);

        controller.close();
        controller.close();
        source.emitAfterClose(ThermalSafetyController.PLATFORM_STATUS_SEVERE);

        assertTrue(source.started);
        assertTrue(source.closed);
        assertEquals(1, source.closeCount);
        assertEquals(List.of(SessionHealth.ThermalState.NOMINAL), changes);
        assertEquals(SessionHealth.ThermalState.NOMINAL, controller.currentState());
    }

    @Test
    public void sourceRegistrationFailureRemainsFailPrivate() {
        FakeThermalStatusSource source = new FakeThermalStatusSource();
        source.failStart = true;
        PrivacySafeTelemetry telemetry = new PrivacySafeTelemetry(4);
        List<SessionHealth.ThermalState> changes = new ArrayList<>();

        ThermalSafetyController controller =
                new ThermalSafetyController(source, telemetry, changes::add);

        assertEquals(SessionHealth.ThermalState.SEVERE, controller.currentState());
        assertEquals(List.of(SessionHealth.ThermalState.SEVERE), changes);
        assertEquals(SessionHealth.ThermalState.SEVERE,
                telemetry.snapshot().latestThermalState());
        assertTrue(source.closed);
        assertEquals(1, source.closeCount);
    }

    private static final class FakeThermalStatusSource
            implements ThermalSafetyController.ThermalStatusSource {
        private ThermalSafetyController.PlatformStatusListener listener;
        private boolean failStart;
        private boolean started;
        private boolean closed;
        private int closeCount;

        @Override
        public void start(ThermalSafetyController.PlatformStatusListener value) {
            started = true;
            if (failStart) {
                throw new IllegalStateException("synthetic registration failure");
            }
            listener = value;
        }

        @Override
        public void close() {
            closed = true;
            closeCount++;
        }

        private void emit(int status) {
            listener.onPlatformStatus(status);
        }

        private void emitAfterClose(int status) {
            listener.onPlatformStatus(status);
        }
    }
}
