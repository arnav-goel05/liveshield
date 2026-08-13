package com.liveshield.vision.pii;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedPoint;
import com.liveshield.privacy.model.TypedFailure;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.junit.Test;

public final class OfflineTextAnalyzerTest {
    @Test
    public void elementPolygonsFeedClassifierAndOnlyProtectedMetadataLeavesAnalyzer()
            throws Exception {
        FakeEngine engine = new FakeEngine(List.of(new OfflineTextAnalyzer.RecognizedElement(
                "mail user@example.com",
                rectangle(0.1, 0.2, 0.8, 0.3),
                0.92,
                true)));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        OfflineTextAnalyzer analyzer = analyzer(engine, executor);
        FakeFrame frame = new FakeFrame();

        DetectorSnapshot result = analyzer.analyze(
                frame,
                FrameTimestamp.ofNanos(100),
                0,
                CoordinateTransform.identity()).get(2, TimeUnit.SECONDS);

        assertEquals(DetectorLane.TEXT, result.lane());
        assertTrue(result.failure().isEmpty());
        assertEquals(1, result.findings().size());
        assertEquals(1, frame.closeCount.get());
        assertFalse(result.toString().contains("user@example.com"));
        analyzer.close();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    public void sessionWatchlistUpdatesAffectLaterFramesAndAreClearedOnClose() throws Exception {
        FakeEngine engine = new FakeEngine(List.of(new OfflineTextAnalyzer.RecognizedElement(
                "fictional employer",
                rectangle(0.1, 0.2, 0.8, 0.3),
                0.92,
                true)));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        OfflineTextAnalyzer analyzer = analyzer(engine, executor);

        DetectorSnapshot before = analyzer.analyze(
                new FakeFrame(), FrameTimestamp.ofNanos(101), 0,
                CoordinateTransform.identity()).get(2, TimeUnit.SECONDS);
        analyzer.updateNormalizedWatchlistTerms(Set.of("fictional employer"));
        DetectorSnapshot after = analyzer.analyze(
                new FakeFrame(), FrameTimestamp.ofNanos(102), 0,
                CoordinateTransform.identity()).get(2, TimeUnit.SECONDS);

        assertTrue(before.findings().isEmpty());
        assertEquals(1, after.findings().size());
        assertFalse(after.toString().contains("fictional employer"));
        analyzer.close();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    public void secondRequestFailsAtCapacityAndReleasesItsInput() throws Exception {
        BlockingEngine engine = new BlockingEngine();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        OfflineTextAnalyzer analyzer = analyzer(engine, executor);
        FakeFrame first = new FakeFrame();
        FakeFrame second = new FakeFrame();
        analyzer.analyze(first, FrameTimestamp.ofNanos(1), 0, CoordinateTransform.identity());
        assertTrue(engine.entered.await(2, TimeUnit.SECONDS));

        DetectorSnapshot rejected = analyzer.analyze(
                second, FrameTimestamp.ofNanos(2), 0, CoordinateTransform.identity())
                .get(2, TimeUnit.SECONDS);

        assertEquals(TypedFailure.Code.QUEUE_CAPACITY,
                rejected.failure().orElseThrow().code());
        assertEquals(1, second.closeCount.get());
        engine.release.countDown();
        analyzer.close();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        assertEquals(1, first.closeCount.get());
    }

    @Test
    public void cancelDuringNativeWorkCompletesOnlyAfterWorkerReleasesInput()
            throws Exception {
        BlockingEngine engine = new BlockingEngine();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        OfflineTextAnalyzer analyzer = analyzer(engine, executor);
        FakeFrame frame = new FakeFrame();
        var result = analyzer.analyze(
                frame, FrameTimestamp.ofNanos(3), 0, CoordinateTransform.identity());
        assertTrue(engine.entered.await(2, TimeUnit.SECONDS));

        analyzer.cancelPending();
        assertFalse(result.isDone());
        assertEquals(0, frame.closeCount.get());
        engine.release.countDown();

        DetectorSnapshot cancelled = result.get(2, TimeUnit.SECONDS);
        assertEquals(TypedFailure.Code.ANALYZER_CANCELLED,
                cancelled.failure().orElseThrow().code());
        assertEquals(1, frame.closeCount.get());
        analyzer.close();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    public void successfulFutureCannotCompleteWhileInputCloseIsStillRunning() throws Exception {
        FakeEngine engine = new FakeEngine(List.of());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        OfflineTextAnalyzer analyzer = analyzer(engine, executor);
        CloseBlockingFrame frame = new CloseBlockingFrame();

        var result = analyzer.analyze(
                frame, FrameTimestamp.ofNanos(31), 0, CoordinateTransform.identity());
        assertTrue(frame.closeEntered.await(2, TimeUnit.SECONDS));

        assertFalse(result.isDone());
        frame.allowClose.countDown();
        DetectorSnapshot completed = result.get(2, TimeUnit.SECONDS);

        assertTrue(completed.failure().isEmpty());
        assertEquals(1, frame.closeCount.get());
        analyzer.close();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    public void closeDuringRunCannotCloseEngineUntilWorkerReleasesInput() throws Exception {
        BlockingEngine engine = new BlockingEngine();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        OfflineTextAnalyzer analyzer = analyzer(engine, executor);
        FakeFrame frame = new FakeFrame();
        analyzer.analyze(frame, FrameTimestamp.ofNanos(4), 0, CoordinateTransform.identity());
        assertTrue(engine.entered.await(2, TimeUnit.SECONDS));

        analyzer.close();

        assertFalse(engine.closed.get());
        assertEquals(0, frame.closeCount.get());
        engine.release.countDown();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        assertEquals(1, frame.closeCount.get());
        assertTrue(engine.closed.get());
        assertFalse(engine.closeRacedWithRun.get());
    }

    @Test
    public void queuedCancellationProvesNoWorkerOwnershipAndReleasesImmediately()
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch releaseExecutor = new CountDownLatch(1);
        executor.execute(() -> await(releaseExecutor));
        FakeEngine engine = new FakeEngine(List.of());
        OfflineTextAnalyzer analyzer = analyzer(engine, executor);
        FakeFrame frame = new FakeFrame();
        var result = analyzer.analyze(
                frame, FrameTimestamp.ofNanos(5), 0, CoordinateTransform.identity());

        analyzer.cancelPending();

        assertEquals(TypedFailure.Code.ANALYZER_CANCELLED,
                result.get(2, TimeUnit.SECONDS).failure().orElseThrow().code());
        assertEquals(1, frame.closeCount.get());
        assertEquals(0, engine.calls.get());
        releaseExecutor.countDown();
        analyzer.close();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    public void engineFailureIsTypedAndAlwaysReleasesInput() throws Exception {
        FakeEngine engine = new FakeEngine(List.of());
        engine.fail = true;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        OfflineTextAnalyzer analyzer = analyzer(engine, executor);
        FakeFrame frame = new FakeFrame();

        DetectorSnapshot result = analyzer.analyze(
                frame, FrameTimestamp.ofNanos(6), 0, CoordinateTransform.identity())
                .get(2, TimeUnit.SECONDS);

        assertEquals(TypedFailure.Code.ANALYZER_ERROR,
                result.failure().orElseThrow().code());
        assertEquals(1, frame.closeCount.get());
        analyzer.close();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    public void paddleCtcDecoderCollapsesBlanksAndRepeatsWithoutRetainingInput() {
        List<String> dictionary = paddleDictionary();
        int a = dictionary.indexOf("a") + 1;
        int b = dictionary.indexOf("b") + 1;
        float[] output = paddleProbabilities(a, a, 0, b);

        PaddleLiteTextRecognitionEngine.Recognition result =
                PaddleLiteTextRecognitionEngine.decodePaddleCtc(
                        output, new long[]{1, 4, 97}, dictionary);

        assertEquals("ab", result.text());
        assertTrue(result.confidence() > 0.99);
    }

    @Test
    public void paddleRecognitionUsesBgrNormalizationAndZeroPadding() {
        int width = 2;
        int height = 48;
        byte[] bgr = new byte[width * height * 3];
        for (int row = 0; row < height; row++) {
            int first = row * width * 3;
            bgr[first] = 0;
            bgr[first + 1] = (byte) 128;
            bgr[first + 2] = (byte) 255;
        }

        float[] tensor = PaddleLiteTextRecognitionEngine.recognizerNormalizedBgrChw(
                bgr, width, height);

        assertEquals(3 * 48 * 320, tensor.length);
        int plane = 48 * 320;
        assertEquals(-1.0F, tensor[0], 0.0001F);
        assertEquals(128.0F / 127.5F - 1.0F, tensor[plane], 0.0001F);
        assertEquals(1.0F, tensor[plane * 2], 0.0001F);
        assertEquals(0.0F, tensor[2], 0.0001F);
        assertEquals(0.0F, tensor[plane + 2], 0.0001F);
        assertEquals(0.0F, tensor[plane * 2 + 2], 0.0001F);
    }

    @Test
    public void ppOcrV3DetectorKeepsAsymmetricBgrChannelOrderAndNormalization() {
        byte[] bgr = {(byte) 17, (byte) 83, (byte) 241};

        float[] tensor = PaddleLiteTextRecognitionEngine.detectorNormalizedBgrChw(bgr, 1, 1);

        assertEquals((17.0F / 255.0F - 0.485F) / 0.229F, tensor[0], 0.0001F);
        assertEquals((83.0F / 255.0F - 0.456F) / 0.224F, tensor[1], 0.0001F);
        assertEquals((241.0F / 255.0F - 0.406F) / 0.225F, tensor[2], 0.0001F);
    }

    @Test
    public void dbMaskedScoreExcludesSurroundingBackground() {
        float[] probability = new float[25];
        for (int row = 1; row < 4; row++) {
            for (int column = 1; column < 4; column++) {
                probability[row * 5 + column] = 0.9F;
            }
        }
        List<Point> core = List.of(
                new Point(1, 1), new Point(4, 1),
                new Point(4, 4), new Point(1, 4));

        double score = PaddleLiteTextRecognitionEngine.maskedPolygonScore(
                probability, 5, 5, core);

        assertEquals(0.9, score, 0.0001);
    }

    @Test
    public void dbRotatedBoxAndExactUnclipConservativelyContainCore() {
        PaddleLiteTextRecognitionEngine.DbBox core =
                new PaddleLiteTextRecognitionEngine.DbBox(10, 10, 8, 4, 30);

        PaddleLiteTextRecognitionEngine.DbBox expanded =
                PaddleLiteTextRecognitionEngine.unclip(core, 1.6);
        List<Point> coreVertices = PaddleLiteTextRecognitionEngine.vertices(core);
        List<Point> expandedVertices = PaddleLiteTextRecognitionEngine.vertices(expanded);

        double distance = (8.0 * 4.0 * 1.6) / (2.0 * (8.0 + 4.0));
        assertEquals(8.0 + 2.0 * distance, expanded.width(), 0.0001);
        assertEquals(4.0 + 2.0 * distance, expanded.height(), 0.0001);
        assertFalse(coreVertices.get(0).x == coreVertices.get(1).x);
        Rect coreBounds = PaddleLiteTextRecognitionEngine.conservativeSourceBounds(
                coreVertices, 20, 20, 20, 20);
        Rect expandedBounds = PaddleLiteTextRecognitionEngine.conservativeSourceBounds(
                expandedVertices, 20, 20, 20, 20);
        assertTrue(expandedBounds.x <= coreBounds.x);
        assertTrue(expandedBounds.y <= coreBounds.y);
        assertTrue(expandedBounds.x + expandedBounds.width
                >= coreBounds.x + coreBounds.width);
        assertTrue(expandedBounds.y + expandedBounds.height
                >= coreBounds.y + coreBounds.height);
    }

    @Test
    public void dbExpandedBoundsClampAtFrameEdgesAndRejectMalformedGeometry() {
        Rect clamped = PaddleLiteTextRecognitionEngine.conservativeSourceBounds(
                List.of(new Point(-4, -2), new Point(8, -2),
                        new Point(8, 6), new Point(-4, 6)),
                10, 10, 100, 50);

        assertEquals(0, clamped.x);
        assertEquals(0, clamped.y);
        assertEquals(80, clamped.width);
        assertEquals(30, clamped.height);
        assertNull(PaddleLiteTextRecognitionEngine.conservativeSourceBounds(
                List.of(new Point(Double.NaN, 0), new Point(1, 0), new Point(1, 1)),
                10, 10, 100, 50));
        assertNull(PaddleLiteTextRecognitionEngine.conservativeSourceBounds(
                List.of(new Point(-3, -3), new Point(-2, -3), new Point(-2, -2)),
                10, 10, 100, 50));
    }

    @Test(expected = IllegalArgumentException.class)
    public void dbUnclipRejectsDegenerateBoxesFailPrivate() {
        PaddleLiteTextRecognitionEngine.unclip(
                new PaddleLiteTextRecognitionEngine.DbBox(1, 1, 0, 2, 0), 1.6);
    }

    @Test(expected = IllegalArgumentException.class)
    public void paddleRecognitionRejectsWidthBeyondFixedTensor() {
        PaddleLiteTextRecognitionEngine.recognizerNormalizedBgrChw(
                new byte[3 * 48 * 321], 321, 48);
    }

    @Test
    public void auditedRuntimeCompatibilityMatrixRejectsUnknownModelOptimizers() {
        assertTrue(PaddleOcrAssets.runtimeSupportsOptimizer("v2.11", "v2.10"));
        assertTrue(PaddleOcrAssets.runtimeSupportsOptimizer("v2.11", "v2.11-rc"));
        assertFalse(PaddleOcrAssets.runtimeSupportsOptimizer("v2.14-rc", "v2.10"));
        assertFalse(PaddleOcrAssets.runtimeSupportsOptimizer("v2.11", "v2.12"));
    }

    @Test
    public void paddleDictionaryAndOutputDimensionCoverStructuredPunctuation() {
        List<String> dictionary = paddleDictionary();
        assertEquals(96, dictionary.size());
        assertEquals(97, PaddleLiteTextRecognitionEngine.RECOGNIZER_CLASSES);
        for (char value : "@._+-/:()".toCharArray()) {
            assertTrue(dictionary.contains(String.valueOf(value)));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void paddleOutputRejectsWrongClassDimension() {
        PaddleLiteTextRecognitionEngine.decodePaddleCtc(
                new float[96], new long[]{1, 1, 96}, paddleDictionary());
    }

    @Test
    public void recognitionDiagnosticsExposeOnlyCountsBucketsAndNormalizedDistance() {
        List<OfflineTextAnalyzer.RecognizedElement> elements = List.of(
                new OfflineTextAnalyzer.RecognizedElement(
                        "DEVl@EXAMPLE.TEST", rectangle(0.1, 0.1, 0.4, 0.2), 0.91, true),
                new OfflineTextAnalyzer.RecognizedElement(
                        "DECOY", rectangle(0.1, 0.3, 0.2, 0.4), 0.62, true));

        PaddleLiteTextRecognitionEngine.RecognitionDiagnostics diagnostic =
                PaddleLiteTextRecognitionEngine.recognitionDiagnostics(
                        3, 3, elements, "DEV1@EXAMPLE.TEST");

        assertEquals(3, diagnostic.detectedRegions());
        assertEquals(3, diagnostic.recognitionAttempts());
        assertEquals(2, diagnostic.recognizedElements());
        assertEquals(22, diagnostic.recognizedCharacters());
        assertEquals(0, diagnostic.lowConfidenceElements());
        assertEquals(1, diagnostic.mediumConfidenceElements());
        assertEquals(1, diagnostic.highConfidenceElements());
        assertFalse(diagnostic.exactExpectedElement());
        assertEquals(1.0 / 17.0, diagnostic.minimumNormalizedEditDistance(), 0.0001);
        assertFalse(diagnostic.toString().contains("EXAMPLE"));
    }

    @Test
    public void smallDetectorInputUsesBoundedUpscaleWithoutChangingAspectRatio() {
        assertArrayEquals(new int[]{640, 416},
                PaddleLiteTextRecognitionEngine.fittedDimensions(192, 128, 640));
        assertArrayEquals(new int[]{640, 352},
                PaddleLiteTextRecognitionEngine.fittedDimensions(1280, 720, 640));
    }

    private static OfflineTextAnalyzer analyzer(
            OfflineTextAnalyzer.TextRecognitionEngine engine, ExecutorService executor) {
        return new OfflineTextAnalyzer(
                engine,
                new OcrPrivacyClassifier(),
                OfflineTextAnalyzer.Configuration.defaults(Set.of()),
                executor);
    }

    private static float[] paddleProbabilities(int... selectedClasses) {
        float[] result = new float[selectedClasses.length
                * PaddleLiteTextRecognitionEngine.RECOGNIZER_CLASSES];
        for (int step = 0; step < selectedClasses.length; step++) {
            result[step * PaddleLiteTextRecognitionEngine.RECOGNIZER_CLASSES
                    + selectedClasses[step]] = 1.0F;
        }
        return result;
    }

    private static List<String> paddleDictionary() {
        String values = "0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`"
                + "abcdefghijklmnopqrstuvwxyz{|}~!\"#$%&'()*+,-./ ";
        List<String> result = new java.util.ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            result.add(values.substring(index, index + 1));
        }
        result.add(" ");
        return List.copyOf(result);
    }

    private static List<NormalizedPoint> rectangle(
            double left, double top, double right, double bottom) {
        return List.of(
                new NormalizedPoint(left, top),
                new NormalizedPoint(right, top),
                new NormalizedPoint(right, bottom),
                new NormalizedPoint(left, bottom));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static class FakeEngine implements OfflineTextAnalyzer.TextRecognitionEngine {
        private final List<OfflineTextAnalyzer.RecognizedElement> results;
        private final AtomicInteger calls = new AtomicInteger();
        protected final AtomicBoolean closed = new AtomicBoolean();
        private boolean fail;

        private FakeEngine(List<OfflineTextAnalyzer.RecognizedElement> results) {
            this.results = results;
        }

        @Override
        public List<OfflineTextAnalyzer.RecognizedElement> recognize(
                TextAnalysisFrame frame, int rotationDegrees, AtomicBoolean cancellation) {
            calls.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("synthetic OCR error");
            }
            return results;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class BlockingEngine extends FakeEngine {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicBoolean closeRacedWithRun = new AtomicBoolean();

        private BlockingEngine() {
            super(List.of());
        }

        @Override
        public List<OfflineTextAnalyzer.RecognizedElement> recognize(
                TextAnalysisFrame frame, int rotationDegrees, AtomicBoolean cancellation) {
            running.set(true);
            entered.countDown();
            await(release);
            running.set(false);
            return List.of();
        }

        @Override
        public void close() {
            closeRacedWithRun.set(running.get());
            super.close();
        }
    }

    private static class FakeFrame implements TextAnalysisFrame {
        protected final AtomicInteger closeCount = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public Bitmap bitmap(int rotationDegrees) {
            throw new AssertionError("Fake engine must not request Android pixels");
        }

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
            closeCount.incrementAndGet();
            closed.countDown();
        }
    }

    private static final class CloseBlockingFrame extends FakeFrame {
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch allowClose = new CountDownLatch(1);

        @Override
        public void close() {
            closeEntered.countDown();
            await(allowClose);
            super.close();
        }
    }
}
