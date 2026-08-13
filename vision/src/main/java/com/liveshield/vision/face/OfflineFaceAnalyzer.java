package com.liveshield.vision.face;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import com.google.common.util.concurrent.ListenableFuture;
import com.liveshield.privacy.model.ConfidenceClass;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorObservation;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.model.ProtectionAction;
import com.liveshield.privacy.model.TypedFailure;
import com.liveshield.vision.contract.AnalysisFrameHandle;
import com.liveshield.vision.contract.VisionAnalyzer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.FaceDetectorYN;

/** Offline YuNet face analyzer that emits geometry and session-local IoU tracking hints only. */
public final class OfflineFaceAnalyzer implements VisionAnalyzer, AutoCloseable {
    // The CameraX frame timestamp precedes bitmap conversion and native YuNet inference. On
    // high-resolution device streams, a 100 ms validity window can be nearly exhausted before
    // the result reaches the renderer and makes otherwise healthy frames alternate with STALE.
    // Match the scheduler's bounded face deadline so one accepted result bridges inference work.
    static final long DEFAULT_FRESHNESS_NANOS = 250_000_000L;
    private static final double MINIMUM_TRACK_IOU = 0.20;

    private final FaceDetectionEngine engine;
    private final long freshnessNanos;
    private final SessionIouTracker tracker = new SessionIouTracker(MINIMUM_TRACK_IOU);
    private final AtomicReference<PendingAnalysis> pending = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public OfflineFaceAnalyzer(Context context) {
        this(new BundledYuNetEngine(context), DEFAULT_FRESHNESS_NANOS);
    }

    OfflineFaceAnalyzer(FaceDetectionEngine engine, long freshnessNanos) {
        this.engine = Objects.requireNonNull(engine, "engine");
        if (freshnessNanos < 0) {
            throw new IllegalArgumentException("freshnessNanos must be non-negative");
        }
        this.freshnessNanos = freshnessNanos;
    }

    @Override
    public ListenableFuture<DetectorSnapshot> analyze(
            AnalysisFrameHandle input,
            FrameTimestamp timestamp,
            int rotationDegrees,
            CoordinateTransform sensorToBufferTransform) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(sensorToBufferTransform, "sensorToBufferTransform");
        if (!(input instanceof FaceAnalysisFrame frame)) {
            input.close();
            return immediate(failure(timestamp, TypedFailure.Code.ANALYZER_ERROR));
        }
        if (!isValidRotation(rotationDegrees) || closed.get()) {
            frame.close();
            return immediate(failure(timestamp, TypedFailure.Code.ANALYZER_ERROR));
        }

        PendingAnalysis request = new PendingAnalysis(frame, timestamp);
        if (!pending.compareAndSet(null, request)) {
            frame.close();
            return immediate(failure(timestamp, TypedFailure.Code.ANALYZER_ERROR));
        }

