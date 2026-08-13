package com.liveshield.video.geometry;

import android.graphics.Matrix;
import android.util.Size;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.NormalizedPoint;
import com.liveshield.privacy.model.NormalizedRect;
import java.util.Objects;

/**
 * Immutable normalized-coordinate mapping from sensor space through CameraX buffer space to the
 * rendered output.
 *
 * <p>The supplied sensor-to-buffer matrix is the shared privacy-domain contract used by vision.
 * Crop, clockwise CameraX rotation, and optional front-camera mirroring are composed after it, so
 * analysis regions and renderer regions use one transform chain. All matrices must be invertible
 * affine transforms. A point outside a cropped output is rejected; rectangle mappings are clipped
 * to the visible output and reject a rectangle with no visible area.</p>
 */
public final class FrameTransform {
    private static final double EPSILON = 1.0e-12;
    private static final double NORMALIZED_EPSILON = 1.0e-9;
    private static final int MATRIX_SIZE = 9;

    private final CoordinateTransform sensorToBuffer;
    private final CoordinateTransform bufferToOutput;
    private final CoordinateTransform sensorToOutput;
    private final CoordinateTransform outputToSensor;

    private FrameTransform(
            CoordinateTransform sensorToBuffer,
            CoordinateTransform bufferToOutput) {
        this.sensorToBuffer = Objects.requireNonNull(sensorToBuffer, "sensorToBuffer");
        this.bufferToOutput = Objects.requireNonNull(bufferToOutput, "bufferToOutput");
        requireAffine(this.sensorToBuffer.matrix(), "sensorToBuffer");
        requireAffine(this.bufferToOutput.matrix(), "bufferToOutput");
        double[] composed = multiply(bufferToOutput.matrix(), sensorToBuffer.matrix());
        this.sensorToOutput = new CoordinateTransform(composed);
        this.outputToSensor = new CoordinateTransform(invert(composed));
    }

    /**
     * Builds the complete transform from normalized CameraX metadata.
     *
     * @param sensorToBuffer normalized sensor-to-buffer matrix shared with detector adapters
     * @param outputCropInBuffer visible crop in normalized buffer coordinates
     * @param rotationDegrees clockwise output rotation: 0, 90, 180, or 270
     * @param mirrored whether to mirror horizontally after rotation
     * @return immutable, invertible transform chain
     */
    public static FrameTransform fromCameraMetadata(
            CoordinateTransform sensorToBuffer,
            NormalizedRect outputCropInBuffer,
            int rotationDegrees,
            boolean mirrored) {
        Objects.requireNonNull(outputCropInBuffer, "outputCropInBuffer");
        requireRotation(rotationDegrees);

        double cropWidth = outputCropInBuffer.right() - outputCropInBuffer.left();
        double cropHeight = outputCropInBuffer.bottom() - outputCropInBuffer.top();
        double[] crop = {
            1.0 / cropWidth, 0.0, -outputCropInBuffer.left() / cropWidth,
            0.0, 1.0 / cropHeight, -outputCropInBuffer.top() / cropHeight,
            0.0, 0.0, 1.0
        };
        double[] rotatedCrop = multiply(rotation(rotationDegrees), crop);
        double[] output = mirrored ? multiply(horizontalMirror(), rotatedCrop) : rotatedCrop;
        return new FrameTransform(sensorToBuffer, new CoordinateTransform(output));
    }

