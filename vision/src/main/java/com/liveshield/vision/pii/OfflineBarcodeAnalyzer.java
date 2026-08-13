package com.liveshield.vision.pii;

import androidx.concurrent.futures.CallbackToFutureAdapter;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.liveshield.privacy.model.ConfidenceClass;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorObservation;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedPoint;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.model.ProtectionAction;
import com.liveshield.privacy.model.TypedFailure;
import com.liveshield.vision.contract.AnalysisFrameHandle;
import com.liveshield.vision.contract.VisionAnalyzer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Fully offline ZXing barcode analyzer that emits protected geometry and no decoded payload. */
public class OfflineBarcodeAnalyzer implements VisionAnalyzer, AutoCloseable {
    private static final int DEFAULT_MAXIMUM_CODES = 16;
    // High-resolution camera frames can take several hundred milliseconds to decode on-device.
    // Keep successful geometry valid up to the scheduler's bounded one-second maximum.
    private static final long DEFAULT_FRESHNESS_NANOS = 1_000_000_000L;
    private static final double MINIMUM_REGION_FRACTION = 0.02;

    private final BarcodeEngine engine;
    private final int maximumCodes;
    private final long freshnessNanos;
    private final AtomicReference<PendingAnalysis> pending = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public OfflineBarcodeAnalyzer() {
        this(new ZxingBarcodeEngine(), DEFAULT_MAXIMUM_CODES, DEFAULT_FRESHNESS_NANOS);
    }

