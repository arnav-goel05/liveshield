package com.liveshield.privacy.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.liveshield.privacy.model.FaceTrackSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedPoint;
import com.liveshield.privacy.model.NormalizedRect;
import java.util.List;
import org.junit.Test;

public final class HostSelectionControllerTest {
    private static final long MAX_FRESH_AGE_NANOS = 50;
    private static final NormalizedPoint TAP = new NormalizedPoint(0.25, 0.25);

    @Test
    public void noFaceAtTapIsRejectedAndDoesNotGrantPermission() {
        DefaultHostSelectionController controller = controller();

        HostSelectionResult result = controller.selectHost(
                TAP, List.of(), FrameTimestamp.ofNanos(100));

        assertEquals(HostSelectionResult.Status.NO_FACE_AT_TAP, result.status());
        assertFalse(controller.selectedTrackId().isPresent());
    }

    @Test
    public void stalePredictedAndFutureTracksCannotBecomeHost() {
        DefaultHostSelectionController controller = controller();

        HostSelectionResult stale = controller.selectHost(
                TAP,
                List.of(track(1, 40, FaceTrackSnapshot.ConfidenceState.FRESH)),
                FrameTimestamp.ofNanos(100));
        HostSelectionResult predicted = controller.selectHost(
                TAP,
                List.of(track(2, 100, FaceTrackSnapshot.ConfidenceState.PREDICTED)),
                FrameTimestamp.ofNanos(100));
        HostSelectionResult future = controller.selectHost(
                TAP,
                List.of(track(3, 101, FaceTrackSnapshot.ConfidenceState.FRESH)),
                FrameTimestamp.ofNanos(100));

        assertEquals(HostSelectionResult.Status.STALE_FACE, stale.status());
        assertEquals(HostSelectionResult.Status.STALE_FACE, predicted.status());
        assertEquals(HostSelectionResult.Status.STALE_FACE, future.status());
        assertFalse(controller.selectedTrackId().isPresent());
    }

    @Test
    public void multipleFacesAtTapAreAmbiguousEvenWhenOneIsFresh() {
        DefaultHostSelectionController controller = controller();
        List<FaceTrackSnapshot> overlapping = List.of(
                track(1, 100, FaceTrackSnapshot.ConfidenceState.FRESH),
                track(2, 100, FaceTrackSnapshot.ConfidenceState.FRESH));

        HostSelectionResult result = controller.selectHost(
                TAP, overlapping, FrameTimestamp.ofNanos(100));

        assertEquals(HostSelectionResult.Status.AMBIGUOUS, result.status());
        assertFalse(controller.selectedTrackId().isPresent());
    }

    @Test
    public void explicitTapSelectsOnlyTheSingleFreshHitTrack() {
        DefaultHostSelectionController controller = controller();
        FaceTrackSnapshot outsideTap = new FaceTrackSnapshot(
                2,
                new NormalizedRect(0.6, 0.6, 0.8, 0.8),
                FrameTimestamp.ofNanos(100),
                FaceTrackSnapshot.ConfidenceState.FRESH,
                FaceTrackSnapshot.Policy.PROTECTED);

        HostSelectionResult result = controller.selectHost(
                TAP,
                List.of(track(1, 100, FaceTrackSnapshot.ConfidenceState.FRESH), outsideTap),
                FrameTimestamp.ofNanos(100));

        assertEquals(HostSelectionResult.Status.SELECTED, result.status());
        assertEquals(1, result.selectedTrackId().orElseThrow());
        assertEquals(1, controller.selectedTrackId().orElseThrow());
    }

    @Test
    public void rejectionRevokesPriorPermissionRatherThanSilentlyKeepingIt() {
        DefaultHostSelectionController controller = controller();
        controller.selectHost(
                TAP,
                List.of(track(1, 100, FaceTrackSnapshot.ConfidenceState.FRESH)),
                FrameTimestamp.ofNanos(100));

        controller.selectHost(TAP, List.of(), FrameTimestamp.ofNanos(101));

        assertFalse(controller.selectedTrackId().isPresent());
    }

    @Test
    public void explicitRevocationAndSessionResetClearPermission() {
        DefaultHostSelectionController controller = controller();
        controller.selectHost(
                TAP,
                List.of(track(1, 100, FaceTrackSnapshot.ConfidenceState.FRESH)),
                FrameTimestamp.ofNanos(100));

        controller.revokeSelection();
        assertFalse(controller.selectedTrackId().isPresent());

        controller.selectHost(
                TAP,
                List.of(track(2, 101, FaceTrackSnapshot.ConfidenceState.FRESH)),
                FrameTimestamp.ofNanos(101));
        controller.resetSession();
        assertFalse(controller.selectedTrackId().isPresent());
    }

    @Test
    public void constructorRejectsNegativeFreshnessWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> new DefaultHostSelectionController(-1));
    }

    private static DefaultHostSelectionController controller() {
        return new DefaultHostSelectionController(MAX_FRESH_AGE_NANOS);
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
