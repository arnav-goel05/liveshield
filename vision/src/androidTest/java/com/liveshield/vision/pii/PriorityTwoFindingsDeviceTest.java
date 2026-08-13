package com.liveshield.vision.pii;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.policy.PriorityTwoPolicy;
import com.liveshield.privacy.policy.SensitiveFindingPolicy;
import com.liveshield.privacy.policy.SessionPrivacyConfiguration;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** One-shot, payload-free findings runner for the frozen synthetic Priority 2 corpus. */
@RunWith(AndroidJUnit4.class)
public final class PriorityTwoFindingsDeviceTest {
    private static final String TAG = "PriorityTwoMetrics";
    private static final int EXPECTED_FIXTURES = 26;
    private static final int EXPECTED_FRAMES = 208;
    private static final Set<String> LANES = Set.of(
            "AUTOMATIC_PATTERN", "CONFIGURED_WATCHLIST", "CONFIGURED_ZONE");
    private static final Map<String, String> DEVELOPMENT_WATCHLIST = Map.of(
            "person-name", "AVERY EXAMPLE",
            "address", "42 EXAMPLE WAY",
            "employer", "EXAMPLE LABS",
            "school", "EXAMPLE ACADEMY");
    private static final Map<String, String> DEVELOPMENT_EXPECTED_TEXT = Map.of(
            "email-address", "DEV1@EXAMPLE.TEST",
            "phone-number", "2025550101",
            "payment-card-like", "4242424242424242",
            "verification-code", "TEST CODE 042731",
            "person-name", "AVERY EXAMPLE",
            "address", "42 EXAMPLE WAY",
            "employer", "EXAMPLE LABS",
            "school", "EXAMPLE ACADEMY");
    private static final Map<String, String> HOLDOUT_WATCHLIST = Map.of(
            "person-name", "RILEY SAMPLE",
            "address", "77 SAMPLE ROAD",
            "employer", "SAMPLE WORKS",
            "school", "SAMPLE ACADEMY");

    @Test
    public void findingsOutputIsRestrictedToAppOwnedFilesDirectory() throws Exception {
        File filesDirectory = ApplicationProvider.getApplicationContext().getFilesDir();
        File resolved = privateOutput(filesDirectory, "priority2-findings.jsonl");

        assertEquals(filesDirectory.getCanonicalFile(), resolved.getParentFile());
        for (String unsafe : List.of("../escape", "/data/local/tmp/findings", "a/b", "")) {
            try {
                privateOutput(filesDirectory, unsafe);
                throw new AssertionError("Unsafe findings path accepted");
            } catch (IllegalArgumentException expected) {
                assertFalse(expected.getMessage().isBlank());
            }
        }
    }

    @Test
    public void truthSequenceDefinesEightUniqueOrderedFramesWithoutContainerMetadata() {
        List<TruthFrame> frames = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            frames.add(new TruthFrame(
                    index,
                    index * 125_000_000L,
                    "EMAIL",
                    new NormalizedRect(0.1, 0.1, 0.2, 0.2)));
        }
        validateTruthSequence(frames);

