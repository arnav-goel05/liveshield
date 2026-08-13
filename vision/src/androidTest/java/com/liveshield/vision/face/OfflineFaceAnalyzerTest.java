package com.liveshield.vision.face;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import androidx.camera.core.ImageProxy;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.util.concurrent.ListenableFuture;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.DetectorObservation;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.TypedFailure;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

@RunWith(AndroidJUnit4.class)
public final class OfflineFaceAnalyzerTest {
    @Test
    public void defaultFreshnessBridgesBoundedSynchronousInferenceGap() {
        assertEquals(550_000_000L, OfflineFaceAnalyzer.DEFAULT_FRESHNESS_NANOS);
    }

    private static final FrameTimestamp TIMESTAMP = FrameTimestamp.ofNanos(123_456_789L);

    @BeforeClass
    public static void initializeOpenCvForNativeMatTests() {
        assertTrue("Bundled OpenCV runtime failed to initialize", OpenCVLoader.initLocal());
    }

    @Test
    public void oneFaceMapsAnalysisBufferBackToSensorAndClosesImageProxyOnce() throws Exception {
        FakeEngine engine = FakeEngine.success(List.of(
                new OfflineFaceAnalyzer.DetectedFace(new Rect(30, 35, 40, 45), 17L)));
        OfflineFaceAnalyzer analyzer = new OfflineFaceAnalyzer(engine, 50L);
        AtomicInteger proxyCloses = new AtomicInteger();
        FaceAnalysisFrame frame = proxyFrame(100, 100, proxyCloses);
        CoordinateTransform sensorToBuffer = new CoordinateTransform(new double[]{
            0.5, 0, 0.25, 0, 0.5, 0.25, 0, 0, 1
        });

        DetectorSnapshot snapshot = await(analyzer.analyze(
                frame, TIMESTAMP, 90, sensorToBuffer));

        assertEquals(TIMESTAMP, snapshot.sourceTimestamp());
        assertEquals(TIMESTAMP.plusNanos(50), snapshot.validUntil());
        assertEquals(90, engine.rotationDegrees);
        DetectorObservation observation = snapshot.observations().get(0);
        assertEquals(17L, observation.detectorTrackingId().getAsLong());
        assertEquals(new NormalizedRect(0.1, 0.2, 0.3, 0.4),
                observation.region().bounds().get(0));
        assertEquals(1, proxyCloses.get());
        assertFalse(snapshot.failure().isPresent());
    }

    @Test
    public void multipleFacesRetainOnlyGeometryAndOptionalSessionHints() throws Exception {
        FakeEngine engine = FakeEngine.success(List.of(
                new OfflineFaceAnalyzer.DetectedFace(new Rect(0, 0, 20, 20), 3L),
                new OfflineFaceAnalyzer.DetectedFace(new Rect(50, 50, 100, 100), null)));
        CountingFrame frame = new CountingFrame(100, 100);

        DetectorSnapshot snapshot = await(new OfflineFaceAnalyzer(engine, 10L).analyze(
                frame, TIMESTAMP, 0, CoordinateTransform.identity()));

        assertEquals(2, snapshot.findings().size());
        assertEquals(3L, snapshot.observations().get(0).detectorTrackingId().getAsLong());
        assertTrue(snapshot.observations().get(1).detectorTrackingId().isPresent());
        assertEquals(new NormalizedRect(0.0, 0.0, 0.2, 0.2),
                snapshot.findings().get(0).bounds().get(0));
        assertEquals(new NormalizedRect(0.5, 0.5, 1.0, 1.0),
                snapshot.findings().get(1).bounds().get(0));
        assertEquals(1, frame.closeCount.get());
    }

