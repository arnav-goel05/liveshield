package com.liveshield.privacy.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.liveshield.privacy.model.ConfidenceClass;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.model.ProtectionAction;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public final class PriorityTwoPolicyTest {
    private static final NormalizedRect FULL_ZONE =
            new NormalizedRect(0.20, 0.20, 0.60, 0.60);
    private PriorityTwoPolicy policy;

    @Before
    public void setUp() {
        policy = new PriorityTwoPolicy(new SensitiveFindingPolicy(
                new SensitiveFindingPolicy.Configuration(
                        Set.of(DetectorLane.TEXT, DetectorLane.BARCODE),
                        10, 30, 0.25, 8, 16, 16, 16)));
    }

    @Test
    public void automaticPatternsWatchlistAndBarcodeKeepExactProvenance() {
        List<ProtectedRegion> text = List.of(
                region(FindingCategory.AUTO_EMAIL, rect(0.05, 0.1, 0.15, 0.2)),
                region(FindingCategory.WATCHLIST_MATCH, rect(0.30, 0.1, 0.50, 0.2)));

        PriorityTwoPolicy.Result result = evaluate(
                100,
                List.of(snapshot(DetectorLane.TEXT, 100, text),
                        snapshot(DetectorLane.BARCODE, 100, List.of(region(
                                FindingCategory.AUTO_BARCODE,
                                rect(0.70, 0.1, 0.85, 0.25))))),
                configuration(List.of()),
                false);

        assertEquals(List.of(
                        FindingCategory.AUTO_BARCODE,
                        FindingCategory.AUTO_EMAIL,
                        FindingCategory.WATCHLIST_MATCH),
                result.regions().stream().map(ProtectedRegion::category).toList());
    }

    @Test
    public void ocrOverlapCannotReplaceOrNarrowOpaqueFullZone() {
        SessionPrivacyConfiguration configuration = configuration(List.of(FULL_ZONE));
        ProtectedRegion smallerOcr = region(
                FindingCategory.WATCHLIST_MATCH, rect(0.30, 0.30, 0.40, 0.40));

        PriorityTwoPolicy.Result result = evaluate(100, List.of(
                snapshot(DetectorLane.TEXT, 100, List.of(smallerOcr)),
                emptySnapshot(DetectorLane.BARCODE, 100)), configuration, false);

        ProtectedRegion zone = only(result, FindingCategory.PRIVACY_ZONE);
        assertEquals(List.of(FULL_ZONE), zone.bounds());
        assertEquals(ProtectionAction.OPAQUE, zone.action());
        assertEquals(List.of(smallerOcr.bounds().get(0)),
                only(result, FindingCategory.WATCHLIST_MATCH).bounds());
    }

    @Test
    public void activeZonePersistsThroughEmptyFreshOcrAndCarry() {
        SessionPrivacyConfiguration configuration = configuration(List.of(FULL_ZONE));

        PriorityTwoPolicy.Result fresh = evaluate(100, List.of(
                emptySnapshot(DetectorLane.TEXT, 100),
                emptySnapshot(DetectorLane.BARCODE, 100)), configuration, false);
        PriorityTwoPolicy.Result carried = evaluate(105, List.of(), configuration, false);

        assertEquals(List.of(FULL_ZONE),
                only(fresh, FindingCategory.PRIVACY_ZONE).bounds());
        assertEquals(List.of(FULL_ZONE),
                only(carried, FindingCategory.PRIVACY_ZONE).bounds());
    }

    @Test
    public void cameraChangeShieldsUntilCompleteZoneTransformIsVerified() {
        SessionPrivacyConfiguration configuration = configuration(List.of(FULL_ZONE));
        evaluate(100, List.of(
                emptySnapshot(DetectorLane.TEXT, 100),
                emptySnapshot(DetectorLane.BARCODE, 100)), configuration, false);

        configuration.markZoneTransformUnsafe(
                SessionPrivacyConfiguration.GeometryChange.CAMERA_CHANGE);
        PriorityTwoPolicy.Result unsafe = evaluate(
                101, List.of(), configuration, false);
        configuration.applyVerifiedZoneTransform(
                SessionPrivacyConfiguration.GeometryChange.CAMERA_CHANGE,
                new CoordinateTransform(new double[]{
                    0.5, 0.0, 0.1,
                    0.0, 0.5, 0.2,
                    0.0, 0.0, 1.0
                }));
        PriorityTwoPolicy.Result recovered = evaluate(102, List.of(), configuration, false);

        assertEquals(SensitiveFindingPolicy.Basis.SHIELD_REQUIRED, unsafe.basis());
        NormalizedRect transformed = only(
                recovered, FindingCategory.PRIVACY_ZONE).bounds().get(0);
        assertEquals(0.20, transformed.left(), 0.000_001);
        assertEquals(0.30, transformed.top(), 0.000_001);
        assertEquals(0.40, transformed.right(), 0.000_001);
        assertEquals(0.50, transformed.bottom(), 0.000_001);
    }

    @Test
    public void sceneChangeRequiresFreshTextAndBarcodeButKeepsZoneObligation() {
        SessionPrivacyConfiguration configuration = configuration(List.of(FULL_ZONE));
        evaluate(100, List.of(
                emptySnapshot(DetectorLane.TEXT, 100),
                emptySnapshot(DetectorLane.BARCODE, 100)), configuration, false);

        PriorityTwoPolicy.Result shield = evaluate(105, List.of(), configuration, true);
        PriorityTwoPolicy.Result fresh = evaluate(106, List.of(
                emptySnapshot(DetectorLane.TEXT, 106),
                emptySnapshot(DetectorLane.BARCODE, 106)), configuration, true);

        assertEquals(SensitiveFindingPolicy.Basis.SHIELD_REQUIRED, shield.basis());
        assertEquals(List.of(FULL_ZONE),
                only(fresh, FindingCategory.PRIVACY_ZONE).bounds());
    }

    @Test
    public void sameProvenanceOverlapsMergeDeterministicallyAcrossInputOrder() {
        ProtectedRegion first = region(
                FindingCategory.AUTO_EMAIL, rect(0.1, 0.1, 0.3, 0.3));
        ProtectedRegion second = region(
                FindingCategory.AUTO_EMAIL, rect(0.2, 0.2, 0.4, 0.4));

        PriorityTwoPolicy.Result result = evaluate(100, List.of(
                snapshot(DetectorLane.TEXT, 100, List.of(second, first)),
                emptySnapshot(DetectorLane.BARCODE, 100)),
                configuration(List.of()), false);

        assertEquals(1, result.regions().size());
        assertEquals(List.of(rect(0.1, 0.1, 0.4, 0.4)), result.regions().get(0).bounds());
    }

    @Test
    public void overlapAcrossDifferentProvenanceNeverErasesEitherProtection() {
        ProtectedRegion email = region(
                FindingCategory.AUTO_EMAIL, rect(0.1, 0.1, 0.4, 0.4));
        ProtectedRegion watchlist = region(
                FindingCategory.WATCHLIST_MATCH, rect(0.2, 0.2, 0.3, 0.3));

        PriorityTwoPolicy.Result result = evaluate(100, List.of(
                snapshot(DetectorLane.TEXT, 100, List.of(email, watchlist)),
                emptySnapshot(DetectorLane.BARCODE, 100)),
                configuration(List.of()), false);

        assertEquals(2, result.regions().size());
        assertTrue(result.regions().stream().anyMatch(region ->
                region.category() == FindingCategory.AUTO_EMAIL));
        assertTrue(result.regions().stream().anyMatch(region ->
                region.category() == FindingCategory.WATCHLIST_MATCH));
    }

    @Test
    public void resultAndPolicyRetainNoRecognizedPayloadTypes() {
        for (Class<?> type : List.of(PriorityTwoPolicy.class, PriorityTwoPolicy.Result.class)) {
            for (Field field : type.getDeclaredFields()) {
                assertFalse(field.getType() == String.class
                        || field.getType() == char[].class
                        || field.getType() == byte[].class
                        || field.getType() == ByteBuffer.class);
            }
        }
    }

    private PriorityTwoPolicy.Result evaluate(
            long timestamp,
            List<DetectorSnapshot> snapshots,
            SessionPrivacyConfiguration configuration,
            boolean sceneChanged) {
        return policy.evaluate(
                FrameTimestamp.ofNanos(timestamp), snapshots, configuration, sceneChanged);
    }

    private static DetectorSnapshot snapshot(
            DetectorLane lane, long timestamp, List<ProtectedRegion> regions) {
        return DetectorSnapshot.success(
                lane,
                FrameTimestamp.ofNanos(timestamp),
                FrameTimestamp.ofNanos(timestamp + 10),
                regions);
    }

    private static DetectorSnapshot emptySnapshot(DetectorLane lane, long timestamp) {
        return snapshot(lane, timestamp, List.of());
    }

    private static ProtectedRegion region(FindingCategory category, NormalizedRect bounds) {
        return new ProtectedRegion(
                category,
                List.of(bounds),
                ConfidenceClass.VALIDATED,
                ProtectionAction.MOSAIC);
    }

    private static NormalizedRect rect(
            double left, double top, double right, double bottom) {
        return new NormalizedRect(left, top, right, bottom);
    }

    private static SessionPrivacyConfiguration configuration(List<NormalizedRect> zones) {
        return new SessionPrivacyConfiguration(Set.of("private employer"), zones);
    }

    private static ProtectedRegion only(
            PriorityTwoPolicy.Result result, FindingCategory category) {
        return result.regions().stream()
                .filter(region -> region.category() == category)
                .findFirst()
                .orElseThrow();
    }
}
