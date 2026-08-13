package com.liveshield.vision.pii;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedPoint;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.TypedFailure;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import org.junit.Test;

/** Tests-first contract for T094's fully offline privacy-relevant barcode analysis. */
public final class BarcodePrivacyAnalyzerTest {
    private static final double EPSILON = 0.000001;
    private static final long FRESHNESS_NANOS = 200_000_000L;

    @Test
    public void matrixFormatsAlwaysProduceProtectedRegions() throws Exception {
        List<BarcodePrivacyAnalyzer.Format> formats = List.of(
                BarcodePrivacyAnalyzer.Format.QR_CODE,
                BarcodePrivacyAnalyzer.Format.PDF_417,
                BarcodePrivacyAnalyzer.Format.AZTEC,
                BarcodePrivacyAnalyzer.Format.DATA_MATRIX);

        DetectorSnapshot snapshot = successfulSnapshot(detections(formats));

        assertEquals(formats.size(), snapshot.findings().size());
        assertTrue(snapshot.findings().stream()
                .allMatch(region -> region.category() == FindingCategory.AUTO_BARCODE));
    }

    @Test
    public void majorOneDimensionalFormatsAreEnabledForPrivacyProtection() throws Exception {
        List<BarcodePrivacyAnalyzer.Format> formats = List.of(
                BarcodePrivacyAnalyzer.Format.CODE_128,
                BarcodePrivacyAnalyzer.Format.CODE_39,
                BarcodePrivacyAnalyzer.Format.EAN_13,
                BarcodePrivacyAnalyzer.Format.EAN_8,
                BarcodePrivacyAnalyzer.Format.UPC_A,
                BarcodePrivacyAnalyzer.Format.UPC_E,
                BarcodePrivacyAnalyzer.Format.ITF,
                BarcodePrivacyAnalyzer.Format.CODABAR);

        DetectorSnapshot snapshot = successfulSnapshot(detections(formats));

        assertEquals(formats.size(), snapshot.findings().size());
        assertTrue(snapshot.failure().isEmpty());
    }

    @Test
    public void boundingPolygonMapsThroughRotationCropAndMirror() throws Exception {
        FakeEngine engine = new FakeEngine();
        BarcodePrivacyAnalyzer analyzer = analyzer(engine, 4);
        FakeFrame frame = new FakeFrame();
        CoordinateTransform transform = new CoordinateTransform(new double[]{
            0.0, -0.5, 0.80,
            0.5, 0.0, 0.10,
            0.0, 0.0, 1.0
        });

        var future = analyzer.analyze(frame, timestamp(), 90, transform);
        engine.succeed(List.of(detection(
                BarcodePrivacyAnalyzer.Format.QR_CODE,
                0.20, 0.30, 0.40, 0.50,
                BarcodePrivacyAnalyzer.PayloadState.VALID)));
        DetectorSnapshot snapshot = future.get();

        assertEquals(1, snapshot.findings().size());
        assertBounds(snapshot.findings().get(0).bounds().get(0), 0.0, 0.30, 1.0, 1.0);
    }

    @Test
    public void emptyOrMalformedPayloadStillProtectsDetectedCodeGeometry() throws Exception {
        DetectorSnapshot snapshot = successfulSnapshot(List.of(
                detection(BarcodePrivacyAnalyzer.Format.QR_CODE,
                        0.10, 0.10, 0.30, 0.30,
                        BarcodePrivacyAnalyzer.PayloadState.EMPTY),
                detection(BarcodePrivacyAnalyzer.Format.DATA_MATRIX,
                        0.50, 0.50, 0.80, 0.80,
                        BarcodePrivacyAnalyzer.PayloadState.MALFORMED)));

        assertEquals(2, snapshot.findings().size());
        assertTrue(snapshot.failure().isEmpty());
    }

    @Test
    public void multipleCodesAreBoundedAndOverflowFailsPrivate() throws Exception {
        FakeEngine engine = new FakeEngine();
        BarcodePrivacyAnalyzer analyzer = analyzer(engine, 2);
        FakeFrame frame = new FakeFrame();

        var future = analyzer.analyze(
                frame, timestamp(), 0, CoordinateTransform.identity());
        engine.succeed(detections(List.of(
                BarcodePrivacyAnalyzer.Format.QR_CODE,
                BarcodePrivacyAnalyzer.Format.AZTEC,
                BarcodePrivacyAnalyzer.Format.PDF_417)));
        DetectorSnapshot snapshot = future.get();

        assertTrue(snapshot.findings().isEmpty());
        assertEquals(TypedFailure.Code.ANALYZER_ERROR,
                snapshot.failure().orElseThrow().code());
        assertEquals(1, frame.closeCount);
    }