        try {
            DetectionOperation operation = engine.detect(
                    frame,
                    rotationDegrees,
                    new DetectionCallback() {
                        @Override
                        public void onSuccess(List<DetectedFace> faces) {
                            if (!request.acceptingResult()) {
                                return;
                            }
                            try {
                                List<DetectorObservation> observations = mapFaces(
                                        tracker.assign(Objects.requireNonNull(faces, "faces")),
                                        frame.width(),
                                        frame.height(),
                                        sensorToBufferTransform);
                                request.finish(DetectorSnapshot.successWithObservations(
                                        DetectorLane.FACE,
                                        timestamp,
                                        timestamp.plusNanos(freshnessNanos),
                                        observations));
                            } catch (RuntimeException mappingFailure) {
                                request.finish(failure(
                                        timestamp, TypedFailure.Code.ANALYZER_ERROR));
                            }
                        }

                        @Override
                        public void onFailure() {
                            request.finish(failure(
                                    timestamp, TypedFailure.Code.ANALYZER_ERROR));
                        }

                        @Override
                        public void onCancelled() {
                            request.finish(failure(
                                    timestamp, TypedFailure.Code.ANALYZER_CANCELLED));
                        }

                        @Override
                        public void onInputReleased() {
                            request.releaseInput();
                        }
                    });
            request.setOperation(operation);
        } catch (RuntimeException startFailure) {
            request.finish(failure(timestamp, TypedFailure.Code.ANALYZER_ERROR));
            request.releaseInput();
        }
        return request.result;
    }

    @Override
    public void cancelPending() {
        PendingAnalysis request = pending.get();
        if (request != null) {
            request.cancelWithSnapshot();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cancelPending();
            engine.close();
            tracker.clear();
        }
    }

    private List<DetectorObservation> mapFaces(
            List<DetectedFace> faces,
            int width,
            int height,
            CoordinateTransform transform) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Frame dimensions must be positive");
        }
        List<DetectorObservation> observations = new ArrayList<>(faces.size());
        for (DetectedFace face : faces) {
            NormalizedRect mapped = transformBounds(face.bounds(), width, height, transform);
            ProtectedRegion region = new ProtectedRegion(
                    FindingCategory.FACE,
                    List.of(mapped),
                    ConfidenceClass.VALIDATED,
                    ProtectionAction.MOSAIC);
            observations.add(face.trackingId() == null
                    ? DetectorObservation.withoutTrackingHint(region)
                    : DetectorObservation.withTrackingHint(region, face.trackingId()));
        }
        return List.copyOf(observations);
    }

    private static NormalizedRect transformBounds(
            Rect bounds, int width, int height, CoordinateTransform transform) {
        Objects.requireNonNull(bounds, "bounds");
        double left = clamp((double) bounds.left / width);
        double top = clamp((double) bounds.top / height);
        double right = clamp((double) bounds.right / width);
        double bottom = clamp((double) bounds.bottom / height);
        if (left >= right || top >= bottom) {
            throw new IllegalArgumentException("Detector returned empty face bounds");
        }
        // YuNet returns analysis-buffer coordinates. Privacy decisions use normalized sensor
        // coordinates so the renderer can map them through its independently cropped preview
        // buffer. CameraX supplies sensor-to-analysis-buffer metadata, hence the inverse here.
        double[] matrix = transform.inverse().matrix();
        double[][] corners = {
            mapPoint(matrix, left, top), mapPoint(matrix, right, top),
            mapPoint(matrix, right, bottom), mapPoint(matrix, left, bottom)
        };
        double mappedLeft = 1.0;
        double mappedTop = 1.0;
        double mappedRight = 0.0;
        double mappedBottom = 0.0;
        for (double[] corner : corners) {
            mappedLeft = Math.min(mappedLeft, corner[0]);
            mappedTop = Math.min(mappedTop, corner[1]);
            mappedRight = Math.max(mappedRight, corner[0]);
            mappedBottom = Math.max(mappedBottom, corner[1]);
        }
        double safeLeft = clamp(mappedLeft);
        double safeTop = clamp(mappedTop);
        double safeRight = clamp(mappedRight);
        double safeBottom = clamp(mappedBottom);
        if (safeLeft >= safeRight || safeTop >= safeBottom) {
            throw new IllegalArgumentException("Transform collapsed face bounds");
        }
        return new NormalizedRect(safeLeft, safeTop, safeRight, safeBottom);
    }

    private static double[] mapPoint(double[] matrix, double x, double y) {
        double divisor = matrix[6] * x + matrix[7] * y + matrix[8];
        if (!Double.isFinite(divisor) || Math.abs(divisor) < 1e-12) {
            throw new IllegalArgumentException("Unsafe coordinate transform");
        }
        double mappedX = (matrix[0] * x + matrix[1] * y + matrix[2]) / divisor;
        double mappedY = (matrix[3] * x + matrix[4] * y + matrix[5]) / divisor;
        if (!Double.isFinite(mappedX) || !Double.isFinite(mappedY)) {
            throw new IllegalArgumentException("Unsafe coordinate transform");
        }
        return new double[]{mappedX, mappedY};
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static boolean isValidRotation(int rotationDegrees) {
        return rotationDegrees == 0 || rotationDegrees == 90
                || rotationDegrees == 180 || rotationDegrees == 270;
    }

    private static DetectorSnapshot failure(FrameTimestamp timestamp, TypedFailure.Code code) {
        return DetectorSnapshot.failure(
                DetectorLane.FACE, timestamp, new TypedFailure(code, timestamp));
    }

    private static ListenableFuture<DetectorSnapshot> immediate(DetectorSnapshot snapshot) {
        return CallbackToFutureAdapter.getFuture(completer -> {
            completer.set(snapshot);
            return "immediate face analysis result";
        });
    }

    interface FaceDetectionEngine extends AutoCloseable {
        DetectionOperation detect(
                FaceAnalysisFrame frame, int rotationDegrees, DetectionCallback callback);

        @Override
        default void close() {
        }
    }

    interface DetectionCallback {
        void onSuccess(List<DetectedFace> faces);

        void onFailure();

        void onCancelled();

        /** Called only after the detector can no longer read the frame's backing image. */
        void onInputReleased();
    }

    interface DetectionOperation {
        void cancel();
    }

    record DetectedFace(Rect bounds, Long trackingId) {
        DetectedFace {
            Objects.requireNonNull(bounds, "bounds");
            bounds = new Rect(bounds);
            if (trackingId != null && trackingId < 0) {
                throw new IllegalArgumentException("Tracking ID must be non-negative");
            }
        }

        @Override
        public Rect bounds() {
            return new Rect(bounds);
        }
    }

    private final class PendingAnalysis {
        private final FaceAnalysisFrame frame;
        private final FrameTimestamp timestamp;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private final AtomicBoolean inputReleased = new AtomicBoolean();
        private final ListenableFuture<DetectorSnapshot> result;
        private volatile DetectionOperation operation;
        private volatile CallbackToFutureAdapter.Completer<DetectorSnapshot> completer;

        private PendingAnalysis(FaceAnalysisFrame frame, FrameTimestamp timestamp) {
            this.frame = frame;
            this.timestamp = timestamp;
            result = CallbackToFutureAdapter.getFuture(value -> {
                completer = value;
                value.addCancellationListener(this::cancelFromCaller, Runnable::run);
                return "face analysis at " + timestamp.nanos();
            });
        }

        private void setOperation(DetectionOperation value) {
            operation = Objects.requireNonNull(value, "operation");
            if (cancellationRequested.get()) {
                operation.cancel();
            }
        }

        private void finish(DetectorSnapshot snapshot) {
            if (finished.compareAndSet(false, true)) {
                completer.set(snapshot);
            }
        }

        private boolean acceptingResult() {
            return !finished.get() && !cancellationRequested.get();
        }

        private void releaseInput() {
            if (inputReleased.compareAndSet(false, true)) {
                frame.close();
                pending.compareAndSet(this, null);
            }
        }

        private void cancelWithSnapshot() {
            cancellationRequested.set(true);
            DetectionOperation active = operation;
            if (active != null) {
                active.cancel();
            }
            finish(failure(timestamp, TypedFailure.Code.ANALYZER_CANCELLED));
        }

        private void cancelFromCaller() {
            if (finished.compareAndSet(false, true)) {
                cancellationRequested.set(true);
                DetectionOperation active = operation;
                if (active != null) {
                    active.cancel();
                }
            }
        }
    }

    static final class SessionIouTracker {
        private final double minimumIntersectionOverUnion;
        private List<DetectedFace> previous = List.of();
        private long nextTrackingId;

        SessionIouTracker(double minimumIntersectionOverUnion) {
            if (!Double.isFinite(minimumIntersectionOverUnion)
                    || minimumIntersectionOverUnion <= 0.0
                    || minimumIntersectionOverUnion > 1.0) {
                throw new IllegalArgumentException("minimumIntersectionOverUnion must be in (0, 1]");
            }
            this.minimumIntersectionOverUnion = minimumIntersectionOverUnion;
        }

        synchronized List<DetectedFace> assign(List<DetectedFace> detections) {
            List<DetectedFace> ordered = new ArrayList<>(Objects.requireNonNull(
                    detections, "detections"));
            ordered.sort(Comparator
                    .comparingInt((DetectedFace face) -> face.bounds().left)
                    .thenComparingInt(face -> face.bounds().top)
                    .thenComparingInt(face -> face.bounds().right)
                    .thenComparingInt(face -> face.bounds().bottom));
            List<DetectedFace> assigned = new ArrayList<>(ordered.size());
            Set<Long> claimed = new HashSet<>();
            for (DetectedFace detection : ordered) {
                Long trackingId = detection.trackingId();
                if (trackingId == null) {
                    trackingId = bestPreviousId(detection.bounds(), claimed);
                    if (trackingId == null) {
                        trackingId = allocateTrackingId();
                    }
                }
                claimed.add(trackingId);
                assigned.add(new DetectedFace(detection.bounds(), trackingId));
            }
            previous = List.copyOf(assigned);
            return previous;
        }

        synchronized void clear() {
            previous = List.of();
            nextTrackingId = 0L;
        }

        private Long bestPreviousId(Rect bounds, Set<Long> claimed) {
            DetectedFace best = null;
            double bestIou = -1.0;
            for (DetectedFace candidate : previous) {
                if (claimed.contains(candidate.trackingId())) {
                    continue;
                }
                double candidateIou = intersectionOverUnion(bounds, candidate.bounds());
                if (candidateIou < minimumIntersectionOverUnion) {
                    continue;
                }
                if (candidateIou > bestIou
                        || (candidateIou == bestIou && best != null
                        && candidate.trackingId() < best.trackingId())) {
                    best = candidate;
                    bestIou = candidateIou;
                }
            }
            return best == null ? null : best.trackingId();
        }

        private long allocateTrackingId() {
            if (nextTrackingId == Long.MAX_VALUE) {
                throw new IllegalStateException("Session tracking identifier space exhausted");
            }
            return nextTrackingId++;
        }

        private static double intersectionOverUnion(Rect first, Rect second) {
            int intersectionWidth = Math.max(
                    0, Math.min(first.right, second.right) - Math.max(first.left, second.left));
            int intersectionHeight = Math.max(
                    0, Math.min(first.bottom, second.bottom) - Math.max(first.top, second.top));
            long intersection = (long) intersectionWidth * intersectionHeight;
            long union = (long) first.width() * first.height()
                    + (long) second.width() * second.height() - intersection;
            return union <= 0L ? 0.0 : (double) intersection / union;
        }
    }

    static final class BundledYuNetEngine implements FaceDetectionEngine {
        // Preserve the official YuNet 320-pixel input so borderline and partially visible faces
        // do not alternate between present and absent. Live cadence is controlled by the
        // single-flight scheduler rather than by discarding model input detail.
        private static final int MAXIMUM_INPUT_EDGE = 320;
        private static final float SCORE_THRESHOLD = 0.60F;
        private static final float NMS_THRESHOLD = 0.30F;
        private static final int TOP_K = 5_000;
        private final FaceDetectorYN detector;

        private BundledYuNetEngine(Context context) {
            Objects.requireNonNull(context, "context");
            if (!OpenCVLoader.initLocal()) {
                throw new IllegalStateException("Bundled OpenCV runtime failed to initialize");
            }
            byte[] model = YuNetModelAsset.loadVerified(context.getAssets());
            MatOfByte modelBuffer = new MatOfByte(model);
            MatOfByte emptyConfiguration = new MatOfByte();
            try {
                detector = FaceDetectorYN.create(
                        "onnx",
                        modelBuffer,
                        emptyConfiguration,
                        new Size(320, 320),
                        SCORE_THRESHOLD,
                        NMS_THRESHOLD,
                        TOP_K);
            } finally {
                modelBuffer.release();
                emptyConfiguration.release();
            }
        }

        @Override
        public DetectionOperation detect(
                FaceAnalysisFrame frame, int rotationDegrees, DetectionCallback callback) {
            AtomicBoolean cancellationRequested = new AtomicBoolean();
            Bitmap bitmap = null;
            Mat rgba = new Mat();
            Mat bgr = new Mat();
            Mat inference = new Mat();
            Mat faces = new Mat();
            OfflineFaceDiagnostics.Stage stage = OfflineFaceDiagnostics.Stage.BITMAP;
            try {
                bitmap = frame.bitmap(rotationDegrees);
                stage = OfflineFaceDiagnostics.Stage.BITMAP_TO_MAT;
                Utils.bitmapToMat(bitmap, rgba);
                stage = OfflineFaceDiagnostics.Stage.COLOR_CONVERSION;
                Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);
                InferenceSize input = InferenceSize.fitWithin(
                        bgr.cols(), bgr.rows(), MAXIMUM_INPUT_EDGE);
                Mat detectorInput = bgr;
                if (input.width() != bgr.cols() || input.height() != bgr.rows()) {
                    stage = OfflineFaceDiagnostics.Stage.INPUT_RESIZE;
                    Imgproc.resize(bgr, inference, new Size(input.width(), input.height()));
                    detectorInput = inference;
                }
                stage = OfflineFaceDiagnostics.Stage.DETECTOR_CONFIGURATION;
                detector.setInputSize(detectorInput.size());
                stage = OfflineFaceDiagnostics.Stage.DETECTOR_INFERENCE;
                detector.detect(detectorInput, faces);
                if (cancellationRequested.get()) {
                    callback.onCancelled();
                } else {
                    stage = OfflineFaceDiagnostics.Stage.OUTPUT_PARSE;
                    callback.onSuccess(readFaces(
                            faces,
                            input,
                            bitmap.getWidth(),
                            bitmap.getHeight(),
                            frame.width(),
                            frame.height(),
                            rotationDegrees));
                }
            } catch (RuntimeException detectorFailure) {
                OfflineFaceDiagnostics.report(stage, detectorFailure);
                callback.onFailure();
            } finally {
                faces.release();
                inference.release();
                bgr.release();
                rgba.release();
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                callback.onInputReleased();
            }
            return () -> cancellationRequested.set(true);
        }

        static List<DetectedFace> readFaces(
                Mat faces,
                InferenceSize inferenceSize,
                int uprightWidth,
                int uprightHeight,
                int sourceWidth,
                int sourceHeight,
                int rotationDegrees) {
            List<DetectedFace> detected = new ArrayList<>(faces.rows());
            if (faces.rows() == 0) {
                return List.of();
            }
            if (faces.cols() != 15 || faces.channels() != 1
                    || faces.type() != CvType.CV_32FC1) {
                OfflineFaceDiagnostics.reportMalformedOutput(
                        faces.rows(), faces.cols(), faces.channels(), faces.type());
                throw new IllegalStateException("YuNet returned malformed output shape");
            }
            double scaleX = (double) uprightWidth / inferenceSize.width();
            double scaleY = (double) uprightHeight / inferenceSize.height();
            for (int row = 0; row < faces.rows(); row++) {
                float[] values = new float[15];
                int bytesRead = faces.get(row, 0, values);
                int expectedBytes = values.length * (Float.SIZE / Byte.SIZE);
                if (bytesRead != expectedBytes) {
                    OfflineFaceDiagnostics.reportMalformedOutput(
                            faces.rows(), faces.cols(), faces.channels(), faces.type());
                    throw new IllegalStateException("YuNet returned malformed output");
                }
                int left = (int) Math.floor(values[0] * scaleX);
                int top = (int) Math.floor(values[1] * scaleY);
                int right = (int) Math.ceil((values[0] + values[2]) * scaleX);
                int bottom = (int) Math.ceil((values[1] + values[3]) * scaleY);
                Rect original = toOriginalOrientation(
                        new Rect(left, top, right, bottom),
                        sourceWidth,
                        sourceHeight,
                        rotationDegrees);
                if (original.intersect(0, 0, sourceWidth, sourceHeight)
                        && !original.isEmpty()) {
                    detected.add(new DetectedFace(original, null));
                }
            }
            return List.copyOf(detected);
        }

        record InferenceSize(int width, int height) {
            InferenceSize {
                if (width <= 0 || height <= 0) {
                    throw new IllegalArgumentException("Inference dimensions must be positive");
                }
            }

            static InferenceSize fitWithin(int width, int height, int maximumEdge) {
                if (width <= 0 || height <= 0 || maximumEdge <= 0) {
                    throw new IllegalArgumentException("Input dimensions must be positive");
                }
                int largest = Math.max(width, height);
                if (largest <= maximumEdge) {
                    return new InferenceSize(width, height);
                }
                double scale = (double) maximumEdge / largest;
                return new InferenceSize(
                        Math.max(1, (int) Math.round(width * scale)),
                        Math.max(1, (int) Math.round(height * scale)));
            }
        }

        private static Rect toOriginalOrientation(
                Rect upright, int width, int height, int rotationDegrees) {
            return switch (rotationDegrees) {
                case 0 -> new Rect(upright);
                case 90 -> new Rect(
                        upright.top,
                        height - upright.right,
                        upright.bottom,
                        height - upright.left);
                case 180 -> new Rect(
                        width - upright.right,
                        height - upright.bottom,
                        width - upright.left,
                        height - upright.top);
                case 270 -> new Rect(
                        width - upright.bottom,
                        upright.left,
                        width - upright.top,
                        upright.right);
                default -> throw new IllegalArgumentException("Unsupported frame rotation");
            };
        }
    }
}
