package com.liveshield.fixtures;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import com.liveshield.fixtures.FaceAnnotationEvaluator.DecodedFaceObservation;
import com.liveshield.fixtures.FaceAnnotationEvaluator.DecodedOutputFrame;
import com.liveshield.fixtures.FaceAnnotationEvaluator.EpisodeAnnotation;
import com.liveshield.fixtures.FaceAnnotationEvaluator.Evaluation;
import com.liveshield.fixtures.FaceAnnotationEvaluator.FaceObject;
import com.liveshield.fixtures.FaceAnnotationEvaluator.FaceRole;
import com.liveshield.fixtures.FaceAnnotationEvaluator.FrameAnnotation;
import com.liveshield.fixtures.FaceAnnotationEvaluator.Limits;
import com.liveshield.fixtures.FaceAnnotationEvaluator.Point;
import com.liveshield.fixtures.FaceAnnotationEvaluator.Polygon;
import com.liveshield.fixtures.FaceAnnotationEvaluator.Rect;
import com.liveshield.fixtures.FaceAnnotationEvaluator.Transform;
import java.util.List;
import java.util.OptionalLong;
import org.junit.Test;

public final class FaceAnnotationEvaluatorTest {
    private static final String UNKNOWN_ID = "face-00112233";
    private static final String HOST_ID = "face-aabbccdd";
    private static final long FRAME_DURATION = 40;
    private static final Limits LIMITS = new Limits(10, 10, 4, 5);
    private static final double EPSILON = 1.0e-9;

    @Test
    public void mapsDecodedPtsAndProducesUnknownFaceMetricsWithoutHostDenominator() {
        EpisodeAnnotation annotation = new EpisodeAnnotation(
                "face-dev-01", FRAME_DURATION, List.of(
                        frame(0, unknown(0), host(0)),
                        frame(1, unknown(0), host(0)),
                        frame(2, unknown(0), host(0))));
        List<DecodedOutputFrame> decoded = List.of(
                decoded(2, true, 11),
                decoded(42, false, -1),
                decoded(82, true, 12));

        Evaluation result = FaceAnnotationEvaluator.evaluate(annotation, decoded, LIMITS);

        assertEquals(3, result.annotationFrameCount());
        assertEquals(3, result.matchedFrameCount());
        assertEquals(0, result.unmatchedDecodedFrameCount());
        assertEquals(2, result.maximumPresentationTimestampDeltaNs());
        assertEquals(1, result.perUnknownObject().size());
        FaceTrackingMetrics.EpisodeMetrics metrics =
                result.perUnknownObject().get(UNKNOWN_ID);
        assertEquals(3, metrics.positiveFrames());
        assertEquals(2, metrics.protectedFrames());
        assertEquals(2.0 / 3.0, metrics.temporalCoverage().orElseThrow(), EPSILON);
        assertEquals(1, metrics.longestUnprotectedGapFrames());
        assertEquals(FRAME_DURATION, metrics.longestUnprotectedGapNanos());
        assertEquals(1, metrics.fragmentationCount());
        assertEquals(1, metrics.idSwitchCount());
        assertEquals(1, result.summary().episodeCount());
    }

    @Test
    public void missingDecodedFrameIsConservativelyUnprotectedAndExtraPtsIsCounted() {
        EpisodeAnnotation annotation = new EpisodeAnnotation(
                "face-holdout-01", FRAME_DURATION,
                List.of(frame(0, unknown(0)), frame(1, unknown(0))));
        List<DecodedOutputFrame> decoded = List.of(
                decoded(0, true, 1),
                new DecodedOutputFrame(200, List.of()));

        Evaluation result = FaceAnnotationEvaluator.evaluate(annotation, decoded, LIMITS);

        assertEquals(1, result.matchedFrameCount());
        assertEquals(1, result.unmatchedDecodedFrameCount());
        FaceTrackingMetrics.EpisodeMetrics metrics =
                result.perUnknownObject().get(UNKNOWN_ID);
        assertEquals(2, metrics.positiveFrames());
        assertEquals(1, metrics.protectedFrames());
        assertEquals(1, metrics.longestUnprotectedGapFrames());
    }

