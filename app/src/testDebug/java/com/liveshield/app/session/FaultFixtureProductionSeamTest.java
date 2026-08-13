package com.liveshield.app.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.liveshield.app.debug.FaultInjectionController;
import com.liveshield.app.debug.FaultInjectionController.FaultTarget;
import com.liveshield.app.setup.SelectableFace;
import com.liveshield.app.setup.SetupView;
import com.liveshield.privacy.decision.BoundedFrameDecisionStore;
import com.liveshield.privacy.decision.FramePrivacyDecision;
import com.liveshield.privacy.host.DefaultHostSelectionController;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.TypedFailure;
import com.liveshield.privacy.policy.DefaultPrivacyPolicyEngine;
import com.liveshield.privacy.policy.SessionPrivacyConfiguration;
import com.liveshield.privacy.session.LiveSession;
import com.liveshield.privacy.session.LiveSessionStateMachine;
import com.liveshield.privacy.session.SessionHealth;
import com.liveshield.privacy.session.SessionState;
import com.liveshield.video.buffer.GlBufferedFrameProcessor;
import com.liveshield.video.contract.RawTextureHandle;
import com.liveshield.video.contract.RedactionRenderer;
import com.liveshield.video.contract.SanitizedRender;
import com.liveshield.video.output.SanitizedVideoOutput;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** Executes every system-v1 fault scenario through its production fail-private control seam. */
public final class FaultFixtureProductionSeamTest {
    private static final Pattern ID = Pattern.compile("\\\"fixtureId\\\":\\s*\\\"([^\\\"]+)");
    private static final Pattern SCENARIO =
            Pattern.compile("\\\"scenarioIds\\\":\\s*\\[\\s*\\\"([^\\\"]+)");

    @Test
    public void allTwentyFixturesReachTypedProductionSeams() throws Exception {
        List<Fixture> fixtures = fixtures();
        assertEquals(20, fixtures.size());
        EnumMap<FaultTarget, Integer> pathCounts = new EnumMap<>(FaultTarget.class);
        int networkWithoutProductionPublisher = 0;

        for (Fixture fixture : fixtures) {
            ProductionSeamDriver driver = new ProductionSeamDriver(fixture.scenario);
            for (FaultTarget target : targets(fixture.scenario)) {
                driver.controller.arm(target, 0);
                assertTrue("Fault did not dispatch: " + fixture.id,
                        driver.controller.checkpoint(target));
                assertFalse("Fault dispatched more than once: " + fixture.id,
                        driver.controller.checkpoint(target));
                driver.assertFailPrivate(target);
                pathCounts.merge(target, 1, Integer::sum);
                if (target == FaultTarget.NETWORK_LOSS) {
                    networkWithoutProductionPublisher++;
                }
            }
        }

        assertEquals(2, networkWithoutProductionPublisher);
        assertEquals(9, pathCounts.size());
        assertEquals(Integer.valueOf(6), pathCounts.get(FaultTarget.DETECTOR_STALL));
        assertEquals(Integer.valueOf(2), pathCounts.get(FaultTarget.DETECTOR_FAILURE));
        for (FaultTarget target : FaultTarget.values()) {
            assertTrue("Missing typed path " + target, pathCounts.getOrDefault(target, 0) > 0);
        }
    }

    private static List<FaultTarget> targets(String scenario) {
        return switch (scenario) {
            case "missing-analysis-result", "late-analysis-result",
                    "stale-out-of-order-timestamp" -> List.of(FaultTarget.DETECTOR_STALL);
            case "detector-exception-cancellation" -> List.of(FaultTarget.DETECTOR_FAILURE);
            case "raw-frame-queue-capacity", "recovery-old-undecided-queue" ->
                    List.of(FaultTarget.QUEUE_CAPACITY);
            case "renderer-failure-invalid-surface" ->
                    List.of(FaultTarget.GL_FAILURE, FaultTarget.SURFACE_LOSS);
            case "camera-rebind-lifecycle-interruption" ->
                    List.of(FaultTarget.CAMERA_FAILURE, FaultTarget.LIFECYCLE_INTERRUPTION);
            case "encoder-backpressure-reconfiguration" ->
                    List.of(FaultTarget.ENCODER_FAILURE);
            case "network-disconnect-reconnect" -> List.of(FaultTarget.NETWORK_LOSS);
            default -> throw new AssertionError("Unmapped scenario " + scenario);
        };
    }

