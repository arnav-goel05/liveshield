package com.liveshield.vision.pii;

import android.content.Context;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import com.google.common.util.concurrent.ListenableFuture;
import com.liveshield.privacy.model.ConfidenceClass;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedPoint;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.model.ProtectionAction;
import com.liveshield.privacy.model.TypedFailure;
import com.liveshield.vision.contract.AnalysisFrameHandle;
import com.liveshield.vision.contract.VisionAnalyzer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fully offline, bounded PP-OCR text analyzer.
 *
 * <p>Recognized text exists only as method-local data while deterministic validators map it to
 * protected geometry. Only category, confidence, polygon-derived bounds, and timestamps leave the
 * analyzer. There is exactly one in-flight request and no internal frame queue.</p>
 */
public final class OfflineTextAnalyzer implements VisionAnalyzer, AutoCloseable {
    /**
     * Bounds how long a camera-time OCR observation may remain usable.
     *
     * <p>The stock API-24 ONNX Runtime English recognizer takes roughly 1.2--1.5 seconds on the
     * reference SM-S921B. A 2.5 second window admits that completed result plus bounded device
     * jitter without changing any recognition or watchlist threshold. Older results still fail
     * private.</p>
     */
    static final long DEFAULT_FRESHNESS_NANOS = 2_500_000_000L;
    private final TextRecognitionEngine engine;
    private final OcrPrivacyClassifier classifier;
    private final Configuration configuration;
    private final AtomicReference<Set<String>> normalizedWatchlistTerms;
    private final ExecutorService executor;
    private final Object lifecycleLock = new Object();
    private final AtomicReference<PendingAnalysis> pending = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public OfflineTextAnalyzer(Context context, Configuration configuration) {
        this(
                new PaddleLiteTextRecognitionEngine(context),
                new OcrPrivacyClassifier(),
                configuration,
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "offline-paddle-ocr");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    OfflineTextAnalyzer(
            TextRecognitionEngine engine,
            OcrPrivacyClassifier classifier,
            Configuration configuration,
            ExecutorService executor) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        normalizedWatchlistTerms = new AtomicReference<>(configuration.normalizedWatchlistTerms());
        this.executor = Objects.requireNonNull(executor, "executor");
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
        if (!(input instanceof TextAnalysisFrame frame) || !validRotation(rotationDegrees)) {
            input.close();
            return immediate(failure(timestamp, TypedFailure.Code.ANALYZER_ERROR));
        }
        synchronized (lifecycleLock) {
            if (closed.get()) {
                frame.close();
                return immediate(failure(timestamp, TypedFailure.Code.ANALYZER_ERROR));
            }
            if (normalizedWatchlistTerms.get().isEmpty()) {
                frame.close();
                return immediate(DetectorSnapshot.success(
                        DetectorLane.TEXT,
                        timestamp,
                        timestamp.plusNanos(configuration.freshnessNanos()),
                        List.of()));
            }
            PendingAnalysis request = new PendingAnalysis(frame, timestamp);
            if (!pending.compareAndSet(null, request)) {
                frame.close();
                return immediate(failure(timestamp, TypedFailure.Code.QUEUE_CAPACITY));
            }
            try {
                Future<?> task = executor.submit(() -> {
                    request.workerStarted();
                    run(request, rotationDegrees, sensorToBufferTransform);
                });
                request.setTask(task);
            } catch (RuntimeException rejected) {
                request.completeAfterRelease(
                        failure(timestamp, TypedFailure.Code.ANALYZER_ERROR));
            }
            return request.result;
        }
    }

    private void run(
            PendingAnalysis request,
            int rotationDegrees,
            CoordinateTransform transform) {
        DetectorSnapshot outcome;
        try {
            if (request.cancelled.get()) {
                outcome = failure(request.timestamp, TypedFailure.Code.ANALYZER_CANCELLED);
            } else {
                List<RecognizedElement> recognized = engine.recognize(
                        request.frame, rotationDegrees, request.cancelled);
                outcome = request.cancelled.get()
                        ? failure(request.timestamp, TypedFailure.Code.ANALYZER_CANCELLED)
                        : toSnapshot(request.timestamp, recognized, transform, rotationDegrees);
            }
        } catch (RuntimeException | LinkageError recognitionFailure) {
            outcome = failure(request.timestamp, request.cancelled.get()
                    ? TypedFailure.Code.ANALYZER_CANCELLED
                    : TypedFailure.Code.ANALYZER_ERROR);
        }
        request.completeAfterRelease(outcome);
    }