    /**
     * Normalizes CameraX's pixel-space sensor-to-buffer matrix for a specific use-case buffer.
     * Preview and ImageAnalysis must each invoke this with their own matrix and buffer size.
     */
    public static CoordinateTransform normalizePixelSensorToBuffer(
            CameraGeometry sensor,
            Size bufferSize,
            Matrix pixelSensorToBuffer) {
        Objects.requireNonNull(sensor, "sensor");
        Objects.requireNonNull(bufferSize, "bufferSize");
        Objects.requireNonNull(pixelSensorToBuffer, "pixelSensorToBuffer");
        if (bufferSize.getWidth() <= 0 || bufferSize.getHeight() <= 0) {
            throw new IllegalArgumentException("Camera buffer must be non-empty");
        }
        float[] pixelMatrix = new float[MATRIX_SIZE];
        pixelSensorToBuffer.getValues(pixelMatrix);
        double sensorWidth = sensor.width();
        double sensorHeight = sensor.height();
        double bufferWidth = bufferSize.getWidth();
        double bufferHeight = bufferSize.getHeight();
        return new CoordinateTransform(new double[]{
            pixelMatrix[0] * sensorWidth / bufferWidth,
            pixelMatrix[1] * sensorHeight / bufferWidth,
            (pixelMatrix[0] * sensor.left() + pixelMatrix[1] * sensor.top()
                    + pixelMatrix[2]) / bufferWidth,
            pixelMatrix[3] * sensorWidth / bufferHeight,
            pixelMatrix[4] * sensorHeight / bufferHeight,
            (pixelMatrix[3] * sensor.left() + pixelMatrix[4] * sensor.top()
                    + pixelMatrix[5]) / bufferHeight,
            pixelMatrix[6] * sensorWidth,
            pixelMatrix[7] * sensorHeight,
            pixelMatrix[6] * sensor.left() + pixelMatrix[7] * sensor.top()
                    + pixelMatrix[8]
        });
    }

    public CoordinateTransform sensorToBuffer() {
        return sensorToBuffer;
    }

    public CoordinateTransform bufferToOutput() {
        return bufferToOutput;
    }

    public CoordinateTransform sensorToOutput() {
        return sensorToOutput;
    }

    public CoordinateTransform outputToSensor() {
        return outputToSensor;
    }

    public NormalizedPoint mapSensorToBuffer(NormalizedPoint point) {
        return mapNormalized(sensorToBuffer.matrix(), point, "sensor point is outside buffer");
    }

    public NormalizedPoint mapBufferToOutput(NormalizedPoint point) {
        return mapNormalized(bufferToOutput.matrix(), point, "buffer point is outside output crop");
    }

    public NormalizedPoint mapSensorToOutput(NormalizedPoint point) {
        return mapNormalized(sensorToOutput.matrix(), point, "sensor point is outside output crop");
    }

    public NormalizedPoint mapOutputToSensor(NormalizedPoint point) {
        return mapNormalized(outputToSensor.matrix(), point, "output point is outside sensor");
    }

    public NormalizedRect mapSensorRectToOutput(NormalizedRect rect) {
        return mapRect(sensorToOutput.matrix(), rect, "sensor rectangle is outside output crop");
    }

    public NormalizedRect mapOutputRectToSensor(NormalizedRect rect) {
        return mapRect(outputToSensor.matrix(), rect, "output rectangle is outside sensor");
    }

    private static NormalizedPoint mapNormalized(
            double[] matrix,
            NormalizedPoint point,
            String outsideMessage) {
        Objects.requireNonNull(point, "point");
        double[] mapped = map(matrix, point.x(), point.y());
        if (mapped[0] < -NORMALIZED_EPSILON || mapped[0] > 1.0 + NORMALIZED_EPSILON
                || mapped[1] < -NORMALIZED_EPSILON || mapped[1] > 1.0 + NORMALIZED_EPSILON) {
            throw new IllegalArgumentException(outsideMessage);
        }
        return new NormalizedPoint(clamp(mapped[0]), clamp(mapped[1]));
    }

    private static NormalizedRect mapRect(double[] matrix, NormalizedRect rect, String message) {
        Objects.requireNonNull(rect, "rect");
        double[][] corners = {
            map(matrix, rect.left(), rect.top()),
            map(matrix, rect.right(), rect.top()),
            map(matrix, rect.right(), rect.bottom()),
            map(matrix, rect.left(), rect.bottom())
        };
        double left = Double.POSITIVE_INFINITY;
        double top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        for (double[] corner : corners) {
            left = Math.min(left, corner[0]);
            top = Math.min(top, corner[1]);
            right = Math.max(right, corner[0]);
            bottom = Math.max(bottom, corner[1]);
        }
        double clippedLeft = clamp(left);
        double clippedTop = clamp(top);
        double clippedRight = clamp(right);
        double clippedBottom = clamp(bottom);
        if (clippedLeft >= clippedRight || clippedTop >= clippedBottom) {
            throw new IllegalArgumentException(message);
        }
        return new NormalizedRect(clippedLeft, clippedTop, clippedRight, clippedBottom);
    }

