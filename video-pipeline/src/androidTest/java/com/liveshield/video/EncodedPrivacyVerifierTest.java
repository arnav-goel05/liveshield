package com.liveshield.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import androidx.camera.core.SurfaceRequest;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.liveshield.app.debug.FaultInjectionController;
import com.liveshield.app.debug.FaultInjectionController.FaultTarget;
import com.liveshield.privacy.decision.BoundedFrameDecisionStore;
import com.liveshield.privacy.decision.FramePrivacyDecision;
import com.liveshield.privacy.model.ConfidenceClass;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.model.ProtectionAction;
import com.liveshield.privacy.model.TypedFailure;
import com.liveshield.privacy.policy.DefaultPrivacyPolicyEngine;
import com.liveshield.privacy.policy.SessionPrivacyConfiguration;
import com.liveshield.privacy.session.SessionHealth;
import com.liveshield.privacy.session.SessionState;
import com.liveshield.video.geometry.FrameTransform;
import com.liveshield.video.output.DebugSanitizedRecorder;
import com.liveshield.video.output.SanitizedVideoOutput;
import com.liveshield.video.render.GlRedactionRenderer;
import com.liveshield.video.render.PrivacySurfaceProcessor;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Decodes real H.264 output and rejects fixture pixels inside protected output bounds. */
@RunWith(AndroidJUnit4.class)
public final class EncodedPrivacyVerifierTest {
    private static final String EVIDENCE_TAG = "LiveShield-US4-Evidence";
    private static final long FRAME_DURATION_NS = 125_000_000L;
    private static final long PTS_TOLERANCE_US = 2_000L;
    private static final int COLOR_CHANNEL_TOLERANCE = 28;
    private static final int RAW_CHANNEL_TOLERANCE = 12;
    private static final double MIN_REDACTED_COLOR_RATIO = 0.90;
    private static final double MAX_RAW_MATCH_RATIO = 0.10;
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final String T100_FINDINGS_SHA256 =
            "b25494bef48f4d4c2e7e34c4d0b8aceb73f0d20201e32e32cdf30c3516b63baa";

    @Test
    public void priorityTwoFindingsRemainHonestThroughDecodedH264() throws Exception {
        assumeArgumentPresent("piiFindings");
        Map<String, JSONObject> observations = priorityTwoObservations(
                new File(requiredArgument("piiFindings")));
        List<PriorityFixture> fixtures = priorityTwoFixtures();
        assertEquals(26, fixtures.size());
        PriorityTotals totals = new PriorityTotals();
        for (PriorityFixture fixture : fixtures) {
            PriorityFrames loaded = loadPriorityFrames(fixture, observations);
            File output = encode(
                    fixture.fixture,
                    loaded.encodedFrames,
                    true,
                    "us3-" + fixture.fixture.fixtureId + ".mp4");
            try {
                PriorityInspection result = inspectPriorityTwo(output, loaded);
                totals.record(fixture, result);
                Log.i("LiveShield-US3-Evidence", "fixture=" + fixture.fixture.fixtureId
                        + " split=" + fixture.fixture.split
                        + " lane=" + fixture.lane
                        + " category=" + fixture.category
                        + " protected=" + result.protectedFrames + "/8"
                        + " exposed=" + result.exposedFrames + "/8"
                        + " maxRawMatchRatio=" + result.maxRawMatchRatio
                        + " minRedactedRatio=" + result.minRedactedRatio
                        + " audioTracks=0");
            } finally {
                assertTrue(!output.exists() || output.delete());
            }
        }
        totals.verify();
        Log.i("LiveShield-US3-Evidence", totals.report());
    }

    @Test
    public void exportsDevelopmentPriorityTwoSanitizedH264ForRelay() throws Exception {
        assumeArgumentPresent("piiFindings");
        Map<String, JSONObject> observations = priorityTwoObservations(
                new File(requiredArgument("piiFindings")));
        List<ExpectedFrame> continuous = new ArrayList<>();
        int globalIndex = 0;
        for (PriorityFixture fixture : priorityTwoFixtures()) {
            if (!"DEVELOPMENT".equals(fixture.fixture.split)) {
                continue;
            }
            PriorityFrames loaded = loadPriorityFrames(fixture, observations);
            for (ExpectedFrame frame : loaded.encodedFrames) {
                continuous.add(new ExpectedFrame(
                        frame.raw,
                        frame.decision,
                        frame.transform,
                        globalIndex++ * FRAME_DURATION_NS));
            }
        }
        assertEquals(104, continuous.size());
        File output = encode(
                new Fixture("pii-development-relay", "PRIORITY_2", "DEVELOPMENT",
                        "T100_FINDINGS", "", "", "", 192, 128),
                continuous, true, requiredArgument("piiRelayOutput"));
        List<Long> samples = videoSampleTimes(output);
        assertEquals(104, samples.size());
        Log.i("LiveShield-US3-RelayExport",
                "frames=104 videoTracks=1 audioTracks=0 sha256=" + fileSha256(output));
        for (ExpectedFrame frame : continuous) {
            frame.raw.recycle();
        }
    }

    @Test
    public void developmentFixturesContainNoForbiddenProtectedPixels() throws Exception {
        verifySplit("DEVELOPMENT");
    }

    @Test
    public void holdoutFixturesContainNoForbiddenProtectedPixels() throws Exception {
        verifySplit("HOLDOUT");
    }

    @Test
    public void untreatedPositiveControlIsRejected() throws Exception {
        // The selected fault fixture requires a full shield, so its binary sentinel band and all
        // other raw pixels are forbidden in the positive-control output.
        Fixture fixture = selectedFixtures("DEVELOPMENT").get(1);
        List<ExpectedFrame> frames = loadFrames(fixture);
        File output = encode(fixture, frames, false, "untreated-positive-control.mp4");
        try {
            AssertionError failure = assertThrows(
                    AssertionError.class, () -> inspect(output, frames));
            assertTrue(failure.getMessage().contains("forbidden raw protected pixels"));
        } finally {
            assertTrue(!output.exists() || output.delete());
        }
    }

