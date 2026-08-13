package com.liveshield.video.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.liveshield.privacy.host.DefaultHostSelectionController;
import com.liveshield.privacy.host.HostSelectionResult;
import com.liveshield.privacy.model.ConfidenceClass;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorObservation;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FaceTrackSnapshot;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.NormalizedPoint;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.model.ProtectionAction;
import com.liveshield.privacy.model.TypedFailure;
import java.util.List;
import java.util.OptionalLong;
import org.junit.Test;

public final class FaceAnalysisCoordinatorTest {
    private static final FrameTimestamp FRAME_ONE = FrameTimestamp.ofNanos(100);
    private static final FrameTimestamp FRAME_TWO = FrameTimestamp.ofNanos(200);
    private static final NormalizedRect FIRST_BOUNDS =
            new NormalizedRect(0.1, 0.1, 0.3, 0.4);
    private static final NormalizedRect SECOND_BOUNDS =
            new NormalizedRect(0.6, 0.2, 0.9, 0.7);
    private static final NormalizedRect MOVED_FIRST_BOUNDS =
            new NormalizedRect(0.14, 0.1, 0.34, 0.4);

    @Test
    public void stableDetectorHintMapsToOneEphemeralSessionTrack() {
        FaceAnalysisCoordinator coordinator = new FaceAnalysisCoordinator();

        FaceAnalysisCoordinator.FaceFrameState first = coordinator.accept(
                success(FRAME_ONE, observation(FIRST_BOUNDS, 41L)), OptionalLong.empty());
        FaceAnalysisCoordinator.FaceFrameState second = coordinator.accept(
                success(FRAME_TWO, observation(MOVED_FIRST_BOUNDS, 41L)), OptionalLong.empty());

        assertEquals(first.tracks().get(0).trackId(), second.tracks().get(0).trackId());
        assertEquals(MOVED_FIRST_BOUNDS, second.tracks().get(0).bounds());
        assertEquals(FRAME_TWO, second.tracks().get(0).lastDetected());
    }

    @Test
    public void everyNonHostAndUnhintedFaceDefaultsToProtected() {
        FaceAnalysisCoordinator coordinator = new FaceAnalysisCoordinator();
        FaceAnalysisCoordinator.FaceFrameState first = coordinator.accept(
                success(
                        FRAME_ONE,
                        observation(FIRST_BOUNDS, 7L),
                        DetectorObservation.withoutTrackingHint(region(SECOND_BOUNDS))),
                OptionalLong.empty());
        long hostTrackId = first.tracks().get(0).trackId();

        FaceAnalysisCoordinator.FaceFrameState selected = coordinator.accept(
                success(
                        FRAME_TWO,
                        observation(FIRST_BOUNDS, 7L),
                        DetectorObservation.withoutTrackingHint(region(SECOND_BOUNDS))),
                OptionalLong.of(hostTrackId));

        assertEquals(FaceTrackSnapshot.Policy.HOST_VISIBLE, selected.tracks().get(0).policy());
        assertEquals(FaceTrackSnapshot.Policy.PROTECTED, selected.tracks().get(1).policy());
        assertEquals(List.of(region(SECOND_BOUNDS)), selected.protectedRegions());
        assertFalse(selected.fullShieldRequired());
    }

    @Test
    public void selectedTrackMissingNeverTransfersPermission() {
        FaceAnalysisCoordinator coordinator = new FaceAnalysisCoordinator();
        FaceAnalysisCoordinator.FaceFrameState initial = coordinator.accept(
                success(FRAME_ONE, observation(FIRST_BOUNDS, 9L)), OptionalLong.empty());
        long selected = initial.tracks().get(0).trackId();

        FaceAnalysisCoordinator.FaceFrameState replacement = coordinator.accept(
                success(FRAME_TWO, observation(SECOND_BOUNDS, 10L)), OptionalLong.of(selected));

        assertTrue(replacement.hostContinuityLost());
        assertEquals(2, replacement.tracks().size());
        assertEquals(2, replacement.protectedRegions().size());
        for (FaceTrackSnapshot track : replacement.tracks()) {
            assertEquals(FaceTrackSnapshot.Policy.PROTECTED, track.policy());
        }
    }