    @Test
    public void failureReturnsTypedFailureAndClosesInputExactlyOnce() throws Exception {
        CountingFrame frame = new CountingFrame(100, 100);
        DetectorSnapshot snapshot = await(new OfflineFaceAnalyzer(
                FakeEngine.failure(), 10L).analyze(
                        frame, TIMESTAMP, 0, CoordinateTransform.identity()));

        assertEquals(TypedFailure.Code.ANALYZER_ERROR,
                snapshot.failure().get().code());
        assertTrue(snapshot.findings().isEmpty());
        assertEquals(1, frame.closeCount.get());
    }

    @Test
    public void cancelPendingReturnsTypedCancellationAndClosesInput() throws Exception {
        FakeEngine engine = FakeEngine.pending();
        CountingFrame frame = new CountingFrame(100, 100);
        OfflineFaceAnalyzer analyzer = new OfflineFaceAnalyzer(engine, 10L);
        ListenableFuture<DetectorSnapshot> result = analyzer.analyze(
                frame, TIMESTAMP, 0, CoordinateTransform.identity());

        analyzer.cancelPending();
        DetectorSnapshot snapshot = await(result);

        assertEquals(TypedFailure.Code.ANALYZER_CANCELLED,
                snapshot.failure().get().code());
        assertTrue(engine.cancelled.get());
        assertEquals(0, frame.closeCount.get());
        engine.completeLate();
        assertEquals(1, frame.closeCount.get());
    }

    @Test
    public void callerCancellationCancelsEngineAndClosesInput() {
        FakeEngine engine = FakeEngine.pending();
        CountingFrame frame = new CountingFrame(100, 100);
        ListenableFuture<DetectorSnapshot> result = new OfflineFaceAnalyzer(
                engine, 10L).analyze(
                        frame, TIMESTAMP, 0, CoordinateTransform.identity());

        assertTrue(result.cancel(false));

        assertTrue(engine.cancelled.get());
        assertEquals(0, frame.closeCount.get());
        engine.completeLate();
        assertEquals(1, frame.closeCount.get());
    }

    @Test
    public void cancellationKeepsBackpressureUntilDetectorReleasesInput() throws Exception {
        FakeEngine engine = FakeEngine.pending();
        CountingFrame first = new CountingFrame(100, 100);
        CountingFrame second = new CountingFrame(100, 100);
        OfflineFaceAnalyzer analyzer = new OfflineFaceAnalyzer(engine, 10L);
        ListenableFuture<DetectorSnapshot> cancelled = analyzer.analyze(
                first, TIMESTAMP, 0, CoordinateTransform.identity());

        assertTrue(cancelled.cancel(false));
        DetectorSnapshot rejected = await(analyzer.analyze(
                second, TIMESTAMP.plusNanos(1), 0, CoordinateTransform.identity()));

        assertEquals(TypedFailure.Code.ANALYZER_ERROR, rejected.failure().get().code());
        assertEquals(0, first.closeCount.get());
        assertEquals(1, second.closeCount.get());
        engine.completeLate();
        assertEquals(1, first.closeCount.get());
    }

    @Test
    public void unsafeTransformFailsClosedAndReleasesInput() throws Exception {
        CountingFrame frame = new CountingFrame(100, 100);
        CoordinateTransform collapsed = new CoordinateTransform(new double[]{
            0, 0, 0, 0, 0, 0, 0, 0, 1
        });
        DetectorSnapshot snapshot = await(new OfflineFaceAnalyzer(
                FakeEngine.success(List.of(new OfflineFaceAnalyzer.DetectedFace(
                        new Rect(10, 10, 20, 20), null))), 10L).analyze(
                                frame, TIMESTAMP, 0, collapsed));

        assertEquals(TypedFailure.Code.ANALYZER_ERROR,
                snapshot.failure().get().code());
        assertEquals(1, frame.closeCount.get());
    }

    @Test
    public void bundledYuNetRunsOnDeviceAgainstSafeBlankSyntheticBitmap() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        CountingFrame frame = new CountingFrame(bitmap);

