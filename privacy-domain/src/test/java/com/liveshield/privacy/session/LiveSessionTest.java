package com.liveshield.privacy.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class LiveSessionTest {
    @Test
    public void sessionTransitionsAlongLegalHappyPathWithoutMutatingPriorSnapshots() {
        LiveSession setup = LiveSession.setup("session-1", Set.of(), List.of());
        LiveSession ready = setup.withHostTrack(42).transitionTo(
                SessionState.READY, FrameTimestamp.ofNanos(10));
        LiveSession live = ready.transitionTo(SessionState.LIVE, FrameTimestamp.ofNanos(20));
        LiveSession stopping = live.transitionTo(
                SessionState.STOPPING, FrameTimestamp.ofNanos(30));
        LiveSession ended = stopping.transitionTo(
                SessionState.ENDED, FrameTimestamp.ofNanos(40));

        assertEquals(SessionState.SETUP, setup.state());
        assertFalse(setup.hostTrackId().isPresent());
        assertEquals(SessionState.LIVE, live.state());
        assertEquals(20, live.startedAt().orElseThrow().nanos());
        assertEquals(SessionState.ENDED, ended.state());
    }

    @Test
    public void illegalTransitionsAndReadyWithoutHostAreRejected() {
        LiveSession session = LiveSession.setup("session-2", Set.of(), List.of());

        assertThrows(IllegalStateException.class,
                () -> session.transitionTo(SessionState.LIVE, FrameTimestamp.ofNanos(1)));
        assertThrows(IllegalStateException.class,
                () -> session.transitionTo(SessionState.READY, FrameTimestamp.ofNanos(1)));
    }

    @Test
    public void sessionConfigurationIsDefensivelyCopied() {
        Set<String> watchlist = new LinkedHashSet<>();
        watchlist.add("fictional name");
        List<NormalizedRect> zones = new ArrayList<>();
        zones.add(new NormalizedRect(0.1, 0.1, 0.4, 0.4));

        LiveSession session = LiveSession.setup("session-3", watchlist, zones);
        watchlist.clear();
        zones.clear();

        assertEquals(Set.of("fictional name"), session.watchlist());
        assertEquals(1, session.privacyZones().size());
        assertThrows(UnsupportedOperationException.class,
                () -> session.watchlist().add("another"));
    }

    @Test
    public void healthRejectsNegativeMetricsAndCopiesLaneAges() {
        assertThrows(IllegalArgumentException.class,
                () -> SessionHealth.builder(SessionState.LIVE).rawQueueDepth(-1).build());

        SessionHealth health = SessionHealth.builder(SessionState.DEGRADED)
                .latestDecisionAgeNanos(5)
                .detectorLaneAgeNanos(com.liveshield.privacy.model.DetectorLane.FACE, 9)
                .thermalState(SessionHealth.ThermalState.WARNING)
                .build();

        assertEquals(Long.valueOf(9),
                health.detectorLaneAgesNanos().get(
                        com.liveshield.privacy.model.DetectorLane.FACE));
        assertTrue(health.lastFailureCode().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> health.detectorLaneAgesNanos().clear());
    }
}