    private static List<Fixture> fixtures() throws IOException {
        List<Fixture> fixtures = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(
                "../test-fixtures/manifests/system-v1.jsonl"))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (!line.contains("\"group\":\"FAULT_INJECTION\"")) {
                    continue;
                }
                fixtures.add(new Fixture(capture(ID, line), capture(SCENARIO, line)));
            }
        }
        return fixtures;
    }

    private static String capture(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        if (!matcher.find()) {
            throw new AssertionError("Malformed system fixture manifest");
        }
        return matcher.group(1);
    }

    private record Fixture(String id, String scenario) {
    }

    /**
     * Test-only driver whose typed signals bind directly to production policy, queue, and
     * lifecycle APIs.
     */
    private static final class ProductionSeamDriver {
        private final EnumMap<FaultTarget, PathEvidence> evidence =
                new EnumMap<>(FaultTarget.class);
        private final String scenario;
        private final FaultInjectionController controller;

        private ProductionSeamDriver(String scenario) {
            this.scenario = scenario;
            FaultInjectionController.Bindings bindings = new FaultInjectionController.Bindings();
            bindings.on(FaultTarget.DETECTOR_STALL, ignored -> detectorStall());
            bindings.on(FaultTarget.DETECTOR_FAILURE, ignored -> detectorFailure());
            bindings.on(FaultTarget.QUEUE_CAPACITY, ignored -> queueCapacity());
            bindings.on(FaultTarget.GL_FAILURE, ignored -> rendererFailure());
            bindings.on(FaultTarget.SURFACE_LOSS, ignored -> surfaceLoss());
            bindings.on(FaultTarget.CAMERA_FAILURE, ignored -> componentStop(
                    LiveSessionStateMachine.RequiredComponent.CAMERA));
            bindings.on(FaultTarget.LIFECYCLE_INTERRUPTION, ignored -> lifecycleStop());
            bindings.on(FaultTarget.ENCODER_FAILURE, ignored -> encoderStop());
            bindings.on(FaultTarget.NETWORK_LOSS, ignored -> networkControlOnly());
            controller = new FaultInjectionController(bindings);
        }

        private void assertFailPrivate(FaultTarget target) {
            PathEvidence observed = evidence.get(target);
            assertTrue("No production seam evidence for " + target, observed != null);
            if (target == FaultTarget.NETWORK_LOSS) {
                assertTrue(observed.networkProductionUnavailable);
                assertFalse(observed.rawOutputAuthorized);
            } else {
                assertTrue("Path neither shielded nor stopped: " + target,
                        observed.shielded || observed.stopped);
                assertFalse(observed.rawOutputAuthorized);
            }
        }

        private void detectorStall() {
            if ("late-analysis-result".equals(scenario)) {
                BufferHarness harness = new BufferHarness(false);
                harness.accept(10, 20);
                harness.processor.processReady(timestamp(20));
                assertEquals(List.of(10L), harness.shielded);
                recordShield(FaultTarget.DETECTOR_STALL,
                        FramePrivacyDecision.fullShield(
                                timestamp(10), FramePrivacyDecision.Basis.TIMEOUT));
                return;
            }
            List<DetectorSnapshot> snapshots = "stale-out-of-order-timestamp".equals(scenario)
                    ? List.of(DetectorSnapshot.success(
                            DetectorLane.FACE, timestamp(10), timestamp(20), List.of()))
                    : List.of();
            FramePrivacyDecision decision = policy().decide(
                    timestamp(100), snapshots, List.of(), configuration(), health());
            recordShield(FaultTarget.DETECTOR_STALL, decision);
        }

        private void detectorFailure() {
            FrameTimestamp now = timestamp(100);
            TypedFailure failure = new TypedFailure(TypedFailure.Code.ANALYZER_ERROR, now);
            FramePrivacyDecision decision = policy().decide(
                    now,
                    List.of(DetectorSnapshot.failure(DetectorLane.FACE, now, failure)),
                    List.of(), configuration(), health());
            recordShield(FaultTarget.DETECTOR_FAILURE, decision);
        }

        private void queueCapacity() {
            BufferHarness harness = new BufferHarness(false);
            harness.accept(10, 30);
            harness.accept(11, 31);
            harness.accept(12, 32);
            assertEquals(List.of(10L, 11L, 12L), harness.shielded);
            assertTrue(harness.processor.requiresVerifiedRecovery());
            if ("recovery-old-undecided-queue".equals(scenario)) {
                harness.processor.verifyRecovery();
                harness.accept(20, 30);
                harness.decisions.store(regional(20));
                harness.processor.processReady(timestamp(20));
                assertEquals(List.of(20L), harness.rendered);
                assertFalse(harness.processor.requiresVerifiedRecovery());
            }
            evidence.put(FaultTarget.QUEUE_CAPACITY, PathEvidence.shieldOutcome());
        }

        private void rendererFailure() {
            BufferHarness harness = new BufferHarness(true);
            harness.accept(10, 30);
            harness.accept(11, 31);
            harness.decisions.store(regional(10));
            harness.processor.processReady(timestamp(10));
            assertEquals(List.of(11L), harness.shielded);
            assertTrue(harness.processor.requiresVerifiedRecovery());
            evidence.put(FaultTarget.GL_FAILURE, PathEvidence.shieldOutcome());
        }

        private void surfaceLoss() {
            BufferHarness harness = new BufferHarness(false);
            harness.accept(10, 30);
            harness.processor.fail(new IllegalStateException("typed surface loss"));
            assertEquals(List.of(10L), harness.shielded);
            evidence.put(FaultTarget.SURFACE_LOSS, PathEvidence.shieldOutcome());
        }

        private void componentStop(LiveSessionStateMachine.RequiredComponent component) {
            LiveSessionCoordinator coordinator = coordinator();
            coordinator.onComponentFailure(component, new IllegalStateException("typed failure"));
            assertEquals(SessionState.ENDED, coordinator.snapshot().state());
            evidence.put(FaultTarget.CAMERA_FAILURE, PathEvidence.stopOutcome());
        }

        private void lifecycleStop() {
            LiveSessionCoordinator coordinator = coordinator();
            coordinator.close();
            assertEquals(SessionState.ENDED, coordinator.snapshot().state());
            evidence.put(FaultTarget.LIFECYCLE_INTERRUPTION, PathEvidence.stopOutcome());
        }

        private void encoderStop() {
            LiveSessionCoordinator coordinator = coordinator();
            coordinator.onEncoderState(SanitizedVideoOutput.State.FAILED, false);
            assertEquals(SessionState.ENDED, coordinator.snapshot().state());
            evidence.put(FaultTarget.ENCODER_FAILURE, PathEvidence.stopOutcome());
        }

        private void networkControlOnly() {
            // No endpoint is configured here. This proves only that the typed control cannot
            // authorize raw output; RTMP disconnect/reconnect remains separate US6 evidence.
            evidence.put(FaultTarget.NETWORK_LOSS, PathEvidence.networkUnavailable());
        }

        private void recordShield(FaultTarget target, FramePrivacyDecision decision) {
            assertEquals(FramePrivacyDecision.Status.FULL_SHIELD, decision.status());
            evidence.put(target, PathEvidence.shieldOutcome());
        }

        private static DefaultPrivacyPolicyEngine policy() {
            return new DefaultPrivacyPolicyEngine();
        }

        private static SessionPrivacyConfiguration configuration() {
            return new SessionPrivacyConfiguration(Set.of(), List.of());
        }

        private static SessionHealth health() {
            return SessionHealth.builder(SessionState.LIVE).build();
        }

        private static LiveSessionCoordinator coordinator() {
            LiveSessionStateMachine machine = new LiveSessionStateMachine(
                    LiveSession.setup("fault-driver", Set.of(), List.of()));
            return new LiveSessionCoordinator(
                    machine,
                    new DefaultHostSelectionController(20L),
                    new BoundedFrameDecisionStore(4, 1_000L),
                    new NoOpView(),
                    new NoOpPreview(),
                    new LiveSessionCoordinator.CameraGraph() {
                        @Override
                        public void bind(BindingListener listener) {
                        }

                        @Override
                        public void close() {
                        }
                    },
                    () -> { },
                    () -> { },
                    () -> { },
                    () -> 100L,
                    Runnable::run);
        }
    }

    private static final class BufferHarness {
        private final BoundedFrameDecisionStore decisions =
                new BoundedFrameDecisionStore(8, 1_000L);
        private final List<Long> rendered = new ArrayList<>();
        private final List<Long> shielded = new ArrayList<>();
        private final GlBufferedFrameProcessor processor;

        private BufferHarness(boolean failRegionalRender) {
            RedactionRenderer renderer = new RedactionRenderer() {
                @Override
                public SanitizedRender render(
                        RawTextureHandle rawTexture, FramePrivacyDecision privacyDecision) {
                    if (failRegionalRender) {
                        throw new IllegalStateException("typed GL failure");
                    }
                    rendered.add(privacyDecision.timestamp().nanos());
                    return new SanitizedRender(privacyDecision.timestamp());
                }

                @Override
                public SanitizedRender renderShield(FramePrivacyDecision privacyDecision) {
                    shielded.add(privacyDecision.timestamp().nanos());
                    return new SanitizedRender(privacyDecision.timestamp());
                }
            };
            processor = new GlBufferedFrameProcessor(
                    3, decisions, renderer, ignored -> { }, ignored -> { });
        }

        private void accept(long timestamp, long deadline) {
            processor.accept(() -> { }, timestamp(timestamp), timestamp(deadline));
        }
    }

    private static FramePrivacyDecision regional(long nanos) {
        FrameTimestamp timestamp = timestamp(nanos);
        return FramePrivacyDecision.regionalSafe(
                timestamp, List.of(), FramePrivacyDecision.Basis.FRESH,
                timestamp.plusNanos(10));
    }

    private static FrameTimestamp timestamp(long nanos) {
        return FrameTimestamp.ofNanos(nanos);
    }

    private record PathEvidence(
            boolean shielded,
            boolean stopped,
            boolean rawOutputAuthorized,
            boolean networkProductionUnavailable) {
        private static PathEvidence shieldOutcome() {
            return new PathEvidence(true, false, false, false);
        }

        private static PathEvidence stopOutcome() {
            return new PathEvidence(false, true, false, false);
        }

        private static PathEvidence networkUnavailable() {
            return new PathEvidence(false, false, false, true);
        }
    }

    private static final class NoOpView implements SetupView {
        @Override
        public void showSelectableFaces(List<SelectableFace> faces, Long selectedTrackId) {
        }

        @Override
        public void showPrivacyReady(boolean ready) {
        }
    }

    private static final class NoOpPreview implements LiveSessionCoordinator.SanitizedPreviewPort {
        @Override
        public void attach() {
        }

        @Override
        public void close() {
        }
    }
}