    @Test
    public void failedOrOutOfOrderSnapshotRequiresFullShieldAndDoesNotAdvanceState() {
        FaceAnalysisCoordinator coordinator = new FaceAnalysisCoordinator();
        coordinator.accept(
                success(FRAME_TWO, observation(FIRST_BOUNDS, 3L)), OptionalLong.empty());

        FaceAnalysisCoordinator.FaceFrameState outOfOrder = coordinator.accept(
                success(FRAME_ONE, observation(SECOND_BOUNDS, 3L)), OptionalLong.empty());
        DetectorSnapshot failure = DetectorSnapshot.failure(
                DetectorLane.FACE,
                FrameTimestamp.ofNanos(300),
                new TypedFailure(TypedFailure.Code.ANALYZER_ERROR, FrameTimestamp.ofNanos(300)));
        FaceAnalysisCoordinator.FaceFrameState failed =
                coordinator.accept(failure, OptionalLong.empty());

        assertTrue(outOfOrder.fullShieldRequired());
        assertTrue(failed.fullShieldRequired());
        assertTrue(outOfOrder.tracks().isEmpty());
        assertTrue(failed.tracks().isEmpty());
    }

    @Test
    public void resetMakesDetectorHintsSessionLocal() {
        FaceAnalysisCoordinator coordinator = new FaceAnalysisCoordinator();
        long first = coordinator.accept(
                success(FRAME_ONE, observation(FIRST_BOUNDS, 5L)), OptionalLong.empty())
                .tracks().get(0).trackId();

        coordinator.resetSession();
        FaceAnalysisCoordinator.FaceFrameState afterReset = coordinator.accept(
                success(FRAME_TWO, observation(SECOND_BOUNDS, 5L)), OptionalLong.empty());

        assertEquals(first, afterReset.tracks().get(0).trackId());
        assertEquals(SECOND_BOUNDS, afterReset.tracks().get(0).bounds());
        assertEquals(FaceTrackSnapshot.ConfidenceState.FRESH,
                afterReset.tracks().get(0).confidenceState());
        assertFalse(afterReset.hostContinuityLost());
    }

    @Test
    public void briefMissingObservationProducesProtectedBoundedPrediction() {
        FaceAnalysisCoordinator coordinator = coordinator(200);
        FaceAnalysisCoordinator.FaceFrameState entered = coordinator.accept(
                success(at(0), observation(FIRST_BOUNDS, 7L)), OptionalLong.empty());
        long trackId = entered.tracks().get(0).trackId();
        coordinator.accept(
                success(at(50), observation(MOVED_FIRST_BOUNDS, 7L)), OptionalLong.empty());

        FaceAnalysisCoordinator.FaceFrameState gap = coordinator.accept(
                success(at(100)), OptionalLong.of(trackId));

        assertFalse(gap.fullShieldRequired());
        assertTrue(gap.hostContinuityLost());
        assertEquals(1, gap.tracks().size());
        assertEquals(FaceTrackSnapshot.ConfidenceState.PREDICTED,
                gap.tracks().get(0).confidenceState());
        assertEquals(FaceTrackSnapshot.Policy.PROTECTED, gap.tracks().get(0).policy());
        assertEquals(1, gap.protectedRegions().size());
    }

    @Test
    public void crossingRevokesHostAndProtectsEveryAmbiguousTrack() {
        FaceAnalysisCoordinator coordinator = coordinator(300);
        FaceAnalysisCoordinator.FaceFrameState entered = coordinator.accept(
                success(
                        at(0),
                        observation(new NormalizedRect(0.10, 0.20, 0.30, 0.50), 1L),
                        observation(new NormalizedRect(0.70, 0.20, 0.90, 0.50), 2L)),
                OptionalLong.empty());
        long selected = entered.tracks().get(0).trackId();
        coordinator.accept(
                success(
                        at(50),
                        observation(new NormalizedRect(0.25, 0.20, 0.45, 0.50), 1L),
                        observation(new NormalizedRect(0.55, 0.20, 0.75, 0.50), 2L)),
                OptionalLong.of(selected));

        FaceAnalysisCoordinator.FaceFrameState crossing = coordinator.accept(
                success(
                        at(100),
                        observation(new NormalizedRect(0.40, 0.20, 0.60, 0.50), 1L),
                        observation(new NormalizedRect(0.42, 0.20, 0.62, 0.50), 2L)),
                OptionalLong.of(selected));

        assertTrue(crossing.hostContinuityLost());
        assertEquals(2, crossing.protectedRegions().size());
        for (FaceTrackSnapshot track : crossing.tracks()) {
            assertEquals(FaceTrackSnapshot.ConfidenceState.AMBIGUOUS,
                    track.confidenceState());
            assertEquals(FaceTrackSnapshot.Policy.PROTECTED, track.policy());
        }
    }