    @Test
    public void successFailureAndCancellationReleaseInputExactlyOnce() throws Exception {
        FakeEngine successEngine = new FakeEngine();
        FakeFrame successFrame = new FakeFrame();
        var success = analyzer(successEngine, 4).analyze(
                successFrame, timestamp(), 0, CoordinateTransform.identity());
        successEngine.succeed(List.of(detection(
                BarcodePrivacyAnalyzer.Format.QR_CODE,
                0.10, 0.10, 0.20, 0.20,
                BarcodePrivacyAnalyzer.PayloadState.VALID)));
        success.get();
        assertEquals(1, successFrame.closeCount);

        FakeEngine failureEngine = new FakeEngine();
        FakeFrame failureFrame = new FakeFrame();
        var failure = analyzer(failureEngine, 4).analyze(
                failureFrame, timestamp(), 0, CoordinateTransform.identity());
        failureEngine.fail();
        assertEquals(TypedFailure.Code.ANALYZER_ERROR,
                failure.get().failure().orElseThrow().code());
        assertEquals(1, failureFrame.closeCount);

        FakeEngine cancelEngine = new FakeEngine();
        FakeFrame cancelFrame = new FakeFrame();
        BarcodePrivacyAnalyzer cancelAnalyzer = analyzer(cancelEngine, 4);
        var cancelled = cancelAnalyzer.analyze(
                cancelFrame, timestamp(), 0, CoordinateTransform.identity());
        cancelAnalyzer.cancelPending();
        cancelEngine.cancel();
        assertEquals(TypedFailure.Code.ANALYZER_CANCELLED,
                cancelled.get().failure().orElseThrow().code());
        assertEquals(1, cancelFrame.closeCount);
        assertEquals(1, cancelEngine.cancelCount);
    }

    @Test
    public void synchronousEngineFailureClosesInputAndReturnsTypedFailure() throws Exception {
        FakeEngine engine = new FakeEngine();
        engine.throwOnDecode = true;
        FakeFrame frame = new FakeFrame();

        DetectorSnapshot snapshot = analyzer(engine, 4).analyze(
                frame, timestamp(), 0, CoordinateTransform.identity()).get();

        assertEquals(TypedFailure.Code.ANALYZER_ERROR,
                snapshot.failure().orElseThrow().code());
        assertEquals(1, frame.closeCount);
    }

    @Test
    public void detectionsAndFindingsCannotRetainDecodedPayload() {
        for (Field field : BarcodePrivacyAnalyzer.DetectedBarcode.class.getDeclaredFields()) {
            assertFalse(isPayloadType(field.getType()));
            String name = field.getName().toLowerCase(Locale.ROOT);
            assertFalse(name.contains("payloadtext"));
            assertFalse(name.contains("rawbytes"));
        }
        BarcodePrivacyAnalyzer.DetectedBarcode detection = detection(
                BarcodePrivacyAnalyzer.Format.QR_CODE,
                0.10, 0.10, 0.20, 0.20,
                BarcodePrivacyAnalyzer.PayloadState.MALFORMED);
        assertNotNull(detection.polygon());
        assertFalse(detection.toString().contains("example.org"));
    }