    @Test
    public void allTwentyFaultFixturesProduceZeroForbiddenRawBypass() throws Exception {
        List<Fixture> fixtures = faultFixtures();
        assertEquals(20, fixtures.size());
        assertEquals(10, countSplit(fixtures, "DEVELOPMENT"));
        assertEquals(10, countSplit(fixtures, "HOLDOUT"));

        verifyPositiveControl(fixtures.get(0));
        int inspectedFrames = 0;
        int stoppedTruthFrames = 0;
        int regionalFrames = 0;
        int fullShieldFrames = 0;
        for (Fixture fixture : fixtures) {
            LoadedTruth truth = loadTruth(fixture);
            File output = encode(fixture, truth.frames, true,
                    "us4-" + fixture.fixtureId + ".mp4");
            try {
                InspectionResult result = inspect(output, truth.frames);
                inspectedFrames += result.inspectedFrames;
                stoppedTruthFrames += truth.stoppedFrames;
                regionalFrames += truth.regionalFrames;
                fullShieldFrames += truth.fullShieldFrames;
                Log.i(EVIDENCE_TAG, "fixture=" + fixture.fixtureId
                        + " split=" + fixture.split
                        + " scenario=" + fixture.scenarioId
                        + " sourceSha256=" + fixture.sourceDigest
                        + " truthSha256=" + assetSha256(fixture.truthPath)
                        + " outputSha256=" + fileSha256(output)
                        + " truthFrames=" + truth.totalFrames
                        + " encodedFrames=" + result.inspectedFrames
                        + " stoppedFrames=" + truth.stoppedFrames
                        + " maxRawMatchRatio=" + result.maxRawMatchRatio
                        + " minRedactedRatio=" + result.minRedactedRatio
                        + " result=PASS");
            } finally {
                assertTrue(!output.exists() || output.delete());
            }
        }
        assertEquals(160, inspectedFrames + stoppedTruthFrames);
        assertEquals(144, inspectedFrames);
        assertEquals(16, stoppedTruthFrames);
        assertEquals(8, regionalFrames);
        assertEquals(136, fullShieldFrames);
    }

    private static void verifyPositiveControl(Fixture fixture) throws Exception {
        LoadedTruth truth = loadTruth(fixture);
        File output = encode(fixture, truth.frames, false, "us4-positive-control.mp4");
        try {
            AssertionError failure = assertThrows(
                    AssertionError.class, () -> inspect(output, truth.frames));
            assertTrue(failure.getMessage().contains("forbidden raw protected pixels"));
            Log.i(EVIDENCE_TAG, "positiveControl=" + fixture.fixtureId
                    + " untreatedRejected=true result=PASS");
        } finally {
            assertTrue(!output.exists() || output.delete());
        }
    }

    private static int countSplit(List<Fixture> fixtures, String split) {
        int count = 0;
        for (Fixture fixture : fixtures) {
            if (split.equals(fixture.split)) {
                count++;
            }
        }
        return count;
    }

    private static void verifySplit(String split) throws Exception {
        List<Fixture> fixtures = selectedFixtures(split);
        assertEquals(2, fixtures.size());
        assertEquals(Set.of("RENDERER", "FAULT_INJECTION"), groups(fixtures));
        for (Fixture fixture : fixtures) {
            List<ExpectedFrame> frames = loadFrames(fixture);
            File output = encode(fixture, frames, true,
                    "sanitized-" + fixture.fixtureId + ".mp4");
            try {
                inspect(output, frames);
            } finally {
                assertTrue(!output.exists() || output.delete());
            }
        }
    }

    private static Set<String> groups(List<Fixture> fixtures) {
        Set<String> groups = new HashSet<>();
        for (Fixture fixture : fixtures) {
            groups.add(fixture.group);
        }
        return groups;
    }