        try (OfflineFaceAnalyzer analyzer = new OfflineFaceAnalyzer(
                androidx.test.core.app.ApplicationProvider.getApplicationContext())) {
            DetectorSnapshot snapshot = await(analyzer.analyze(
                    frame, TIMESTAMP, 0, CoordinateTransform.identity()));

            assertFalse(snapshot.failure().isPresent());
            assertTrue(snapshot.findings().isEmpty());
            assertEquals(1, frame.closeCount.get());
        } finally {
            bitmap.recycle();
        }
    }

    @Test
    public void bundledYuNetAcceptsLargeNonModelDimensionsAndRgb565() throws Exception {
        List<Bitmap> bitmaps = List.of(
                Bitmap.createBitmap(1024, 754, Bitmap.Config.ARGB_8888),
                Bitmap.createBitmap(641, 479, Bitmap.Config.RGB_565),
                Bitmap.createBitmap(319, 241, Bitmap.Config.ARGB_8888));
        try (OfflineFaceAnalyzer analyzer = new OfflineFaceAnalyzer(
                androidx.test.core.app.ApplicationProvider.getApplicationContext())) {
            long timestamp = TIMESTAMP.nanos();
            for (Bitmap bitmap : bitmaps) {
                bitmap.eraseColor(Color.WHITE);
                CountingFrame frame = new CountingFrame(bitmap);

                DetectorSnapshot snapshot = await(analyzer.analyze(
                        frame,
                        FrameTimestamp.ofNanos(timestamp++),
                        0,
                        CoordinateTransform.identity()));

                assertFalse(snapshot.failure().isPresent());
                assertTrue(snapshot.findings().isEmpty());
                assertEquals(1, frame.closeCount.get());
            }
        }
    }

    @Test
    public void inferenceSizePreservesAspectRatioAndBoundsLongEdge() {
        OfflineFaceAnalyzer.BundledYuNetEngine.InferenceSize large =
                OfflineFaceAnalyzer.BundledYuNetEngine.InferenceSize.fitWithin(1024, 754, 640);
        OfflineFaceAnalyzer.BundledYuNetEngine.InferenceSize small =
                OfflineFaceAnalyzer.BundledYuNetEngine.InferenceSize.fitWithin(319, 241, 640);

        assertEquals(640, large.width());
        assertEquals(471, large.height());
        assertEquals(319, small.width());
        assertEquals(241, small.height());
    }

    @Test
    public void outputParserReadsEachCv32fc1FifteenColumnRow() {
        Mat faces = new Mat(2, 15, CvType.CV_32FC1);
        try {
            float[] first = new float[15];
            first[0] = 10.0F;
            first[1] = 20.0F;
            first[2] = 30.0F;
            first[3] = 40.0F;
            first[14] = 0.90F;
            float[] second = first.clone();
            second[0] = 60.0F;
            int rowBytes = 15 * (Float.SIZE / Byte.SIZE);
            assertEquals(rowBytes, faces.put(0, 0, first));
            assertEquals(rowBytes, faces.put(1, 0, second));

            List<OfflineFaceAnalyzer.DetectedFace> parsed =
                    OfflineFaceAnalyzer.BundledYuNetEngine.readFaces(
                            faces,
                            new OfflineFaceAnalyzer.BundledYuNetEngine.InferenceSize(100, 100),
                            200,
                            200,
                            200,
                            200,
                            0);

            assertEquals(2, parsed.size());
            assertEquals(new Rect(20, 40, 80, 120), parsed.get(0).bounds());
            assertEquals(new Rect(120, 40, 180, 120), parsed.get(1).bounds());
        } finally {
            faces.release();
        }
    }

    @Test
    public void outputParserAcceptsEmptyDetectorOutput() {
        Mat faces = new Mat();
        try {
            assertTrue(readFaces(faces).isEmpty());
        } finally {
            faces.release();
        }
    }

    @Test
    public void outputParserRejectsTruncatedNonEmptyRow() {
        assertMalformedOutput(new Mat(1, 14, CvType.CV_32FC1));
    }

    @Test
    public void outputParserRejectsNonFloatOutput() {
        assertMalformedOutput(new Mat(1, 15, CvType.CV_64FC1));
    }

    @Test
    public void outputParserRejectsWrongChannelCount() {
        assertMalformedOutput(new Mat(1, 15, CvType.CV_32FC2));
    }

    @Test
    public void malformedOutputDiagnosticContainsOnlyShapeAndTypeMetadata() {
        assertEquals(
                "stage=OUTPUT_PARSE exception=MalformedOutput"
                        + " rows=1 columns=14 channels=2 type=13",
                OfflineFaceDiagnostics.malformedOutputMessage(1, 14, 2, 13));
    }

    private static void assertMalformedOutput(Mat faces) {
        try {
            assertThrows(IllegalStateException.class, () -> readFaces(faces));
        } finally {
            faces.release();
        }
    }

    private static List<OfflineFaceAnalyzer.DetectedFace> readFaces(Mat faces) {
        return OfflineFaceAnalyzer.BundledYuNetEngine.readFaces(
                faces,
                new OfflineFaceAnalyzer.BundledYuNetEngine.InferenceSize(100, 100),
                100,
                100,
                100,
                100,
                0);
    }

    @Test
    public void iouTrackingIsDeterministicAndRejectsImpossibleTransfer() {
        OfflineFaceAnalyzer.SessionIouTracker tracker =
                new OfflineFaceAnalyzer.SessionIouTracker(0.20);

        List<OfflineFaceAnalyzer.DetectedFace> first = tracker.assign(List.of(
                new OfflineFaceAnalyzer.DetectedFace(new Rect(70, 10, 90, 30), null),
                new OfflineFaceAnalyzer.DetectedFace(new Rect(10, 10, 30, 30), null)));
        List<OfflineFaceAnalyzer.DetectedFace> moved = tracker.assign(List.of(
                new OfflineFaceAnalyzer.DetectedFace(new Rect(12, 10, 32, 30), null),
                new OfflineFaceAnalyzer.DetectedFace(new Rect(68, 10, 88, 30), null)));
        List<OfflineFaceAnalyzer.DetectedFace> jumped = tracker.assign(List.of(
                new OfflineFaceAnalyzer.DetectedFace(new Rect(40, 60, 60, 80), null)));

        assertEquals(0L, first.get(0).trackingId().longValue());
        assertEquals(1L, first.get(1).trackingId().longValue());
        assertEquals(first.get(0).trackingId(), moved.get(0).trackingId());
        assertEquals(first.get(1).trackingId(), moved.get(1).trackingId());
        assertEquals(2L, jumped.get(0).trackingId().longValue());
    }

    @Test
    public void trackerClearEndsSessionAndDropsPriorGeometry() {
        OfflineFaceAnalyzer.SessionIouTracker tracker =
                new OfflineFaceAnalyzer.SessionIouTracker(0.20);
        tracker.assign(List.of(new OfflineFaceAnalyzer.DetectedFace(
                new Rect(10, 10, 30, 30), null)));

        tracker.clear();
        List<OfflineFaceAnalyzer.DetectedFace> newSession = tracker.assign(List.of(
                new OfflineFaceAnalyzer.DetectedFace(new Rect(10, 10, 30, 30), null)));

        assertEquals(0L, newSession.get(0).trackingId().longValue());
    }

    @Test
    public void closeIsIdempotentAndClosesInjectedEngineOnce() {
        FakeEngine engine = FakeEngine.success(List.of());
        OfflineFaceAnalyzer analyzer = new OfflineFaceAnalyzer(engine, 10L);

        analyzer.close();
        analyzer.close();

        assertEquals(1, engine.closeCount.get());
    }

    @Test
    public void bundledModelMatchesReviewedSizeAndDigest() {
        byte[] model = YuNetModelAsset.loadVerified(
                androidx.test.core.app.ApplicationProvider
                        .getApplicationContext()
                        .getAssets());

        assertEquals(YuNetModelAsset.EXPECTED_BYTES, model.length);
        assertEquals(YuNetModelAsset.EXPECTED_SHA256, YuNetModelAsset.sha256(model));

        model[0] ^= 1;
        assertThrows(IllegalStateException.class, () -> YuNetModelAsset.verify(model));
    }

    private static DetectorSnapshot await(ListenableFuture<DetectorSnapshot> future)
            throws Exception {
        return future.get(30, TimeUnit.SECONDS);
    }

    private static FaceAnalysisFrame proxyFrame(
            int width, int height, AtomicInteger closeCount) {
        ImageProxy proxy = (ImageProxy) Proxy.newProxyInstance(
                ImageProxy.class.getClassLoader(),
                new Class<?>[]{ImageProxy.class},
                (instance, method, arguments) -> switch (method.getName()) {
                    case "getWidth" -> width;
                    case "getHeight" -> height;
                    case "close" -> {
                        closeCount.incrementAndGet();
                        yield null;
                    }
                    case "toString" -> "SyntheticImageProxy";
                    case "hashCode" -> System.identityHashCode(instance);
                    case "equals" -> instance == arguments[0];
                    default -> null;
                });
        return new ImageProxyFaceAnalysisFrame(proxy);
    }

    private static final class CountingFrame implements FaceAnalysisFrame {
        private final int width;
        private final int height;
        private final Bitmap bitmap;
        private final AtomicInteger closeCount = new AtomicInteger();

        private CountingFrame(int width, int height) {
            this.width = width;
            this.height = height;
            bitmap = null;
        }

        private CountingFrame(Bitmap bitmap) {
            this.width = bitmap.getWidth();
            this.height = bitmap.getHeight();
            this.bitmap = bitmap;
        }

        @Override
        public Bitmap bitmap(int rotationDegrees) {
            if (bitmap == null) {
                throw new AssertionError("Fake engine must not request pixels");
            }
            return bitmap;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    private static final class FakeEngine implements OfflineFaceAnalyzer.FaceDetectionEngine {
        private enum Mode { SUCCESS, FAILURE, PENDING }

        private final Mode mode;
        private final List<OfflineFaceAnalyzer.DetectedFace> faces;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicInteger closeCount = new AtomicInteger();
        private OfflineFaceAnalyzer.DetectionCallback callback;
        private int rotationDegrees = -1;

        private FakeEngine(Mode mode, List<OfflineFaceAnalyzer.DetectedFace> faces) {
            this.mode = mode;
            this.faces = faces;
        }

        private static FakeEngine success(List<OfflineFaceAnalyzer.DetectedFace> faces) {
            return new FakeEngine(Mode.SUCCESS, faces);
        }

        private static FakeEngine failure() {
            return new FakeEngine(Mode.FAILURE, List.of());
        }

        private static FakeEngine pending() {
            return new FakeEngine(Mode.PENDING, List.of());
        }

        @Override
        public OfflineFaceAnalyzer.DetectionOperation detect(
                FaceAnalysisFrame frame,
                int rotationDegrees,
                OfflineFaceAnalyzer.DetectionCallback callback) {
            this.rotationDegrees = rotationDegrees;
            this.callback = callback;
            if (mode == Mode.SUCCESS) {
                callback.onSuccess(faces);
                callback.onInputReleased();
            } else if (mode == Mode.FAILURE) {
                callback.onFailure();
                callback.onInputReleased();
            }
            return () -> cancelled.set(true);
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }

        private void completeLate() {
            callback.onSuccess(List.of(new OfflineFaceAnalyzer.DetectedFace(
                    new Rect(1, 1, 2, 2), 1L)));
            callback.onInputReleased();
        }
    }
}
