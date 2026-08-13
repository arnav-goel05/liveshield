package com.liveshield.app.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.liveshield.app.setup.SelectableFace;
import com.liveshield.app.setup.SetupView;
import com.liveshield.privacy.decision.BoundedFrameDecisionStore;
import com.liveshield.privacy.decision.FramePrivacyDecision;
import com.liveshield.privacy.host.DefaultHostSelectionController;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FaceTrackSnapshot;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.model.TypedFailure;
import com.liveshield.privacy.policy.DefaultPrivacyPolicyEngine;
import com.liveshield.privacy.policy.PriorityTwoPolicy;
import com.liveshield.privacy.policy.PriorityTwoPrivacyPolicyEngine;
import com.liveshield.privacy.policy.SensitiveFindingPolicy;
import com.liveshield.privacy.policy.SessionPrivacyConfigurationView;
import com.liveshield.privacy.session.LiveSession;
import com.liveshield.privacy.session.LiveSessionStateMachine;
import com.liveshield.privacy.session.SessionHealth;
import com.liveshield.video.analysis.FaceAnalysisCoordinator;
import com.liveshield.video.output.SanitizedVideoOutput;
import com.liveshield.video.render.PrivacySurfaceProcessor;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class ProductionPriorityTwoIntegrationTest {
    private static final NormalizedRect FACE = new NormalizedRect(0.1, 0.1, 0.3, 0.4);
    private static final NormalizedRect ZONE = new NormalizedRect(0.6, 0.2, 0.9, 0.8);

    @Test
    public void configuredZoneAndFaceReachExactRendererDecision() {
        Fixture fixture = fixture(configuration(List.of(ZONE), true));
        readyGraph(fixture);
        publishSuccessfulLanes(fixture, 100L);

        fixture.coordinator.onFaceState(faceState(100L));

        FramePrivacyDecision decision = fixture.decision(100L);
        assertEquals(FramePrivacyDecision.Status.REGIONAL_SAFE, decision.status());
        assertTrue(hasCategory(decision, FindingCategory.FACE));
        assertTrue(hasCategory(decision, FindingCategory.PRIVACY_ZONE));
        assertTrue(decision.regions().stream()
                .filter(region -> region.category() == FindingCategory.PRIVACY_ZONE)
                .flatMap(region -> region.bounds().stream())
                .anyMatch(bounds -> sameBounds(bounds, ZONE)));
    }

    @Test
    public void boundedDetectorEvidenceMaterializesContinuousExactFrameDecisions() {
        Fixture fixture = fixture(configuration(List.of(ZONE), true));
        readyGraph(fixture);
        publishSuccessfulLanes(fixture, 100L);
        fixture.coordinator.onFaceState(faceState(100L));

        fixture.coordinator.materializeRendererDecision(FrameTimestamp.ofNanos(150L));

        FramePrivacyDecision carried = fixture.decision(150L);
        assertEquals(FramePrivacyDecision.Status.REGIONAL_SAFE, carried.status());
        assertTrue(hasCategory(carried, FindingCategory.FACE));
        assertTrue(hasCategory(carried, FindingCategory.PRIVACY_ZONE));

        fixture.coordinator.materializeRendererDecision(FrameTimestamp.ofNanos(1_200L));
        assertEquals(FramePrivacyDecision.Status.FULL_SHIELD,
                fixture.decision(1_200L).status());
    }

    @Test
    public void missingPriorityTwoLaneShieldsInsteadOfEmittingFaceOnlyDecision() {
        Fixture fixture = fixture(configuration(List.of(), true));
        readyGraph(fixture);
        fixture.coordinator.onDetectorSnapshot(success(DetectorLane.FACE, 100L));
        fixture.coordinator.onDetectorSnapshot(success(DetectorLane.TEXT, 100L));

        fixture.coordinator.onFaceState(faceState(100L));

        assertEquals(FramePrivacyDecision.Status.FULL_SHIELD,
                fixture.decision(100L).status());
    }

    @Test
    public void failedPriorityTwoLaneShieldsAndUnsafeZoneTransformShields() {
        Fixture failed = fixture(configuration(List.of(), true));
        readyGraph(failed);
        publishSuccessfulLanes(failed, 100L);
        FrameTimestamp timestamp = FrameTimestamp.ofNanos(100L);
        failed.coordinator.onDetectorSnapshot(DetectorSnapshot.failure(
                DetectorLane.BARCODE,
                timestamp,
                new TypedFailure(TypedFailure.Code.ANALYZER_ERROR, timestamp)));
        failed.coordinator.onFaceState(faceState(100L));
        assertEquals(FramePrivacyDecision.Status.FULL_SHIELD,
                failed.decision(100L).status());

        Fixture unsafeZone = fixture(configuration(List.of(ZONE), false));
        readyGraph(unsafeZone);
        publishSuccessfulLanes(unsafeZone, 200L);
        unsafeZone.coordinator.onFaceState(faceState(200L));
        assertEquals(FramePrivacyDecision.Status.FULL_SHIELD,
                unsafeZone.decision(200L).status());
    }

    @Test
    public void realHealthProbeQueueThermalSceneAndRecoveryReachPolicy() {
        LiveSessionCoordinator.SafetyHealthSnapshot unsafe =
                new LiveSessionCoordinator.SafetyHealthSnapshot(
                        PrivacySurfaceProcessor.MAX_RAW_TEXTURES,
                        SessionHealth.RecoveryState.UNSAFE,
                        SessionHealth.ThermalState.SEVERE,
                        SessionHealth.SceneState.CHANGED);
        Fixture fixture = fixture(configuration(List.of(), true), () -> unsafe);
        readyGraph(fixture);
        publishSuccessfulLanes(fixture, 100L);

        fixture.coordinator.onFaceState(faceState(100L));

        assertEquals(FramePrivacyDecision.Status.FULL_SHIELD,
                fixture.decision(100L).status());
    }

    @Test
    public void changedSceneRecoversLiveOnlyAfterVerifiedNominalFreshAllLaneDecision() {
        MutableSafety safety = new MutableSafety();
        Fixture fixture = fixture(configuration(List.of(), true), safety::snapshot);
        readyGraph(fixture);
        publishSuccessfulLanes(fixture, 100L);
        fixture.coordinator.onFaceState(faceState(100L));
        fixture.coordinator.onHostSelectionRequested(4L);
        fixture.coordinator.onStartRequested();
        assertEquals(com.liveshield.privacy.session.SessionState.LIVE,
                fixture.coordinator.snapshot().state());

        safety.rawDepth = PrivacySurfaceProcessor.MAX_RAW_TEXTURES;
        safety.recovery = SessionHealth.RecoveryState.UNSAFE;
        safety.thermal = SessionHealth.ThermalState.SEVERE;
        safety.scene = SessionHealth.SceneState.CHANGED;
        fixture.coordinator.onSafetyHealthChanged();
        assertEquals(com.liveshield.privacy.session.SessionState.SHIELDING,
                fixture.coordinator.snapshot().state());

        safety.rawDepth = 0;
        safety.recovery = SessionHealth.RecoveryState.VERIFIED;
        safety.thermal = SessionHealth.ThermalState.NOMINAL;
        safety.scene = SessionHealth.SceneState.STABLE;
        publishSuccessfulLanes(fixture, 200L);
        fixture.coordinator.onFaceState(faceState(200L));

        assertEquals(FramePrivacyDecision.Status.REGIONAL_SAFE,
                fixture.decision(200L).status());
        assertEquals(com.liveshield.privacy.session.SessionState.LIVE,
                fixture.coordinator.snapshot().state());
    }

    private static Fixture fixture(SessionPrivacyConfigurationView configuration) {
        return fixture(configuration, LiveSessionCoordinator.SafetyHealthProbe.NOMINAL);
    }

    private static Fixture fixture(
            SessionPrivacyConfigurationView configuration,
            LiveSessionCoordinator.SafetyHealthProbe safetyHealth) {
        BoundedFrameDecisionStore decisions = new BoundedFrameDecisionStore(12, 1_000L);
        FakeCamera camera = new FakeCamera();
        PriorityTwoPrivacyPolicyEngine policy = new PriorityTwoPrivacyPolicyEngine(
                new DefaultPrivacyPolicyEngine(),
                new PriorityTwoPolicy(new SensitiveFindingPolicy(
                        new SensitiveFindingPolicy.Configuration(
                                Set.of(DetectorLane.TEXT, DetectorLane.BARCODE),
                                750L, 1_000L, 0.25, 16, 32, 32, 8))));
        LiveSessionCoordinator coordinator = new LiveSessionCoordinator(
                new LiveSessionStateMachine(LiveSession.setup("priority-two", Set.of(), List.of())),
                new DefaultHostSelectionController(1_000L),
                decisions,
                new NoOpView(),
                new NoOpPreview(),
                camera,
                () -> { },
                () -> { },
                () -> { },
                () -> 300L,
                Runnable::run,
                () -> null,
                LiveSessionCoordinator.PublicationPort.NO_OP,
                policy,
                () -> configuration,
                policy::reset,
                LiveSessionCoordinator.SessionUiPort.NO_OP,
                safetyHealth);
        return new Fixture(coordinator, camera, decisions);
    }

    private static void readyGraph(Fixture fixture) {
        fixture.coordinator.begin();
        fixture.camera.bound();
        fixture.coordinator.onRendererReadiness(PrivacySurfaceProcessor.Readiness.READY);
        fixture.coordinator.onEncoderState(SanitizedVideoOutput.State.RUNNING, true);
    }

    private static void publishSuccessfulLanes(Fixture fixture, long nanos) {
        fixture.coordinator.onDetectorSnapshot(success(DetectorLane.FACE, nanos));
        fixture.coordinator.onDetectorSnapshot(success(DetectorLane.TEXT, nanos));
        fixture.coordinator.onDetectorSnapshot(success(DetectorLane.BARCODE, nanos));
    }

    private static DetectorSnapshot success(DetectorLane lane, long nanos) {
        FrameTimestamp timestamp = FrameTimestamp.ofNanos(nanos);
        return DetectorSnapshot.success(lane, timestamp, timestamp.plusNanos(1_000L), List.of());
    }

    private static FaceAnalysisCoordinator.FaceFrameState faceState(long nanos) {
        FrameTimestamp timestamp = FrameTimestamp.ofNanos(nanos);
        FaceTrackSnapshot track = new FaceTrackSnapshot(
                4L,
                FACE,
                timestamp,
                FaceTrackSnapshot.ConfidenceState.FRESH,
                FaceTrackSnapshot.Policy.PROTECTED);
        return new FaceAnalysisCoordinator.FaceFrameState(
                timestamp,
                List.of(track),
                List.<ProtectedRegion>of(),
                false,
                false);
    }

    private static SessionPrivacyConfigurationView configuration(
            List<NormalizedRect> zones, boolean transformSafe) {
        return new SessionPrivacyConfigurationView() {
            @Override
            public Set<String> normalizedWatchlistTerms() {
                return Set.of("fictional watch term");
            }

            @Override
            public List<NormalizedRect> activePrivacyZones() {
                return zones;
            }

            @Override
            public boolean zonesSafelyTransformed() {
                return transformSafe;
            }
        };
    }

    private static boolean hasCategory(
            FramePrivacyDecision decision, FindingCategory category) {
        return decision.regions().stream().anyMatch(region -> region.category() == category);
    }

    private static boolean sameBounds(NormalizedRect first, NormalizedRect second) {
        return Double.compare(first.left(), second.left()) == 0
                && Double.compare(first.top(), second.top()) == 0
                && Double.compare(first.right(), second.right()) == 0
                && Double.compare(first.bottom(), second.bottom()) == 0;
    }

    private record Fixture(
            LiveSessionCoordinator coordinator,
            FakeCamera camera,
            BoundedFrameDecisionStore decisions) {
        private FramePrivacyDecision decision(long nanos) {
            FrameTimestamp timestamp = FrameTimestamp.ofNanos(nanos);
            return decisions.lookup(timestamp, timestamp);
        }
    }

    private static final class MutableSafety {
        private int rawDepth;
        private SessionHealth.RecoveryState recovery = SessionHealth.RecoveryState.SAFE;
        private SessionHealth.ThermalState thermal = SessionHealth.ThermalState.NOMINAL;
        private SessionHealth.SceneState scene = SessionHealth.SceneState.STABLE;

        private LiveSessionCoordinator.SafetyHealthSnapshot snapshot() {
            return new LiveSessionCoordinator.SafetyHealthSnapshot(
                    rawDepth, recovery, thermal, scene);
        }
    }

    private static final class FakeCamera implements LiveSessionCoordinator.CameraGraph {
        private BindingListener listener;

        @Override
        public void bind(BindingListener value) {
            listener = value;
        }

        private void bound() {
            listener.onBound();
        }

        @Override
        public void close() {
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

    private static final class NoOpView implements SetupView {
        @Override
        public void showSelectableFaces(List<SelectableFace> faces, Long selectedTrack) {
        }

        @Override
        public void showPrivacyReady(boolean ready) {
        }

        @Override
        public void showHostReselectionRequired(boolean required) {
        }
    }
}
