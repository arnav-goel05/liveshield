package com.liveshield.privacy.session;

import com.liveshield.privacy.host.HostSelectionResult;
import com.liveshield.privacy.model.FrameTimestamp;
import java.util.EnumSet;
import java.util.Objects;

/** Coordinates explicit host permission and pipeline readiness before sanitized output starts. */
public final class LiveSessionStateMachine {
    private final EnumSet<RequiredComponent> readyComponents =
            EnumSet.noneOf(RequiredComponent.class);
    private LiveSession session;

    public LiveSessionStateMachine(LiveSession initialSession) {
        session = Objects.requireNonNull(initialSession, "initialSession");
        if (session.state() != SessionState.SETUP) {
            throw new IllegalArgumentException("A state machine must begin in SETUP");
        }
    }

    public synchronized LiveSession snapshot() {
        return session;
    }

    public synchronized void acceptHostSelection(HostSelectionResult selection) {
        Objects.requireNonNull(selection, "selection");
        if (selection.status() != HostSelectionResult.Status.SELECTED) {
            revokeHost();
            return;
        }
        ensureConfigurableState();
        session = session.withHostTrack(selection.selectedTrackId().orElseThrow());
    }

    public synchronized void revokeHost() {
        SessionState state = session.state();
        if (state == SessionState.ENDED || state == SessionState.FAILED
                || state == SessionState.STOPPING) {
            return;
        }
        session = session.withoutHostTrack();
        if (state == SessionState.READY) {
            session = session.returnToSetup();
        } else if (state == SessionState.LIVE || state == SessionState.DEGRADED) {
            session = session.transitionTo(SessionState.SHIELDING, currentStateTimestamp());
        }
    }

    public synchronized void markComponentReady(RequiredComponent component) {
        ensureNotTerminal();
        readyComponents.add(Objects.requireNonNull(component, "component"));
    }

    public synchronized void markComponentUnavailable(RequiredComponent component) {
        Objects.requireNonNull(component, "component");
        readyComponents.remove(component);
        SessionState state = session.state();
        if (state == SessionState.READY) {
            session = session.returnToSetup();
        } else if (state == SessionState.LIVE || state == SessionState.DEGRADED) {
            session = session.transitionTo(SessionState.SHIELDING, currentStateTimestamp());
        }
    }

    public synchronized boolean tryBecomeReady(FrameTimestamp timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        if (session.state() == SessionState.READY) {
            return true;
        }
        if (session.state() != SessionState.SETUP
                || session.hostTrackId().isEmpty()
                || readyComponents.size() != RequiredComponent.values().length) {
            return false;
        }
        session = session.transitionTo(SessionState.READY, timestamp);
        return true;
    }

    public synchronized void start(FrameTimestamp timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        if (session.state() != SessionState.READY
                || session.hostTrackId().isEmpty()
                || readyComponents.size() != RequiredComponent.values().length) {
            throw new IllegalStateException("Session is not safe and ready to start");
        }
        session = session.transitionTo(SessionState.LIVE, timestamp);
    }

    public synchronized boolean canResumeLive() {
        return session.state() == SessionState.SHIELDING
                && session.hostTrackId().isPresent()
                && readyComponents.size() == RequiredComponent.values().length;
    }

    public synchronized void resumeLive(FrameTimestamp timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        if (!canResumeLive()) {
            throw new IllegalStateException("Fresh host permission and all components are required");
        }
        session = session.transitionTo(SessionState.LIVE, timestamp);
    }

    public synchronized void enterDegraded(FrameTimestamp timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        if (session.state() == SessionState.LIVE) {
            session = session.transitionTo(SessionState.DEGRADED, timestamp);
        }
    }

    /** Recovers a degraded session only while host permission and all components remain ready. */
    public synchronized void recoverDegraded(FrameTimestamp timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        if (session.state() != SessionState.DEGRADED
                || session.hostTrackId().isEmpty()
                || readyComponents.size() != RequiredComponent.values().length) {
            throw new IllegalStateException("A degraded session is not ready to recover");
        }
        session = session.transitionTo(SessionState.LIVE, timestamp);
    }

    public synchronized void enterShielding(FrameTimestamp timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        SessionState state = session.state();
        if (state == SessionState.LIVE || state == SessionState.DEGRADED) {
            session = session.transitionTo(SessionState.SHIELDING, timestamp);
        }
    }

    public synchronized void stop(FrameTimestamp timestamp) {
        Objects.requireNonNull(timestamp, "timestamp");
        if (session.state() == SessionState.ENDED) {
            return;
        }
        if (session.state() == SessionState.FAILED) {
            throw new IllegalStateException("A failed session cannot restart or stop again");
        }
        session = session.transitionTo(SessionState.STOPPING, timestamp);
        readyComponents.clear();
        session = session.transitionTo(SessionState.ENDED, timestamp);
    }

    public synchronized boolean mayEmitRenderedOutput() {
        if (session.state() == SessionState.SHIELDING) {
            return readyComponents.contains(RequiredComponent.RENDERER)
                    && readyComponents.contains(RequiredComponent.ENCODER);
        }
        return (session.state() == SessionState.LIVE
                || session.state() == SessionState.DEGRADED)
                && session.hostTrackId().isPresent()
                && readyComponents.size() == RequiredComponent.values().length;
    }

    private void ensureConfigurableState() {
        SessionState state = session.state();
        if (state != SessionState.SETUP
                && state != SessionState.READY
                && state != SessionState.SHIELDING) {
            throw new IllegalStateException("Host selection is not allowed in state " + state);
        }
    }

    private void ensureNotTerminal() {
        SessionState state = session.state();
        if (state == SessionState.ENDED
                || state == SessionState.FAILED
                || state == SessionState.STOPPING) {
            throw new IllegalStateException("Session is terminal or stopping");
        }
    }

    private FrameTimestamp currentStateTimestamp() {
        return session.startedAt().orElse(FrameTimestamp.ofNanos(0));
    }

    public enum RequiredComponent {
        CAMERA,
        ANALYSIS,
        RENDERER,
        ENCODER
    }
}