    @Test
    public void realOfflineZxingEngineDecodesSyntheticQrWithoutRetainingPayload()
            throws Exception {
        int width = 128;
        int height = 128;
        var matrix = new MultiFormatWriter().encode(
                "fictional-local-only-code", BarcodeFormat.QR_CODE, width, height);
        byte[] luminance = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                luminance[y * width + x] = matrix.get(x, y) ? (byte) 0 : (byte) 0xff;
            }
        }
        LuminanceFrame frame = new LuminanceFrame(width, height, luminance);
        try (OfflineBarcodeAnalyzer analyzer = new OfflineBarcodeAnalyzer()) {
            DetectorSnapshot snapshot = analyzer.analyze(
                            frame, timestamp(), 0, CoordinateTransform.identity())
                    .get();
            assertTrue(snapshot.failure().isEmpty());
            assertFalse(snapshot.findings().isEmpty());
            assertTrue(snapshot.findings().stream()
                    .allMatch(region -> region.category() == FindingCategory.AUTO_BARCODE));
            assertEquals(1, frame.closeCount);
        }
    }

    @Test
    public void realEngineDecodesSmallEmbeddedQrAfterBoundedUpscale() throws Exception {
        int width = 192;
        int height = 128;
        int symbolSize = 48;
        var matrix = new MultiFormatWriter().encode(
                "fictional-development-sized-code",
                BarcodeFormat.QR_CODE,
                symbolSize,
                symbolSize,
                java.util.Map.of(com.google.zxing.EncodeHintType.MARGIN, 4));
        byte[] luminance = new byte[width * height];
        java.util.Arrays.fill(luminance, (byte) 0x78);
        int left = 24;
        int top = 24;
        for (int y = 0; y < symbolSize; y++) {
            for (int x = 0; x < symbolSize; x++) {
                luminance[(top + y) * width + left + x] =
                        matrix.get(x, y) ? (byte) 0 : (byte) 0xff;
            }
        }

        LuminanceFrame frame = new LuminanceFrame(width, height, luminance);
        try (OfflineBarcodeAnalyzer analyzer = new OfflineBarcodeAnalyzer()) {
            DetectorSnapshot snapshot = analyzer.analyze(
                            frame, timestamp(), 0, CoordinateTransform.identity())
                    .get();

            assertTrue(snapshot.failure().isEmpty());
            assertEquals(1, snapshot.findings().size());
            assertEquals(FindingCategory.AUTO_BARCODE,
                    snapshot.findings().get(0).category());
            assertEquals(1, frame.closeCount);
        }
    }

    @Test
    public void smallCameraInputIsNearestUpscaledWithinBound() {
        int width = 192;
        int height = 128;
        byte[] luminance = new byte[width * height];
        java.util.Arrays.fill(luminance, (byte) 0x7f);

        OfflineBarcodeAnalyzer.ZxingBarcodeEngine.ScaledLuminance scaled =
                OfflineBarcodeAnalyzer.ZxingBarcodeEngine.upscaleSmallInput(
                        luminance, width, height);

        assertEquals(384, scaled.width());
        assertEquals(256, scaled.height());
        assertEquals(384 * 256, scaled.bytes().length);
        assertEquals(luminance[0], scaled.bytes()[0]);
        assertEquals(luminance[width + 1], scaled.bytes()[2 * scaled.width() + 2]);
    }

    @Test
    public void ordinaryCameraInputIsNotCopiedOrUpscaled() {
        byte[] luminance = new byte[640 * 480];

        OfflineBarcodeAnalyzer.ZxingBarcodeEngine.ScaledLuminance scaled =
                OfflineBarcodeAnalyzer.ZxingBarcodeEngine.upscaleSmallInput(
                        luminance, 640, 480);

        assertTrue(luminance == scaled.bytes());
        assertEquals(640, scaled.width());
        assertEquals(480, scaled.height());
    }

    @Test
    public void realOneDimensionalDecodeUsesFullFrameWhenZxingReturnsOnlyCenterline()
            throws Exception {
        int width = 320;
        int height = 120;
        var matrix = new MultiFormatWriter().encode(
                "FICTIONAL-128", BarcodeFormat.CODE_128, width, height);
        byte[] luminance = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                luminance[y * width + x] = matrix.get(x, y) ? (byte) 0 : (byte) 0xff;
            }
        }
        LuminanceFrame frame = new LuminanceFrame(width, height, luminance);
        try (OfflineBarcodeAnalyzer analyzer = new OfflineBarcodeAnalyzer()) {
            DetectorSnapshot snapshot = analyzer.analyze(
                            frame, timestamp(), 0, CoordinateTransform.identity())
                    .get();
            assertTrue(snapshot.failure().isEmpty());
            assertEquals(1, snapshot.findings().size());
            assertBounds(snapshot.findings().get(0).bounds().get(0), 0.0, 0.0, 1.0, 1.0);
            assertEquals(1, frame.closeCount);
        }
    }

    private static DetectorSnapshot successfulSnapshot(
            List<BarcodePrivacyAnalyzer.DetectedBarcode> detections)
            throws InterruptedException, ExecutionException {
        FakeEngine engine = new FakeEngine();
        BarcodePrivacyAnalyzer analyzer = analyzer(engine, 16);
        FakeFrame frame = new FakeFrame();
        var future = analyzer.analyze(
                frame, timestamp(), 0, CoordinateTransform.identity());
        engine.succeed(detections);
        DetectorSnapshot snapshot = future.get();
        assertEquals(1, frame.closeCount);
        assertTrue(snapshot.failure().isEmpty());
        return snapshot;
    }

    private static BarcodePrivacyAnalyzer analyzer(FakeEngine engine, int maximumCodes) {
        return new BarcodePrivacyAnalyzer(engine, maximumCodes, FRESHNESS_NANOS);
    }

    private static FrameTimestamp timestamp() {
        return FrameTimestamp.ofNanos(1_000_000_000L);
    }

    private static List<BarcodePrivacyAnalyzer.DetectedBarcode> detections(
            List<BarcodePrivacyAnalyzer.Format> formats) {
        List<BarcodePrivacyAnalyzer.DetectedBarcode> detections = new ArrayList<>();
        double offset = 0.05;
        for (BarcodePrivacyAnalyzer.Format format : formats) {
            detections.add(detection(
                    format, offset, offset, offset + 0.04, offset + 0.04,
                    BarcodePrivacyAnalyzer.PayloadState.VALID));
            offset += 0.06;
        }
        return detections;
    }

    private static BarcodePrivacyAnalyzer.DetectedBarcode detection(
            BarcodePrivacyAnalyzer.Format format,
            double left,
            double top,
            double right,
            double bottom,
            BarcodePrivacyAnalyzer.PayloadState payloadState) {
        return new BarcodePrivacyAnalyzer.DetectedBarcode(
                format,
                List.of(
                        new NormalizedPoint(left, top),
                        new NormalizedPoint(right, top),
                        new NormalizedPoint(right, bottom),
                        new NormalizedPoint(left, bottom)),
                payloadState);
    }

    private static void assertBounds(
            NormalizedRect bounds,
            double left,
            double top,
            double right,
            double bottom) {
        assertEquals(left, bounds.left(), EPSILON);
        assertEquals(top, bounds.top(), EPSILON);
        assertEquals(right, bounds.right(), EPSILON);
        assertEquals(bottom, bounds.bottom(), EPSILON);
    }

    private static boolean isPayloadType(Class<?> type) {
        return type == String.class
                || type == char[].class
                || type == byte[].class
                || type == ByteBuffer.class;
    }

    private static final class FakeFrame
            implements BarcodePrivacyAnalyzer.BarcodeAnalysisFrame {
        private int closeCount;

        @Override
        public int width() {
            return 100;
        }

        @Override
        public int height() {
            return 100;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    private static final class LuminanceFrame
            implements OfflineBarcodeAnalyzer.BarcodeAnalysisFrame {
        private final int width;
        private final int height;
        private final byte[] luminance;
        private int closeCount;

        private LuminanceFrame(int width, int height, byte[] luminance) {
            this.width = width;
            this.height = height;
            this.luminance = luminance.clone();
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
        public byte[] luminance() {
            return luminance;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    private static final class FakeEngine implements BarcodePrivacyAnalyzer.BarcodeEngine {
        private BarcodePrivacyAnalyzer.DecodeCallback callback;
        private int cancelCount;
        private boolean throwOnDecode;

        @Override
        public BarcodePrivacyAnalyzer.DecodeOperation decode(
                BarcodePrivacyAnalyzer.BarcodeAnalysisFrame frame,
                BarcodePrivacyAnalyzer.DecodeCallback callback) {
            if (throwOnDecode) {
                throw new IllegalStateException("fictional decoder start failure");
            }
            this.callback = callback;
            return () -> cancelCount++;
        }

        @Override
        public void close() {
        }

        private void succeed(List<BarcodePrivacyAnalyzer.DetectedBarcode> detections) {
            assertNotNull(callback);
            callback.onSuccess(detections);
            callback.onInputReleased();
        }

        private void fail() {
            assertNotNull(callback);
            callback.onFailure();
            callback.onInputReleased();
        }

        private void cancel() {
            assertNotNull(callback);
            callback.onCancelled();
            callback.onInputReleased();
        }
    }
}
