package com.liveshield.vision.pii;

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
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Local-only BIV-Priv-Seg localization smoke; public image bytes stay outside the APK. */
@RunWith(AndroidJUnit4.class)
public final class BivPrivSmokeTest {
    private static final String TAG = "BivPrivMetrics";
    private static final int EXPECTED_IMAGES = 16;

    @Test
    public void allSixteenPublicImagesRunThroughOfflineTextAnalyzer() throws Exception {
        assumeArgumentPresent("bivManifest");
        File manifest = requiredArgumentFile("bivManifest");
        File annotationRoot = requiredArgumentDirectory("bivAnnotations");
        File mediaRoot = requiredArgumentDirectory("bivMedia");
        List<Fixture> fixtures = readFixtures(manifest, annotationRoot, mediaRoot);
        validateFixtures(fixtures);
        Accumulator total = new Accumulator();

        try (OfflineTextAnalyzer analyzer = new OfflineTextAnalyzer(
                ApplicationProvider.getApplicationContext(),
                OfflineTextAnalyzer.Configuration.defaults(Set.of()))) {
            long timestamp = 1L;
            for (Fixture fixture : fixtures) {
                verifySource(fixture);
                Bitmap bitmap = BitmapFactory.decodeFile(fixture.media().getAbsolutePath());
                assertNotNull("Could not decode fixture " + fixture.fixtureId(), bitmap);
                BitmapFrame frame = new BitmapFrame(bitmap);
                DetectorSnapshot snapshot = analyzer.analyze(
                                frame,
                                FrameTimestamp.ofNanos(timestamp++),
                                0,
                                CoordinateTransform.identity())
                        .get(60, TimeUnit.SECONDS);
                assertFalse("Analyzer failed for " + fixture.fixtureId(),
                        snapshot.failure().isPresent());
                assertTrue("Analyzer did not release " + fixture.fixtureId(), frame.closed.get());
                List<NormalizedRect> predictions = snapshot.findings().stream()
                        .flatMap(finding -> finding.bounds().stream())
                        .toList();
                total.add(evaluate(fixture.truth(), predictions));
            }
        }

        assertEquals(EXPECTED_IMAGES, total.images);
        Log.i(TAG, total.report());
    }

    @Test
    public void localizationMetricsReportCoverageExcessMaskAndFalsePositives() {
        List<NormalizedRect> truth = List.of(
                rect(0.10, 0.10, 0.30, 0.30),
                rect(0.60, 0.60, 0.80, 0.80));
        List<NormalizedRect> predictions = List.of(
                rect(0.10, 0.10, 0.30, 0.30),
                rect(0.55, 0.55, 0.85, 0.85),
                rect(0.00, 0.80, 0.10, 0.90));

        LocalizationMetrics metrics = evaluate(truth, predictions);

        assertEquals(2, metrics.truthRegions());
        assertEquals(3, metrics.predictions());
        assertEquals(2, metrics.matchedRegions());
        assertEquals(1, metrics.falsePositives());
        assertEquals(2.0, metrics.coverageSum(), 0.000_001);
        assertEquals(5.0 / 9.0, metrics.excessiveMaskSum(), 0.000_001);
    }

    @Test
    public void metricResultContainsNumbersOnlyAndCannotRetainPayload() {
        for (Field field : LocalizationMetrics.class.getDeclaredFields()) {
            Class<?> type = field.getType();
            assertTrue(type.isPrimitive());
            assertFalse(field.getName().toLowerCase(Locale.ROOT).contains("payload"));
            assertFalse(field.getName().toLowerCase(Locale.ROOT).contains("text"));
        }
    }

