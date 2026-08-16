package com.liveshield.privacy.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.liveshield.privacy.model.ConfidenceClass;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.model.ProtectionAction;
import com.liveshield.privacy.model.TypedFailure;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public final class SensitiveFindingPolicyTest {
    private SensitiveFindingPolicy policy;

    @Before
    public void setUp() {
        policy = new SensitiveFindingPolicy(configuration());
    }

    @Test
    public void freshIndependentLanesCombineWithProvenance() {
        SensitiveFindingPolicy.Result result = evaluate(
                100,
                List.of(
                        snapshot(DetectorLane.TEXT, 100, FindingCategory.WATCHLIST_MATCH),
                        snapshot(DetectorLane.BARCODE, 100, FindingCategory.AUTO_BARCODE)),
                false);

        assertEquals(SensitiveFindingPolicy.Basis.FRESH, result.basis());
        assertEquals(List.of(FindingCategory.WATCHLIST_MATCH, FindingCategory.AUTO_BARCODE),
                result.regions().stream().map(ProtectedRegion::category).toList());
    }

    @Test
    public void oneFreshLaneDoesNotRefreshOtherLaneCarryAge() {
        evaluate(100, List.of(
                snapshot(DetectorLane.TEXT, 100, FindingCategory.WATCHLIST_MATCH),
                snapshot(DetectorLane.BARCODE, 100, FindingCategory.AUTO_BARCODE)), false);

        SensitiveFindingPolicy.Result carried = evaluate(110,
                List.of(snapshot(DetectorLane.TEXT, 110, FindingCategory.WATCHLIST_MATCH)), false);
        SensitiveFindingPolicy.Result stale = evaluate(141,
                List.of(snapshot(DetectorLane.TEXT, 141, FindingCategory.WATCHLIST_MATCH)), false);

        assertEquals(SensitiveFindingPolicy.Basis.CARRIED, carried.basis());
        assertEquals(SensitiveFindingPolicy.Basis.SHIELD_REQUIRED, stale.basis());
    }

    @Test
    public void laneCarriesThenExpandsThenShieldsAtExactAges() {
        evaluate(100, freshBoth(100), false);

        SensitiveFindingPolicy.Result carried = evaluate(110, List.of(), false);
        SensitiveFindingPolicy.Result expanded = evaluate(120, List.of(), false);
        SensitiveFindingPolicy.Result shield = evaluate(131, List.of(), false);

        assertEquals(SensitiveFindingPolicy.Basis.CARRIED, carried.basis());
        assertEquals(SensitiveFindingPolicy.Basis.EXPANDED, expanded.basis());
        assertEquals(ConfidenceClass.UNCERTAIN,
                expanded.regions().get(0).confidenceClass());
        assertTrue(expanded.regions().get(0).bounds().get(0).right() > 0.3);
        assertEquals(SensitiveFindingPolicy.Basis.SHIELD_REQUIRED, shield.basis());
    }

    @Test
    public void delayedFreshAssessmentGetsFullCarryWindowAfterAcceptance() {
        DetectorSnapshot delayedText = DetectorSnapshot.success(
                DetectorLane.TEXT,
                FrameTimestamp.ofNanos(100),
                FrameTimestamp.ofNanos(160),
                List.of(region(FindingCategory.WATCHLIST_MATCH)));
        DetectorSnapshot delayedBarcode = DetectorSnapshot.success(
                DetectorLane.BARCODE,
                FrameTimestamp.ofNanos(100),
                FrameTimestamp.ofNanos(160),
                List.of(region(FindingCategory.AUTO_BARCODE)));

        assertEquals(SensitiveFindingPolicy.Basis.FRESH,
                evaluate(150, List.of(delayedText, delayedBarcode), false).basis());
        assertEquals(SensitiveFindingPolicy.Basis.CARRIED,
                evaluate(160, List.of(), false).basis());
        assertEquals(SensitiveFindingPolicy.Basis.EXPANDED,
                evaluate(180, List.of(), false).basis());
        assertEquals(SensitiveFindingPolicy.Basis.SHIELD_REQUIRED,
                evaluate(181, List.of(), false).basis());
    }

    @Test
    public void expiredSnapshotCannotBeReacceptedUntilANewerAssessmentArrives() {
        List<DetectorSnapshot> initial = List.of(
                snapshot(DetectorLane.TEXT, 100, FindingCategory.WATCHLIST_MATCH),
                snapshot(DetectorLane.BARCODE, 100, FindingCategory.AUTO_BARCODE));
        evaluate(100, initial, false);

        assertEquals(SensitiveFindingPolicy.Basis.SHIELD_REQUIRED,
                evaluate(131, initial, false).basis());
        assertEquals(SensitiveFindingPolicy.Basis.SHIELD_REQUIRED,
                evaluate(132, initial, false).basis());

        List<DetectorSnapshot> replacement = List.of(
                snapshot(DetectorLane.TEXT, 133, FindingCategory.WATCHLIST_MATCH),
                snapshot(DetectorLane.BARCODE, 133, FindingCategory.AUTO_BARCODE));
        assertEquals(SensitiveFindingPolicy.Basis.FRESH,
                evaluate(133, replacement, false).basis());
    }

    @Test
    public void changedSceneInvalidatesCarryUntilExactFrameFreshAssessments() {
        evaluate(100, freshBoth(100), false);

        SensitiveFindingPolicy.Result invalidated = evaluate(105, List.of(), true);
        SensitiveFindingPolicy.Result oldButNominallyFresh = evaluate(
                106, freshBoth(105), true);
        SensitiveFindingPolicy.Result recovered = evaluate(107, freshBoth(107), true);

        assertEquals(SensitiveFindingPolicy.Basis.SHIELD_REQUIRED, invalidated.basis());
        assertEquals(SensitiveFindingPolicy.Basis.SHIELD_REQUIRED,
                oldButNominallyFresh.basis());
        assertEquals(SensitiveFindingPolicy.Basis.FRESH, recovered.basis());
    }

    @Test
    public void detectorFailureCarriesPriorProtectionButCannotExtendItsAge() {
        evaluate(100, freshBoth(100), false);
        TypedFailure failure = new TypedFailure(
                TypedFailure.Code.ANALYZER_ERROR, FrameTimestamp.ofNanos(105));

        SensitiveFindingPolicy.Result failed = evaluate(105, List.of(
                DetectorSnapshot.failure(
                        DetectorLane.TEXT, FrameTimestamp.ofNanos(105), failure)), false);
        SensitiveFindingPolicy.Result afterFailure = evaluate(106,
                List.of(snapshot(
                        DetectorLane.BARCODE, 106, FindingCategory.AUTO_BARCODE)), false);
        SensitiveFindingPolicy.Result expired = evaluate(131, List.of(), false);

        assertEquals(SensitiveFindingPolicy.Basis.CARRIED, failed.basis());
        assertEquals(SensitiveFindingPolicy.Basis.CARRIED, afterFailure.basis());
        assertEquals(SensitiveFindingPolicy.Basis.SHIELD_REQUIRED, expired.basis());
    }

    @Test
    public void wrongLaneProvenanceFailsPrivate() {
        SensitiveFindingPolicy.Result result = evaluate(100, List.of(
                snapshot(DetectorLane.TEXT, 100, FindingCategory.AUTO_BARCODE),
                snapshot(DetectorLane.BARCODE, 100, FindingCategory.AUTO_BARCODE)), false);

        assertEquals(SensitiveFindingPolicy.Basis.SHIELD_REQUIRED, result.basis());
    }

    @Test
    public void regionAndSnapshotBoundsFailPrivateAndClearCarry() {
        List<ProtectedRegion> tooMany = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            tooMany.add(region(FindingCategory.WATCHLIST_MATCH));
        }
        DetectorSnapshot overflow = DetectorSnapshot.success(
                DetectorLane.TEXT,
                FrameTimestamp.ofNanos(100),
                FrameTimestamp.ofNanos(100),
                tooMany);

        assertEquals(SensitiveFindingPolicy.Basis.SHIELD_REQUIRED,
                evaluate(100, List.of(overflow,
                        snapshot(DetectorLane.BARCODE, 100,
                                FindingCategory.AUTO_BARCODE)), false).basis());
        assertEquals(SensitiveFindingPolicy.Basis.SHIELD_REQUIRED,
                evaluate(101, List.of(), false).basis());
        assertEquals(SensitiveFindingPolicy.Basis.SHIELD_REQUIRED,
                evaluate(102, List.of(), false).basis());
    }

    @Test
    public void emptyFreshAssessmentsAreValidButEventuallyBecomeStale() {
        List<DetectorSnapshot> empty = List.of(
                emptySnapshot(DetectorLane.TEXT, 100),
                emptySnapshot(DetectorLane.BARCODE, 100));

        SensitiveFindingPolicy.Result fresh = evaluate(100, empty, false);
        SensitiveFindingPolicy.Result stale = evaluate(131, List.of(), false);

        assertEquals(SensitiveFindingPolicy.Basis.FRESH, fresh.basis());
        assertTrue(fresh.regions().isEmpty());
        assertEquals(SensitiveFindingPolicy.Basis.SHIELD_REQUIRED, stale.basis());
    }

    @Test
    public void resetPreventsCrossSessionCarry() {
        evaluate(100, freshBoth(100), false);

        policy.reset();

        assertEquals(SensitiveFindingPolicy.Basis.SHIELD_REQUIRED,
                evaluate(101, List.of(), false).basis());
    }

    @Test
    public void resultAndStateCannotRetainRecognizedPayloadTypes() {
        for (Class<?> type : List.of(
                SensitiveFindingPolicy.class,
                SensitiveFindingPolicy.Result.class)) {
            for (Field field : type.getDeclaredFields()) {
                assertTrue(field.getType() != String.class
                        && field.getType() != char[].class
                        && field.getType() != byte[].class
                        && field.getType() != ByteBuffer.class);
            }
        }
        SensitiveFindingPolicy.Result result = evaluate(100, freshBoth(100), false);
        assertThrows(UnsupportedOperationException.class, result.regions()::clear);
    }

    @Test
    public void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SensitiveFindingPolicy.Configuration(
                Set.of(DetectorLane.FACE), 10, 20, 0.25, 3, 3, 6, 8));
        assertThrows(IllegalArgumentException.class, () -> new SensitiveFindingPolicy.Configuration(
                Set.of(DetectorLane.TEXT), 20, 10, 0.25, 3, 3, 6, 8));
    }

    private SensitiveFindingPolicy.Result evaluate(
            long timestamp, List<DetectorSnapshot> snapshots, boolean sceneChanged) {
        return policy.evaluate(FrameTimestamp.ofNanos(timestamp), snapshots, sceneChanged);
    }

    private static List<DetectorSnapshot> freshBoth(long timestamp) {
        return List.of(
                snapshot(DetectorLane.TEXT, timestamp, FindingCategory.WATCHLIST_MATCH),
                snapshot(DetectorLane.BARCODE, timestamp, FindingCategory.AUTO_BARCODE));
    }

    private static DetectorSnapshot snapshot(
            DetectorLane lane, long timestamp, FindingCategory category) {
        return DetectorSnapshot.success(
                lane,
                FrameTimestamp.ofNanos(timestamp),
                FrameTimestamp.ofNanos(timestamp + 10),
                List.of(region(category)));
    }

    private static DetectorSnapshot emptySnapshot(DetectorLane lane, long timestamp) {
        return DetectorSnapshot.success(
                lane,
                FrameTimestamp.ofNanos(timestamp),
                FrameTimestamp.ofNanos(timestamp + 10),
                List.of());
    }

    private static ProtectedRegion region(FindingCategory category) {
        return new ProtectedRegion(
                category,
                List.of(new NormalizedRect(0.1, 0.1, 0.3, 0.3)),
                ConfidenceClass.VALIDATED,
                ProtectionAction.MOSAIC);
    }

    private static SensitiveFindingPolicy.Configuration configuration() {
        return new SensitiveFindingPolicy.Configuration(
                Set.of(DetectorLane.TEXT, DetectorLane.BARCODE),
                10,
                30,
                0.25,
                3,
                6,
                6,
                8);
    }
}
