package com.liveshield.privacy.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.liveshield.privacy.model.FaceTrackSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedPoint;
import com.liveshield.privacy.model.NormalizedRect;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

/** Failing contract tests for explicit, non-transferable host visibility after continuity loss. */
public final class HostContinuityPolicyTest {
    private static final long MAX_FRESH_AGE_NANOS = 50;
    private static final NormalizedPoint TAP = new NormalizedPoint(0.25, 0.25);
    private DefaultHostSelectionController controller;

    @Before
    public void setUp() {
        controller = new DefaultHostSelectionController(MAX_FRESH_AGE_NANOS);
        explicitlySelect(7, 100);
    }

    @Test
    public void ambiguousSelectedTrackIsProtectedAndPermissionIsRevoked() {
        FaceTrackSnapshot ambiguous = track(
                7, 101, FaceTrackSnapshot.ConfidenceState.AMBIGUOUS);

        controller.reconcileHostContinuity(List.of(ambiguous), Set.of(7L), Set.of());

        assertFalse(controller.selectedTrackId().isPresent());
        assertEquals(FaceTrackSnapshot.Policy.PROTECTED, ambiguous.policy());
    }

    @Test
    public void expiredHostCannotTransferPermissionToFreshReplacement() {
        FaceTrackSnapshot replacement = track(
                8, 151, FaceTrackSnapshot.ConfidenceState.FRESH);

        controller.reconcileHostContinuity(List.of(replacement), Set.of(7L), Set.of(7L));

        assertFalse(controller.selectedTrackId().isPresent());
        assertEquals(FaceTrackSnapshot.Policy.PROTECTED, replacement.policy());
    }

    @Test
    public void sameIdentifierReturningFreshRemainsProtectedUntilExplicitReselection() {
        controller.reconcileHostContinuity(List.of(), Set.of(7L), Set.of(7L));
        FaceTrackSnapshot returned = track(
                7, 152, FaceTrackSnapshot.ConfidenceState.FRESH);

        controller.reconcileHostContinuity(List.of(returned), Set.of(), Set.of());

        assertFalse(controller.selectedTrackId().isPresent());
        assertEquals(FaceTrackSnapshot.Policy.PROTECTED, returned.policy());

        HostSelectionResult reselection = controller.selectHost(
                TAP, List.of(returned), FrameTimestamp.ofNanos(152));
        assertEquals(HostSelectionResult.Status.SELECTED, reselection.status());
        assertEquals(7L, controller.selectedTrackId().orElseThrow());
    }

    @Test
    public void ambiguousReplacementCannotBeExplicitlyReselected() {
        controller.reconcileHostContinuity(List.of(), Set.of(7L), Set.of(7L));
        FaceTrackSnapshot ambiguousReplacement = track(
                8, 152, FaceTrackSnapshot.ConfidenceState.AMBIGUOUS);

        HostSelectionResult result = controller.selectHost(
                TAP, List.of(ambiguousReplacement), FrameTimestamp.ofNanos(152));

        assertEquals(HostSelectionResult.Status.STALE_FACE, result.status());
        assertFalse(controller.selectedTrackId().isPresent());
        assertEquals(FaceTrackSnapshot.Policy.PROTECTED, ambiguousReplacement.policy());
    }

    @Test
    public void unrelatedContinuityLossDoesNotRevokeFreshSelectedHost() {
        FaceTrackSnapshot host = track(7, 101, FaceTrackSnapshot.ConfidenceState.FRESH);
        FaceTrackSnapshot bystander = track(8, 101, FaceTrackSnapshot.ConfidenceState.AMBIGUOUS);

        controller.reconcileHostContinuity(List.of(host, bystander), Set.of(8L), Set.of());

        assertTrue(controller.selectedTrackId().isPresent());
        assertEquals(7L, controller.selectedTrackId().orElseThrow());
        assertEquals(FaceTrackSnapshot.Policy.PROTECTED, bystander.policy());
    }

    private void explicitlySelect(long trackId, long timestampNanos) {
        HostSelectionResult result = controller.selectHost(
                TAP,
                List.of(track(trackId, timestampNanos, FaceTrackSnapshot.ConfidenceState.FRESH)),
                FrameTimestamp.ofNanos(timestampNanos));
        assertEquals(HostSelectionResult.Status.SELECTED, result.status());
    }

    private static FaceTrackSnapshot track(
            long trackId, long lastDetectedNanos, FaceTrackSnapshot.ConfidenceState confidence) {
        return new FaceTrackSnapshot(
                trackId,
                new NormalizedRect(0.1, 0.1, 0.4, 0.4),
                FrameTimestamp.ofNanos(lastDetectedNanos),
                confidence,
                FaceTrackSnapshot.Policy.PROTECTED);
    }
}
