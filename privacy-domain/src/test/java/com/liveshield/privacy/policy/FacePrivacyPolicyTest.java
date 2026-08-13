package com.liveshield.privacy.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.liveshield.privacy.model.FaceTrackSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.Test;

public final class FacePrivacyPolicyTest {
    private final FacePrivacyPolicy policy = new FacePrivacyPolicy();

    @Test
    public void exactFreshSelectedTrackAloneBecomesVisible() {
        FacePrivacyPolicy.Result result = apply(
                List.of(fresh(7), fresh(8)), OptionalLong.of(7), Set.of(), Set.of());

        assertFalse(result.hostContinuityLost());
        assertEquals(FaceTrackSnapshot.Policy.HOST_VISIBLE, find(result, 7).policy());
        assertEquals(FaceTrackSnapshot.Policy.PROTECTED, find(result, 8).policy());
    }

    @Test
    public void noSelectionProtectsEveryFreshUnknownTrack() {
        FacePrivacyPolicy.Result result = apply(
                List.of(fresh(7), fresh(8)), OptionalLong.empty(), Set.of(), Set.of());

        assertFalse(result.hostContinuityLost());
        assertEquals(FaceTrackSnapshot.Policy.PROTECTED, find(result, 7).policy());
        assertEquals(FaceTrackSnapshot.Policy.PROTECTED, find(result, 8).policy());
    }

    @Test
    public void selectedPredictedAmbiguousAndExpiredTracksAreProtected() {
        for (FaceTrackSnapshot.ConfidenceState state : List.of(
                FaceTrackSnapshot.ConfidenceState.PREDICTED,
                FaceTrackSnapshot.ConfidenceState.AMBIGUOUS,
                FaceTrackSnapshot.ConfidenceState.EXPIRED)) {
            FacePrivacyPolicy.Result result = apply(
                    List.of(track(7, state)), OptionalLong.of(7), Set.of(), Set.of());

            assertTrue(result.hostContinuityLost());
            assertEquals(FaceTrackSnapshot.Policy.PROTECTED, find(result, 7).policy());
        }
    }

    @Test
    public void explicitContinuityLossProtectsEvenFreshSelectedTrack() {
        FacePrivacyPolicy.Result result = apply(
                List.of(fresh(7)), OptionalLong.of(7), Set.of(7L), Set.of());

        assertTrue(result.hostContinuityLost());
        assertEquals(FaceTrackSnapshot.Policy.PROTECTED, find(result, 7).policy());
    }

    @Test
    public void missingOrExpiredHostCannotTransferVisibilityToReplacement() {
        FacePrivacyPolicy.Result missing = apply(
                List.of(fresh(8)), OptionalLong.of(7), Set.of(7L), Set.of());
        FacePrivacyPolicy.Result expired = apply(
                List.of(fresh(8)), OptionalLong.of(7), Set.of(), Set.of(7L));

        assertTrue(missing.hostContinuityLost());
        assertTrue(expired.hostContinuityLost());
        assertEquals(FaceTrackSnapshot.Policy.PROTECTED, find(missing, 8).policy());
        assertEquals(FaceTrackSnapshot.Policy.PROTECTED, find(expired, 8).policy());
    }

    @Test
    public void duplicateSessionIdentifiersAreRejectedConservatively() {
        assertThrows(IllegalArgumentException.class, () -> apply(
                List.of(fresh(7), fresh(7)), OptionalLong.of(7), Set.of(), Set.of()));
    }

    private FacePrivacyPolicy.Result apply(
            List<FaceTrackSnapshot> tracks,
            OptionalLong selected,
            Set<Long> continuityLost,
            Set<Long> expired) {
        return policy.apply(tracks, selected, continuityLost, expired);
    }

    private static FaceTrackSnapshot fresh(long id) {
        return track(id, FaceTrackSnapshot.ConfidenceState.FRESH);
    }

    private static FaceTrackSnapshot track(
            long id, FaceTrackSnapshot.ConfidenceState confidence) {
        return new FaceTrackSnapshot(
                id,
                new NormalizedRect(0.1, 0.2, 0.3, 0.5),
                FrameTimestamp.ofNanos(100),
                confidence,
                FaceTrackSnapshot.Policy.PROTECTED);
    }

    private static FaceTrackSnapshot find(FacePrivacyPolicy.Result result, long id) {
        return result.tracks().stream()
                .filter(track -> track.trackId() == id)
                .findFirst()
                .orElseThrow();
    }
}
