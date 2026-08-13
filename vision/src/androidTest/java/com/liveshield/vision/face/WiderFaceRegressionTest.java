package com.liveshield.vision.face;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Local-only WIDER FACE detector regression; public image bytes stay outside the test APK. */
@RunWith(AndroidJUnit4.class)
public final class WiderFaceRegressionTest {
    private static final String TAG = "WiderFaceMetrics";
    private static final int EXPECTED_IMAGES = 200;
    private static final int EXPECTED_PRIMARY_IMAGES = 40;
    private static final double MATCH_IOU = 0.20;
    private static final double PADDING_FRACTION = 0.25;
    private static final List<String> SLICES = List.of(
            "small", "heavy_blur", "heavy_occlusion", "difficult_capture", "baseline");

    @Test
    public void selectedPublicV1ImagesReportPaddedContainmentAndLocalization() throws Exception {
        assumeArgumentPresent("widerManifest");
        File manifest = requiredArgumentFile("widerManifest");
        File annotationRoot = requiredArgumentDirectory("widerAnnotations");
        File mediaRoot = requiredArgumentDirectory("widerMedia");
        List<Fixture> fixtures = readFixtures(manifest, annotationRoot, mediaRoot);
        validateSelection(fixtures);

        Map<String, Accumulator> metrics = new LinkedHashMap<>();
        metrics.put("overall", new Accumulator());
        for (String slice : SLICES) {
            metrics.put(slice, new Accumulator());
        }

        try (OfflineFaceAnalyzer analyzer = new OfflineFaceAnalyzer(
                ApplicationProvider.getApplicationContext())) {
            long timestamp = 1L;
            for (Fixture fixture : fixtures) {
                verifySource(fixture);
                Bitmap bitmap = BitmapFactory.decodeFile(fixture.media().getAbsolutePath());
                assertNotNull("Could not decode selected fixture " + fixture.fixtureId(), bitmap);
                BitmapFrame frame = new BitmapFrame(bitmap);
                DetectorSnapshot snapshot = analyzer.analyze(
                                frame,
                                FrameTimestamp.ofNanos(timestamp++),
                                0,
                                CoordinateTransform.identity())
                        .get(60, TimeUnit.SECONDS);
                assertFalse("Detector failed for selected fixture " + fixture.fixtureId(),
                        snapshot.failure().isPresent());
                assertTrue("Detector did not release " + fixture.fixtureId(), frame.closed.get());
                List<NormalizedRect> predictions = snapshot.findings().stream()
                        .flatMap(region -> region.bounds().stream())
                        .toList();
                ImageMetrics result = evaluate(fixture.truth(), predictions);
                metrics.get("overall").add(result);
                for (String slice : fixture.slices()) {
                    metrics.get(slice).add(result);
                }
            }
        }

        for (Map.Entry<String, Accumulator> entry : metrics.entrySet()) {
            Log.i(TAG, entry.getValue().report(entry.getKey()));
        }
        assertEquals(EXPECTED_IMAGES, metrics.get("overall").images);
    }

    @Test
    public void metricUsesOneToOneMatchesAndReportsUnmatchedPredictions() {
        List<NormalizedRect> truth = List.of(
                rect(0.10, 0.10, 0.30, 0.30),
                rect(0.60, 0.60, 0.80, 0.80));
        List<NormalizedRect> predictions = List.of(
                rect(0.12, 0.12, 0.28, 0.28),
                rect(0.00, 0.70, 0.10, 0.80));

        ImageMetrics metrics = evaluate(truth, predictions);

        assertEquals(2, metrics.truthFaces());
        assertEquals(2, metrics.predictions());
        assertEquals(1, metrics.matchedFaces());
        assertEquals(1, metrics.containedFaces());
        assertEquals(1, metrics.falsePositives());
        assertEquals(0.64, metrics.iouSum(), 0.000_001);
    }

    private static File requiredArgumentFile(String name) {
        String value = InstrumentationRegistry.getArguments().getString(name);
        assertNotNull("Missing instrumentation argument: " + name, value);
        File file = new File(value);
        assertTrue(name + " must reference a local file", file.isFile());
        return file;
    }

