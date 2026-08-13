package com.liveshield.app.debug;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Debug-only, deterministic fault dispatcher for fail-private system verification.
 *
 * <p>The controller carries only typed control state. It cannot accept pixels, recognized text,
 * arbitrary errors, credentials, or network payloads. Each armed fault is one-shot and fires on
 * an exact checkpoint count, which makes fixture runs reproducible.</p>
 */
public final class FaultInjectionController {
    private final Map<FaultTarget, FaultHandler> handlers;
    private final EnumMap<FaultTarget, Integer> armed = new EnumMap<>(FaultTarget.class);
    private long nextSequence;

    public FaultInjectionController(Bindings bindings) {
        handlers = Objects.requireNonNull(bindings, "bindings").handlers();
    }

    /** Arms a one-shot fault after exactly {@code checkpointsBeforeTrigger} earlier checkpoints. */
    public synchronized void arm(FaultTarget target, int checkpointsBeforeTrigger) {
        Objects.requireNonNull(target, "target");
        if (checkpointsBeforeTrigger < 0) {
            throw new IllegalArgumentException("checkpointsBeforeTrigger must be non-negative");
        }
        armed.put(target, checkpointsBeforeTrigger);
    }

    /** Disarms one fault without invoking its path handler. */
    public synchronized void disarm(FaultTarget target) {
        armed.remove(Objects.requireNonNull(target, "target"));
    }

    /** Disarms every pending fault without retaining a history or payload. */
    public synchronized void clear() {
        armed.clear();
    }

    /** Returns whether a target is armed; this exposes no payload or internal callback. */
    public synchronized boolean isArmed(FaultTarget target) {
        return armed.containsKey(Objects.requireNonNull(target, "target"));
    }

    /**
     * Advances one deterministic path checkpoint and invokes a one-shot handler when due.
     *
     * @return true only when this checkpoint injected the target fault
     */
    public boolean checkpoint(FaultTarget target) {
        Objects.requireNonNull(target, "target");
        FaultHandler handler;
        FaultSignal signal;
        synchronized (this) {
            Integer remaining = armed.get(target);
            if (remaining == null) {
                return false;
            }
            if (remaining > 0) {
                armed.put(target, remaining - 1);
                return false;
            }
            armed.remove(target);
            signal = new FaultSignal(target, nextSequence++);
            handler = handlers.get(target);
        }
        handler.onFault(signal);
        return true;
    }

    /** Every supported injection point; values contain no user, pixel, or transport data. */
    public enum FaultTarget {
        DETECTOR_STALL,
        DETECTOR_FAILURE,
        QUEUE_CAPACITY,
        GL_FAILURE,
        SURFACE_LOSS,
        CAMERA_FAILURE,
        LIFECYCLE_INTERRUPTION,
        ENCODER_FAILURE,
        NETWORK_LOSS
    }

    /** Payload-free evidence identifying only the injected path and deterministic order. */
    public record FaultSignal(FaultTarget target, long sequence) {
        public FaultSignal {
            Objects.requireNonNull(target, "target");
            if (sequence < 0) {
                throw new IllegalArgumentException("sequence must be non-negative");
            }
        }
    }

    @FunctionalInterface
    public interface FaultHandler {
        void onFault(FaultSignal signal);
    }

    /** Builder requires one explicit fail-private handler for every fault path. */
    public static final class Bindings {
        private final EnumMap<FaultTarget, FaultHandler> handlers =
                new EnumMap<>(FaultTarget.class);

        public Bindings on(FaultTarget target, FaultHandler handler) {
            handlers.put(
                    Objects.requireNonNull(target, "target"),
                    Objects.requireNonNull(handler, "handler"));
            return this;
        }

        private Map<FaultTarget, FaultHandler> handlers() {
            if (handlers.size() != FaultTarget.values().length) {
                throw new IllegalStateException(
                        "Every debug fault target requires an explicit safe handler");
            }
            return Map.copyOf(handlers);
        }
    }
}
