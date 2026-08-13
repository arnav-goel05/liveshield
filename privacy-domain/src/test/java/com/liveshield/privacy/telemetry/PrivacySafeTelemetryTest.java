package com.liveshield.privacy.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.liveshield.privacy.decision.FramePrivacyDecision;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.TypedFailure;
import com.liveshield.privacy.session.SessionHealth;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.Test;

public final class PrivacySafeTelemetryTest {
    @Test
    public void recordsOnlyBoundedNumericAndEnumEvidence() {
        PrivacySafeTelemetry telemetry = new PrivacySafeTelemetry(2);

        telemetry.recordDecision(
                FrameTimestamp.ofNanos(10), 4,
                FramePrivacyDecision.Status.REGIONAL_SAFE);
        telemetry.recordQueueDepth(3);
        telemetry.recordThermalState(SessionHealth.ThermalState.WARNING);
        telemetry.recordFailure(new TypedFailure(
                TypedFailure.Code.DEADLINE_EXCEEDED,
                FrameTimestamp.ofNanos(11)));

        PrivacySafeTelemetry.Snapshot snapshot = telemetry.snapshot();
        assertEquals(1, snapshot.decisionCount());
        assertEquals(4, snapshot.latestDecisionLatencyNanos());
        assertEquals(FramePrivacyDecision.Status.REGIONAL_SAFE,
                snapshot.latestDecisionStatus());
        assertEquals(3, snapshot.latestQueueDepth());
        assertEquals(SessionHealth.ThermalState.WARNING, snapshot.latestThermalState());
        assertEquals(TypedFailure.Code.DEADLINE_EXCEEDED, snapshot.latestFailureCode());
        assertEquals(2, snapshot.events().size());
    }

    @Test
    public void capacityEvictsOldestEventAndClearRemovesSessionEvidence() {
        PrivacySafeTelemetry telemetry = new PrivacySafeTelemetry(2);
        telemetry.recordQueueDepth(1);
        telemetry.recordQueueDepth(2);
        telemetry.recordQueueDepth(3);

        assertEquals(2, telemetry.snapshot().events().size());
        assertEquals(2, telemetry.snapshot().events().get(0).numericValue());

        telemetry.clear();

        assertTrue(telemetry.snapshot().events().isEmpty());
        assertEquals(0, telemetry.snapshot().decisionCount());
    }

    @Test
    public void rejectsInvalidNumericValues() {
        PrivacySafeTelemetry telemetry = new PrivacySafeTelemetry(2);

        assertThrows(IllegalArgumentException.class,
                () -> telemetry.recordQueueDepth(-1));
        assertThrows(IllegalArgumentException.class,
                () -> telemetry.recordDecision(
                        FrameTimestamp.ofNanos(1), -1,
                        FramePrivacyDecision.Status.FULL_SHIELD));
    }

    @Test
    public void publicApiCannotAcceptPayloadStringsBytesOrObjects() {
        for (Method method : PrivacySafeTelemetry.class.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            assertTrue(method.toString(), Arrays.stream(method.getParameterTypes()).noneMatch(type ->
                    type == String.class || type == byte[].class || type == Object.class
                            || CharSequence.class.isAssignableFrom(type)));
        }
    }
}
