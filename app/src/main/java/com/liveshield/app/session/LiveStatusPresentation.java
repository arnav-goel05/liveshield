package com.liveshield.app.session;

import com.liveshield.privacy.session.SessionState;
import java.util.Objects;

/** Maps private session lifecycle state to honest creator-facing status modes. */
public final class LiveStatusPresentation {
    private LiveStatusPresentation() {
    }

    public static Mode from(SessionState state) {
        return switch (Objects.requireNonNull(state, "state")) {
            case SETUP, READY -> Mode.NOT_LIVE;
            case LIVE -> Mode.HEALTHY;
            case DEGRADED -> Mode.DEGRADED;
            case SHIELDING -> Mode.SHIELDING;
            case STOPPING, ENDED -> Mode.STOPPED;
            case FAILED -> Mode.FAILED;
        };
    }

    public enum Mode {
        NOT_LIVE,
        HEALTHY,
        DEGRADED,
        SHIELDING,
        STOPPED,
        FAILED
    }
}
