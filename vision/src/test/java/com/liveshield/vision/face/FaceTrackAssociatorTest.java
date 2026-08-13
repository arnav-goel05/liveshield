package com.liveshield.vision.face;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.liveshield.privacy.model.ConfidenceClass;
import com.liveshield.privacy.model.DetectorObservation;
import com.liveshield.privacy.model.FaceTrackSnapshot;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.model.ProtectionAction;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;

/** Contract tests for conservative, non-biometric, session-only face association. */
public final class FaceTrackAssociatorTest {
    private static final long EXPIRY_NANOS = 200;
    private FaceTrackAssociator associator;

    @Before
    public void setUp() {
        associator = new FaceTrackAssociator(new FaceTrackAssociator.Configuration(
                EXPIRY_NANOS,
                0.10,
                0.35,
                2.0,
                0.30));
    }

    @Test
    public void enteringFaceGetsFreshProtectedSessionTrack() {
        FaceTrackAssociator.AssociationFrame frame = update(
                0, observation(rect(0.05, 0.20, 0.25, 0.50)));

        assertEquals(1, frame.tracks().size());
        FaceTrackSnapshot entered = frame.tracks().get(0);
        assertTrue(entered.trackId() >= 0);
        assertEquals(FaceTrackSnapshot.ConfidenceState.FRESH, entered.confidenceState());
        assertEquals(FaceTrackSnapshot.Policy.PROTECTED, entered.policy());
        assertTrue(frame.continuityLostTrackIds().isEmpty());
        assertTrue(frame.expiredTrackIds().isEmpty());
    }

    @Test
    public void shortGapKeepsSameProtectedTrackWithBoundedPrediction() {
        FaceTrackSnapshot entered = only(update(
                0, observation(rect(0.10, 0.20, 0.30, 0.50))));
        FaceTrackSnapshot moved = only(update(
                50, observation(rect(0.15, 0.20, 0.35, 0.50))));

        FaceTrackSnapshot predicted = only(update(100));

        assertEquals(entered.trackId(), moved.trackId());
        assertEquals(moved.trackId(), predicted.trackId());
        assertEquals(FaceTrackSnapshot.ConfidenceState.PREDICTED, predicted.confidenceState());
        assertEquals(FaceTrackSnapshot.Policy.PROTECTED, predicted.policy());
        assertTrue(predicted.bounds().left() >= moved.bounds().left());
        assertTrue(width(predicted.bounds()) >= width(moved.bounds()));
    }

    @Test
    public void crossingMarksBothTracksAmbiguousInsteadOfSwitchingIdentity() {
        List<FaceTrackSnapshot> entered = update(
                0,
                observation(rect(0.10, 0.20, 0.30, 0.50)),
                observation(rect(0.70, 0.20, 0.90, 0.50))).tracks();
        update(
                50,
                observation(rect(0.25, 0.20, 0.45, 0.50)),
                observation(rect(0.55, 0.20, 0.75, 0.50)));

        FaceTrackAssociator.AssociationFrame crossing = update(
                100,
                observation(rect(0.40, 0.20, 0.60, 0.50)),
                observation(rect(0.42, 0.20, 0.62, 0.50)));

        assertEquals(2, crossing.tracks().size());
        assertEquals(ids(entered), crossing.continuityLostTrackIds());
        for (FaceTrackSnapshot track : crossing.tracks()) {
            assertEquals(FaceTrackSnapshot.ConfidenceState.AMBIGUOUS, track.confidenceState());
            assertEquals(FaceTrackSnapshot.Policy.PROTECTED, track.policy());
        }
    }

    @Test
    public void mergeThenSplitCannotRestoreEitherOldIdentityAutomatically() {
        List<FaceTrackSnapshot> entered = update(
                0,
                observation(rect(0.15, 0.20, 0.35, 0.50)),
                observation(rect(0.65, 0.20, 0.85, 0.50))).tracks();
        FaceTrackAssociator.AssociationFrame merged = update(
                50, observation(rect(0.25, 0.18, 0.75, 0.52)));

        FaceTrackAssociator.AssociationFrame split = update(
                100,
                observation(rect(0.20, 0.20, 0.40, 0.50)),
                observation(rect(0.60, 0.20, 0.80, 0.50)));

        assertEquals(ids(entered), merged.continuityLostTrackIds());
        assertFalse(merged.tracks().isEmpty());
        assertProtectedAndNotFresh(merged.tracks());
        assertTrue(split.continuityLostTrackIds().containsAll(ids(entered)));
        assertProtectedAndNotFreshForIds(split.tracks(), ids(entered));
    }

    @Test
    public void reusedDetectorHintAtImpossibleLocationCannotTransferSessionTrack() {
        FaceTrackSnapshot original = only(update(
                0, hintedObservation(rect(0.05, 0.20, 0.25, 0.50), 7)));

        FaceTrackAssociator.AssociationFrame jumped = update(
                50, hintedObservation(rect(0.75, 0.20, 0.95, 0.50), 7));
        FaceTrackSnapshot replacement = freshAt(jumped.tracks(), 0.75);

        assertNotEquals(original.trackId(), replacement.trackId());
        assertTrue(jumped.continuityLostTrackIds().contains(original.trackId()));
        assertEquals(FaceTrackSnapshot.Policy.PROTECTED, replacement.policy());
    }