    @Test
    public void mergeSplitRevokesControllerPermissionAndKeepsAllTracksProtected() {
        FaceAnalysisCoordinator coordinator = coordinator(300);
        DefaultHostSelectionController host = new DefaultHostSelectionController(50);
        FaceAnalysisCoordinator.FaceFrameState entered = coordinator.accept(
                success(
                        at(0),
                        observation(new NormalizedRect(0.15, 0.20, 0.35, 0.50), 1L),
                        observation(new NormalizedRect(0.65, 0.20, 0.85, 0.50), 2L)),
                host);
        HostSelectionResult selection = host.selectHost(
                new NormalizedPoint(0.25, 0.30), entered.tracks(), at(0));
        assertEquals(HostSelectionResult.Status.SELECTED, selection.status());

        FaceAnalysisCoordinator.FaceFrameState merged = coordinator.accept(
                success(at(50), observation(
                        new NormalizedRect(0.25, 0.18, 0.75, 0.52), 1L)), host);
        FaceAnalysisCoordinator.FaceFrameState split = coordinator.accept(
                success(
                        at(100),
                        observation(new NormalizedRect(0.20, 0.20, 0.40, 0.50), 1L),
                        observation(new NormalizedRect(0.60, 0.20, 0.80, 0.50), 2L)),
                host);

        assertTrue(merged.hostContinuityLost());
        assertFalse(host.selectedTrackId().isPresent());
        assertAllProtected(merged.tracks());
        assertAllProtected(split.tracks());
    }

    @Test
    public void expiryDropsOldHostAndFreshReplacementNeverInheritsVisibility() {
        FaceAnalysisCoordinator coordinator = coordinator(100);
        FaceAnalysisCoordinator.FaceFrameState entered = coordinator.accept(
                success(at(0), observation(FIRST_BOUNDS, 4L)), OptionalLong.empty());
        long selected = entered.tracks().get(0).trackId();

        FaceAnalysisCoordinator.FaceFrameState replacement = coordinator.accept(
                success(at(100), observation(SECOND_BOUNDS, 5L)), OptionalLong.of(selected));

        assertTrue(replacement.hostContinuityLost());
        assertEquals(1, replacement.tracks().size());
        assertEquals(FaceTrackSnapshot.Policy.PROTECTED,
                replacement.tracks().get(0).policy());
        assertTrue(replacement.tracks().get(0).trackId() != selected);
    }

    @Test
    public void malformedOfflineObservationShieldsAndClearsAssociationState() {
        FaceAnalysisCoordinator coordinator = new FaceAnalysisCoordinator();
        coordinator.accept(
                success(FRAME_ONE, observation(FIRST_BOUNDS, 3L)), OptionalLong.empty());
        ProtectedRegion notFace = new ProtectedRegion(
                FindingCategory.AUTO_BARCODE,
                List.of(FIRST_BOUNDS),
                ConfidenceClass.VALIDATED,
                ProtectionAction.MOSAIC);

        FaceAnalysisCoordinator.FaceFrameState unsafe = coordinator.accept(
                DetectorSnapshot.success(
                        DetectorLane.FACE,
                        FRAME_TWO,
                        FRAME_TWO.plusNanos(100),
                        List.of(notFace)),
                OptionalLong.of(0));
        FaceAnalysisCoordinator.FaceFrameState recovered = coordinator.accept(
                success(at(300), observation(FIRST_BOUNDS, 3L)), OptionalLong.of(0));

        assertTrue(unsafe.fullShieldRequired());
        assertTrue(unsafe.hostContinuityLost());
        assertTrue(recovered.hostContinuityLost());
        assertEquals(FaceTrackSnapshot.Policy.PROTECTED,
                recovered.tracks().get(0).policy());
    }

    private static FaceAnalysisCoordinator coordinator(long expiryNanos) {
        return new FaceAnalysisCoordinator(
                new com.liveshield.vision.face.FaceTrackAssociator(
                        new com.liveshield.vision.face.FaceTrackAssociator.Configuration(
                                expiryNanos, 0.10, 0.35, 2.0, 0.30)),
                new com.liveshield.privacy.policy.FacePrivacyPolicy());
    }

    private static FrameTimestamp at(long nanos) {
        return FrameTimestamp.ofNanos(nanos);
    }

    private static void assertAllProtected(List<FaceTrackSnapshot> tracks) {
        assertFalse(tracks.isEmpty());
        for (FaceTrackSnapshot track : tracks) {
            assertEquals(FaceTrackSnapshot.Policy.PROTECTED, track.policy());
        }
    }

    private static DetectorSnapshot success(
            FrameTimestamp timestamp, DetectorObservation... observations) {
        return DetectorSnapshot.successWithObservations(
                DetectorLane.FACE,
                timestamp,
                timestamp.plusNanos(100),
                List.of(observations));
    }

    private static DetectorObservation observation(NormalizedRect bounds, long detectorId) {
        return DetectorObservation.withTrackingHint(region(bounds), detectorId);
    }

    private static ProtectedRegion region(NormalizedRect bounds) {
        return new ProtectedRegion(
                FindingCategory.FACE,
                List.of(bounds),
                ConfidenceClass.VALIDATED,
                ProtectionAction.MOSAIC);
    }
}