    static LocalizationMetrics evaluate(
            List<NormalizedRect> truth, List<NormalizedRect> predictions) {
        List<Candidate> candidates = new ArrayList<>();
        for (int truthIndex = 0; truthIndex < truth.size(); truthIndex++) {
            for (int predictionIndex = 0; predictionIndex < predictions.size(); predictionIndex++) {
                double intersection = intersectionArea(
                        truth.get(truthIndex), predictions.get(predictionIndex));
                if (intersection > 0.0) {
                    candidates.add(new Candidate(truthIndex, predictionIndex, intersection));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::intersection).reversed()
                .thenComparingInt(Candidate::truthIndex)
                .thenComparingInt(Candidate::predictionIndex));
        Set<Integer> matchedTruth = new HashSet<>();
        Set<Integer> matchedPredictions = new HashSet<>();
        double coverage = 0.0;
        double excessiveMask = 0.0;
        for (Candidate candidate : candidates) {
            if (matchedTruth.contains(candidate.truthIndex())
                    || matchedPredictions.contains(candidate.predictionIndex())) {
                continue;
            }
            matchedTruth.add(candidate.truthIndex());
            matchedPredictions.add(candidate.predictionIndex());
            NormalizedRect expected = truth.get(candidate.truthIndex());
            NormalizedRect prediction = predictions.get(candidate.predictionIndex());
            coverage += candidate.intersection() / area(expected);
            excessiveMask += (area(prediction) - candidate.intersection()) / area(prediction);
        }
        return new LocalizationMetrics(
                truth.size(),
                predictions.size(),
                matchedTruth.size(),
                predictions.size() - matchedPredictions.size(),
                coverage,
                excessiveMask);
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
                if (!"BIV_PRIV_SEG".equals(entry.getString("group"))) {
                    continue;
                }
                assertEquals("SMOKE", entry.getString("split"));
                JSONObject dataset = entry.getJSONObject("publicDataset");
                assertEquals("CC BY 4.0", dataset.getString("license"));
                assertEquals("local unmodified detector localization smoke evaluation",
                        dataset.getString("allowedUsage"));
                String sourcePath = entry.getString("sourcePath");
                assertTrue(sourcePath.startsWith("biv-support/")
                        && !sourcePath.contains(".."));
                String truthPath = entry.getString("truthPath");
                assertTrue(truthPath.startsWith("public-v1/biv/")
                        && !truthPath.contains(".."));
                fixtures.add(new Fixture(
                        entry.getString("fixtureId"),
                        new File(mediaRoot,
                                sourcePath.substring("biv-support/".length())),
                        entry.getString("sourceDigest"),
                        dataset.getLong("byteLength"),
                        readTruth(new File(annotationRoot,
                                truthPath.substring("public-v1/biv/".length())),
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
        assertEquals("PROTECT_REGIONS", truth.getString("requiredAction"));
        List<NormalizedRect> regions = new ArrayList<>();
        JSONArray objects = truth.getJSONArray("objects");
        for (int index = 0; index < objects.length(); index++) {
            JSONObject object = objects.getJSONObject(index);
            assertEquals("BIV_PRIVATE_OBJECT", object.getString("category"));
            if (object.getBoolean("protectable")) {
                regions.add(bounds(object.getJSONArray("polygon")));
            }
        }
        assertFalse("BIV truth needs a protected region: " + fixtureId, regions.isEmpty());
        return List.copyOf(regions);
    }

    private static NormalizedRect bounds(JSONArray polygon) throws JSONException {
        assertTrue("BIV polygon requires at least four points", polygon.length() >= 4);
        double left = 1.0;
        double top = 1.0;
        double right = 0.0;
        double bottom = 0.0;
        for (int index = 0; index < polygon.length(); index++) {
            JSONArray point = polygon.getJSONArray(index);
            left = Math.min(left, point.getDouble(0));
            top = Math.min(top, point.getDouble(1));
            right = Math.max(right, point.getDouble(0));
            bottom = Math.max(bottom, point.getDouble(1));
        }
        return rect(left, top, right, bottom);
    }

    private static void validateFixtures(List<Fixture> fixtures) {
        assertEquals(EXPECTED_IMAGES, fixtures.size());
        Set<String> ids = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (Fixture fixture : fixtures) {
            assertTrue("Duplicate BIV fixture ID", ids.add(fixture.fixtureId()));
            assertTrue("Duplicate BIV source", paths.add(fixture.media().getPath()));
        }
    }

    private static void verifySource(Fixture fixture) throws IOException {
        assertTrue("Missing BIV media: " + fixture.fixtureId(), fixture.media().isFile());
        assertEquals("BIV byte length changed: " + fixture.fixtureId(),
                fixture.byteLength(), fixture.media().length());
        assertEquals("BIV digest changed: " + fixture.fixtureId(),
                fixture.digest(), sha256(fixture.media()));
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Android runtime has no SHA-256", impossible);
        }
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8_192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest.digest()) {
            value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return value.toString();
    }

    private static double intersectionArea(NormalizedRect first, NormalizedRect second) {
        double width = Math.max(0.0,
                Math.min(first.right(), second.right()) - Math.max(first.left(), second.left()));
        double height = Math.max(0.0,
                Math.min(first.bottom(), second.bottom()) - Math.max(first.top(), second.top()));
        return width * height;
    }

    private static double area(NormalizedRect region) {
        return (region.right() - region.left()) * (region.bottom() - region.top());
    }

    private static NormalizedRect rect(double left, double top, double right, double bottom) {
        return new NormalizedRect(left, top, right, bottom);
    }

    record LocalizationMetrics(
            int truthRegions,
            int predictions,
            int matchedRegions,
            int falsePositives,
            double coverageSum,
            double excessiveMaskSum) {
    }

    private record Candidate(int truthIndex, int predictionIndex, double intersection) {
    }

    private record Fixture(
            String fixtureId,
            File media,
            String digest,
            long byteLength,
            List<NormalizedRect> truth) {
        private Fixture {
            Objects.requireNonNull(fixtureId, "fixtureId");
            Objects.requireNonNull(media, "media");
            Objects.requireNonNull(digest, "digest");
            truth = List.copyOf(Objects.requireNonNull(truth, "truth"));
        }
    }

    private static final class Accumulator {
        private int images;
        private int truthRegions;
        private int predictions;
        private int matchedRegions;
        private int falsePositives;
        private double coverageSum;
        private double excessiveMaskSum;

        private void add(LocalizationMetrics metrics) {
            images++;
            truthRegions += metrics.truthRegions();
            predictions += metrics.predictions();
            matchedRegions += metrics.matchedRegions();
            falsePositives += metrics.falsePositives();
            coverageSum += metrics.coverageSum();
            excessiveMaskSum += metrics.excessiveMaskSum();
        }

        private String report() {
            return String.format(
                    Locale.ROOT,
                    "images=%d matched=%d/%d coverageSum=%.6f/%d "
                            + "excessiveMaskSum=%.6f/%d falsePositives=%d/%d",
                    images,
                    matchedRegions,
                    truthRegions,
                    coverageSum,
                    truthRegions,
                    excessiveMaskSum,
                    matchedRegions,
                    falsePositives,
                    predictions);
        }
    }

    private static final class BitmapFrame implements TextAnalysisFrame {
        private final Bitmap bitmap;
        private final AtomicBoolean closed = new AtomicBoolean();

        private BitmapFrame(Bitmap bitmap) {
            this.bitmap = Objects.requireNonNull(bitmap, "bitmap");
        }

        @Override
        public Bitmap bitmap(int rotationDegrees) {
            assertEquals(0, rotationDegrees);
            return bitmap.copy(Bitmap.Config.ARGB_8888, false);
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
            bitmap.recycle();
        }
    }
}
