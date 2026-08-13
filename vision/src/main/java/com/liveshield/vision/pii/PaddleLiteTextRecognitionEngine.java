package com.liveshield.vision.pii;

import android.content.Context;
import android.graphics.Bitmap;
import com.baidu.paddle.lite.MobileConfig;
import com.baidu.paddle.lite.PaddlePredictor;
import com.baidu.paddle.lite.PowerMode;
import com.baidu.paddle.lite.Tensor;
import com.liveshield.privacy.model.NormalizedPoint;
import java.io.File;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/** Fully offline PP-OCRv3 detection and recognition, with no egress API. */
final class PaddleLiteTextRecognitionEngine
        implements OfflineTextAnalyzer.TextRecognitionEngine {
    private static final String EXPECTED_RUNTIME_VERSION = "v2.11";
    static final int RECOGNIZER_WIDTH = 320;
    static final int RECOGNIZER_HEIGHT = 48;
    static final int RECOGNIZER_DICTIONARY_SIZE = 96;
    static final int RECOGNIZER_CLASSES = RECOGNIZER_DICTIONARY_SIZE + 1;
    private static final int MAXIMUM_DETECTOR_EDGE = 640;
    private static final int MAXIMUM_ELEMENTS = 256;
    private static final double DETECTION_THRESHOLD = 0.30;
    private static final double BOX_SCORE_THRESHOLD = 0.50;
    private static final double MINIMUM_BOX_AREA = 16.0;
    private static final double DB_UNCLIP_RATIO = 1.6;
    private final Context context;
    private final String diagnosticExpected;
    private final Consumer<RecognitionDiagnostics> diagnosticObserver;
    private final Object predictorLock = new Object();
    private volatile Predictors predictors;
    private volatile boolean closed;

    PaddleLiteTextRecognitionEngine(Context context) {
        this(context, null, ignored -> { });
    }

    PaddleLiteTextRecognitionEngine(
            Context context,
            String diagnosticExpected,
            Consumer<RecognitionDiagnostics> diagnosticObserver) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        this.diagnosticExpected = diagnosticExpected == null
                ? null : normalizeDiagnosticText(diagnosticExpected);
        this.diagnosticObserver = Objects.requireNonNull(
                diagnosticObserver, "diagnosticObserver");
    }

    @Override
    public List<OfflineTextAnalyzer.RecognizedElement> recognize(
            TextAnalysisFrame frame, int rotationDegrees, AtomicBoolean cancellation) {
        Bitmap bitmap = null;
        Mat rgba = null;
        Mat bgr = null;
        try {
            // OpenCV Java objects invoke JNI in their constructors, so the bundled native
            // runtime must be initialized before even allocating an empty Mat.
            ensurePredictors();
            rgba = new Mat();
            bgr = new Mat();
            bitmap = frame.bitmap(rotationDegrees);
            if (cancellation.get()) {
                return List.of();
            }
            Utils.bitmapToMat(bitmap, rgba);
            // PP-OCRv3 was trained and exported with OpenCV's BGR channel contract.
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);
            List<DetectedBox> boxes = detect(bgr, cancellation);
            List<OfflineTextAnalyzer.RecognizedElement> results = new ArrayList<>();
            int recognitionAttempts = 0;
            for (DetectedBox box : boxes) {
                if (cancellation.get()) {
                    break;
                }
                recognitionAttempts++;
                Recognition recognition = recognizeCrop(bgr, box.bounds());
                if (!recognition.text().isBlank()) {
                    results.add(new OfflineTextAnalyzer.RecognizedElement(
                            recognition.text(),
                            normalizedPolygon(box.bounds(), bgr.cols(), bgr.rows()),
                            recognition.confidence(),
                            box.score() >= 0.65 && recognition.confidence() >= 0.65));
                }
            }
            if (diagnosticExpected != null) {
                diagnosticObserver.accept(recognitionDiagnostics(
                        boxes.size(), recognitionAttempts, results, diagnosticExpected));
            }
            return List.copyOf(results);
        } finally {
            if (bgr != null) {
                bgr.release();
            }
            if (rgba != null) {
                rgba.release();
            }
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private List<DetectedBox> detect(Mat bgr, AtomicBoolean cancellation) {
        int[] dimensions = fittedDimensions(bgr.cols(), bgr.rows(), MAXIMUM_DETECTOR_EDGE);
        Mat resized = new Mat();
        Mat probability = new Mat();
        Mat binary = new Mat();
        try {
            Imgproc.resize(bgr, resized, new Size(dimensions[0], dimensions[1]));
            float[] input = detectorNormalizedBgrChw(resized);
            Tensor tensor = required(predictors.detector().getInput(0));
            require(tensor.resize(new long[]{1, 3, dimensions[1], dimensions[0]}));
            require(tensor.setData(input));
            if (cancellation.get() || !predictors.detector().run()) {
                return List.of();
            }
            Tensor output = required(predictors.detector().getOutput(0));
            float[] scores = output.getFloatData();
            long[] shape = output.shape();
            int outputHeight = checkedDimension(shape[shape.length - 2]);
            int outputWidth = checkedDimension(shape[shape.length - 1]);
            probability = new Mat(outputHeight, outputWidth, CvType.CV_32FC1);
            probability.put(0, 0, scores);
            Imgproc.threshold(
                    probability, binary, DETECTION_THRESHOLD, 255.0, Imgproc.THRESH_BINARY);
            binary.convertTo(binary, CvType.CV_8UC1);
            List<MatOfPoint> contours = new ArrayList<>();
            Imgproc.findContours(
                    binary, contours, new Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
            List<DetectedBox> boxes = new ArrayList<>();
            for (MatOfPoint contour : contours) {
                MatOfPoint2f floatingContour = new MatOfPoint2f(contour.toArray());
                try {
                    RotatedRect minimum = Imgproc.minAreaRect(floatingContour);
                    DbBox core = dbBox(minimum);
                    if (!validDbBox(core) || core.area() < MINIMUM_BOX_AREA) {
                        continue;
                    }
                    double score = maskedPolygonScore(
                            scores, outputWidth, outputHeight, vertices(core));
                    if (score < BOX_SCORE_THRESHOLD) {
                        continue;
                    }
                    DbBox expanded = unclip(core, DB_UNCLIP_RATIO);
                    Rect sourceBox = conservativeSourceBounds(
                            vertices(expanded), outputWidth, outputHeight,
                            bgr.cols(), bgr.rows());
                    if (sourceBox == null) {
                        continue;
                    }
                    boxes.add(new DetectedBox(sourceBox, score));
                } finally {
                    floatingContour.release();
                    contour.release();
                }
            }
            boxes.sort(Comparator
                    .comparingInt((DetectedBox value) -> value.bounds().y)
                    .thenComparingInt(value -> value.bounds().x));
            return List.copyOf(boxes.subList(0, Math.min(boxes.size(), MAXIMUM_ELEMENTS)));
        } finally {
            binary.release();
            probability.release();
            resized.release();
        }
    }

    private Recognition recognizeCrop(Mat bgr, Rect bounds) {
        Mat crop = bgr.submat(bounds);
        Mat resized = new Mat();
        try {
            int resizedWidth = Math.min(
                    RECOGNIZER_WIDTH,
                    Math.max(1, (int) Math.ceil(
                            RECOGNIZER_HEIGHT * crop.cols() / (double) crop.rows())));
            Imgproc.resize(crop, resized, new Size(resizedWidth, RECOGNIZER_HEIGHT));
            float[] chw = recognizerNormalizedBgrChw(resized);
            Tensor input = required(predictors.recognizer().getInput(0));
            require(input.resize(new long[]{
                    1, 3, RECOGNIZER_HEIGHT, RECOGNIZER_WIDTH}));
            require(input.setData(chw));
            if (!predictors.recognizer().run()) {
                throw new IllegalStateException("Paddle recognizer inference failed");
            }
            Tensor output = required(predictors.recognizer().getOutput(0));
            return decodePaddleCtc(
                    output.getFloatData(), output.shape(), predictors.characters());
        } finally {
            resized.release();
            crop.release();
        }
    }

    static Recognition decodePaddleCtc(
            float[] output, long[] shape, List<String> characters) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(characters, "characters");
        if (characters.size() != RECOGNIZER_DICTIONARY_SIZE
                || shape.length != 3 || shape[0] != 1 || shape[1] <= 0
                || shape[2] != RECOGNIZER_CLASSES
                || output.length != shape[1] * RECOGNIZER_CLASSES) {
            throw new IllegalArgumentException("Unexpected PP-OCRv3 recognition output shape");
        }
        int steps = checkedDimension(shape[1]);
        StringBuilder text = new StringBuilder();
        int previous = -1;
        double score = 0.0;
        int count = 0;
        for (int step = 0; step < steps; step++) {
            int best = 0;
            float bestScore = output[step * RECOGNIZER_CLASSES];
            for (int index = 1; index < RECOGNIZER_CLASSES; index++) {
                float candidate = output[step * RECOGNIZER_CLASSES + index];
                if (candidate > bestScore) {
                    best = index;
                    bestScore = candidate;
                }
            }
            if (best > 0 && best != previous) {
                text.append(characters.get(best - 1));
                score += Math.max(0.0, Math.min(1.0, bestScore));
                count++;
            }
            previous = best;
        }
        return new Recognition(text.toString(), count == 0 ? 0.0 : score / count);
    }

    static RecognitionDiagnostics recognitionDiagnostics(
            int detectedRegions,
            int recognitionAttempts,
            List<OfflineTextAnalyzer.RecognizedElement> elements,
            String expected) {
        Objects.requireNonNull(elements, "elements");
        String normalizedExpected = normalizeDiagnosticText(expected);
        int characters = 0;
        int lowConfidence = 0;
        int mediumConfidence = 0;
        int highConfidence = 0;
        boolean exact = false;
        double minimumDistance = 1.0;
        for (OfflineTextAnalyzer.RecognizedElement element : elements) {
            String normalized = normalizeDiagnosticText(element.text());
            characters += normalized.codePointCount(0, normalized.length());
            if (element.confidence() < 0.5) {
                lowConfidence++;
            } else if (element.confidence() < 0.8) {
                mediumConfidence++;
            } else {
                highConfidence++;
            }
            double distance = normalizedEditDistance(normalized, normalizedExpected);
            minimumDistance = Math.min(minimumDistance, distance);
            exact |= normalized.equals(normalizedExpected);
        }
        return new RecognitionDiagnostics(
                detectedRegions,
                recognitionAttempts,
                elements.size(),
                characters,
                lowConfidence,
                mediumConfidence,
                highConfidence,
                exact,
                minimumDistance);
    }

    private static String normalizeDiagnosticText(String value) {
        Objects.requireNonNull(value, "value");
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toUpperCase(Locale.ROOT)
                .strip()
                .replaceAll("\\s+", " ");
    }

    private static double normalizedEditDistance(String actual, String expected) {
        int[] left = codePoints(actual);
        int[] right = codePoints(expected);
        int denominator = Math.max(left.length, right.length);
        if (denominator == 0) {
            return 0.0;
        }
        int[] previous = new int[right.length + 1];
        int[] current = new int[right.length + 1];
        for (int index = 0; index <= right.length; index++) {
            previous[index] = index;
        }
        for (int row = 1; row <= left.length; row++) {
            current[0] = row;
            for (int column = 1; column <= right.length; column++) {
                int substitution = previous[column - 1]
                        + (left[row - 1] == right[column - 1] ? 0 : 1);
                current[column] = Math.min(
                        Math.min(previous[column] + 1, current[column - 1] + 1),
                        substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length] / (double) denominator;
    }

    private static int[] codePoints(String value) {
        int[] result = new int[value.codePointCount(0, value.length())];
        int index = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            result[index++] = codePoint;
            offset += Character.charCount(codePoint);
        }
        return result;
    }

    private void ensurePredictors() {
        if (closed) {
            throw new IllegalStateException("Offline OCR engine is closed");
        }
        if (predictors != null) {
            return;
        }
        synchronized (predictorLock) {
            if (predictors != null) {
                return;
            }
            if (closed) {
                throw new IllegalStateException("Offline OCR engine is closed");
            }
            if (!OpenCVLoader.initLocal()) {
                throw new IllegalStateException("Bundled OpenCV runtime failed to initialize");
            }
            File detector = PaddleOcrAssets.verifiedPrivateCopy(context, PaddleOcrAssets.DETECTOR);
            File recognizer = PaddleOcrAssets.verifiedPrivateCopy(
                    context, PaddleOcrAssets.RECOGNIZER);
            predictors = new Predictors(
                    createPredictor(detector, PaddleOcrAssets.DETECTOR.optimizerVersion()),
                    createPredictor(recognizer, PaddleOcrAssets.RECOGNIZER.optimizerVersion()),
                    PaddleOcrAssets.verifiedDictionary(context));
        }
    }

    private ReleasablePredictor createPredictor(File model, String optimizerVersion) {
        MobileConfig config = new MobileConfig();
        config.setModelFromFile(model.getAbsolutePath());
        config.setThreads(2);
        config.setPowerMode(PowerMode.LITE_POWER_NO_BIND);
        ReleasablePredictor predictor = new ReleasablePredictor(config);
        String runtimeVersion = predictor.getVersion();
        if (!EXPECTED_RUNTIME_VERSION.equals(runtimeVersion)
                || !PaddleOcrAssets.runtimeSupportsOptimizer(runtimeVersion, optimizerVersion)) {
            predictor.release();
            throw new IllegalStateException("Unsupported Paddle Lite runtime/model combination");
        }
        return predictor;
    }

    @Override
    public void close() {
        synchronized (predictorLock) {
            if (closed) {
                return;
            }
            closed = true;
            Predictors active = predictors;
            predictors = null;
            if (active != null) {
                active.detector().release();
                active.recognizer().release();
            }
        }
    }

    private static float[] detectorNormalizedBgrChw(Mat bgr) {
        byte[] pixels = new byte[(int) (bgr.total() * bgr.channels())];
        bgr.get(0, 0, pixels);
        return detectorNormalizedBgrChw(pixels, bgr.cols(), bgr.rows());
    }

    static float[] detectorNormalizedBgrChw(byte[] bgr, int width, int height) {
        Objects.requireNonNull(bgr, "bgr");
        if (width <= 0 || height <= 0
                || bgr.length != Math.multiplyExact(Math.multiplyExact(width, height), 3)) {
            throw new IllegalArgumentException("Invalid PP-OCRv3 detector BGR input");
        }
        double[] mean = {0.485, 0.456, 0.406};
        double[] deviation = {0.229, 0.224, 0.225};
        int plane = width * height;
        float[] output = new float[plane * 3];
        for (int index = 0; index < plane; index++) {
            for (int channel = 0; channel < 3; channel++) {
                int unsigned = bgr[index * 3 + channel] & 0xff;
                output[channel * plane + index] =
                        (float) ((unsigned / 255.0 - mean[channel]) / deviation[channel]);
            }
        }
        return output;
    }

    private static float[] recognizerNormalizedBgrChw(Mat resizedBgr) {
        byte[] pixels = new byte[(int) (resizedBgr.total() * resizedBgr.channels())];
        resizedBgr.get(0, 0, pixels);
        return recognizerNormalizedBgrChw(pixels, resizedBgr.cols(), resizedBgr.rows());
    }

    /**
     * Converts an aspect-preserving 48-pixel-high BGR crop into a zero-padded v3 tensor.
     */
    static float[] recognizerNormalizedBgrChw(byte[] bgr, int width, int height) {
        Objects.requireNonNull(bgr, "bgr");
        if (height != RECOGNIZER_HEIGHT || width <= 0 || width > RECOGNIZER_WIDTH
                || bgr.length != Math.multiplyExact(Math.multiplyExact(width, height), 3)) {
            throw new IllegalArgumentException("Invalid PP-OCRv3 recognizer BGR crop");
        }
        int plane = RECOGNIZER_HEIGHT * RECOGNIZER_WIDTH;
        float[] output = new float[plane * 3];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int pixel = (row * width + column) * 3;
                int target = row * RECOGNIZER_WIDTH + column;
                for (int channel = 0; channel < 3; channel++) {
                    int unsigned = bgr[pixel + channel] & 0xff;
                    output[channel * plane + target] = unsigned / 127.5F - 1.0F;
                }
            }
        }
        return output;
    }

    static int[] fittedDimensions(int width, int height, int maximumEdge) {
        if (width <= 0 || height <= 0 || maximumEdge < 32) {
            throw new IllegalArgumentException("Invalid OCR detector dimensions");
        }
        // Small camera/test inputs otherwise leave legible glyphs below the detector's feature
        // scale. Fit the longest edge to the audited 640-pixel bound in both directions; mapping
        // the detected boxes back to the source preserves output coordinates.
        double scale = maximumEdge / (double) Math.max(width, height);
        int fittedWidth = Math.max(32, ((int) Math.floor(width * scale / 32.0)) * 32);
        int fittedHeight = Math.max(32, ((int) Math.floor(height * scale / 32.0)) * 32);
        return new int[]{fittedWidth, fittedHeight};
    }

    private static DbBox dbBox(RotatedRect value) {
        return new DbBox(
                value.center.x, value.center.y, value.size.width, value.size.height, value.angle);
    }

    static DbBox unclip(DbBox core, double ratio) {
        if (!validDbBox(core) || !Double.isFinite(ratio) || ratio <= 0.0) {
            throw new IllegalArgumentException("Invalid DB unclip geometry");
        }
        double distance = core.area() * ratio / core.perimeter();
        return new DbBox(
                core.centerX(), core.centerY(),
                core.width() + 2.0 * distance,
                core.height() + 2.0 * distance,
                core.angleDegrees());
    }

    static List<Point> vertices(DbBox box) {
        if (!validDbBox(box)) {
            throw new IllegalArgumentException("Invalid DB rotated box");
        }
        double radians = Math.toRadians(box.angleDegrees());
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        double halfWidth = box.width() / 2.0;
        double halfHeight = box.height() / 2.0;
        List<Point> result = new ArrayList<>(4);
        for (double[] corner : new double[][]{
                {-halfWidth, -halfHeight},
                {halfWidth, -halfHeight},
                {halfWidth, halfHeight},
                {-halfWidth, halfHeight}}) {
            result.add(new Point(
                    box.centerX() + corner[0] * cosine - corner[1] * sine,
                    box.centerY() + corner[0] * sine + corner[1] * cosine));
        }
        return List.copyOf(result);
    }

    static double maskedPolygonScore(
            float[] probability, int width, int height, List<Point> polygon) {
        Objects.requireNonNull(probability, "probability");
        Objects.requireNonNull(polygon, "polygon");
        if (width <= 0 || height <= 0 || probability.length != width * height
                || polygon.size() < 3 || polygon.stream().anyMatch(
                        point -> point == null || !Double.isFinite(point.x)
                                || !Double.isFinite(point.y))) {
            throw new IllegalArgumentException("Invalid DB score geometry");
        }
        int left = Math.max(0, (int) Math.floor(polygon.stream()
                .mapToDouble(point -> point.x).min().orElseThrow()));
        int top = Math.max(0, (int) Math.floor(polygon.stream()
                .mapToDouble(point -> point.y).min().orElseThrow()));
        int right = Math.min(width, (int) Math.ceil(polygon.stream()
                .mapToDouble(point -> point.x).max().orElseThrow()));
        int bottom = Math.min(height, (int) Math.ceil(polygon.stream()
                .mapToDouble(point -> point.y).max().orElseThrow()));
        double total = 0.0;
        int count = 0;
        for (int row = top; row < bottom; row++) {
            for (int column = left; column < right; column++) {
                if (contains(polygon, column + 0.5, row + 0.5)) {
                    total += probability[row * width + column];
                    count++;
                }
            }
        }
        return count == 0 ? 0.0 : total / count;
    }

    private static boolean contains(List<Point> polygon, double x, double y) {
        boolean inside = false;
        for (int current = 0, previous = polygon.size() - 1;
                current < polygon.size(); previous = current++) {
            Point first = polygon.get(current);
            Point second = polygon.get(previous);
            boolean crosses = (first.y > y) != (second.y > y)
                    && x < (second.x - first.x) * (y - first.y)
                            / (second.y - first.y) + first.x;
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }

    static Rect conservativeSourceBounds(
            List<Point> polygon,
            int fromWidth,
            int fromHeight,
            int toWidth,
            int toHeight) {
        Objects.requireNonNull(polygon, "polygon");
        if (polygon.size() < 3 || fromWidth <= 0 || fromHeight <= 0
                || toWidth <= 0 || toHeight <= 0 || polygon.stream().anyMatch(
                        point -> point == null || !Double.isFinite(point.x)
                                || !Double.isFinite(point.y))) {
            return null;
        }
        double scaleX = toWidth / (double) fromWidth;
        double scaleY = toHeight / (double) fromHeight;
        int left = Math.max(0, (int) Math.floor(polygon.stream()
                .mapToDouble(point -> point.x * scaleX).min().orElseThrow()));
        int top = Math.max(0, (int) Math.floor(polygon.stream()
                .mapToDouble(point -> point.y * scaleY).min().orElseThrow()));
        int right = Math.min(toWidth, (int) Math.ceil(polygon.stream()
                .mapToDouble(point -> point.x * scaleX).max().orElseThrow()));
        int bottom = Math.min(toHeight, (int) Math.ceil(polygon.stream()
                .mapToDouble(point -> point.y * scaleY).max().orElseThrow()));
        return right <= left || bottom <= top ? null
                : new Rect(left, top, right - left, bottom - top);
    }

    private static boolean validDbBox(DbBox value) {
        return value != null && Double.isFinite(value.centerX())
                && Double.isFinite(value.centerY()) && Double.isFinite(value.width())
                && Double.isFinite(value.height()) && Double.isFinite(value.angleDegrees())
                && value.width() > 0.0 && value.height() > 0.0;
    }

    private static List<NormalizedPoint> normalizedPolygon(Rect box, int width, int height) {
        double left = box.x / (double) width;
        double top = box.y / (double) height;
        double right = (box.x + box.width) / (double) width;
        double bottom = (box.y + box.height) / (double) height;
        return List.of(
                new NormalizedPoint(left, top),
                new NormalizedPoint(right, top),
                new NormalizedPoint(right, bottom),
                new NormalizedPoint(left, bottom));
    }

    private static Tensor required(Tensor tensor) {
        return Objects.requireNonNull(tensor, "Paddle tensor");
    }

    private static int checkedDimension(long value) {
        if (value <= 0L || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid Paddle tensor dimension");
        }
        return (int) value;
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalStateException("Paddle tensor operation failed");
        }
    }

    private record DetectedBox(Rect bounds, double score) {
    }

    record DbBox(
            double centerX, double centerY, double width, double height, double angleDegrees) {
        private double area() {
            return width * height;
        }

        private double perimeter() {
            return 2.0 * (width + height);
        }
    }

    record Recognition(String text, double confidence) {
    }

    /** Payload-free, test-observable stage counters; no recognized or expected text escapes. */
    record RecognitionDiagnostics(
            int detectedRegions,
            int recognitionAttempts,
            int recognizedElements,
            int recognizedCharacters,
            int lowConfidenceElements,
            int mediumConfidenceElements,
            int highConfidenceElements,
            boolean exactExpectedElement,
            double minimumNormalizedEditDistance) {
        RecognitionDiagnostics {
            if (detectedRegions < 0 || recognitionAttempts < 0 || recognizedElements < 0
                    || recognizedCharacters < 0 || lowConfidenceElements < 0
                    || mediumConfidenceElements < 0 || highConfidenceElements < 0
                    || !Double.isFinite(minimumNormalizedEditDistance)
                    || minimumNormalizedEditDistance < 0.0
                    || minimumNormalizedEditDistance > 1.0) {
                throw new IllegalArgumentException("Invalid payload-free OCR diagnostics");
            }
        }
    }

    private record Predictors(
            ReleasablePredictor detector,
            ReleasablePredictor recognizer,
            List<String> characters) {
    }

    private static final class ReleasablePredictor extends PaddlePredictor {
        private ReleasablePredictor(MobileConfig config) {
            super(config);
        }

        private void release() {
            clear();
        }
    }

}
