package com.liveshield.privacy.telemetry;

import com.liveshield.privacy.decision.FramePrivacyDecision;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.TypedFailure;
import com.liveshield.privacy.session.SessionHealth;

/** Numeric and typed safety telemetry; payload and recognized-string APIs are intentionally absent. */
public interface SafetyTelemetry {
    void recordDecision(
            FrameTimestamp frameTimestamp,
            long decisionLatencyNanos,
            FramePrivacyDecision.Status status);

    void recordQueueDepth(int depth);

    void recordThermalState(SessionHealth.ThermalState state);

    void recordFailure(TypedFailure failure);
}