        List<TruthFrame> duplicateTimestamp = new ArrayList<>(frames);
        duplicateTimestamp.set(7, new TruthFrame(
                7, frames.get(6).timestampNanos(), "EMAIL",
                new NormalizedRect(0.1, 0.1, 0.2, 0.2)));
        try {
            validateTruthSequence(duplicateTimestamp);
            throw new AssertionError("Duplicate timestamp accepted");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isBlank());
        }
    }

    @Test
    public void timestampSelectionIsExactAndOutputKeysMustBeCompleteAndUnique() {
        assertEquals(0L, timestampMicros(0L));
        assertEquals(125_000L, timestampMicros(125_000_000L));
        try {
            timestampMicros(1_001L);
            throw new AssertionError("Sub-microsecond timestamp accepted");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isBlank());
        }

        Counts counts = new Counts();
        for (int fixture = 0; fixture < EXPECTED_FIXTURES; fixture++) {
            for (int frame = 0; frame < 8; frame++) {
                counts.recordFrame("fixture-" + fixture, frame, false, 0);
            }
        }
        counts.verifyComplete();
        try {
            counts.recordFrame("fixture-0", 0, false, 0);
            throw new AssertionError("Duplicate finding key accepted");
        } catch (IllegalStateException expected) {
            assertFalse(expected.getMessage().isBlank());
        }
    }

    @Test
    public void verifiedMediaCopyIsAppPrivateAndDeletedOnClose() throws Exception {
        File filesDirectory = ApplicationProvider.getApplicationContext().getFilesDir();
        File cacheDirectory = new File(
                ApplicationProvider.getApplicationContext().getCacheDir(),
                "priority2-media-copy-success");
        assertTrue(cacheDirectory.mkdir() || cacheDirectory.isDirectory());
        File source = new File(filesDirectory, "priority2-copy-source.mp4");
        byte[] sourceBytes = new byte[] {0, 1, 2, 3, 4, 5, 6, 7};
        try (FileOutputStream output = new FileOutputStream(source, false)) {
            output.write(sourceBytes);
        }

        File copied;
        try (VerifiedPrivateMedia media = VerifiedPrivateMedia.copyOf(
                source, cacheDirectory, sha256(source))) {
            copied = media.file();
            assertTrue(copied.isFile());
            assertEquals(cacheDirectory.getCanonicalFile(), copied.getParentFile());
            assertEquals(source.length(), copied.length());
            assertEquals(sha256(source), sha256(copied));
            try (FileInputStream input = media.open()) {
                assertNotNull(input.getFD());
            }
        } finally {
            assertTrue(source.delete());
        }
        assertFalse("Private media copy survived close", copied.exists());
        assertTrue(cacheDirectory.delete());
    }

    @Test
    public void mediaCopyRejectsDigestMismatchWithoutLeavingPrivateData() throws Exception {
        File filesDirectory = ApplicationProvider.getApplicationContext().getFilesDir();
        File cacheDirectory = new File(
                ApplicationProvider.getApplicationContext().getCacheDir(),
                "priority2-media-copy-failure");
        assertTrue(cacheDirectory.mkdir() || cacheDirectory.isDirectory());
        File source = new File(filesDirectory, "priority2-copy-bad-source.mp4");
        try (FileOutputStream output = new FileOutputStream(source, false)) {
            output.write(new byte[] {8, 9, 10, 11});
        }

        try {
            VerifiedPrivateMedia.copyOf(
                    source,
                    cacheDirectory,
                    "0000000000000000000000000000000000000000000000000000000000000000");
            throw new AssertionError("Mismatched staged media digest accepted");
        } catch (IOException expected) {
            assertFalse(expected.getMessage().isBlank());
        } finally {
            assertTrue(source.delete());
        }
        File[] leftovers = cacheDirectory.listFiles();
        assertNotNull(leftovers);
        assertEquals(0, leftovers.length);
        assertTrue(cacheDirectory.delete());
    }

    @Test
    public void allDevelopmentAndHoldoutFixturesEmitCompletePayloadFreeFindings()
            throws Exception {
        assumeArgumentPresent("piiManifest");
        File manifest = requiredFileArgument("piiManifest");
        File truthRoot = requiredDirectoryArgument("piiTruth");
        File mediaRoot = requiredDirectoryArgument("piiMedia");
        File output = requiredPrivateOutputArgument("piiFindings");
        String requestedSplit = InstrumentationRegistry.getArguments().getString("piiSplit");
        if (requestedSplit != null) {
            assertTrue("Unsupported Priority 2 split", Set.of(
                    "DEVELOPMENT", "HOLDOUT").contains(requestedSplit));
        }
        List<Fixture> fixtures = readFixtures(
                manifest, truthRoot, mediaRoot, requestedSplit);
        validateFixtures(fixtures, requestedSplit);
        int expectedFrames = Math.multiplyExact(fixtures.size(), 8);
        Counts counts = new Counts(expectedFrames);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(output, false));
                OfflineBarcodeAnalyzer barcodeAnalyzer = new OfflineBarcodeAnalyzer()) {
            for (Fixture fixture : fixtures) {
                verifySource(fixture);
                if ("CONFIGURED_ZONE".equals(fixture.lane())) {
                    evaluateZoneFixture(fixture, writer, counts);
                } else if ("machine-readable-code".equals(fixture.categoryId())) {
                    evaluateBarcodeFixture(fixture, barcodeAnalyzer, writer, counts);
                } else {
                    evaluateTextFixture(fixture, writer, counts);
                }
            }
        }

        assertEquals(expectedFrames, counts.frames);
        assertEquals(expectedFrames, counts.releasedInputs);
        counts.verifyComplete();
        Log.i(TAG, counts.report());
    }

    private static void evaluateTextFixture(
            Fixture fixture, BufferedWriter writer, Counts counts) throws Exception {
        Set<String> watchlist = "CONFIGURED_WATCHLIST".equals(fixture.lane())
                ? configuredTerm(fixture) : Set.of();
        boolean diagnosticEnabled = "true".equals(InstrumentationRegistry.getArguments()
                .getString("piiOcrDiagnostics"));
        if (diagnosticEnabled) {
            assertEquals("OCR diagnostics are DEVELOPMENT-only",
                    "DEVELOPMENT", fixture.split());
        }
        AtomicReference<PaddleLiteTextRecognitionEngine.RecognitionDiagnostics> diagnostic =
                new AtomicReference<>();
        PaddleLiteTextRecognitionEngine engine = diagnosticEnabled
                ? new PaddleLiteTextRecognitionEngine(
                        ApplicationProvider.getApplicationContext(),
                        DEVELOPMENT_EXPECTED_TEXT.get(fixture.categoryId()),
                        value -> assertTrue("Duplicate OCR diagnostic", diagnostic.compareAndSet(
                                null, value)))
                : new PaddleLiteTextRecognitionEngine(
                        ApplicationProvider.getApplicationContext());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (SessionPrivacyConfiguration configuration = new SessionPrivacyConfiguration(
                watchlist, List.of());
                OfflineTextAnalyzer analyzer = new OfflineTextAnalyzer(
                        engine,
                        new OcrPrivacyClassifier(),
                        OfflineTextAnalyzer.Configuration.defaults(
                                configuration.normalizedWatchlistTerms()),
                        executor)) {
            withFrames(fixture, (truth, bitmap) -> {
                TextBitmapFrame frame = new TextBitmapFrame(bitmap);
                DetectorSnapshot snapshot = analyzer.analyze(
                                frame,
                                FrameTimestamp.ofNanos(truth.timestampNanos()),
                                0,
                                CoordinateTransform.identity())
                        .get(60, TimeUnit.SECONDS);
                assertTrue("Text input was not released", frame.closed.get());
                counts.recordRelease();
                PaddleLiteTextRecognitionEngine.RecognitionDiagnostics frameDiagnostic =
                        diagnosticEnabled ? diagnostic.getAndSet(null) : null;
                if (diagnosticEnabled) {
                    assertNotNull("Missing payload-free OCR diagnostic", frameDiagnostic);
                }
                writeSnapshot(fixture, truth, snapshot, frameDiagnostic, writer, counts);
            });
        }
        assertTrue("OCR diagnostic executor did not stop",
                executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    private static void evaluateBarcodeFixture(
            Fixture fixture,
            OfflineBarcodeAnalyzer analyzer,
            BufferedWriter writer,
            Counts counts) throws Exception {
        withFrames(fixture, (truth, bitmap) -> {
            BarcodeBitmapFrame frame = new BarcodeBitmapFrame(bitmap);
            DetectorSnapshot snapshot = analyzer.analyze(
                            frame,
                            FrameTimestamp.ofNanos(truth.timestampNanos()),
                            0,
                            CoordinateTransform.identity())
                    .get(60, TimeUnit.SECONDS);
            assertTrue("Barcode input was not released", frame.closed.get());
            counts.recordRelease();
            writeSnapshot(fixture, truth, snapshot, writer, counts);
        });
    }

    private static void evaluateZoneFixture(
            Fixture fixture, BufferedWriter writer, Counts counts) throws Exception {
        NormalizedRect completeZone = fixture.truth().stream()
                .map(TruthFrame::target)
                .reduce(PriorityTwoFindingsDeviceTest::union)
                .orElseThrow();
        try (SessionPrivacyConfiguration configuration = new SessionPrivacyConfiguration(
                Set.of(), List.of(completeZone))) {
            PriorityTwoPolicy policy = new PriorityTwoPolicy(new SensitiveFindingPolicy(
                    new SensitiveFindingPolicy.Configuration(
                            Set.of(DetectorLane.TEXT), 0L, 0L, 0.25, 8, 8, 16, 4)));
            withFrames(fixture, (truth, bitmap) -> {
                bitmap.recycle();
                FrameTimestamp timestamp = FrameTimestamp.ofNanos(truth.timestampNanos());
                DetectorSnapshot emptyText = DetectorSnapshot.success(
                        DetectorLane.TEXT, timestamp, timestamp, List.of());
                PriorityTwoPolicy.Result result = policy.evaluate(
                        timestamp, List.of(emptyText), configuration.snapshot(), false);
                counts.recordRelease();
                if (result.basis() == SensitiveFindingPolicy.Basis.SHIELD_REQUIRED) {
                    writeFindings(fixture, truth, List.of(), "SHIELD_REQUIRED", writer, counts);
                } else {
                    writeFindings(fixture, truth, result.regions(), null, writer, counts);
                }
            });
        }
    }

    private static void withFrames(Fixture fixture, FrameConsumer consumer) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        File cacheDirectory = ApplicationProvider.getApplicationContext().getCacheDir();
        try (VerifiedPrivateMedia media = VerifiedPrivateMedia.copyOf(
                fixture.media(), cacheDirectory, fixture.digest());
                FileInputStream input = media.open()) {
            retriever.setDataSource(input.getFD());
            for (TruthFrame truth : fixture.truth()) {
                Bitmap bitmap = retriever.getFrameAtTime(
                        timestampMicros(truth.timestampNanos()),
                        MediaMetadataRetriever.OPTION_CLOSEST);
                assertNotNull("Could not decode frame " + truth.frameIndex()
                        + " for " + fixture.fixtureId(), bitmap);
                consumer.accept(truth, bitmap);
            }
        } finally {
            retriever.release();
        }
    }

    private static void writeSnapshot(
            Fixture fixture,
            TruthFrame truth,
            DetectorSnapshot snapshot,
            BufferedWriter writer,
            Counts counts) throws IOException, JSONException {
        writeSnapshot(fixture, truth, snapshot, null, writer, counts);
    }

    private static void writeSnapshot(
            Fixture fixture,
            TruthFrame truth,
            DetectorSnapshot snapshot,
            PaddleLiteTextRecognitionEngine.RecognitionDiagnostics diagnostic,
            BufferedWriter writer,
            Counts counts) throws IOException, JSONException {
        String failure = snapshot.failure().map(value -> value.code().name()).orElse(null);
        writeFindings(fixture, truth, snapshot.findings(), failure, diagnostic, writer, counts);
    }

    private static void writeFindings(
            Fixture fixture,
            TruthFrame truth,
            List<ProtectedRegion> regions,
            String failure,
            BufferedWriter writer,
            Counts counts) throws IOException, JSONException {
        writeFindings(fixture, truth, regions, failure, null, writer, counts);
    }

    private static void writeFindings(
            Fixture fixture,
            TruthFrame truth,
            List<ProtectedRegion> regions,
            String failure,
            PaddleLiteTextRecognitionEngine.RecognitionDiagnostics diagnostic,
            BufferedWriter writer,
            Counts counts) throws IOException, JSONException {
        JSONArray findings = new JSONArray();
        for (ProtectedRegion region : regions) {
            String category = evaluationCategory(region.category(), fixture.truthCategory());
            if (category == null) {
                continue;
            }
            for (NormalizedRect bounds : region.bounds()) {
                findings.put(new JSONObject()
                        .put("category", category)
                        .put("polygon", polygon(bounds)));
            }
        }
        JSONObject observation = new JSONObject()
                .put("fixtureId", fixture.fixtureId())
                .put("frameIndex", truth.frameIndex())
                .put("findings", findings);
        if (failure != null) {
            observation.put("failure", failure);
        }
        if (diagnostic != null) {
            observation.put("ocrDiagnostics", diagnosticJson(
                    diagnostic, findings, fixture.truthCategory()));
        }
        writer.write(observation.toString());
        writer.newLine();
        writer.flush();
        counts.recordFrame(
                fixture.fixtureId(), truth.frameIndex(), failure != null, findings.length());
    }

    private static JSONObject diagnosticJson(
            PaddleLiteTextRecognitionEngine.RecognitionDiagnostics diagnostic,
            JSONArray findings,
            String expectedCategory) throws JSONException {
        int matchingFindings = 0;
        for (int index = 0; index < findings.length(); index++) {
            if (expectedCategory.equals(findings.getJSONObject(index).getString("category"))) {
                matchingFindings++;
            }
        }
        return new JSONObject()
                .put("detectedRegions", diagnostic.detectedRegions())
                .put("recognitionAttempts", diagnostic.recognitionAttempts())
                .put("recognizedElements", diagnostic.recognizedElements())
                .put("recognizedCharacters", diagnostic.recognizedCharacters())
                .put("lowConfidenceElements", diagnostic.lowConfidenceElements())
                .put("mediumConfidenceElements", diagnostic.mediumConfidenceElements())
                .put("highConfidenceElements", diagnostic.highConfidenceElements())
                .put("exactExpectedElement", diagnostic.exactExpectedElement())
                .put("minimumNormalizedEditDistance",
                        diagnostic.minimumNormalizedEditDistance())
                .put("validatorFindingCount", findings.length())
                .put("expectedCategoryFindingCount", matchingFindings);
    }

    private static String evaluationCategory(
            FindingCategory category, String configuredCategory) {
        return switch (category) {
            case AUTO_BARCODE -> "MACHINE_READABLE_CODE";
            case AUTO_EMAIL -> "EMAIL";
            case AUTO_PHONE -> "PHONE";
            case AUTO_CARD -> "PAYMENT_CARD";
            case AUTO_OTP -> "VERIFICATION_CODE";
            case WATCHLIST_MATCH, PRIVACY_ZONE -> configuredCategory;
            case FACE -> null;
        };
    }

    private static JSONArray polygon(NormalizedRect bounds) throws JSONException {
        return new JSONArray()
                .put(new JSONArray().put(bounds.left()).put(bounds.top()))
                .put(new JSONArray().put(bounds.right()).put(bounds.top()))
                .put(new JSONArray().put(bounds.right()).put(bounds.bottom()))
                .put(new JSONArray().put(bounds.left()).put(bounds.bottom()));
    }

    private static Set<String> configuredTerm(Fixture fixture) {
        Map<String, String> terms = "DEVELOPMENT".equals(fixture.split())
                ? DEVELOPMENT_WATCHLIST : HOLDOUT_WATCHLIST;
        String term = terms.get(fixture.categoryId());
        assertNotNull("Missing fixture-scoped watchlist configuration", term);
        return Set.of(term);
    }

    private static List<Fixture> readFixtures(
            File manifest, File truthRoot, File mediaRoot, String requestedSplit)
            throws IOException, JSONException {
        List<Fixture> fixtures = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(manifest))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JSONObject entry = new JSONObject(line);
                if (requestedSplit != null
                        && !requestedSplit.equals(entry.getString("split"))) {
                    continue;
                }
                assertEquals("PRIORITY_2", entry.getString("group"));
                assertEquals("SYNTHETIC", entry.getString("sourceKind"));
                assertEquals(1, entry.getJSONArray("mediaStreams").length());
                assertEquals("VIDEO", entry.getJSONArray("mediaStreams").getString(0));
                String sourcePath = safeRelative(entry.getString("sourcePath"), "pii-v1/");
                String truthPath = safeRelative(entry.getString("truthPath"), "pii-v1/");
                List<String> scenarios = strings(entry.getJSONArray("scenarioIds"));
                List<String> selectedLanes = scenarios.stream().filter(LANES::contains).toList();
                assertEquals("Fixture must have exactly one lane", 1, selectedLanes.size());
                String lane = selectedLanes.get(0);
                List<String> selectedCategories = scenarios.stream()
                        .filter(value -> !LANES.contains(value))
                        .filter(value -> value.equals("machine-readable-code")
                                || value.equals("email-address")
                                || value.equals("phone-number")
                                || value.equals("payment-card-like")
                                || value.equals("verification-code")
                                || value.equals("person-name")
                                || value.equals("address")
                                || value.equals("employer")
                                || value.equals("school")
                                || value.equals("document")
                                || value.equals("badge")
                                || value.equals("parcel-label")
                                || value.equals("device-screen"))
                        .toList();
                assertEquals("Fixture must have exactly one category", 1,
                        selectedCategories.size());
                String categoryId = selectedCategories.get(0);
                List<TruthFrame> truth = readTruth(
                        new File(truthRoot, truthPath), entry.getString("fixtureId"));
                fixtures.add(new Fixture(
                        entry.getString("fixtureId"),
                        entry.getString("split"),
                        lane,
                        categoryId,
                        truth.get(0).category(),
                        new File(mediaRoot, sourcePath),
                        entry.getString("sourceDigest"),
                        truth));
            }
        }
        fixtures.sort(Comparator.comparing(Fixture::fixtureId));
        return List.copyOf(fixtures);
    }

    private static List<TruthFrame> readTruth(File file, String fixtureId)
            throws IOException, JSONException {
        assertTrue("Missing truth for " + fixtureId, file.isFile());
        List<TruthFrame> frames = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JSONObject truth = new JSONObject(line);
                assertEquals(fixtureId, truth.getString("fixtureId"));
                List<JSONObject> protectedObjects = new ArrayList<>();
                JSONArray objects = truth.getJSONArray("objects");
                for (int index = 0; index < objects.length(); index++) {
                    JSONObject object = objects.getJSONObject(index);
                    if (object.getBoolean("protectable")) {
                        protectedObjects.add(object);
                    }
                }
                assertFalse("Truth frame has no protected target", protectedObjects.isEmpty());
                JSONObject complete = protectedObjects.stream()
                        .max(Comparator.comparingDouble(value -> area(boundsUnchecked(value))))
                        .orElseThrow();
                frames.add(new TruthFrame(
                        truth.getInt("frameIndex"),
                        truth.getLong("sourceTimestampNs"),
                        complete.getString("category"),
                        bounds(complete.getJSONArray("polygon"))));
            }
        }
        frames.sort(Comparator.comparingInt(TruthFrame::frameIndex));
        return List.copyOf(frames);
    }

    private static NormalizedRect boundsUnchecked(JSONObject object) {
        try {
            return bounds(object.getJSONArray("polygon"));
        } catch (JSONException exception) {
            throw new IllegalArgumentException("Invalid truth polygon", exception);
        }
    }

    private static NormalizedRect bounds(JSONArray polygon) throws JSONException {
        assertTrue("Truth polygon requires at least four points", polygon.length() >= 4);
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
        return new NormalizedRect(left, top, right, bottom);
    }

    private static void validateFixtures(List<Fixture> fixtures, String requestedSplit) {
        int expectedFixtures = requestedSplit == null ? EXPECTED_FIXTURES : 13;
        assertEquals(expectedFixtures, fixtures.size());
        if (requestedSplit == null) {
            assertEquals(13, fixtures.stream()
                    .filter(value -> "DEVELOPMENT".equals(value.split())).count());
            assertEquals(13, fixtures.stream()
                    .filter(value -> "HOLDOUT".equals(value.split())).count());
        } else {
            assertTrue(fixtures.stream().allMatch(
                    fixture -> requestedSplit.equals(fixture.split())));
        }
        Set<String> ids = new HashSet<>();
        Set<String> paths = new HashSet<>();
        int frames = 0;
        for (Fixture fixture : fixtures) {
            assertTrue("Duplicate fixture ID", ids.add(fixture.fixtureId()));
            assertTrue("Duplicate fixture media", paths.add(fixture.media().getPath()));
            assertEquals(8, fixture.truth().size());
            validateTruthSequence(fixture.truth());
            frames += fixture.truth().size();
        }
        assertEquals(Math.multiplyExact(expectedFixtures, 8), frames);
    }

    static void validateTruthSequence(List<TruthFrame> frames) {
        Objects.requireNonNull(frames, "frames");
        if (frames.size() != 8) {
            throw new IllegalArgumentException("Priority 2 fixture must have eight truth frames");
        }
        long priorTimestamp = -1L;
        for (int index = 0; index < frames.size(); index++) {
            TruthFrame frame = Objects.requireNonNull(frames.get(index), "truth frame");
            if (frame.frameIndex() != index || frame.timestampNanos() <= priorTimestamp) {
                throw new IllegalArgumentException(
                        "Truth frames require ordered indices and unique increasing timestamps");
            }
            priorTimestamp = frame.timestampNanos();
        }
    }

    static long timestampMicros(long timestampNanos) {
        if (timestampNanos < 0 || timestampNanos % 1_000L != 0L) {
            throw new IllegalArgumentException(
                    "Truth timestamp must convert exactly to non-negative microseconds");
        }
        return timestampNanos / 1_000L;
    }

    private static void verifySource(Fixture fixture) throws IOException {
        assertTrue("Missing fixture media: " + fixture.fixtureId(), fixture.media().isFile());
        assertEquals("Fixture digest changed: " + fixture.fixtureId(),
                fixture.digest(), sha256(fixture.media()));
    }

    private static File requiredFileArgument(String name) {
        File file = new File(requiredArgument(name));
        assertTrue(name + " must be a file", file.isFile());
        return file;
    }

    private static File requiredDirectoryArgument(String name) {
        File directory = new File(requiredArgument(name));
        assertTrue(name + " must be a directory", directory.isDirectory());
        return directory;
    }

    private static File requiredPrivateOutputArgument(String name) throws IOException {
        File filesDirectory = ApplicationProvider.getApplicationContext().getFilesDir();
        File output = privateOutput(filesDirectory, requiredArgument(name));
        if (output.exists()) {
            assertTrue("Unable to clear prior findings", output.delete());
        }
        assertTrue("Unable to create findings output", output.createNewFile());
        return output;
    }

    static File privateOutput(File filesDirectory, String filename) throws IOException {
        Objects.requireNonNull(filesDirectory, "filesDirectory");
        Objects.requireNonNull(filename, "filename");
        if (filename.isBlank() || filename.contains("/") || filename.contains("..")) {
            throw new IllegalArgumentException("Findings output must be an app-private basename");
        }
        File output = new File(filesDirectory, filename).getCanonicalFile();
        if (!filesDirectory.getCanonicalFile().equals(output.getParentFile())) {
            throw new IllegalArgumentException("Findings output escaped app-private storage");
        }
        return output;
    }

    private static String requiredArgument(String name) {
        String value = InstrumentationRegistry.getArguments().getString(name);
        assertNotNull("Missing instrumentation argument: " + name, value);
        return value;
    }

    private static void assumeArgumentPresent(String name) {
        assumeNotNull(
                "Opt-in instrumentation argument is absent: " + name,
                InstrumentationRegistry.getArguments().getString(name));
    }

    private static String safeRelative(String value, String prefix) {
        assertTrue("Unsafe fixture path", value.startsWith(prefix) && !value.contains(".."));
        return value.substring(prefix.length());
    }

    private static List<String> strings(JSONArray values) throws JSONException {
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            result.add(values.getString(index));
        }
        return List.copyOf(result);
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

    private static NormalizedRect union(NormalizedRect first, NormalizedRect second) {
        return new NormalizedRect(
                Math.min(first.left(), second.left()),
                Math.min(first.top(), second.top()),
                Math.max(first.right(), second.right()),
                Math.max(first.bottom(), second.bottom()));
    }

    private static double area(NormalizedRect value) {
        return (value.right() - value.left()) * (value.bottom() - value.top());
    }

    private record Fixture(
            String fixtureId,
            String split,
            String lane,
            String categoryId,
            String truthCategory,
            File media,
            String digest,
            List<TruthFrame> truth) {
        private Fixture {
            Objects.requireNonNull(fixtureId, "fixtureId");
            Objects.requireNonNull(split, "split");
            Objects.requireNonNull(lane, "lane");
            Objects.requireNonNull(categoryId, "categoryId");
            Objects.requireNonNull(truthCategory, "truthCategory");
            Objects.requireNonNull(media, "media");
            Objects.requireNonNull(digest, "digest");
            truth = List.copyOf(Objects.requireNonNull(truth, "truth"));
        }
    }

    private record TruthFrame(
            int frameIndex, long timestampNanos, String category, NormalizedRect target) {
        private TruthFrame {
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(target, "target");
        }
    }

    @FunctionalInterface
    private interface FrameConsumer {
        void accept(TruthFrame truth, Bitmap bitmap) throws Exception;
    }

    private static final class Counts {
        private final int expectedFrames;
        private int frames;
        private int releasedInputs;
        private int typedFailures;
        private int findings;
        private final Set<String> keys = new HashSet<>();

        private Counts() {
            this(EXPECTED_FRAMES);
        }

        private Counts(int expectedFrames) {
            if (expectedFrames <= 0) {
                throw new IllegalArgumentException("Expected frames must be positive");
            }
            this.expectedFrames = expectedFrames;
        }

        private void recordFrame(
                String fixtureId, int frameIndex, boolean failed, int findingCount) {
            if (!keys.add(fixtureId + ":" + frameIndex)) {
                throw new IllegalStateException("Duplicate Priority 2 findings key");
            }
            frames++;
            if (failed) {
                typedFailures++;
            }
            findings += findingCount;
        }

        private void verifyComplete() {
            if (frames != expectedFrames || keys.size() != expectedFrames) {
                throw new IllegalStateException("Priority 2 findings are incomplete");
            }
        }

        private void recordRelease() {
            releasedInputs++;
        }

        private String report() {
            return String.format(
                    Locale.ROOT,
                    "frames=%d released=%d/%d typedFailures=%d/%d findings=%d",
                    frames, releasedInputs, frames, typedFailures, frames, findings);
        }
    }

    private static final class VerifiedPrivateMedia implements AutoCloseable {
        private static final String PREFIX = "priority2-verified-";
        private final File file;
        private boolean closed;

        private VerifiedPrivateMedia(File file) {
            this.file = file;
        }

        private static VerifiedPrivateMedia copyOf(
                File source, File privateDirectory, String expectedDigest) throws IOException {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(privateDirectory, "privateDirectory");
            Objects.requireNonNull(expectedDigest, "expectedDigest");
            if (!source.isFile() || !privateDirectory.isDirectory()) {
                throw new IOException("Media source and private destination must exist");
            }
            if (!expectedDigest.equals(sha256(source))) {
                throw new IOException("Staged media digest did not match frozen fixture");
            }
            long sourceLength = source.length();
            File copy = File.createTempFile(PREFIX, ".mp4", privateDirectory)
                    .getCanonicalFile();
            if (!privateDirectory.getCanonicalFile().equals(copy.getParentFile())) {
                if (copy.exists() && !copy.delete()) {
                    throw new IOException("Unable to clear escaped private media copy");
                }
                throw new IOException("Private media copy escaped destination directory");
            }
            boolean accepted = false;
            try {
                try (InputStream input = new FileInputStream(source);
                        FileOutputStream output = new FileOutputStream(copy, false)) {
                    byte[] buffer = new byte[8_192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                    }
                    output.getFD().sync();
                }
                if (copy.length() != sourceLength || !expectedDigest.equals(sha256(copy))) {
                    throw new IOException("Private media copy failed length or digest verification");
                }
                accepted = true;
                return new VerifiedPrivateMedia(copy);
            } finally {
                if (!accepted && copy.exists() && !copy.delete()) {
                    throw new IOException("Unable to clear rejected private media copy");
                }
            }
        }

        private File file() {
            return file;
        }

        private FileInputStream open() throws IOException {
            if (closed) {
                throw new IOException("Private media copy is closed");
            }
            return new FileInputStream(file);
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                if (file.exists() && !file.delete()) {
                    throw new IOException("Unable to delete private media copy");
                }
            }
        }
    }

    private static final class TextBitmapFrame implements TextAnalysisFrame {
        private final Bitmap bitmap;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TextBitmapFrame(Bitmap bitmap) {
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
            assertTrue("Text frame must close once", closed.compareAndSet(false, true));
            bitmap.recycle();
        }
    }

    private static final class BarcodeBitmapFrame
            implements OfflineBarcodeAnalyzer.BarcodeAnalysisFrame {
        private final Bitmap bitmap;
        private final AtomicBoolean closed = new AtomicBoolean();

        private BarcodeBitmapFrame(Bitmap bitmap) {
            this.bitmap = Objects.requireNonNull(bitmap, "bitmap");
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
        public byte[] luminance() {
            int[] pixels = new int[width() * height()];
            bitmap.getPixels(pixels, 0, width(), 0, 0, width(), height());
            byte[] luminance = new byte[pixels.length];
            for (int index = 0; index < pixels.length; index++) {
                int pixel = pixels[index];
                int value = (Color.red(pixel) * 77
                        + Color.green(pixel) * 150
                        + Color.blue(pixel) * 29) >> 8;
                luminance[index] = (byte) value;
            }
            return luminance;
        }

        @Override
        public void close() {
            assertTrue("Barcode frame must close once", closed.compareAndSet(false, true));
            bitmap.recycle();
        }
    }
}