    @Test
    public void exactExpiryDropsTrackAndLaterDetectionGetsNewSessionTrack() {
        FaceTrackSnapshot entered = only(update(
                0, observation(rect(0.10, 0.20, 0.30, 0.50))));

        FaceTrackSnapshot beforeExpiry = only(update(EXPIRY_NANOS - 1));
        FaceTrackAssociator.AssociationFrame expired = update(EXPIRY_NANOS);
        FaceTrackSnapshot reentered = only(update(
                EXPIRY_NANOS + 1,
                observation(rect(0.10, 0.20, 0.30, 0.50))));

        assertEquals(entered.trackId(), beforeExpiry.trackId());
        assertEquals(FaceTrackSnapshot.ConfidenceState.PREDICTED,
                beforeExpiry.confidenceState());
        assertTrue(expired.tracks().isEmpty());
        assertEquals(Set.of(entered.trackId()), expired.expiredTrackIds());
        assertNotEquals(entered.trackId(), reentered.trackId());
        assertEquals(FaceTrackSnapshot.Policy.PROTECTED, reentered.policy());
    }

    @Test
    public void timestampsMustStrictlyIncrease() {
        update(10, observation(rect(0.10, 0.20, 0.30, 0.50)));

        assertThrows(IllegalArgumentException.class,
                () -> update(10, observation(rect(0.11, 0.20, 0.31, 0.50))));
        assertThrows(IllegalArgumentException.class, () -> update(9));
    }

    @Test
    public void configurationRejectsUnsafeOrNonFiniteBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> configuration(0, 0.10, 0.35, 2.0, 0.30));
        assertThrows(IllegalArgumentException.class,
                () -> configuration(200, 0.0, 0.35, 2.0, 0.30));
        assertThrows(IllegalArgumentException.class,
                () -> configuration(200, 0.10, Double.NaN, 2.0, 0.30));
        assertThrows(IllegalArgumentException.class,
                () -> configuration(200, 0.10, 0.35, 0.99, 0.30));
        assertThrows(IllegalArgumentException.class,
                () -> configuration(200, 0.10, 0.35, 2.0, 1.01));
    }

    @Test
    public void resetClearsTimestampTracksHintsAndIdentifierSequence() {
        FaceTrackSnapshot firstSession = only(update(
                100, hintedObservation(rect(0.10, 0.20, 0.30, 0.50), 9)));

        associator.resetSession();
        FaceTrackAssociator.AssociationFrame reset = update(
                0, hintedObservation(rect(0.70, 0.20, 0.90, 0.50), 9));

        FaceTrackSnapshot secondSession = only(reset);
        assertEquals(firstSession.trackId(), secondSession.trackId());
        assertEquals(FaceTrackSnapshot.ConfidenceState.FRESH,
                secondSession.confidenceState());
        assertTrue(reset.continuityLostTrackIds().isEmpty());
        assertTrue(reset.expiredTrackIds().isEmpty());
    }

    private FaceTrackAssociator.AssociationFrame update(
            long timestampNanos, DetectorObservation... observations) {
        return associator.update(
                FrameTimestamp.ofNanos(timestampNanos), List.of(observations));
    }

    private static FaceTrackSnapshot only(FaceTrackAssociator.AssociationFrame frame) {
        assertEquals(1, frame.tracks().size());
        return frame.tracks().get(0);
    }

    private static DetectorObservation observation(NormalizedRect bounds) {
        return DetectorObservation.withoutTrackingHint(region(bounds));
    }

    private static DetectorObservation hintedObservation(NormalizedRect bounds, long hint) {
        return DetectorObservation.withTrackingHint(region(bounds), hint);
    }

    private static ProtectedRegion region(NormalizedRect bounds) {
        return new ProtectedRegion(
                FindingCategory.FACE,
                List.of(bounds),
                ConfidenceClass.VALIDATED,
                ProtectionAction.OPAQUE);
    }

    private static NormalizedRect rect(
            double left, double top, double right, double bottom) {
        return new NormalizedRect(left, top, right, bottom);
    }

    private static FaceTrackAssociator.Configuration configuration(
            long expiry, double iou, double distance, double scale, double ambiguity) {
        return new FaceTrackAssociator.Configuration(
                expiry, iou, distance, scale, ambiguity);
    }

    private static double width(NormalizedRect bounds) {
        return bounds.right() - bounds.left();
    }

    private static Set<Long> ids(List<FaceTrackSnapshot> tracks) {
        return tracks.stream().map(FaceTrackSnapshot::trackId).collect(Collectors.toSet());
    }

    private static void assertProtectedAndNotFresh(List<FaceTrackSnapshot> tracks) {
        for (FaceTrackSnapshot track : tracks) {
            assertEquals(FaceTrackSnapshot.Policy.PROTECTED, track.policy());
            assertNotEquals(FaceTrackSnapshot.ConfidenceState.FRESH, track.confidenceState());
        }
    }

    private static void assertProtectedAndNotFreshForIds(
            List<FaceTrackSnapshot> tracks, Set<Long> oldIds) {
        for (FaceTrackSnapshot track : tracks) {
            assertEquals(FaceTrackSnapshot.Policy.PROTECTED, track.policy());
            if (oldIds.contains(track.trackId())) {
                assertNotEquals(FaceTrackSnapshot.ConfidenceState.FRESH,
                        track.confidenceState());
            }
        }
    }

    private static FaceTrackSnapshot freshAt(
            List<FaceTrackSnapshot> tracks, double minimumLeft) {
        for (FaceTrackSnapshot track : tracks) {
            if (track.bounds().left() >= minimumLeft
                    && track.confidenceState() == FaceTrackSnapshot.ConfidenceState.FRESH) {
                return track;
            }
        }
        throw new AssertionError("Expected a fresh protected replacement track");
    }
}