    private DetectorSnapshot toSnapshot(
            FrameTimestamp timestamp,
            List<RecognizedElement> engineElements,
            CoordinateTransform transform,
            int rotationDegrees) {
        if (engineElements.size() > configuration.maximumElements()) {
            throw new IllegalArgumentException("OCR result exceeds element bound");
        }
        List<RecognizedElement> elements = new ArrayList<>(engineElements);
        elements.sort(Comparator
                .comparingDouble((RecognizedElement value) -> top(value.polygon()))
                .thenComparingDouble(value -> left(value.polygon())));
        StringBuilder text = new StringBuilder();
        List<OcrRegionMapper.OcrElement> mappedElements = new ArrayList<>();
        for (RecognizedElement element : elements) {
            validateElement(element);
            if (text.length() > 0) {
                text.append(' ');
            }
            int start = text.length();
            text.append(element.text());
            if (text.length() > configuration.maximumRecognizedCharacters()) {
                throw new IllegalArgumentException("OCR text exceeds character bound");
            }
            mappedElements.add(new OcrRegionMapper.OcrElement(
                    start,
                    text.length(),
                    toAnalysisBufferOrientation(element.polygon(), rotationDegrees),
                    element.confidence(),
                    element.boundaryCertain()));
        }

        List<OcrRegionMapper.MappedRegion> mapped = classifier.classifyWatchlistOnly(
                text.toString(),
                mappedElements,
                normalizedWatchlistTerms.get(),
                transform,
                configuration.mappingOptions());
        List<ProtectedRegion> protectedRegions = mapped.stream()
                .map(OfflineTextAnalyzer::toProtectedRegion)
                .toList();
        text.setLength(0);
        return DetectorSnapshot.success(
                DetectorLane.TEXT,
                timestamp,
                timestamp.plusNanos(configuration.freshnessNanos()),
                protectedRegions);
    }

    /**
     * Converts polygons from the upright bitmap used by OCR back into CameraX analysis-buffer
     * coordinates. The shared sensor-to-buffer transform can only be inverted after this step.
     */
    private static List<NormalizedPoint> toAnalysisBufferOrientation(
            List<NormalizedPoint> uprightPolygon, int rotationDegrees) {
        return uprightPolygon.stream().map(point -> switch (rotationDegrees) {
            case 0 -> point;
            case 90 -> new NormalizedPoint(point.y(), 1.0 - point.x());
            case 180 -> new NormalizedPoint(1.0 - point.x(), 1.0 - point.y());
            case 270 -> new NormalizedPoint(1.0 - point.y(), point.x());
            default -> throw new IllegalArgumentException("Unsupported frame rotation");
        }).toList();
    }

    private static ProtectedRegion toProtectedRegion(OcrRegionMapper.MappedRegion mapped) {
        List<NormalizedPoint> polygon = mapped.polygon();
        double left = polygon.stream().mapToDouble(NormalizedPoint::x).min().orElseThrow();
        double top = polygon.stream().mapToDouble(NormalizedPoint::y).min().orElseThrow();
        double right = polygon.stream().mapToDouble(NormalizedPoint::x).max().orElseThrow();
        double bottom = polygon.stream().mapToDouble(NormalizedPoint::y).max().orElseThrow();
        return new ProtectedRegion(
                mapped.category(),
                List.of(new NormalizedRect(left, top, right, bottom)),
                mapped.conservativelyExpanded()
                        ? ConfidenceClass.UNCERTAIN : ConfidenceClass.VALIDATED,
                ProtectionAction.MOSAIC);
    }

    @Override
    public void cancelPending() {
        PendingAnalysis request = pending.get();
        if (request != null) {
            request.cancel();
        }
    }