    OfflineBarcodeAnalyzer(BarcodeEngine engine, int maximumCodes, long freshnessNanos) {
        this.engine = Objects.requireNonNull(engine, "engine");
        if (maximumCodes <= 0) {
            throw new IllegalArgumentException("maximumCodes must be positive");
        }
        if (freshnessNanos < 0) {
            throw new IllegalArgumentException("freshnessNanos must be non-negative");
        }
        this.maximumCodes = maximumCodes;
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
        if (!(input instanceof BarcodeAnalysisFrame frame)
                || !isValidRotation(rotationDegrees)
                || closed.get()) {
            input.close();
            return immediate(failure(timestamp, TypedFailure.Code.ANALYZER_ERROR));
        }
        PendingAnalysis request = new PendingAnalysis(frame, timestamp);
        if (!pending.compareAndSet(null, request)) {
            frame.close();
            return immediate(failure(timestamp, TypedFailure.Code.ANALYZER_ERROR));
        }
        try {
            DecodeOperation operation = engine.decode(frame, new DecodeCallback() {
                @Override
                public void onSuccess(List<DetectedBarcode> detections) {
                    if (!request.acceptingResult()) {
                        return;
                    }
                    try {
                        request.finish(success(
                                timestamp,
                                mapDetections(
                                        Objects.requireNonNull(detections, "detections"),
                                        sensorToBufferTransform)));
                    } catch (RuntimeException invalidResult) {
                        request.finish(failure(timestamp, TypedFailure.Code.ANALYZER_ERROR));
                    }
                }

                @Override
                public void onFailure() {
                    request.finish(failure(timestamp, TypedFailure.Code.ANALYZER_ERROR));
                }

                @Override
                public void onCancelled() {
                    request.finish(failure(timestamp, TypedFailure.Code.ANALYZER_CANCELLED));
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
        }
    }

    private List<DetectorObservation> mapDetections(
            List<DetectedBarcode> detections, CoordinateTransform transform) {
        if (detections.size() > maximumCodes) {
            throw new IllegalArgumentException("Barcode result limit exceeded");
        }
        List<DetectorObservation> observations = new ArrayList<>(detections.size());
        for (DetectedBarcode detection : detections) {
            Objects.requireNonNull(detection, "detection");
            NormalizedRect bounds = transformedBounds(
                    conservativePolygon(detection), transform);
            observations.add(DetectorObservation.withoutTrackingHint(new ProtectedRegion(
                    FindingCategory.AUTO_BARCODE,
                    List.of(bounds),
                    detection.payloadState() == PayloadState.VALID
                            ? ConfidenceClass.VALIDATED : ConfidenceClass.UNCERTAIN,
                    ProtectionAction.OPAQUE)));
        }
        return List.copyOf(observations);
    }

    private DetectorSnapshot success(
            FrameTimestamp timestamp, List<DetectorObservation> observations) {
        return DetectorSnapshot.successWithObservations(
                DetectorLane.BARCODE,
                timestamp,
                timestamp.plusNanos(freshnessNanos),
                observations);
    }

    private static NormalizedRect transformedBounds(
            List<NormalizedPoint> polygon, CoordinateTransform transform) {
        if (polygon.size() < 2) {
            throw new IllegalArgumentException("Barcode polygon needs at least two points");
        }
        if (polygon.size() < 3 || polygonArea(polygon) < 1e-9) {
            // ZXing 1D readers generally return a centerline, not the bars' complete height.
            // Treat non-area geometry as uncertain and shield the frame rather than expose bars.
            return new NormalizedRect(0.0, 0.0, 1.0, 1.0);
        }
        double left = 1.0;
        double top = 1.0;
        double right = 0.0;
        double bottom = 0.0;
        // ZXing geometry is in the analysis buffer; emit the shared sensor-space contract.
        double[] matrix = transform.inverse().matrix();
        for (NormalizedPoint point : polygon) {
            double[] mapped = mapPoint(matrix, point.x(), point.y());
            left = Math.min(left, mapped[0]);
            top = Math.min(top, mapped[1]);
            right = Math.max(right, mapped[0]);
            bottom = Math.max(bottom, mapped[1]);
        }
        left = clamp(left);
        top = clamp(top);
        right = clamp(right);
        bottom = clamp(bottom);
        if (right - left < MINIMUM_REGION_FRACTION) {
            double center = (left + right) / 2.0;
            left = clamp(center - MINIMUM_REGION_FRACTION / 2.0);
            right = clamp(center + MINIMUM_REGION_FRACTION / 2.0);
        }
        if (bottom - top < MINIMUM_REGION_FRACTION) {
            double center = (top + bottom) / 2.0;
            top = clamp(center - MINIMUM_REGION_FRACTION / 2.0);
            bottom = clamp(center + MINIMUM_REGION_FRACTION / 2.0);
        }
        if (left >= right || top >= bottom) {
            throw new IllegalArgumentException("Barcode transform collapsed its protected region");
        }
        return new NormalizedRect(left, top, right, bottom);
    }

    /**
     * ZXing QR points are finder/alignment centres, not the symbol perimeter. Expand matrix-code
     * geometry before transformation; if the points cannot define area, the existing mapper
     * deliberately returns full-frame protection.
     */
    private static List<NormalizedPoint> conservativePolygon(DetectedBarcode detection) {
        List<NormalizedPoint> points = detection.polygon();
        if (points.size() < 3 || polygonArea(points) < 1e-9) {
            return points;
        }
        double left = points.stream().mapToDouble(NormalizedPoint::x).min().orElseThrow();
        double top = points.stream().mapToDouble(NormalizedPoint::y).min().orElseThrow();
        double right = points.stream().mapToDouble(NormalizedPoint::x).max().orElseThrow();
        double bottom = points.stream().mapToDouble(NormalizedPoint::y).max().orElseThrow();
        double width = right - left;
        double height = bottom - top;
        // QR finder-pattern centres are approximately 3.5 modules in from the symbol edge.
        // Their centre-to-centre span is the symbol width minus 7 modules, so one quarter of
        // that span per side reconstructs the perimeter for the common QR versions without
        // turning a localized code into a near-full-frame mask.
        double paddingFactor = detection.format() == Format.QR_CODE ? 0.25 : 0.20;
        if (width <= 0.0 || height <= 0.0) {
            return List.of();
        }
        double paddedLeft = clamp(left - width * paddingFactor);
        double paddedTop = clamp(top - height * paddingFactor);
        double paddedRight = clamp(right + width * paddingFactor);
        double paddedBottom = clamp(bottom + height * paddingFactor);
        return List.of(
                new NormalizedPoint(paddedLeft, paddedTop),
                new NormalizedPoint(paddedRight, paddedTop),
                new NormalizedPoint(paddedRight, paddedBottom),
                new NormalizedPoint(paddedLeft, paddedBottom));
    }

    private static double polygonArea(List<NormalizedPoint> polygon) {
        double twiceArea = 0.0;
        for (int index = 0; index < polygon.size(); index++) {
            NormalizedPoint current = polygon.get(index);
            NormalizedPoint next = polygon.get((index + 1) % polygon.size());
            twiceArea += current.x() * next.y() - next.x() * current.y();
        }
        return Math.abs(twiceArea) / 2.0;
    }

    private static double[] mapPoint(double[] matrix, double x, double y) {
        double divisor = matrix[6] * x + matrix[7] * y + matrix[8];
        if (!Double.isFinite(divisor) || Math.abs(divisor) < 1e-12) {
            throw new IllegalArgumentException("Unsafe barcode coordinate transform");
        }
        double mappedX = (matrix[0] * x + matrix[1] * y + matrix[2]) / divisor;
        double mappedY = (matrix[3] * x + matrix[4] * y + matrix[5]) / divisor;
        if (!Double.isFinite(mappedX) || !Double.isFinite(mappedY)) {
            throw new IllegalArgumentException("Unsafe barcode coordinate transform");
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
                DetectorLane.BARCODE, timestamp, new TypedFailure(code, timestamp));
    }

    private static ListenableFuture<DetectorSnapshot> immediate(DetectorSnapshot snapshot) {
        return CallbackToFutureAdapter.getFuture(completer -> {
            completer.set(snapshot);
            return "immediate barcode analysis result";
        });
    }

    /** Ephemeral luminance input; returned bytes remain caller-owned until {@link #close()}. */
    public interface BarcodeAnalysisFrame extends AnalysisFrameHandle {
        int width();

        int height();

        default byte[] luminance() {
            throw new UnsupportedOperationException("Frame does not expose luminance pixels");
        }
    }

    /** Privacy-relevant formats intentionally enabled in the offline reader. */
    public enum Format {
        QR_CODE,
        PDF_417,
        AZTEC,
        DATA_MATRIX,
        CODE_128,
        CODE_39,
        EAN_13,
        EAN_8,
        UPC_A,
        UPC_E,
        ITF,
        CODABAR
    }

    /** Coarse decode state only; recognized payload is never exposed. */
    public enum PayloadState {
        VALID,
        EMPTY,
        MALFORMED
    }

    /** Immutable geometry and non-sensitive classification with no decoded content. */
    public record DetectedBarcode(
            Format format,
            List<NormalizedPoint> polygon,
            PayloadState payloadState) {
        public DetectedBarcode {
            Objects.requireNonNull(format, "format");
            polygon = List.copyOf(Objects.requireNonNull(polygon, "polygon"));
            Objects.requireNonNull(payloadState, "payloadState");
        }
    }

    interface BarcodeEngine extends AutoCloseable {
        DecodeOperation decode(BarcodeAnalysisFrame frame, DecodeCallback callback);

        @Override
        void close();
    }

    interface DecodeOperation {
        void cancel();
    }

    interface DecodeCallback {
        void onSuccess(List<DetectedBarcode> detections);

        void onFailure();

        void onCancelled();

        void onInputReleased();
    }

    private final class PendingAnalysis {
        private final BarcodeAnalysisFrame frame;
        private final FrameTimestamp timestamp;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private final AtomicBoolean inputReleased = new AtomicBoolean();
        private final ListenableFuture<DetectorSnapshot> result;
        private volatile DecodeOperation operation;
        private volatile CallbackToFutureAdapter.Completer<DetectorSnapshot> completer;

        private PendingAnalysis(BarcodeAnalysisFrame frame, FrameTimestamp timestamp) {
            this.frame = frame;
            this.timestamp = timestamp;
            result = CallbackToFutureAdapter.getFuture(value -> {
                completer = value;
                value.addCancellationListener(this::cancelFromCaller, Runnable::run);
                return "barcode analysis at " + timestamp.nanos();
            });
        }

        private void setOperation(DecodeOperation value) {
            operation = Objects.requireNonNull(value, "operation");
            if (cancellationRequested.get()) {
                operation.cancel();
            }
        }

        private void finish(DetectorSnapshot snapshot) {
            if (finished.compareAndSet(false, true)) {
                completer.set(snapshot);
                completeOwnershipIfDone();
            }
        }

        private boolean acceptingResult() {
            return !finished.get() && !cancellationRequested.get();
        }

        private void releaseInput() {
            if (inputReleased.compareAndSet(false, true)) {
                frame.close();
                completeOwnershipIfDone();
            }
        }

        private void completeOwnershipIfDone() {
            if (finished.get() && inputReleased.get()) {
                pending.compareAndSet(this, null);
            }
        }

        private void cancelWithSnapshot() {
            cancellationRequested.set(true);
            DecodeOperation active = operation;
            if (active != null) {
                active.cancel();
            }
            finish(failure(timestamp, TypedFailure.Code.ANALYZER_CANCELLED));
        }

        private void cancelFromCaller() {
            if (finished.compareAndSet(false, true)) {
                cancellationRequested.set(true);
                DecodeOperation active = operation;
                if (active != null) {
                    active.cancel();
                }
            }
        }
    }

    static final class ZxingBarcodeEngine implements BarcodeEngine {
        private static final int MINIMUM_DECODE_EDGE = 256;
        private static final int MAXIMUM_UPSCALE_FACTOR = 4;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "LiveShield-ZXing");
            thread.setDaemon(true);
            return thread;
        });
        private final AtomicBoolean closed = new AtomicBoolean();
        // Executor-confined normalized hint. It contains geometry only, never decoded payload.
        private SearchRegion lastQrSearchRegion;

        @Override
        public DecodeOperation decode(BarcodeAnalysisFrame frame, DecodeCallback callback) {
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(callback, "callback");
            if (closed.get() || frame.width() <= 0 || frame.height() <= 0) {
                throw new IllegalStateException("Barcode engine is unavailable");
            }
            byte[] source = Objects.requireNonNull(frame.luminance(), "luminance");
            int expectedLength = Math.multiplyExact(frame.width(), frame.height());
            if (source.length != expectedLength) {
                throw new IllegalArgumentException("Luminance size does not match frame dimensions");
            }
            byte[] owned = source.clone();
            callback.onInputReleased();
            AtomicBoolean cancelled = new AtomicBoolean();
            try {
                executor.execute(() -> {
                    try {
                        if (cancelled.get()) {
                            return;
                        }
                        callback.onSuccess(decodeOwned(owned, frame.width(), frame.height()));
                    } catch (RuntimeException decodeFailure) {
                        callback.onFailure();
                    } finally {
                        Arrays.fill(owned, (byte) 0);
                    }
                });
            } catch (RuntimeException rejected) {
                Arrays.fill(owned, (byte) 0);
                throw rejected;
            }
            return () -> {
                if (cancelled.compareAndSet(false, true)) {
                    callback.onCancelled();
                }
            };
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                executor.shutdown();
            }
        }

