package com.liveshield.app.session;

import com.liveshield.privacy.host.HostSelectionResult;
import java.util.Objects;

/** Payload-free UI state for explicit host reselection after continuity loss. */
public final class HostReselectionController {
    private State state = State.NOT_REQUIRED;

    /** Revokes any implied continuity and requires another explicit fresh-face tap. */
    public synchronized State onContinuityLost() {
        state = State.REQUIRED;
        return state;
    }

    /** Clears the prompt only after the domain controller accepts an explicit selection. */
    public synchronized State onSelectionResult(HostSelectionResult result) {
        Objects.requireNonNull(result, "result");
        if (result.status() == HostSelectionResult.Status.SELECTED) {
            state = State.NOT_REQUIRED;
        }
        return state;
    }

    public synchronized State state() {
        return state;
    }

    /** Clears session-only UI state on lifecycle stop/reset. */
    public synchronized void resetSession() {
        state = State.NOT_REQUIRED;
    }

    public enum State {
        NOT_REQUIRED,
        REQUIRED
    }
}