    /** Replaces only session-scoped normalized terms; no recognized OCR payload is retained. */
    public void updateNormalizedWatchlistTerms(Set<String> terms) {
        if (closed.get()) {
            throw new IllegalStateException("Offline text analyzer is closed");
        }
        normalizedWatchlistTerms.set(Set.copyOf(Objects.requireNonNull(terms, "terms")));
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed.compareAndSet(false, true)) {
                normalizedWatchlistTerms.set(Set.of());
                cancelPending();
                // The single executor is the engine ownership boundary. Closing behind the active
                // request prevents destruction of native predictors while inference is using it.
                executor.execute(engine::close);
                executor.shutdown();
            }
        }
    }

    private static void validateElement(RecognizedElement element) {
        Objects.requireNonNull(element, "recognized element");
        if (element.text().isBlank()
                || element.polygon().size() < 4
                || !Double.isFinite(element.confidence())
                || element.confidence() < 0.0
                || element.confidence() > 1.0) {
            throw new IllegalArgumentException("Invalid OCR element");
        }
    }

    private static double top(List<NormalizedPoint> polygon) {
        return polygon.stream().mapToDouble(NormalizedPoint::y).min().orElseThrow();
    }

    private static double left(List<NormalizedPoint> polygon) {
        return polygon.stream().mapToDouble(NormalizedPoint::x).min().orElseThrow();
    }

    private static boolean validRotation(int rotation) {
        return rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270;
    }

    private static DetectorSnapshot failure(FrameTimestamp timestamp, TypedFailure.Code code) {
        return DetectorSnapshot.failure(
                DetectorLane.TEXT, timestamp, new TypedFailure(code, timestamp));
    }

    private static ListenableFuture<DetectorSnapshot> immediate(DetectorSnapshot result) {
        return CallbackToFutureAdapter.getFuture(completer -> {
            completer.set(result);
            return "immediate offline text result";
        });
    }

    /** Immutable session OCR settings; recognized payload is deliberately absent. */
    public record Configuration(
            String defaultRegionCode,
            Set<String> normalizedWatchlistTerms,
            OcrRegionMapper.MappingOptions mappingOptions,
            long freshnessNanos,
            int maximumElements,
            int maximumRecognizedCharacters) {
        public Configuration {
            Objects.requireNonNull(defaultRegionCode, "defaultRegionCode");
            normalizedWatchlistTerms = Set.copyOf(Objects.requireNonNull(
                    normalizedWatchlistTerms, "normalizedWatchlistTerms"));
            Objects.requireNonNull(mappingOptions, "mappingOptions");
            if (freshnessNanos < 0 || maximumElements <= 0 || maximumElements > 512
                    || maximumRecognizedCharacters <= 0
                    || maximumRecognizedCharacters > 16_384) {
                throw new IllegalArgumentException("Invalid offline OCR bounds");
            }
        }

        public static Configuration defaults(Set<String> normalizedWatchlistTerms) {
            return new Configuration(
                    "SG",
                    normalizedWatchlistTerms,
                    new OcrRegionMapper.MappingOptions(0.01, 0.03, 0.65),
                    DEFAULT_FRESHNESS_NANOS,
                    256,
                    8_192);
        }
    }

    interface TextRecognitionEngine extends AutoCloseable {
        List<RecognizedElement> recognize(
                TextAnalysisFrame frame, int rotationDegrees, AtomicBoolean cancellation);

        @Override
        default void close() {
        }
    }

    record RecognizedElement(
            String text,
            List<NormalizedPoint> polygon,
            double confidence,
            boolean boundaryCertain) {
        RecognizedElement {
            Objects.requireNonNull(text, "text");
            polygon = List.copyOf(Objects.requireNonNull(polygon, "polygon"));
        }
    }

    private final class PendingAnalysis {
        private final TextAnalysisFrame frame;
        private final FrameTimestamp timestamp;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Object taskLock = new Object();
        private final ListenableFuture<DetectorSnapshot> result;
        private Future<?> task;
        private boolean workerStarted;
        private volatile CallbackToFutureAdapter.Completer<DetectorSnapshot> completer;

        private PendingAnalysis(TextAnalysisFrame frame, FrameTimestamp timestamp) {
            this.frame = frame;
            this.timestamp = timestamp;
            result = CallbackToFutureAdapter.getFuture(value -> {
                completer = value;
                value.addCancellationListener(this::cancel, Runnable::run);
                return "offline text analysis at " + timestamp.nanos();
            });
        }

        private void finish(DetectorSnapshot snapshot) {
            if (finished.compareAndSet(false, true)) {
                completer.set(snapshot);
            }
        }

        private void cancel() {
            cancelled.set(true);
            cancelIfNotStarted();
        }

        private void setTask(Future<?> value) {
            synchronized (taskLock) {
                task = Objects.requireNonNull(value, "task");
            }
            cancelIfNotStarted();
        }

        private void workerStarted() {
            synchronized (taskLock) {
                workerStarted = true;
            }
        }

        private void cancelIfNotStarted() {
            boolean safelyRemoved = false;
            synchronized (taskLock) {
                if (cancelled.get() && task != null && !workerStarted) {
                    safelyRemoved = task.cancel(false);
                }
            }
            if (safelyRemoved) {
                completeAfterRelease(failure(
                        timestamp, TypedFailure.Code.ANALYZER_CANCELLED));
            }
        }

        private void completeAfterRelease(DetectorSnapshot snapshot) {
            DetectorSnapshot outcome = snapshot;
            try {
                releaseInput();
            } catch (RuntimeException releaseFailure) {
                outcome = failure(timestamp, TypedFailure.Code.ANALYZER_ERROR);
            }
            finish(outcome);
        }

        private void releaseInput() {
            if (released.compareAndSet(false, true)) {
                try {
                    frame.close();
                } finally {
                    pending.compareAndSet(this, null);
                }
            }
        }
    }
}
