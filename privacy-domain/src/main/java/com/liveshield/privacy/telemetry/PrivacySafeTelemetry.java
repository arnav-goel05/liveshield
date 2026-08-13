package com.liveshield.privacy.telemetry;

import com.liveshield.privacy.decision.FramePrivacyDecision;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.TypedFailure;
import com.liveshield.privacy.session.SessionHealth;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/** Bounded in-memory safety evidence whose public API cannot represent media or text payloads. */
public final class PrivacySafeTelemetry implements SafetyTelemetry {
    private final int eventCapacity;
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private long decisionCount;
    private long latestDecisionLatencyNanos;
    private FramePrivacyDecision.Status latestDecisionStatus;
    private int latestQueueDepth;
    private SessionHealth.ThermalState latestThermalState = SessionHealth.ThermalState.NOMINAL;
    private TypedFailure.Code latestFailureCode;

    public PrivacySafeTelemetry(int eventCapacity) {
        if (eventCapacity <= 0) {
            throw new IllegalArgumentException("eventCapacity must be positive");
        }
        this.eventCapacity = eventCapacity;
    }

    @Override
    public synchronized void recordDecision(
            FrameTimestamp frameTimestamp,
            long decisionLatencyNanos,
            FramePrivacyDecision.Status status) {
        Objects.requireNonNull(frameTimestamp, "frameTimestamp");
        if (decisionLatencyNanos < 0) {
            throw new IllegalArgumentException("decisionLatencyNanos must be non-negative");
        }
        latestDecisionLatencyNanos = decisionLatencyNanos;
        latestDecisionStatus = Objects.requireNonNull(status, "status");
        decisionCount++;
        append(new Event(EventType.DECISION, frameTimestamp.nanos(), decisionLatencyNanos,
                status, null, null));
    }

    @Override
    public synchronized void recordQueueDepth(int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("depth must be non-negative");
        }
        latestQueueDepth = depth;
        append(new Event(EventType.QUEUE_DEPTH, 0L, depth, null, null, null));
    }

    @Override
    public synchronized void recordThermalState(SessionHealth.ThermalState state) {
        latestThermalState = Objects.requireNonNull(state, "state");
        append(new Event(EventType.THERMAL, 0L, 0L, null, state, null));
    }

    @Override
    public synchronized void recordFailure(TypedFailure failure) {
        Objects.requireNonNull(failure, "failure");
        latestFailureCode = failure.code();
        append(new Event(EventType.FAILURE, failure.occurredAt().nanos(), 0L,
                null, null, failure.code()));
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                decisionCount,
                latestDecisionLatencyNanos,
                latestDecisionStatus,
                latestQueueDepth,
                latestThermalState,
                latestFailureCode,
                List.copyOf(events));
    }

    /** Clears session-scoped evidence and resets all summary fields to conservative defaults. */
    public synchronized void clear() {
        events.clear();
        decisionCount = 0L;
        latestDecisionLatencyNanos = 0L;
        latestDecisionStatus = null;
        latestQueueDepth = 0;
        latestThermalState = SessionHealth.ThermalState.NOMINAL;
        latestFailureCode = null;
    }

    private void append(Event event) {
        events.addLast(event);
        while (events.size() > eventCapacity) {
            events.removeFirst();
        }
    }

    public enum EventType {
        DECISION,
        QUEUE_DEPTH,
        THERMAL,
        FAILURE
    }

    /** One immutable numeric/enum-only event. */
    public record Event(
            EventType type,
            long timestampNanos,
            long numericValue,
            FramePrivacyDecision.Status decisionStatus,
            SessionHealth.ThermalState thermalState,
            TypedFailure.Code failureCode) {
        public Event {
            Objects.requireNonNull(type, "type");
            if (timestampNanos < 0 || numericValue < 0) {
                throw new IllegalArgumentException("Event numeric values must be non-negative");
            }
        }
    }

    /** Immutable bounded summary suitable for local acceptance analysis. */
    public record Snapshot(
            long decisionCount,
            long latestDecisionLatencyNanos,
            FramePrivacyDecision.Status latestDecisionStatus,
            int latestQueueDepth,
            SessionHealth.ThermalState latestThermalState,
            TypedFailure.Code latestFailureCode,
            List<Event> events) {
        public Snapshot {
            if (decisionCount < 0 || latestDecisionLatencyNanos < 0 || latestQueueDepth < 0) {
                throw new IllegalArgumentException("Snapshot numeric values must be non-negative");
            }
            Objects.requireNonNull(latestThermalState, "latestThermalState");
            events = List.copyOf(Objects.requireNonNull(events, "events"));
        }
    }
}
