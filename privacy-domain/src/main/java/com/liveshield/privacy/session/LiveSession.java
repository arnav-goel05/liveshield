package com.liveshield.privacy.session;

import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** An immutable snapshot of session-only state and privacy configuration. */
public final class LiveSession {
    private final String sessionId;
    private final SessionState state;
    private final FrameTimestamp startedAt;
    private final Long hostTrackId;
    private final Set<String> watchlist;
    private final List<NormalizedRect> privacyZones;

    private LiveSession(
            String sessionId,
            SessionState state,
            FrameTimestamp startedAt,
            Long hostTrackId,
            Set<String> watchlist,
            List<NormalizedRect> privacyZones) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session identifier must not be blank");
        }
        this.sessionId = sessionId;
        this.state = Objects.requireNonNull(state, "state");
        this.startedAt = startedAt;
        if (hostTrackId != null && hostTrackId < 0) {
            throw new IllegalArgumentException("Host track identifier must be non-negative");
        }
        this.hostTrackId = hostTrackId;
        this.watchlist = Set.copyOf(Objects.requireNonNull(watchlist, "watchlist"));
        this.privacyZones = List.copyOf(
                Objects.requireNonNull(privacyZones, "privacyZones"));
    }

    public static LiveSession setup(
            String sessionId, Set<String> watchlist, List<NormalizedRect> privacyZones) {
        return new LiveSession(
                sessionId, SessionState.SETUP, null, null, watchlist, privacyZones);
    }

    public LiveSession withHostTrack(long trackId) {
        if (state != SessionState.SETUP
                && state != SessionState.READY
                && state != SessionState.SHIELDING) {
            throw new IllegalStateException(
                    "Host selection is only valid during setup, readiness, or shielding");
        }
        return new LiveSession(
                sessionId, state, startedAt, trackId, watchlist, privacyZones);
    }

    public LiveSession withoutHostTrack() {
        return new LiveSession(sessionId, state, startedAt, null, watchlist, privacyZones);
    }

    public LiveSession returnToSetup() {
        if (state != SessionState.READY) {
            throw new IllegalStateException("Only a not-yet-started ready session can return to setup");
        }
        return new LiveSession(
                sessionId, SessionState.SETUP, null, hostTrackId, watchlist, privacyZones);
    }

    public LiveSession transitionTo(SessionState target, FrameTimestamp transitionTimestamp) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(transitionTimestamp, "transitionTimestamp");
        if (!isLegalTransition(state, target)) {
            throw new IllegalStateException("Illegal transition from " + state + " to " + target);
        }
        if (target == SessionState.READY && hostTrackId == null) {
            throw new IllegalStateException("A fresh, explicitly selected host is required");
        }
        FrameTimestamp nextStartedAt = startedAt;
        if (target == SessionState.LIVE && nextStartedAt == null) {
            nextStartedAt = transitionTimestamp;
        }
        Long nextHost = target == SessionState.ENDED || target == SessionState.FAILED
                ? null : hostTrackId;
        Set<String> nextWatchlist = target == SessionState.ENDED || target == SessionState.FAILED
                ? Set.of() : watchlist;
        List<NormalizedRect> nextZones = target == SessionState.ENDED
                || target == SessionState.FAILED ? List.of() : privacyZones;
        return new LiveSession(
                sessionId, target, nextStartedAt, nextHost, nextWatchlist, nextZones);
    }

    private static boolean isLegalTransition(SessionState source, SessionState target) {
        if (source == target) {
            return false;
        }
        if (source != SessionState.ENDED && target == SessionState.FAILED) {
            return true;
        }
        if (source != SessionState.ENDED && target == SessionState.STOPPING) {
            return true;
        }
        return switch (source) {
            case SETUP -> target == SessionState.READY;
            case READY -> target == SessionState.LIVE;
            case LIVE -> target == SessionState.DEGRADED || target == SessionState.SHIELDING;
            case DEGRADED -> target == SessionState.LIVE || target == SessionState.SHIELDING;
            case SHIELDING -> target == SessionState.LIVE;
            case STOPPING -> target == SessionState.ENDED;
            case ENDED, FAILED -> false;
        };
    }

    public String sessionId() {
        return sessionId;
    }

    public SessionState state() {
        return state;
    }

    public Optional<FrameTimestamp> startedAt() {
        return Optional.ofNullable(startedAt);
    }

    public OptionalLong hostTrackId() {
        return hostTrackId == null ? OptionalLong.empty() : OptionalLong.of(hostTrackId);
    }

    public Set<String> watchlist() {
        return watchlist;
    }

    public List<NormalizedRect> privacyZones() {
        return privacyZones;
    }
}