    private static Map<String, JSONObject> priorityTwoObservations(File file) throws Exception {
        assertTrue("Missing T100 findings input", file.isFile());
        assertEquals("T100 findings changed after the accepted run",
                T100_FINDINGS_SHA256, fileSha256(file));
        Map<String, JSONObject> result = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            for (String line; (line = reader.readLine()) != null; ) {
                JSONObject observation = new JSONObject(line);
                String key = observation.getString("fixtureId") + ":"
                        + observation.getInt("frameIndex");
                assertEquals("Duplicate T100 observation", null, result.put(key, observation));
            }
        }
        assertEquals("T100 findings must contain every fixture/frame", 208, result.size());
        return Map.copyOf(result);
    }

    private static List<PriorityFixture> priorityTwoFixtures() throws Exception {
        List<PriorityFixture> result = new ArrayList<>();
        try (BufferedReader reader = reader(context().getAssets(), "pii-v1.jsonl")) {
            for (String line; (line = reader.readLine()) != null; ) {
                JSONObject json = new JSONObject(line);
                assertEquals("PRIORITY_2", json.getString("group"));
                JSONArray scenarios = json.getJSONArray("scenarioIds");
                JSONObject device = json.getJSONObject("deviceContext");
                Fixture fixture = new Fixture(
                        json.getString("fixtureId"),
                        json.getString("group"),
                        json.getString("split"),
                        scenarios.getString(0),
                        json.getString("sourcePath"),
                        json.getString("sourceDigest"),
                        json.getString("truthPath"),
                        device.getInt("width"),
                        device.getInt("height"));
                assertEquals(fixture.sourceDigest, assetSha256(fixture.sourcePath));
                result.add(new PriorityFixture(fixture, scenarios.getString(0),
                        scenarios.getString(1)));
            }
        }
        return List.copyOf(result);
    }

    private static PriorityFrames loadPriorityFrames(
            PriorityFixture fixture, Map<String, JSONObject> observations) throws Exception {
        List<ExpectedFrame> encoded = new ArrayList<>();
        List<NormalizedRect> targets = new ArrayList<>();
        try (AssetVideo source = new AssetVideo(fixture.fixture.sourcePath);
                BufferedReader reader = reader(context().getAssets(), fixture.fixture.truthPath)) {
            int frameIndex = 0;
            for (String line; (line = reader.readLine()) != null; frameIndex++) {
                JSONObject truth = new JSONObject(line);
                assertEquals(frameIndex, truth.getInt("frameIndex"));
                assertEquals(fixture.fixture.fixtureId, truth.getString("fixtureId"));
                long timestampNs = truth.getLong("sourceTimestampNs");
                Bitmap raw = source.frameAt(timestampNs / 1_000L);
                assertNotNull("Missing Priority 2 source frame", raw);
                JSONObject observation = observations.get(
                        fixture.fixture.fixtureId + ":" + frameIndex);
                assertNotNull("Missing T100 finding observation", observation);
                assertFalse("T100 analyzer failure cannot be promoted to safe output",
                        observation.has("failure"));
                FrameTransform frameTransform = transform(truth.getJSONObject("transform"));
                List<ProtectedRegion> findings = findingRegions(
                        observation.getJSONArray("findings"), frameTransform);
                FrameTimestamp timestamp = FrameTimestamp.ofNanos(timestampNs);
                FramePrivacyDecision decision = FramePrivacyDecision.regionalSafe(
                        timestamp,
                        findings,
                        FramePrivacyDecision.Basis.FRESH,
                        timestamp.plusNanos(FRAME_DURATION_NS));
                encoded.add(new ExpectedFrame(
                        raw, decision, frameTransform, timestampNs));
                targets.add(largestProtectableTarget(truth.getJSONArray("objects")));
            }
        }
        assertEquals(8, encoded.size());
        return new PriorityFrames(List.copyOf(encoded), List.copyOf(targets));
    }

    private static List<ProtectedRegion> findingRegions(
            JSONArray values, FrameTransform frameTransform) throws Exception {
        List<ProtectedRegion> result = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject finding = values.getJSONObject(index);
            NormalizedRect outputBounds = polygonBounds(finding.getJSONArray("polygon"));
            NormalizedRect sensorBounds = frameTransform.mapOutputRectToSensor(outputBounds);
            assertRectNear(
                    outputBounds,
                    frameTransform.mapSensorRectToOutput(sensorBounds),
                    "Finding coordinate conversion did not round-trip");
            result.add(new ProtectedRegion(
                    findingCategory(finding.getString("category")),
                    List.of(sensorBounds),
                    ConfidenceClass.VALIDATED,
                    ProtectionAction.OPAQUE));
        }
        return List.copyOf(result);
    }

    private static FindingCategory findingCategory(String category) {
        return switch (category) {
            case "MACHINE_READABLE_CODE" -> FindingCategory.AUTO_BARCODE;
            case "EMAIL" -> FindingCategory.AUTO_EMAIL;
            case "PHONE" -> FindingCategory.AUTO_PHONE;
            case "PAYMENT_CARD" -> FindingCategory.AUTO_CARD;
            case "VERIFICATION_CODE" -> FindingCategory.AUTO_OTP;
            case "PERSON_NAME", "ADDRESS", "EMPLOYER", "SCHOOL" ->
                    FindingCategory.WATCHLIST_MATCH;
            case "DOCUMENT", "BADGE", "PARCEL_LABEL", "DEVICE_SCREEN" ->
                    FindingCategory.PRIVACY_ZONE;
            default -> throw new AssertionError("Unsupported T100 finding category " + category);
        };
    }

    private static NormalizedRect largestProtectableTarget(JSONArray objects) throws Exception {
        NormalizedRect selected = null;
        double selectedArea = -1.0;
        for (int index = 0; index < objects.length(); index++) {
            JSONObject object = objects.getJSONObject(index);
            if (!object.getBoolean("protectable")) {
                continue;
            }
            NormalizedRect candidate = polygonBounds(object.getJSONArray("polygon"));
            double area = (candidate.right() - candidate.left())
                    * (candidate.bottom() - candidate.top());
            if (area > selectedArea) {
                selected = candidate;
                selectedArea = area;
            }
        }
        assertNotNull("Truth has no protectable target", selected);
        return selected;
    }

    private static NormalizedRect polygonBounds(JSONArray polygon) throws Exception {
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

    private static void assertRectNear(
            NormalizedRect expected, NormalizedRect actual, String message) {
        double tolerance = 1.0e-9;
        assertEquals(message, expected.left(), actual.left(), tolerance);
        assertEquals(message, expected.top(), actual.top(), tolerance);
        assertEquals(message, expected.right(), actual.right(), tolerance);
        assertEquals(message, expected.bottom(), actual.bottom(), tolerance);
    }

    private static List<Fixture> selectedFixtures(String split) throws Exception {
        AssetManager assets = context().getAssets();
        List<Fixture> all = new ArrayList<>();
        try (BufferedReader reader = reader(assets, "system-v1.jsonl")) {
            for (String line; (line = reader.readLine()) != null; ) {
                JSONObject json = new JSONObject(line);
                if (split.equals(json.getString("split"))) {
                    JSONArray streams = json.getJSONArray("mediaStreams");
                    assertEquals(1, streams.length());
                    assertEquals("VIDEO", streams.getString(0));
                    JSONObject device = json.getJSONObject("deviceContext");
                    JSONArray scenarios = json.getJSONArray("scenarioIds");
                    all.add(new Fixture(
                            json.getString("fixtureId"),
                            json.getString("group"),
                            json.getString("split"),
                            scenarios.getString(0),
                            json.getString("sourcePath"),
                            json.getString("sourceDigest"),
                            json.getString("truthPath"),
                            device.getInt("width"),
                            device.getInt("height")));
                }
            }
        }
        assertEquals(16, all.size());
        Fixture renderer = firstGroup(all, "RENDERER");
        Fixture fault = firstGroup(all, "FAULT_INJECTION");
        return List.of(renderer, fault);
    }

    private static List<Fixture> faultFixtures() throws Exception {
        List<Fixture> fixtures = new ArrayList<>();
        try (BufferedReader reader = reader(context().getAssets(), "system-v1.jsonl")) {
            for (String line; (line = reader.readLine()) != null; ) {
                JSONObject json = new JSONObject(line);
                if (!"FAULT_INJECTION".equals(json.getString("group"))) {
                    continue;
                }
                JSONArray streams = json.getJSONArray("mediaStreams");
                assertEquals(1, streams.length());
                assertEquals("VIDEO", streams.getString(0));
                JSONArray scenarios = json.getJSONArray("scenarioIds");
                assertEquals(1, scenarios.length());
                JSONObject device = json.getJSONObject("deviceContext");
                Fixture fixture = new Fixture(
                        json.getString("fixtureId"),
                        json.getString("group"),
                        json.getString("split"),
                        scenarios.getString(0),
                        json.getString("sourcePath"),
                        json.getString("sourceDigest"),
                        json.getString("truthPath"),
                        device.getInt("width"),
                        device.getInt("height"));
                assertEquals(fixture.sourceDigest, assetSha256(fixture.sourcePath));
                fixtures.add(fixture);
            }
        }
        return fixtures;
    }

    private static Fixture firstGroup(List<Fixture> fixtures, String group) {
        for (Fixture fixture : fixtures) {
            if (group.equals(fixture.group)) {
                return fixture;
            }
        }
        throw new AssertionError("Missing fixture group " + group);
    }

    private static List<ExpectedFrame> loadFrames(Fixture fixture) throws Exception {
        return loadTruth(fixture).frames;
    }

    private static LoadedTruth loadTruth(Fixture fixture) throws Exception {
        List<ExpectedFrame> result = new ArrayList<>();
        int totalFrames = 0;
        int stoppedFrames = 0;
        int regionalFrames = 0;
        int fullShieldFrames = 0;
        boolean stopped = false;
        try (AssetVideo source = new AssetVideo(fixture.sourcePath);
                ScenarioDecisionDriver decisions = new ScenarioDecisionDriver(fixture.scenarioId);
                BufferedReader reader = reader(context().getAssets(), fixture.truthPath)) {
            for (String line; (line = reader.readLine()) != null; ) {
                JSONObject truth = new JSONObject(line);
                assertEquals(fixture.fixtureId, truth.getString("fixtureId"));
                assertEquals(totalFrames, truth.getInt("frameIndex"));
                long sourceTimestampNs = truth.getLong("sourceTimestampNs");
                assertEquals(totalFrames * FRAME_DURATION_NS, sourceTimestampNs);
                totalFrames++;
                String state = truth.getString("expectedState");
                if ("STOPPED".equals(state)) {
                    assertEquals("STOP_OUTPUT", truth.getString("requiredAction"));
                    stopped = true;
                    stoppedFrames++;
                    continue;
                }
                assertFalse("Output must not resume after STOPPED truth", stopped);
                assertTrue("Unexpected truth state " + state,
                        "FULL_SHIELD".equals(state)
                                || "REGIONAL_PROTECTION".equals(state));
                Bitmap raw = source.frameAt(sourceTimestampNs / 1_000L);
                assertNotNull("Missing source frame at " + sourceTimestampNs, raw);
                FrameTransform transform = transform(truth.getJSONObject("transform"));
                FrameTimestamp timestamp = FrameTimestamp.ofNanos(sourceTimestampNs);
                List<ProtectedRegion> expectedRegions = "FULL_SHIELD".equals(state)
                        ? List.of() : regions(truth.getJSONArray("objects"));
                FramePrivacyDecision decision = decisions.decide(
                        timestamp, expectedRegions, "REGIONAL_PROTECTION".equals(state));
                if ("FULL_SHIELD".equals(state)) {
                    assertEquals("FULL_SHIELD", truth.getString("requiredAction"));
                    assertEquals(FramePrivacyDecision.Status.FULL_SHIELD, decision.status());
                    fullShieldFrames++;
                } else {
                    assertEquals("PROTECT_REGIONS", truth.getString("requiredAction"));
                    assertEquals(FramePrivacyDecision.Status.REGIONAL_SAFE, decision.status());
                    regionalFrames++;
                }
                result.add(new ExpectedFrame(raw, decision, transform, sourceTimestampNs));
            }
        }
        assertFalse(result.isEmpty());
        assertEquals(totalFrames, result.size() + stoppedFrames);
        return new LoadedTruth(
                result, totalFrames, stoppedFrames, regionalFrames, fullShieldFrames);
    }

    private static List<ProtectedRegion> regions(JSONArray objects) throws Exception {
        List<ProtectedRegion> regions = new ArrayList<>();
        for (int index = 0; index < objects.length(); index++) {
            JSONObject object = objects.getJSONObject(index);
            if (!object.getBoolean("protectable")) {
                continue;
            }
            JSONArray polygon = object.getJSONArray("polygon");
            double left = 1.0;
            double top = 1.0;
            double right = 0.0;
            double bottom = 0.0;
            for (int pointIndex = 0; pointIndex < polygon.length(); pointIndex++) {
                JSONArray point = polygon.getJSONArray(pointIndex);
                left = Math.min(left, point.getDouble(0));
                top = Math.min(top, point.getDouble(1));
                right = Math.max(right, point.getDouble(0));
                bottom = Math.max(bottom, point.getDouble(1));
            }
            regions.add(new ProtectedRegion(
                    FindingCategory.FACE,
                    List.of(new NormalizedRect(left, top, right, bottom)),
                    ConfidenceClass.VALIDATED,
                    ProtectionAction.OPAQUE));
        }
        assertFalse(regions.isEmpty());
        return regions;
    }

    /**
     * Produces fixture decisions through the production policy rather than copying truth states.
     */
    private static final class ScenarioDecisionDriver implements AutoCloseable {
        private final String scenario;
        private final EnumSet<FaultTarget> injected = EnumSet.noneOf(FaultTarget.class);
        private final DefaultPrivacyPolicyEngine policy = new DefaultPrivacyPolicyEngine();
        private final SessionPrivacyConfiguration configuration =
                new SessionPrivacyConfiguration(Set.of(), List.of());

        private ScenarioDecisionDriver(String scenario) {
            this.scenario = scenario;
            FaultInjectionController.Bindings bindings = new FaultInjectionController.Bindings();
            for (FaultTarget target : FaultTarget.values()) {
                bindings.on(target, signal -> injected.add(signal.target()));
            }
            FaultInjectionController controller = new FaultInjectionController(bindings);
            for (FaultTarget target : targets(scenario)) {
                controller.arm(target, 0);
                assertTrue("Typed fault checkpoint did not fire for " + scenario,
                        controller.checkpoint(target));
            }
        }

        private FramePrivacyDecision decide(
                FrameTimestamp timestamp,
                List<ProtectedRegion> regions,
                boolean recoveryRegional) {
            assertEquals(Set.copyOf(targets(scenario)), Set.copyOf(injected));
            SessionHealth.Builder health = SessionHealth.builder(SessionState.LIVE);
            List<DetectorSnapshot> snapshots;
            switch (scenario) {
                case "moving-protected-region" -> snapshots = fresh(timestamp, regions);
                case "missing-analysis-result", "late-analysis-result" -> snapshots = List.of();
                case "stale-out-of-order-timestamp" -> snapshots = List.of(
                        DetectorSnapshot.success(
                                DetectorLane.FACE,
                                FrameTimestamp.ofNanos(1L),
                                FrameTimestamp.ofNanos(1L),
                                List.of()));
                case "detector-exception-cancellation" -> snapshots = List.of(
                        DetectorSnapshot.failure(
                                DetectorLane.FACE,
                                timestamp,
                                new TypedFailure(TypedFailure.Code.ANALYZER_ERROR, timestamp)));
                case "raw-frame-queue-capacity" -> {
                    health.rawQueueDepth(12);
                    snapshots = fresh(timestamp, regions);
                }
                case "renderer-failure-invalid-surface" -> {
                    health.rendererState(SessionHealth.RendererState.FAILED);
                    snapshots = fresh(timestamp, regions);
                }
                case "camera-rebind-lifecycle-interruption",
                        "encoder-backpressure-reconfiguration" -> {
                    health = SessionHealth.builder(SessionState.STOPPING);
                    snapshots = fresh(timestamp, regions);
                }
                case "network-disconnect-reconnect", "recovery-old-undecided-queue" -> {
                    health.recoveryState(recoveryRegional
                            ? SessionHealth.RecoveryState.VERIFIED
                            : SessionHealth.RecoveryState.UNSAFE);
                    snapshots = fresh(timestamp, regions);
                }
                default -> throw new AssertionError("Unmapped fault scenario " + scenario);
            }
            return policy.decide(
                    timestamp, snapshots, List.of(), configuration, health.build());
        }

        private List<FaultTarget> targets(String value) {
            return switch (value) {
                case "moving-protected-region" -> List.of();
                case "missing-analysis-result", "late-analysis-result",
                        "stale-out-of-order-timestamp" ->
                        List.of(FaultTarget.DETECTOR_STALL);
                case "detector-exception-cancellation" ->
                        List.of(FaultTarget.DETECTOR_FAILURE);
                case "raw-frame-queue-capacity", "recovery-old-undecided-queue" ->
                        List.of(FaultTarget.QUEUE_CAPACITY);
                case "renderer-failure-invalid-surface" ->
                        List.of(FaultTarget.GL_FAILURE, FaultTarget.SURFACE_LOSS);
                case "camera-rebind-lifecycle-interruption" ->
                        List.of(FaultTarget.CAMERA_FAILURE, FaultTarget.LIFECYCLE_INTERRUPTION);
                case "encoder-backpressure-reconfiguration" ->
                        List.of(FaultTarget.ENCODER_FAILURE);
                case "network-disconnect-reconnect" -> List.of(FaultTarget.NETWORK_LOSS);
                default -> throw new AssertionError("Unmapped fault scenario " + value);
            };
        }

        private static List<DetectorSnapshot> fresh(
                FrameTimestamp timestamp, List<ProtectedRegion> regions) {
            return List.of(DetectorSnapshot.success(
                    DetectorLane.FACE,
                    timestamp,
                    timestamp.plusNanos(FRAME_DURATION_NS),
                    regions));
        }

        @Override
        public void close() {
            assertEquals(Set.copyOf(targets(scenario)), Set.copyOf(injected));
            configuration.close();
            policy.reset();
        }
    }

    private static FrameTransform transform(JSONObject json) throws Exception {
        JSONArray crop = json.getJSONArray("crop");
        JSONArray matrix = json.getJSONArray("sensorToBuffer");
        double[] values = new double[9];
        for (int index = 0; index < values.length; index++) {
            values[index] = matrix.getDouble(index);
        }
        return FrameTransform.fromCameraMetadata(
                new CoordinateTransform(values),
                new NormalizedRect(
                        crop.getDouble(0), crop.getDouble(1),
                        crop.getDouble(2), crop.getDouble(3)),
                json.getInt("rotationDegrees"),
                json.getBoolean("mirrored"));
    }

    @SuppressLint("RestrictedApi")
    private static File encode(
            Fixture fixture,
            List<ExpectedFrame> frames,
            boolean sanitize,
            String fileName) throws Exception {
        File file = new File(context().getCacheDir(), fileName);
        if (file.exists()) {
            assertTrue(file.delete());
        }
        PrivacySurfaceProcessor renderer = privacyProcessor();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SanitizedVideoOutput output = new SanitizedVideoOutput(
                renderer.sanitizedOutputCapability(),
                new DebugSanitizedRecorder(file),
                failure::set,
                new SanitizedVideoOutput.EncoderSettings(1_000_000, 8, 1));
        SurfaceRequest request = new SurfaceRequest(
                new Size(fixture.width, fixture.height), null, () -> { });
        List<Bitmap> submittedFrames = new ArrayList<>();
        try {
            for (ExpectedFrame expected : frames) {
                submittedFrames.add(sanitize
                        ? GlRedactionRenderer.renderForTest(
                                expected.raw, expected.decision, expected.transform)
                        : expected.raw);
            }
            output.onSurfaceRequested(request);
            Surface surface = request.getDeferrableSurface().getSurface().get(
                    5, TimeUnit.SECONDS);
            try (EglBitmapProducer producer = new EglBitmapProducer(surface, fixture.width,
                    fixture.height)) {
                for (int index = 0; index < submittedFrames.size(); index++) {
                    producer.draw(
                            submittedFrames.get(index), frames.get(index).sourceTimestampNs);
                }
            }
            request.getDeferrableSurface().close();
            awaitIdle(output);
            assertEquals(null, failure.get());
        } finally {
            output.close();
            renderer.close();
            if (sanitize) {
                for (Bitmap submitted : submittedFrames) {
                    submitted.recycle();
                }
            }
        }
        return file;
    }

    private static void awaitIdle(SanitizedVideoOutput output) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (output.state() != SanitizedVideoOutput.State.IDLE
                && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertEquals(SanitizedVideoOutput.State.IDLE, output.state());
    }

    private static InspectionResult inspect(
            File file, List<ExpectedFrame> frames) throws Exception {
        List<Long> sampleTimesUs = videoSampleTimes(file);
        assertEquals("Every non-stopped truth frame needs one encoded sample",
                frames.size(), sampleTimesUs.size());
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        double maxRawMatchRatio = 0.0;
        double minRedactedRatio = 1.0;
        try {
            retriever.setDataSource(file.getAbsolutePath());
            for (int index = 0; index < frames.size(); index++) {
                long expectedPtsUs = frames.get(index).sourceTimestampNs / 1_000L;
                long actualPtsUs = sampleTimesUs.get(index);
                assertTrue("PTS does not correlate with truth frame " + index,
                        Math.abs(actualPtsUs - expectedPtsUs) <= PTS_TOLERANCE_US);
                Bitmap decoded = retriever.getFrameAtTime(
                        actualPtsUs, MediaMetadataRetriever.OPTION_CLOSEST);
                assertNotNull("Decoder returned no frame for PTS " + actualPtsUs, decoded);
                FrameInspection frameInspection = inspectFrame(
                        decoded, frames.get(index), index);
                maxRawMatchRatio = Math.max(
                        maxRawMatchRatio, frameInspection.rawMatchRatio);
                minRedactedRatio = Math.min(
                        minRedactedRatio, frameInspection.redactedRatio);
                decoded.recycle();
            }
        } finally {
            retriever.release();
            for (ExpectedFrame frame : frames) {
                frame.raw.recycle();
            }
        }
        return new InspectionResult(frames.size(), maxRawMatchRatio, minRedactedRatio);
    }

    private static PriorityInspection inspectPriorityTwo(
            File file, PriorityFrames frames) throws Exception {
        List<Long> sampleTimesUs = videoSampleTimes(file);
        assertEquals(8, sampleTimesUs.size());
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        int protectedFrames = 0;
        int exposedFrames = 0;
        double maxRawMatchRatio = 0.0;
        double minRedactedRatio = 1.0;
        try {
            retriever.setDataSource(file.getAbsolutePath());
            for (int index = 0; index < frames.encodedFrames.size(); index++) {
                ExpectedFrame expected = frames.encodedFrames.get(index);
                long actualPtsUs = sampleTimesUs.get(index);
                assertTrue(Math.abs(actualPtsUs - expected.sourceTimestampNs / 1_000L)
                        <= PTS_TOLERANCE_US);
                Bitmap decoded = retriever.getFrameAtTime(
                        actualPtsUs, MediaMetadataRetriever.OPTION_CLOSEST);
                assertNotNull("Missing decoded Priority 2 frame", decoded);
                TargetInspection target = inspectTarget(
                        decoded, expected.raw, frames.truthTargets.get(index));
                maxRawMatchRatio = Math.max(maxRawMatchRatio, target.rawMatchRatio);
                minRedactedRatio = Math.min(minRedactedRatio, target.redactedRatio);
                boolean protectedTarget = target.rawMatchRatio <= MAX_RAW_MATCH_RATIO
                        && target.redactedRatio >= MIN_REDACTED_COLOR_RATIO;
                if (expected.decision.regions().isEmpty()) {
                    assertFalse("Empty T100 findings unexpectedly counted as protected",
                            protectedTarget);
                    exposedFrames++;
                } else {
                    assertTrue("T100 finding did not protect decoded truth target",
                            protectedTarget);
                    protectedFrames++;
                }
                decoded.recycle();
            }
        } finally {
            retriever.release();
            for (ExpectedFrame frame : frames.encodedFrames) {
                frame.raw.recycle();
            }
        }
        return new PriorityInspection(
                protectedFrames, exposedFrames, maxRawMatchRatio, minRedactedRatio);
    }

    private static TargetInspection inspectTarget(
            Bitmap decoded, Bitmap raw, NormalizedRect target) {
        Bitmap output = decoded.getWidth() == raw.getWidth() && decoded.getHeight() == raw.getHeight()
                ? decoded : Bitmap.createScaledBitmap(decoded, raw.getWidth(), raw.getHeight(), false);
        try {
            int left = (int) Math.ceil(target.left() * output.getWidth());
            int top = (int) Math.ceil(target.top() * output.getHeight());
            int right = (int) Math.floor(target.right() * output.getWidth());
            int bottom = (int) Math.floor(target.bottom() * output.getHeight());
            long pixels = 0;
            long rawMatches = 0;
            long redactedMatches = 0;
            for (int y = top; y < bottom; y++) {
                for (int x = left; x < right; x++) {
                    int pixel = output.getPixel(x, y);
                    pixels++;
                    if (near(pixel, raw.getPixel(x, y), RAW_CHANNEL_TOLERANCE)) {
                        rawMatches++;
                    }
                    if (near(pixel, GlRedactionRenderer.OPAQUE_MASK_COLOR,
                            COLOR_CHANNEL_TOLERANCE)) {
                        redactedMatches++;
                    }
                }
            }
            assertTrue("No Priority 2 truth pixels inspected", pixels > 0);
            return new TargetInspection(
                    (double) rawMatches / pixels, (double) redactedMatches / pixels);
        } finally {
            if (output != decoded) {
                output.recycle();
            }
        }
    }

    private static List<Long> videoSampleTimes(File file) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            assertEquals("Sanitized recording must be video-only", 1, extractor.getTrackCount());
            MediaFormat format = extractor.getTrackFormat(0);
            assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC,
                    format.getString(MediaFormat.KEY_MIME));
            extractor.selectTrack(0);
            List<Long> times = new ArrayList<>();
            while (extractor.getSampleTime() >= 0) {
                times.add(extractor.getSampleTime());
                extractor.advance();
            }
            return times;
        } finally {
            extractor.release();
        }
    }

    private static FrameInspection inspectFrame(
            Bitmap decoded, ExpectedFrame expected, int frameIndex) {
        Bitmap output = decoded.getWidth() == expected.raw.getWidth()
                && decoded.getHeight() == expected.raw.getHeight()
                ? decoded
                : Bitmap.createScaledBitmap(
                        decoded, expected.raw.getWidth(), expected.raw.getHeight(), false);
        try {
            List<NormalizedRect> bounds = expected.decision.status()
                    == FramePrivacyDecision.Status.FULL_SHIELD
                    ? List.of(new NormalizedRect(0.0, 0.0, 1.0, 1.0))
                    : outputBounds(expected);
            int expectedColor = expected.decision.status()
                    == FramePrivacyDecision.Status.FULL_SHIELD
                    ? GlRedactionRenderer.FULL_SHIELD_COLOR
                    : GlRedactionRenderer.OPAQUE_MASK_COLOR;
            long protectedPixels = 0;
            long redactedColorPixels = 0;
            long rawMatches = 0;
            for (NormalizedRect bound : bounds) {
                int left = (int) Math.ceil(bound.left() * output.getWidth());
                int top = (int) Math.ceil(bound.top() * output.getHeight());
                int right = (int) Math.floor(bound.right() * output.getWidth());
                int bottom = (int) Math.floor(bound.bottom() * output.getHeight());
                for (int y = top; y < bottom; y++) {
                    for (int x = left; x < right; x++) {
                        int pixel = output.getPixel(x, y);
                        protectedPixels++;
                        if (near(pixel, expectedColor, COLOR_CHANNEL_TOLERANCE)) {
                            redactedColorPixels++;
                        }
                        if (near(pixel, expected.raw.getPixel(x, y), RAW_CHANNEL_TOLERANCE)) {
                            rawMatches++;
                        }
                    }
                }
            }
            assertTrue("No protected pixels inspected for frame " + frameIndex,
                    protectedPixels > 0);
            double redactedRatio = (double) redactedColorPixels / protectedPixels;
            double rawMatchRatio = (double) rawMatches / protectedPixels;
            assertTrue("forbidden raw protected pixels in frame " + frameIndex
                            + ": ratio=" + rawMatchRatio,
                    rawMatchRatio <= MAX_RAW_MATCH_RATIO);
            assertTrue("Encoded redaction color missing for frame " + frameIndex
                            + ": ratio=" + redactedRatio,
                    redactedRatio >= MIN_REDACTED_COLOR_RATIO);
            return new FrameInspection(rawMatchRatio, redactedRatio);
        } finally {
            if (output != decoded) {
                output.recycle();
            }
        }
    }

    private static List<NormalizedRect> outputBounds(ExpectedFrame frame) {
        List<NormalizedRect> result = new ArrayList<>();
        for (ProtectedRegion region : frame.decision.regions()) {
            for (NormalizedRect bound : region.bounds()) {
                result.add(frame.transform.mapSensorRectToOutput(bound));
            }
        }
        return result;
    }

    private static boolean near(int first, int second, int tolerance) {
        return Math.abs(Color.red(first) - Color.red(second)) <= tolerance
                && Math.abs(Color.green(first) - Color.green(second)) <= tolerance
                && Math.abs(Color.blue(first) - Color.blue(second)) <= tolerance;
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

    private static BufferedReader reader(AssetManager assets, String path) throws Exception {
        return new BufferedReader(new InputStreamReader(
                assets.open(path), StandardCharsets.UTF_8));
    }

    private static String assetSha256(String path) throws Exception {
        try (InputStream input = context().getAssets().open(path)) {
            return sha256(input);
        }
    }

    private static String fileSha256(File file) throws Exception {
        try (InputStream input = new java.io.FileInputStream(file)) {
            return sha256(input);
        }
    }

    private static String sha256(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        for (int read; (read = input.read(buffer)) >= 0; ) {
            if (read > 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder value = new StringBuilder();
        for (byte item : digest.digest()) {
            value.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xFF));
        }
        return value.toString();
    }

    private static Context context() {
        return ApplicationProvider.getApplicationContext();
    }

    private static PrivacySurfaceProcessor privacyProcessor() {
        return new PrivacySurfaceProcessor(
                Runnable::run,
                new BoundedFrameDecisionStore(12, 1_000_000_000L),
                FrameTransform.fromCameraMetadata(
                        CoordinateTransform.identity(),
                        new NormalizedRect(0.0, 0.0, 1.0, 1.0),
                        0,
                        false),
                ignored -> { });
    }

    private static final class AssetVideo implements AutoCloseable {
        private final AssetFileDescriptor descriptor;
        private final MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        private AssetVideo(String path) throws Exception {
            descriptor = context().getAssets().openFd(path);
            retriever.setDataSource(
                    descriptor.getFileDescriptor(),
                    descriptor.getStartOffset(),
                    descriptor.getLength());
        }

        private Bitmap frameAt(long timestampUs) {
            return retriever.getFrameAtTime(timestampUs, MediaMetadataRetriever.OPTION_CLOSEST);
        }

        @Override
        public void close() throws Exception {
            retriever.release();
            descriptor.close();
        }
    }

    private static final class EglBitmapProducer implements AutoCloseable {
        private static final int BYTES_PER_FLOAT = 4;
        private static final float[] QUAD = {
            -1.0f, -1.0f, 0.0f, 1.0f,
            1.0f, -1.0f, 1.0f, 1.0f,
            -1.0f, 1.0f, 0.0f, 0.0f,
            1.0f, 1.0f, 1.0f, 0.0f
        };
        private static final String VERTEX = "attribute vec2 aPosition;"
                + "attribute vec2 aTexCoord;varying vec2 vTexCoord;"
                + "void main(){gl_Position=vec4(aPosition,0.0,1.0);vTexCoord=aTexCoord;}";
        private static final String FRAGMENT = "precision mediump float;"
                + "uniform sampler2D uTexture;varying vec2 vTexCoord;"
                + "void main(){gl_FragColor=texture2D(uTexture,vTexCoord);}";

        private final EGLDisplay display;
        private final EGLContext context;
        private final EGLSurface surface;
        private final int width;
        private final int height;
        private final int program;
        private final int texture;
        private final FloatBuffer quad;

        private EglBitmapProducer(Surface target, int width, int height) {
            this.width = width;
            this.height = height;
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] versions = new int[2];
            assertTrue(EGL14.eglInitialize(display, versions, 0, versions, 1));
            int[] attributes = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            assertTrue(EGL14.eglChooseConfig(
                    display, attributes, 0, configs, 0, 1, count, 0));
            assertEquals(1, count[0]);
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreateWindowSurface(
                    display, configs[0], target, new int[]{EGL14.EGL_NONE}, 0);
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));
            program = linkProgram();
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            texture = textures[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            quad = ByteBuffer.allocateDirect(QUAD.length * BYTES_PER_FLOAT)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(QUAD).position(0);
        }

        private void draw(Bitmap bitmap, long presentationTimeNs) {
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));
            GLES20.glViewport(0, 0, width, height);
            GLES20.glUseProgram(program);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            int position = GLES20.glGetAttribLocation(program, "aPosition");
            int coordinate = GLES20.glGetAttribLocation(program, "aTexCoord");
            quad.position(0);
            GLES20.glVertexAttribPointer(
                    position, 2, GLES20.GL_FLOAT, false, 4 * BYTES_PER_FLOAT, quad);
            GLES20.glEnableVertexAttribArray(position);
            quad.position(2);
            GLES20.glVertexAttribPointer(
                    coordinate, 2, GLES20.GL_FLOAT, false, 4 * BYTES_PER_FLOAT, quad);
            GLES20.glEnableVertexAttribArray(coordinate);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeNs);
            assertTrue(EGL14.eglSwapBuffers(display, surface));
        }

        private static int linkProgram() {
            int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX);
            int fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT);
            int linked = GLES20.glCreateProgram();
            GLES20.glAttachShader(linked, vertex);
            GLES20.glAttachShader(linked, fragment);
            GLES20.glLinkProgram(linked);
            int[] status = new int[1];
            GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0);
            assertEquals(1, status[0]);
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            return linked;
        }

        private static int compile(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            assertEquals(1, status[0]);
            return shader;
        }

        @Override
        public void close() {
            GLES20.glDeleteTextures(1, new int[]{texture}, 0);
            GLES20.glDeleteProgram(program);
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }

    private static final class Fixture {
        private final String fixtureId;
        private final String group;
        private final String split;
        private final String scenarioId;
        private final String sourcePath;
        private final String sourceDigest;
        private final String truthPath;
        private final int width;
        private final int height;

        private Fixture(
                String fixtureId,
                String group,
                String split,
                String scenarioId,
                String sourcePath,
                String sourceDigest,
                String truthPath,
                int width,
                int height) {
            this.fixtureId = fixtureId;
            this.group = group;
            this.split = split;
            this.scenarioId = scenarioId;
            this.sourcePath = sourcePath;
            this.sourceDigest = sourceDigest;
            this.truthPath = truthPath;
            this.width = width;
            this.height = height;
        }
    }

    private static final class ExpectedFrame {
        private final Bitmap raw;
        private final FramePrivacyDecision decision;
        private final FrameTransform transform;
        private final long sourceTimestampNs;

        private ExpectedFrame(
                Bitmap raw,
                FramePrivacyDecision decision,
                FrameTransform transform,
                long sourceTimestampNs) {
            this.raw = raw;
            this.decision = decision;
            this.transform = transform;
            this.sourceTimestampNs = sourceTimestampNs;
        }
    }

    private static final class LoadedTruth {
        private final List<ExpectedFrame> frames;
        private final int totalFrames;
        private final int stoppedFrames;
        private final int regionalFrames;
        private final int fullShieldFrames;

        private LoadedTruth(
                List<ExpectedFrame> frames,
                int totalFrames,
                int stoppedFrames,
                int regionalFrames,
                int fullShieldFrames) {
            this.frames = frames;
            this.totalFrames = totalFrames;
            this.stoppedFrames = stoppedFrames;
            this.regionalFrames = regionalFrames;
            this.fullShieldFrames = fullShieldFrames;
        }
    }

    private static final class InspectionResult {
        private final int inspectedFrames;
        private final double maxRawMatchRatio;
        private final double minRedactedRatio;

        private InspectionResult(
                int inspectedFrames, double maxRawMatchRatio, double minRedactedRatio) {
            this.inspectedFrames = inspectedFrames;
            this.maxRawMatchRatio = maxRawMatchRatio;
            this.minRedactedRatio = minRedactedRatio;
        }
    }

    private static final class FrameInspection {
        private final double rawMatchRatio;
        private final double redactedRatio;

        private FrameInspection(double rawMatchRatio, double redactedRatio) {
            this.rawMatchRatio = rawMatchRatio;
            this.redactedRatio = redactedRatio;
        }
    }

    private static final class PriorityFixture {
        private final Fixture fixture;
        private final String lane;
        private final String category;

        private PriorityFixture(Fixture fixture, String lane, String category) {
            this.fixture = fixture;
            this.lane = lane;
            this.category = category;
        }
    }

    private static final class PriorityFrames {
        private final List<ExpectedFrame> encodedFrames;
        private final List<NormalizedRect> truthTargets;

        private PriorityFrames(
                List<ExpectedFrame> encodedFrames, List<NormalizedRect> truthTargets) {
            this.encodedFrames = encodedFrames;
            this.truthTargets = truthTargets;
        }
    }

    private static final class PriorityInspection {
        private final int protectedFrames;
        private final int exposedFrames;
        private final double maxRawMatchRatio;
        private final double minRedactedRatio;

        private PriorityInspection(
                int protectedFrames,
                int exposedFrames,
                double maxRawMatchRatio,
                double minRedactedRatio) {
            this.protectedFrames = protectedFrames;
            this.exposedFrames = exposedFrames;
            this.maxRawMatchRatio = maxRawMatchRatio;
            this.minRedactedRatio = minRedactedRatio;
        }
    }

    private static final class TargetInspection {
        private final double rawMatchRatio;
        private final double redactedRatio;

        private TargetInspection(double rawMatchRatio, double redactedRatio) {
            this.rawMatchRatio = rawMatchRatio;
            this.redactedRatio = redactedRatio;
        }
    }

    private static final class PriorityTotals {
        private int fixtures;
        private int automaticProtected;
        private int automaticExposed;
        private int watchlistProtected;
        private int watchlistExposed;
        private int zoneProtected;
        private int zoneExposed;

        private void record(PriorityFixture fixture, PriorityInspection result) {
            fixtures++;
            switch (fixture.lane) {
                case "AUTOMATIC_PATTERN" -> {
                    automaticProtected += result.protectedFrames;
                    automaticExposed += result.exposedFrames;
                }
                case "CONFIGURED_WATCHLIST" -> {
                    watchlistProtected += result.protectedFrames;
                    watchlistExposed += result.exposedFrames;
                }
                case "CONFIGURED_ZONE" -> {
                    zoneProtected += result.protectedFrames;
                    zoneExposed += result.exposedFrames;
                }
                default -> throw new AssertionError("Unknown Priority 2 lane " + fixture.lane);
            }
        }

        private void verify() {
            assertEquals(26, fixtures);
            assertEquals(0, automaticProtected);
            assertEquals(80, automaticExposed);
            assertEquals(0, watchlistProtected);
            assertEquals(64, watchlistExposed);
            assertEquals(64, zoneProtected);
            assertEquals(0, zoneExposed);
        }

        private String report() {
            return "automaticProtected=" + automaticProtected + "/80"
                    + " automaticExposed=" + automaticExposed + "/80"
                    + " watchlistProtected=" + watchlistProtected + "/64"
                    + " watchlistExposed=" + watchlistExposed + "/64"
                    + " zoneProtected=" + zoneProtected + "/64"
                    + " zoneExposed=" + zoneExposed + "/64 audioTracks=0";
        }
    }
}