    private static double[] map(double[] matrix, double x, double y) {
        double divisor = matrix[6] * x + matrix[7] * y + matrix[8];
        if (!Double.isFinite(divisor) || Math.abs(divisor) < EPSILON) {
            throw new IllegalArgumentException("Transform has an unsafe homogeneous divisor");
        }
        double mappedX = (matrix[0] * x + matrix[1] * y + matrix[2]) / divisor;
        double mappedY = (matrix[3] * x + matrix[4] * y + matrix[5]) / divisor;
        if (!Double.isFinite(mappedX) || !Double.isFinite(mappedY)) {
            throw new IllegalArgumentException("Transform produced non-finite coordinates");
        }
        return new double[]{mappedX, mappedY};
    }

    private static double[] rotation(int rotationDegrees) {
        return switch (rotationDegrees) {
            case 0 -> new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
            case 90 -> new double[]{0, -1, 1, 1, 0, 0, 0, 0, 1};
            case 180 -> new double[]{-1, 0, 1, 0, -1, 1, 0, 0, 1};
            case 270 -> new double[]{0, 1, 0, -1, 0, 1, 0, 0, 1};
            default -> throw new IllegalArgumentException("Unsupported rotation: " + rotationDegrees);
        };
    }

    private static double[] horizontalMirror() {
        return new double[]{-1, 0, 1, 0, 1, 0, 0, 0, 1};
    }

    private static void requireRotation(int rotationDegrees) {
        if (rotationDegrees != 0 && rotationDegrees != 90
                && rotationDegrees != 180 && rotationDegrees != 270) {
            throw new IllegalArgumentException("Unsupported rotation: " + rotationDegrees);
        }
    }

    private static void requireAffine(double[] matrix, String label) {
        if (matrix.length != MATRIX_SIZE
                || Math.abs(matrix[6]) > EPSILON
                || Math.abs(matrix[7]) > EPSILON
                || Math.abs(matrix[8]) < EPSILON) {
            throw new IllegalArgumentException(label + " must be an invertible affine transform");
        }
        invert(matrix);
    }

    private static double[] multiply(double[] left, double[] right) {
        double[] result = new double[MATRIX_SIZE];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                result[row * 3 + column] = left[row * 3] * right[column]
                        + left[row * 3 + 1] * right[3 + column]
                        + left[row * 3 + 2] * right[6 + column];
            }
        }
        return result;
    }

    private static double[] invert(double[] matrix) {
        double determinant = matrix[0] * (matrix[4] * matrix[8] - matrix[5] * matrix[7])
                - matrix[1] * (matrix[3] * matrix[8] - matrix[5] * matrix[6])
                + matrix[2] * (matrix[3] * matrix[7] - matrix[4] * matrix[6]);
        if (!Double.isFinite(determinant) || Math.abs(determinant) < EPSILON) {
            throw new IllegalArgumentException("Transform must be invertible");
        }
        double inverseDeterminant = 1.0 / determinant;
        return new double[]{
            (matrix[4] * matrix[8] - matrix[5] * matrix[7]) * inverseDeterminant,
            (matrix[2] * matrix[7] - matrix[1] * matrix[8]) * inverseDeterminant,
            (matrix[1] * matrix[5] - matrix[2] * matrix[4]) * inverseDeterminant,
            (matrix[5] * matrix[6] - matrix[3] * matrix[8]) * inverseDeterminant,
            (matrix[0] * matrix[8] - matrix[2] * matrix[6]) * inverseDeterminant,
            (matrix[2] * matrix[3] - matrix[0] * matrix[5]) * inverseDeterminant,
            (matrix[3] * matrix[7] - matrix[4] * matrix[6]) * inverseDeterminant,
            (matrix[1] * matrix[6] - matrix[0] * matrix[7]) * inverseDeterminant,
            (matrix[0] * matrix[4] - matrix[1] * matrix[3]) * inverseDeterminant
        };
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