    @Test
    public void malformedDegenerateRepeatedAndSelfIntersectingPolygonsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Polygon(List.of(point(0.1, 0.1), point(0.2, 0.2))));
        assertThrows(IllegalArgumentException.class,
                () -> new Polygon(List.of(
                        point(0.1, 0.1), point(0.2, 0.2), point(0.3, 0.3))));
        assertThrows(IllegalArgumentException.class,
                () -> new Polygon(List.of(
                        point(0.1, 0.1), point(0.8, 0.1),
                        point(0.8, 0.1), point(0.1, 0.8))));
        assertThrows(IllegalArgumentException.class,
                () -> new Polygon(List.of(
                        point(0.1, 0.1), point(0.8, 0.8),
                        point(0.1, 0.8), point(0.8, 0.1))));
        assertThrows(IllegalArgumentException.class, () -> point(-0.1, 0.5));
    }

    @Test
    public void invalidTransformCropRotationAndMatrixAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Rect(0.5, 0.1, 0.5, 0.8));
        assertThrows(IllegalArgumentException.class,
                () -> new Transform(45, false, crop(), identity()));
        assertThrows(IllegalArgumentException.class,
                () -> new Transform(0, false, crop(), List.of(
                        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)));
        assertThrows(IllegalArgumentException.class,
                () -> new Transform(0, false, crop(), List.of(
                        Double.NaN, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)));
    }

    @Test
    public void futureMissingAndChangingProtectableTimestampsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new FaceObject(
                        UNKNOWN_ID, FaceRole.UNKNOWN, polygon(), 1.0,
                        true, OptionalLong.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new FrameAnnotation(
                        0, 0, transform(), List.of(unknown(1))));

        EpisodeAnnotation changed = new EpisodeAnnotation(
                "face-dev-02", FRAME_DURATION,
                List.of(frame(1, unknown(0)), frame(2, unknown(FRAME_DURATION))));
        assertThrows(IllegalArgumentException.class,
                () -> FaceAnnotationEvaluator.evaluate(changed, List.of(), LIMITS));
    }

    @Test
    public void duplicateAndOutOfOrderFrameObjectAndPtsRecordsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new EpisodeAnnotation(
                        "face-dev-03", FRAME_DURATION,
                        List.of(frame(1, unknown(0)), frame(1, unknown(0)))));
        assertThrows(IllegalArgumentException.class,
                () -> new FrameAnnotation(
                        0, 0, transform(), List.of(unknown(0), unknown(0))));
        assertThrows(IllegalArgumentException.class,
                () -> new DecodedOutputFrame(0, List.of(
                        decodedFace(true, 1), decodedFace(false, 1))));
        EpisodeAnnotation valid = new EpisodeAnnotation(
                "face-dev-04", FRAME_DURATION, List.of(frame(0, unknown(0))));
        assertThrows(IllegalArgumentException.class,
                () -> FaceAnnotationEvaluator.evaluate(
                        valid,
                        List.of(decoded(3, true, 1), decoded(2, true, 1)),
                        LIMITS));
    }

    @Test
    public void identityLikeOrSensitiveIdentifiersAndUnsupportedRolesCannotEnterTool() {
        assertThrows(IllegalArgumentException.class,
                () -> new EpisodeAnnotation("alice@example.com", 40, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new FaceObject(
                        "participant-alice", FaceRole.UNKNOWN, polygon(), 1.0,
                        true, OptionalLong.of(0)));
        assertEquals(2, FaceRole.values().length);
    }

    @Test
    public void configuredBoundsRejectOversizedAnnotationDecodedAndObjectInputs() {
        EpisodeAnnotation twoFrames = new EpisodeAnnotation(
                "face-dev-05", FRAME_DURATION,
                List.of(frame(0, unknown(0)), frame(1, unknown(0))));
        Limits oneFrame = new Limits(1, 1, 1, 5);
        assertThrows(IllegalArgumentException.class,
                () -> FaceAnnotationEvaluator.evaluate(twoFrames, List.of(), oneFrame));

        EpisodeAnnotation tooManyObjects = new EpisodeAnnotation(
                "face-dev-06", FRAME_DURATION,
                List.of(frame(0, unknown(0), host(0))));
        assertThrows(IllegalArgumentException.class,
                () -> FaceAnnotationEvaluator.evaluate(
                        tooManyObjects, List.of(), new Limits(2, 2, 1, 5)));
        assertThrows(IllegalArgumentException.class,
                () -> new Limits(0, 1, 1, 0));
    }

    @Test
    public void zeroUnknownFacesProducesExplicitEmptyCoverageSummary() {
        EpisodeAnnotation hostOnly = new EpisodeAnnotation(
                "face-dev-07", FRAME_DURATION, List.of(frame(0, host(0))));

        Evaluation result = FaceAnnotationEvaluator.evaluate(hostOnly, List.of(), LIMITS);

        assertEquals(0, result.perUnknownObject().size());
        assertFalse(result.summary().microTemporalCoverage().isPresent());
        assertFalse(result.summary().macroEpisodeCoverage().isPresent());
    }

    private static FrameAnnotation frame(long index, FaceObject... objects) {
        return new FrameAnnotation(
                index, index * FRAME_DURATION, transform(), List.of(objects));
    }

    private static FaceObject unknown(long protectableSince) {
        return new FaceObject(
                UNKNOWN_ID, FaceRole.UNKNOWN, polygon(), 1.0,
                true, OptionalLong.of(protectableSince));
    }

    private static FaceObject host(long protectableSince) {
        return new FaceObject(
                HOST_ID, FaceRole.HOST, polygon(), 1.0,
                true, OptionalLong.of(protectableSince));
    }

    private static DecodedOutputFrame decoded(
            long presentationTimestamp, boolean protectedFrame, long trackId) {
        return new DecodedOutputFrame(
                presentationTimestamp,
                List.of(trackId < 0
                        ? new DecodedFaceObservation(
                                UNKNOWN_ID, protectedFrame, OptionalLong.empty())
                        : decodedFace(protectedFrame, trackId)));
    }

    private static DecodedFaceObservation decodedFace(
            boolean protectedFrame, long trackId) {
        return new DecodedFaceObservation(
                UNKNOWN_ID, protectedFrame, OptionalLong.of(trackId));
    }

    private static Polygon polygon() {
        return new Polygon(List.of(
                point(0.1, 0.1), point(0.4, 0.1),
                point(0.4, 0.4), point(0.1, 0.4)));
    }

    private static Point point(double x, double y) {
        return new Point(x, y);
    }

    private static Transform transform() {
        return new Transform(0, false, crop(), identity());
    }

    private static Rect crop() {
        return new Rect(0, 0, 1, 1);
    }

    private static List<Double> identity() {
        return List.of(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0);
    }
}
