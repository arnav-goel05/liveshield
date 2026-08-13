package com.liveshield.video.geometry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.NormalizedPoint;
import com.liveshield.privacy.model.NormalizedRect;
import java.util.List;
import org.junit.Test;

public final class FrameTransformTest {
    private static final double TOLERANCE = 1.0e-9;

    @Test
    public void identityPreservesPointsAndRectangles() {
        FrameTransform transform = FrameTransform.fromCameraMetadata(
                CoordinateTransform.identity(),
                new NormalizedRect(0.0, 0.0, 1.0, 1.0),
                0,
                false);

        assertPoint(0.25, 0.75,
                transform.mapSensorToOutput(new NormalizedPoint(0.25, 0.75)));
        assertEquals(new NormalizedRect(0.1, 0.2, 0.4, 0.6),
                transform.mapSensorRectToOutput(new NormalizedRect(0.1, 0.2, 0.4, 0.6)));
    }

    @Test
    public void rotationUsesClockwiseCameraXConvention() {
        NormalizedPoint input = new NormalizedPoint(0.2, 0.3);

        assertPoint(0.7, 0.2, transform(90, false).mapBufferToOutput(input));
        assertPoint(0.8, 0.7, transform(180, false).mapBufferToOutput(input));
        assertPoint(0.3, 0.8, transform(270, false).mapBufferToOutput(input));
    }

    @Test
    public void outputFindingRoundTripsThroughSensorForRotatedDeviceScreenFixture() {
        FrameTransform transform = transform(90, false);
        NormalizedRect outputFinding = new NormalizedRect(
                0.21875, 0.0625, 0.4583333333333333, 0.9375);

        NormalizedRect sensorFinding = transform.mapOutputRectToSensor(outputFinding);

        assertEquals(new NormalizedRect(
                0.0625, 0.5416666666666667, 0.9375, 0.78125), sensorFinding);
        assertRect(
                outputFinding.left(), outputFinding.top(),
                outputFinding.right(), outputFinding.bottom(),
                transform.mapSensorRectToOutput(sensorFinding));
    }

    @Test
    public void cropMapsOnlyConfiguredBufferAreaIntoOutput() {
        FrameTransform transform = FrameTransform.fromCameraMetadata(
                CoordinateTransform.identity(),
                new NormalizedRect(0.25, 0.2, 0.75, 0.8),
                0,
                false);

        assertPoint(0.0, 0.0, transform.mapBufferToOutput(new NormalizedPoint(0.25, 0.2)));
        assertPoint(1.0, 1.0, transform.mapBufferToOutput(new NormalizedPoint(0.75, 0.8)));
        assertPoint(0.5, 0.5, transform.mapBufferToOutput(new NormalizedPoint(0.5, 0.5)));
        assertThrows(IllegalArgumentException.class,
                () -> transform.mapBufferToOutput(new NormalizedPoint(0.1, 0.5)));
    }

    @Test
    public void horizontalMirrorAppliesAfterOutputRotation() {
        FrameTransform transform = transform(90, true);

        assertPoint(0.3, 0.2,
                transform.mapBufferToOutput(new NormalizedPoint(0.2, 0.3)));
    }

    @Test
    public void composesExistingSensorToBufferContractWithoutDuplicatingIt() {
        CoordinateTransform sensorToBuffer = new CoordinateTransform(new double[]{
            -1.0, 0.0, 1.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
        });
        FrameTransform transform = FrameTransform.fromCameraMetadata(
                sensorToBuffer,
                new NormalizedRect(0.0, 0.0, 1.0, 1.0),
                90,
                false);

        assertEquals(sensorToBuffer, transform.sensorToBuffer());
        assertPoint(0.7, 0.8,
                transform.mapSensorToOutput(new NormalizedPoint(0.2, 0.3)));
    }

    @Test
    public void forwardAndInverseMappingsRoundTripInsideCrop() {
        FrameTransform transform = FrameTransform.fromCameraMetadata(
                CoordinateTransform.identity(),
                new NormalizedRect(0.2, 0.1, 0.9, 0.8),
                270,
                true);

        for (NormalizedPoint sensor : List.of(
                new NormalizedPoint(0.2, 0.1),
                new NormalizedPoint(0.5, 0.4),
                new NormalizedPoint(0.9, 0.8))) {
            NormalizedPoint output = transform.mapSensorToOutput(sensor);
            assertPoint(sensor.x(), sensor.y(), transform.mapOutputToSensor(output));
        }
    }

    @Test
    public void rectangleMappingUsesAllFourCornersAndClipsAtOutputBoundary() {
        FrameTransform transform = FrameTransform.fromCameraMetadata(
                CoordinateTransform.identity(),
                new NormalizedRect(0.25, 0.25, 0.75, 0.75),
                90,
                false);

        assertEquals(new NormalizedRect(0.0, 0.0, 1.0, 1.0),
                transform.mapSensorRectToOutput(new NormalizedRect(0.1, 0.1, 0.9, 0.9)));
        assertRect(0.2, 0.2, 0.8, 0.8,
                transform.mapSensorRectToOutput(new NormalizedRect(0.35, 0.35, 0.65, 0.65)));
    }

    @Test
    public void invalidMetadataAndNonInvertibleTransformsAreRejected() {
        CoordinateTransform singular = new CoordinateTransform(new double[]{
            1.0, 0.0, 0.0,
            0.0, 0.0, 0.0,
            0.0, 0.0, 1.0
        });

        assertThrows(IllegalArgumentException.class,
                () -> FrameTransform.fromCameraMetadata(
                        CoordinateTransform.identity(),
                        new NormalizedRect(0.0, 0.0, 1.0, 1.0),
                        45,
                        false));
        assertThrows(IllegalArgumentException.class,
                () -> FrameTransform.fromCameraMetadata(
                        singular,
                        new NormalizedRect(0.0, 0.0, 1.0, 1.0),
                        0,
                        false));
    }

    private static FrameTransform transform(int rotationDegrees, boolean mirrored) {
        return FrameTransform.fromCameraMetadata(
                CoordinateTransform.identity(),
                new NormalizedRect(0.0, 0.0, 1.0, 1.0),
                rotationDegrees,
                mirrored);
    }

    private static void assertPoint(double expectedX, double expectedY, NormalizedPoint actual) {
        assertEquals(expectedX, actual.x(), TOLERANCE);
        assertEquals(expectedY, actual.y(), TOLERANCE);
    }

    private static void assertRect(
            double expectedLeft,
            double expectedTop,
            double expectedRight,
            double expectedBottom,
            NormalizedRect actual) {
        assertEquals(expectedLeft, actual.left(), TOLERANCE);
        assertEquals(expectedTop, actual.top(), TOLERANCE);
        assertEquals(expectedRight, actual.right(), TOLERANCE);
        assertEquals(expectedBottom, actual.bottom(), TOLERANCE);
    }
}
