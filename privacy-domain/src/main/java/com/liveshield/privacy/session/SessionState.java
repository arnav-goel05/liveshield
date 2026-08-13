package com.liveshield.privacy.session;

/** Ephemeral live-session lifecycle states. */
public enum SessionState {
    SETUP,
    READY,
    LIVE,
    DEGRADED,
    SHIELDING,
    STOPPING,
    ENDED,
    FAILED
}