        private List<DetectedBarcode> decodeOwned(byte[] luminance, int width, int height) {
            ScaledLuminance scaled = upscaleSmallInput(luminance, width, height);
            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, enabledFormats());
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            try {
                LocatedResult qr = decodeQr(scaled, lastQrSearchRegion, hints);
                if (qr == null) {
                    qr = decodeQr(scaled, SearchRegion.fullFrame(), hints);
                }
                if (qr != null) {
                    DetectedBarcode detection = detection(
                            qr.result(), scaled.width(), scaled.height(),
                            qr.left(), qr.top());
                    lastQrSearchRegion = searchRegion(detection.polygon());
                    return List.of(detection);
                }

                PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                        scaled.bytes(), scaled.width(), scaled.height(),
                        0, 0, scaled.width(), scaled.height(), false);
                BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
                MultiFormatReader reader = new MultiFormatReader();
                // A recursive multi-code scan can exceed the live result-validity window on
                // high-resolution CameraX frames. Return the first privacy code promptly; the
                // bounded camera cadence continues scanning subsequent frames for other codes.
                try {
                    Result result = reader.decode(bitmap, hints);
                    Format format = format(result.getBarcodeFormat());
                    if (format == null) {
                        return List.of();
                    }
                    return List.of(detection(result, scaled.width(), scaled.height(), 0, 0));
                } finally {
                    reader.reset();
                }
            } catch (NotFoundException noCode) {
                return List.of();
            } finally {
                if (scaled.bytes() != luminance) {
                    Arrays.fill(scaled.bytes(), (byte) 0);
                }
            }
        }

        private static LocatedResult decodeQr(
                ScaledLuminance scaled,
                SearchRegion region,
                Map<DecodeHintType, Object> hints) {
            if (region == null) {
                return null;
            }
            int left = (int) Math.floor(region.left() * scaled.width());
            int top = (int) Math.floor(region.top() * scaled.height());
            int right = (int) Math.ceil(region.right() * scaled.width());
            int bottom = (int) Math.ceil(region.bottom() * scaled.height());
            left = Math.max(0, Math.min(left, scaled.width() - 1));
            top = Math.max(0, Math.min(top, scaled.height() - 1));
            right = Math.max(left + 1, Math.min(right, scaled.width()));
            bottom = Math.max(top + 1, Math.min(bottom, scaled.height()));
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    scaled.bytes(), scaled.width(), scaled.height(),
                    left, top, right - left, bottom - top, false);
            try {
                Result result = new QRCodeReader().decode(
                        new BinaryBitmap(new HybridBinarizer(source)), hints);
                return new LocatedResult(result, left, top);
            } catch (ReaderException invalidQr) {
                return null;
            }
        }

        private static DetectedBarcode detection(
                Result result, int width, int height, int offsetX, int offsetY) {
            Format format = format(result.getBarcodeFormat());
            if (format == null) {
                throw new IllegalArgumentException("Unsupported barcode format");
            }
            return new DetectedBarcode(
                    format,
                    polygon(result.getResultPoints(), width, height, offsetX, offsetY),
                    result.getText() == null || result.getText().isEmpty()
                            ? PayloadState.EMPTY : PayloadState.VALID);
        }

        private static SearchRegion searchRegion(List<NormalizedPoint> points) {
            if (points.size() < 2) {
                return SearchRegion.fullFrame();
            }
            double left = points.stream().mapToDouble(NormalizedPoint::x).min().orElse(0.0);
            double top = points.stream().mapToDouble(NormalizedPoint::y).min().orElse(0.0);
            double right = points.stream().mapToDouble(NormalizedPoint::x).max().orElse(1.0);
            double bottom = points.stream().mapToDouble(NormalizedPoint::y).max().orElse(1.0);
            double width = right - left;
            double height = bottom - top;
            return new SearchRegion(
                    clamp(left - width), clamp(top - height),
                    clamp(right + width), clamp(bottom + height));
        }

        /**
         * Preserves hard module edges when a complete camera frame is below ZXing's useful scale.
         * The cap keeps both memory and work bounded; ordinary 720p frames are returned unchanged.
         */
        static ScaledLuminance upscaleSmallInput(
                byte[] luminance, int width, int height) {
            Objects.requireNonNull(luminance, "luminance");
            if (width <= 0 || height <= 0
                    || luminance.length != Math.multiplyExact(width, height)) {
                throw new IllegalArgumentException("Invalid barcode luminance dimensions");
            }
            int shortest = Math.min(width, height);
            int factor = Math.min(
                    MAXIMUM_UPSCALE_FACTOR,
                    Math.max(1, (MINIMUM_DECODE_EDGE + shortest - 1) / shortest));
            if (factor == 1) {
                return new ScaledLuminance(luminance, width, height);
            }
            int scaledWidth = Math.multiplyExact(width, factor);
            int scaledHeight = Math.multiplyExact(height, factor);
            byte[] scaled = new byte[Math.multiplyExact(scaledWidth, scaledHeight)];
            for (int y = 0; y < scaledHeight; y++) {
                int sourceRow = (y / factor) * width;
                int targetRow = y * scaledWidth;
                for (int x = 0; x < scaledWidth; x++) {
                    scaled[targetRow + x] = luminance[sourceRow + x / factor];
                }
            }
            return new ScaledLuminance(scaled, scaledWidth, scaledHeight);
        }

        private static List<BarcodeFormat> enabledFormats() {
            return List.of(
                    BarcodeFormat.QR_CODE, BarcodeFormat.PDF_417,
                    BarcodeFormat.AZTEC, BarcodeFormat.DATA_MATRIX,
                    BarcodeFormat.CODE_128, BarcodeFormat.CODE_39,
                    BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
                    BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
                    BarcodeFormat.ITF, BarcodeFormat.CODABAR);
        }

        private static Format format(BarcodeFormat value) {
            try {
                return Format.valueOf(value.name());
            } catch (IllegalArgumentException unsupported) {
                return null;
            }
        }

        private static List<NormalizedPoint> polygon(
                ResultPoint[] points, int width, int height, int offsetX, int offsetY) {
            if (points == null || points.length < 2) {
                return List.of(
                        new NormalizedPoint(0.0, 0.0), new NormalizedPoint(1.0, 0.0),
                        new NormalizedPoint(1.0, 1.0), new NormalizedPoint(0.0, 1.0));
            }
            List<NormalizedPoint> normalized = new ArrayList<>(points.length);
            for (ResultPoint point : points) {
                normalized.add(new NormalizedPoint(
                        clamp((point.getX() + offsetX) / width),
                        clamp((point.getY() + offsetY) / height)));
            }
            return List.copyOf(normalized);
        }

        record ScaledLuminance(byte[] bytes, int width, int height) {
            ScaledLuminance {
                Objects.requireNonNull(bytes, "bytes");
            }
        }

        private record LocatedResult(Result result, int left, int top) {
        }

        private record SearchRegion(double left, double top, double right, double bottom) {
            private static SearchRegion fullFrame() {
                return new SearchRegion(0.0, 0.0, 1.0, 1.0);
            }
        }
    }
}
