package com.liveshield.privacy.model;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class PrivacyValueObjectsTest {
    @Test
    public void normalizedRectRejectsInvalidCoordinates() {
        assertThrows(IllegalArgumentException.class,
                () -> new NormalizedRect(-0.01, 0.0, 0.5, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new NormalizedRect(0.5, 0.0, 0.5, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new NormalizedRect(0.0, Double.NaN, 0.5, 0.5));
    }

    @Test
    public void protectedRegionDefensivelyCopiesBounds() {
        List<NormalizedRect> mutableBounds = new ArrayList<>();
        mutableBounds.add(new NormalizedRect(0.1, 0.2, 0.3, 0.4));

        ProtectedRegion region = new ProtectedRegion(
                FindingCategory.FACE,
                mutableBounds,
                ConfidenceClass.VALIDATED,
                ProtectionAction.MOSAIC);
        mutableBounds.clear();

        assertEquals(1, region.bounds().size());
        assertThrows(UnsupportedOperationException.class,
                () -> region.bounds().add(new NormalizedRect(0.5, 0.5, 0.6, 0.6)));
    }

    @Test
    public void detectorSnapshotIsImmutableAndSeparatesFailureFromFindings() {
        FrameTimestamp timestamp = FrameTimestamp.ofNanos(100);
        List<ProtectedRegion> mutableFindings = new ArrayList<>();
        mutableFindings.add(new ProtectedRegion(
                FindingCategory.AUTO_BARCODE,
                List.of(new NormalizedRect(0.1, 0.1, 0.2, 0.2)),
                ConfidenceClass.VALIDATED,
                ProtectionAction.OPAQUE));

        DetectorSnapshot snapshot = DetectorSnapshot.success(
                DetectorLane.BARCODE,
                timestamp,
                FrameTimestamp.ofNanos(200),
                mutableFindings);
        mutableFindings.clear();

        assertEquals(1, snapshot.findings().size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.findings().clear());
        DetectorSnapshot failure = DetectorSnapshot.failure(
                DetectorLane.BARCODE,
                timestamp,
                new TypedFailure(TypedFailure.Code.ANALYZER_ERROR, timestamp));
        assertTrue(failure.findings().isEmpty());
        assertTrue(failure.failure().isPresent());
    }

    @Test
    public void detectorObservationCarriesOnlyOptionalNonNegativeSessionHint() {
        ProtectedRegion region = new ProtectedRegion(
                FindingCategory.FACE,
                List.of(new NormalizedRect(0.1, 0.1, 0.2, 0.2)),
                ConfidenceClass.VALIDATED,
                ProtectionAction.MOSAIC);

        DetectorObservation tracked = DetectorObservation.withTrackingHint(region, 42);
        DetectorObservation untracked = DetectorObservation.withoutTrackingHint(region);

        assertEquals(region, tracked.region());
        assertEquals(42, tracked.detectorTrackingId().orElseThrow());
        assertTrue(untracked.detectorTrackingId().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> DetectorObservation.withTrackingHint(region, -1));
    }

    @Test
    public void observationSnapshotIsImmutableAndRetainsFindingsCompatibilityView() {
        ProtectedRegion region = new ProtectedRegion(
                FindingCategory.FACE,
                List.of(new NormalizedRect(0.1, 0.1, 0.2, 0.2)),
                ConfidenceClass.VALIDATED,
                ProtectionAction.MOSAIC);
        List<DetectorObservation> mutable = new ArrayList<>();
        mutable.add(DetectorObservation.withTrackingHint(region, 7));

        DetectorSnapshot snapshot = DetectorSnapshot.successWithObservations(
                DetectorLane.FACE,
                FrameTimestamp.ofNanos(100),
                FrameTimestamp.ofNanos(200),
                mutable);
        mutable.clear();

        assertEquals(List.of(region), snapshot.findings());
        assertEquals(7, snapshot.observations().get(0).detectorTrackingId().orElseThrow());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.observations().clear());
    }

    @Test
    public void coordinateTransformDefensivelyCopiesMatrix() {
        double[] matrix = {1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0};
        CoordinateTransform transform = new CoordinateTransform(matrix);
        matrix[0] = 99.0;
        double[] returned = transform.matrix();
        returned[4] = 99.0;

        assertEquals(1.0, transform.matrix()[0], 0.0);
        assertEquals(1.0, transform.matrix()[4], 0.0);
    }

    @Test
    public void coordinateTransformInverseRoundTripsAffineMapping() {
        CoordinateTransform transform = new CoordinateTransform(new double[]{
            0.5, 0.0, 0.25,
            0.0, 0.25, 0.50,
            0.0, 0.0, 1.0
        });

        double[] inverse = transform.inverse().matrix();

        assertArrayEquals(new double[]{
            2.0, 0.0, -0.5,
            0.0, 4.0, -2.0,
            0.0, 0.0, 1.0
        }, inverse, 1.0e-12);
        assertThrows(IllegalArgumentException.class, () -> new CoordinateTransform(new double[]{
            0.0, 0.0, 0.0,
            0.0, 0.0, 0.0,
            0.0, 0.0, 1.0
        }).inverse());
    }
}