    private static void assumeArgumentPresent(String name) {
        assumeNotNull(
                "Opt-in instrumentation argument is absent: " + name,
                InstrumentationRegistry.getArguments().getString(name));
    }

    private static File requiredArgumentDirectory(String name) {
        String value = InstrumentationRegistry.getArguments().getString(name);
        assertNotNull("Missing instrumentation argument: " + name, value);
        File directory = new File(value);
        assertTrue(name + " must reference a local directory", directory.isDirectory());
        return directory;
    }

    private static List<Fixture> readFixtures(
            File manifest, File annotationRoot, File mediaRoot) throws IOException, JSONException {
        List<Fixture> fixtures = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(manifest))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JSONObject entry = new JSONObject(line);
                if (!"WIDER_FACE".equals(entry.getString("group"))) {
                    continue;
                }
                assertEquals("REGRESSION", entry.getString("split"));
                JSONObject dataset = entry.getJSONObject("publicDataset");
                assertEquals("CC BY-NC-ND 4.0", dataset.getString("license"));
                assertEquals("local non-commercial unmodified detector evaluation only",
                        dataset.getString("allowedUsage"));
                String sourcePath = entry.getString("sourcePath");
                assertTrue(sourcePath.startsWith("wider/") && !sourcePath.contains(".."));
                String truthPath = entry.getString("truthPath");
                assertTrue(truthPath.startsWith("public-v1/wider/")
                        && !truthPath.contains(".."));
                List<String> labels = strings(entry.getJSONArray("scenarioIds"));
                fixtures.add(new Fixture(
                        entry.getString("fixtureId"),
                        new File(mediaRoot, sourcePath.substring("wider/".length())),
                        entry.getString("sourceDigest"),
                        dataset.getLong("byteLength"),
                        primarySlice(labels),
                        applicableSlices(labels),
                        readTruth(new File(
                                annotationRoot,
                                truthPath.substring("public-v1/wider/".length())),
                                entry.getString("fixtureId"))));
            }
        }
        fixtures.sort(Comparator.comparing(Fixture::fixtureId));
        return List.copyOf(fixtures);
    }

    private static List<NormalizedRect> readTruth(File file, String fixtureId)
            throws IOException, JSONException {
        assertTrue("Missing truth for " + fixtureId, file.isFile());
        String line;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            line = reader.readLine();
            assertNotNull("Empty truth for " + fixtureId, line);
            assertTrue("Truth must contain exactly one frame", reader.readLine() == null);
        }
        JSONObject truth = new JSONObject(line);
        assertEquals(fixtureId, truth.getString("fixtureId"));
        assertEquals(0, truth.getInt("frameIndex"));
        List<NormalizedRect> faces = new ArrayList<>();
        JSONArray objects = truth.getJSONArray("objects");
        for (int index = 0; index < objects.length(); index++) {
            JSONObject object = objects.getJSONObject(index);
            assertEquals("FACE", object.getString("category"));
            if (!object.getBoolean("protectable")) {
                continue;
            }
            JSONArray polygon = object.getJSONArray("polygon");
            assertEquals(4, polygon.length());
            JSONArray topLeft = polygon.getJSONArray(0);
            JSONArray bottomRight = polygon.getJSONArray(2);
            faces.add(rect(
                    topLeft.getDouble(0), topLeft.getDouble(1),
                    bottomRight.getDouble(0), bottomRight.getDouble(1)));
        }
        assertFalse("Selected image must contain a valid face: " + fixtureId, faces.isEmpty());
        return List.copyOf(faces);
    }

    private static List<String> strings(JSONArray values) throws JSONException {
        List<String> result = new ArrayList<>(values.length());
        for (int index = 0; index < values.length(); index++) {
            result.add(values.getString(index));
        }
        return List.copyOf(result);
    }

    private static String primarySlice(List<String> labels) {
        List<String> primary = labels.stream()
                .filter(label -> label.startsWith("primary-"))
                .map(label -> label.substring("primary-".length()))
                .toList();
        assertEquals("Each WIDER fixture needs one primary selection slot", 1, primary.size());
        assertTrue("Unknown primary WIDER slice", SLICES.contains(primary.get(0)));
        return primary.get(0);
    }

    private static Set<String> applicableSlices(List<String> labels) {
        Set<String> slices = new HashSet<>();
        labels.stream()
                .filter(label -> label.startsWith("slice-"))
                .map(label -> label.substring("slice-".length()))
                .forEach(slices::add);
        assertFalse("Each WIDER fixture needs an applicable slice", slices.isEmpty());
        assertTrue("Unknown applicable WIDER slice", SLICES.containsAll(slices));
        return Set.copyOf(slices);
    }

    private static void validateSelection(List<Fixture> fixtures) {
        assertEquals(EXPECTED_IMAGES, fixtures.size());
        Set<String> fixtureIds = new HashSet<>();
        Set<String> sourcePaths = new HashSet<>();
        Map<String, Integer> primaryCounts = new HashMap<>();
        for (Fixture fixture : fixtures) {
            assertTrue("Duplicate fixture ID", fixtureIds.add(fixture.fixtureId()));
            assertTrue("Duplicate selected source", sourcePaths.add(fixture.media().getPath()));
            primaryCounts.merge(fixture.primarySlice(), 1, Integer::sum);
        }
        for (String slice : SLICES) {
            assertEquals("Unexpected primary count for " + slice,
                    EXPECTED_PRIMARY_IMAGES, (int) primaryCounts.getOrDefault(slice, 0));
        }
    }

    private static void verifySource(Fixture fixture) throws IOException {
        assertTrue("Selected media is missing: " + fixture.fixtureId(), fixture.media().isFile());
        assertEquals("Selected byte length changed: " + fixture.fixtureId(),
                fixture.byteLength(), fixture.media().length());
        assertEquals("Selected digest changed: " + fixture.fixtureId(),
                fixture.digest(), sha256(fixture.media()));
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Android runtime has no SHA-256", exception);
        }
        try (InputStream input = new FileInputStream(file)) {
            byte[] chunk = new byte[8_192];
            int count;
            while ((count = input.read(chunk)) != -1) {
                digest.update(chunk, 0, count);
            }
        }
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest.digest()) {
            value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return value.toString();
    }

    static ImageMetrics evaluate(
            List<NormalizedRect> truth, List<NormalizedRect> predictions) {
        List<MatchCandidate> candidates = new ArrayList<>();
        for (int truthIndex = 0; truthIndex < truth.size(); truthIndex++) {
            for (int predictionIndex = 0; predictionIndex < predictions.size(); predictionIndex++) {
                NormalizedRect expected = truth.get(truthIndex);
                NormalizedRect predicted = predictions.get(predictionIndex);
                double overlap = iou(expected, predicted);
                boolean contained = contains(pad(predicted), expected);
                if (overlap >= MATCH_IOU || contained) {
                    candidates.add(new MatchCandidate(
                            truthIndex, predictionIndex, overlap, contained));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(MatchCandidate::iou).reversed()
                .thenComparingInt(MatchCandidate::truthIndex)
                .thenComparingInt(MatchCandidate::predictionIndex));
        Set<Integer> matchedTruth = new HashSet<>();
        Set<Integer> matchedPredictions = new HashSet<>();
        int contained = 0;
        double iouSum = 0.0;
        for (MatchCandidate candidate : candidates) {
            if (!matchedTruth.add(candidate.truthIndex())) {
                continue;
            }
            if (!matchedPredictions.add(candidate.predictionIndex())) {
                matchedTruth.remove(candidate.truthIndex());
                continue;
            }
            iouSum += candidate.iou();
            if (candidate.contained()) {
                contained++;
            }
        }
        return new ImageMetrics(
                truth.size(), predictions.size(), matchedTruth.size(), contained,
                predictions.size() - matchedPredictions.size(), iouSum);
    }

    private static NormalizedRect pad(NormalizedRect source) {
        double horizontal = (source.right() - source.left()) * PADDING_FRACTION;
        double vertical = (source.bottom() - source.top()) * PADDING_FRACTION;
        return rect(
                Math.max(0.0, source.left() - horizontal),
                Math.max(0.0, source.top() - vertical),
                Math.min(1.0, source.right() + horizontal),
                Math.min(1.0, source.bottom() + vertical));
    }

    private static boolean contains(NormalizedRect outer, NormalizedRect inner) {
        return outer.left() <= inner.left() && outer.top() <= inner.top()
                && outer.right() >= inner.right() && outer.bottom() >= inner.bottom();
    }

    private static double iou(NormalizedRect first, NormalizedRect second) {
        double left = Math.max(first.left(), second.left());
        double top = Math.max(first.top(), second.top());
        double right = Math.min(first.right(), second.right());
        double bottom = Math.min(first.bottom(), second.bottom());
        double intersection = Math.max(0.0, right - left) * Math.max(0.0, bottom - top);
        double firstArea = (first.right() - first.left()) * (first.bottom() - first.top());
        double secondArea = (second.right() - second.left())
                * (second.bottom() - second.top());
        return intersection / (firstArea + secondArea - intersection);
    }

    private static NormalizedRect rect(double left, double top, double right, double bottom) {
        return new NormalizedRect(left, top, right, bottom);
    }

    private record Fixture(
            String fixtureId,
            File media,
            String digest,
            long byteLength,
            String primarySlice,
            Set<String> slices,
            List<NormalizedRect> truth) {
        private Fixture {
            Objects.requireNonNull(fixtureId, "fixtureId");
            Objects.requireNonNull(media, "media");
            Objects.requireNonNull(digest, "digest");
            Objects.requireNonNull(primarySlice, "primarySlice");
            slices = Set.copyOf(Objects.requireNonNull(slices, "slices"));
            truth = List.copyOf(Objects.requireNonNull(truth, "truth"));
        }
    }

    record ImageMetrics(
            int truthFaces,
            int predictions,
            int matchedFaces,
            int containedFaces,
            int falsePositives,
            double iouSum) {
    }

    private record MatchCandidate(
            int truthIndex, int predictionIndex, double iou, boolean contained) {
    }

    private static final class Accumulator {
        private int images;
        private int truthFaces;
        private int predictions;
        private int matchedFaces;
        private int containedFaces;
        private int falsePositives;
        private double iouSum;

        private void add(ImageMetrics metrics) {
            images++;
            truthFaces += metrics.truthFaces();
            predictions += metrics.predictions();
            matchedFaces += metrics.matchedFaces();
            containedFaces += metrics.containedFaces();
            falsePositives += metrics.falsePositives();
            iouSum += metrics.iouSum();
        }

        private String report(String slice) {
            return String.format(
                    Locale.ROOT,
                    "slice=%s images=%d containment=%d/%d matched=%d/%d "
                            + "falsePositives=%d/%d meanMatchedIoU=%s "
                            + "paddingFraction=%.2f matchIoU=%.2f",
                    slice,
                    images,
                    containedFaces,
                    truthFaces,
                    matchedFaces,
                    truthFaces,
                    falsePositives,
                    predictions,
                    matchedFaces == 0 ? "NA"
                            : String.format(Locale.ROOT, "%.6f", iouSum / matchedFaces),
                    PADDING_FRACTION,
                    MATCH_IOU);
        }
    }

    private static final class BitmapFrame implements FaceAnalysisFrame {
        private final Bitmap bitmap;
        private final AtomicBoolean closed = new AtomicBoolean();

        private BitmapFrame(Bitmap bitmap) {
            this.bitmap = Objects.requireNonNull(bitmap, "bitmap");
        }

        @Override
        public Bitmap bitmap(int rotationDegrees) {
            assertEquals(0, rotationDegrees);
            return bitmap;
        }

        @Override
        public int width() {
            return bitmap.getWidth();
        }

        @Override
        public int height() {
            return bitmap.getHeight();
        }

        @Override
        public void close() {
            assertTrue("Frame must close exactly once", closed.compareAndSet(false, true));
        }
    }
}
