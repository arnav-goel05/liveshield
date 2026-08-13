package com.liveshield.app.session;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.liveshield.app.R;
import com.liveshield.app.setup.SelectableFace;
import com.liveshield.app.setup.SetupView;
import com.liveshield.privacy.decision.BoundedFrameDecisionStore;
import com.liveshield.privacy.decision.FramePrivacyDecision;
import com.liveshield.privacy.host.DefaultHostSelectionController;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FaceTrackSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.policy.DefaultPrivacyPolicyEngine;
import com.liveshield.privacy.policy.PriorityTwoPolicy;
import com.liveshield.privacy.policy.PriorityTwoPrivacyPolicyEngine;
import com.liveshield.privacy.policy.SensitiveFindingPolicy;
import com.liveshield.privacy.policy.SessionPrivacyConfigurationView;
import com.liveshield.privacy.session.LiveSession;
import com.liveshield.privacy.session.LiveSessionStateMachine;
import com.liveshield.privacy.session.SessionHealth;
import com.liveshield.privacy.session.SessionState;
import com.liveshield.video.analysis.FaceAnalysisCoordinator;
import com.liveshield.video.output.SanitizedVideoOutput;
import com.liveshield.video.render.PrivacySurfaceProcessor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Production-composition proof using only typed, payload-free safety evidence. */
@RunWith(AndroidJUnit4.class)
public final class ProductionSafetyCompositionTest {
    private static final NormalizedRect FACE =
            new NormalizedRect(0.1, 0.1, 0.3, 0.4);

    @Test
    public void typedHealthDrivesPrivateUiAndFreshVerifiedRecovery() {
        LiveSessionUiRegistry.resetForTest();
        Fixture fixture = fixture();
        try {
            fixture.readyAndStart(100L);
            assertEquals(SessionState.LIVE, fixture.coordinator.snapshot().state());

            try (ActivityScenario<LiveActivity> ignored =
                         ActivityScenario.launch(LiveActivity.class)) {
                assertPrivateLabel(R.string.live_status_healthy_label);

                fixture.health.updateScene(SessionHealth.SceneState.CHANGED);
                fixture.coordinator.onSafetyHealthChanged();
                assertShielding(fixture);
                fixture.health.updateScene(SessionHealth.SceneState.STABLE);
                fixture.recover(600L);

                fixture.rawDepth.set(PrivacySurfaceProcessor.MAX_RAW_TEXTURES);
                fixture.coordinator.onSafetyHealthChanged();
                assertShielding(fixture);
                fixture.rawDepth.set(0);
                fixture.recover(700L);

                fixture.recovery.set(SessionHealth.RecoveryState.UNSAFE);
                fixture.coordinator.onSafetyHealthChanged();
                assertShielding(fixture);
                fixture.recovery.set(SessionHealth.RecoveryState.VERIFIED);
                fixture.recover(800L);

                assertEquals(SessionState.LIVE, fixture.coordinator.snapshot().state());
                assertPrivateLabel(R.string.live_status_healthy_label);
            }
        } finally {
            fixture.coordinator.close();
            LiveSessionUiRegistry.resetForTest();
        }
    }

    @Test
    public void productionSafetySeamIsPackagePrivatePayloadFreeAndNotIntentActivated() {
        assertFalse(Modifier.isPublic(ProductionSafetyHealth.class.getModifiers()));
        for (Method method : ProductionSafetyHealth.class.getDeclaredMethods()) {
            if (method.getName().startsWith("update")) {
                assertFalse(Modifier.isPublic(method.getModifiers()));
            }
            assertFalse(method.getName().contains("Intent"));
        }

        assertSnapshotAccessor("rawQueueDepth", int.class);
        assertSnapshotAccessor("recoveryState", SessionHealth.RecoveryState.class);
        assertSnapshotAccessor("thermalState", SessionHealth.ThermalState.class);
        assertSnapshotAccessor("sceneState", SessionHealth.SceneState.class);
    }

    private static void assertShielding(Fixture fixture) {
        assertEquals(SessionState.SHIELDING, fixture.coordinator.snapshot().state());
        assertPrivateLabel(R.string.live_status_shielding_label);
    }

    private static void assertPrivateLabel(int resourceId) {
        onView(withId(R.id.live_status_label)).check(matches(withText(resourceId)));
    }

    private static void assertSnapshotAccessor(String name, Class<?> expectedType) {
        try {
            Method accessor =
                    LiveSessionCoordinator.SafetyHealthSnapshot.class.getDeclaredMethod(name);
            assertEquals(expectedType, accessor.getReturnType());
            assertFalse(accessor.getReturnType().isArray());
            assertFalse(CharSequence.class.isAssignableFrom(accessor.getReturnType()));
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("Missing typed safety accessor", exception);
        }
    }

    private static Fixture fixture() {
        AtomicInteger rawDepth = new AtomicInteger();
        AtomicReference<SessionHealth.RecoveryState> recovery =
                new AtomicReference<>(SessionHealth.RecoveryState.SAFE);
        ProductionSafetyHealth health =
                new ProductionSafetyHealth(rawDepth::get, recovery::get);
        assertEquals(SessionHealth.ThermalState.NOMINAL, health.thermalState());
        FakeCamera camera = new FakeCamera();
        BoundedFrameDecisionStore decisions = new BoundedFrameDecisionStore(12, 10_000L);
        PriorityTwoPrivacyPolicyEngine policy = new PriorityTwoPrivacyPolicyEngine(
                new DefaultPrivacyPolicyEngine(),
                new PriorityTwoPolicy(new SensitiveFindingPolicy(
                        new SensitiveFindingPolicy.Configuration(
                                Set.of(DetectorLane.TEXT, DetectorLane.BARCODE),
                                750L, 1_000L, 0.25, 16, 32, 32, 8))));
        LiveSessionCoordinator coordinator = new LiveSessionCoordinator(
                new LiveSessionStateMachine(
                        LiveSession.setup("typed-production-health", Set.of(), List.of())),
                new DefaultHostSelectionController(10_000L),
                decisions,
                new NoOpView(),
                new NoOpPreview(),
                camera,
                () -> { },
                () -> { },
                () -> { },
                () -> 1_000L,
                Runnable::run,
                () -> null,
                LiveSessionCoordinator.PublicationPort.NO_OP,
                policy,
                ProductionSafetyCompositionTest::emptyConfiguration,
                policy::reset,
                new RegistrySessionUi(),
                health);
        return new Fixture(coordinator, camera, decisions, health, rawDepth, recovery);
    }

    private static SessionPrivacyConfigurationView emptyConfiguration() {
        return new SessionPrivacyConfigurationView() {
            @Override
            public Set<String> normalizedWatchlistTerms() {
                return Set.of();
            }

            @Override
            public List<NormalizedRect> activePrivacyZones() {
                return List.of();
            }

            @Override
            public boolean zonesSafelyTransformed() {
                return true;
            }
        };
    }

    private static DetectorSnapshot success(DetectorLane lane, long nanos) {
        FrameTimestamp timestamp = FrameTimestamp.ofNanos(nanos);
        return DetectorSnapshot.success(
                lane, timestamp, timestamp.plusNanos(10_000L), List.of());
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
                timestamp, List.of(track), List.<ProtectedRegion>of(), false, false);
    }

    private record Fixture(
            LiveSessionCoordinator coordinator,
            FakeCamera camera,
            BoundedFrameDecisionStore decisions,
            ProductionSafetyHealth health,
            AtomicInteger rawDepth,
            AtomicReference<SessionHealth.RecoveryState> recovery) {
        private void readyAndStart(long nanos) {
            coordinator.begin();
            camera.bound();
            coordinator.onRendererReadiness(PrivacySurfaceProcessor.Readiness.READY);
            coordinator.onEncoderState(SanitizedVideoOutput.State.RUNNING, true);
            frame(nanos);
            coordinator.onHostSelectionRequested(4L);
            coordinator.onStartRequested();
        }

        private void frame(long nanos) {
            coordinator.onDetectorSnapshot(success(DetectorLane.FACE, nanos));
            coordinator.onDetectorSnapshot(success(DetectorLane.TEXT, nanos));
            coordinator.onDetectorSnapshot(success(DetectorLane.BARCODE, nanos));
            coordinator.onFaceState(faceState(nanos));
        }

        private FramePrivacyDecision decision(long nanos) {
            FrameTimestamp timestamp = FrameTimestamp.ofNanos(nanos);
            return decisions.lookup(timestamp, timestamp);
        }

        private void recover(long nanos) {
            recovery.set(SessionHealth.RecoveryState.VERIFIED);
            health.updateScene(SessionHealth.SceneState.STABLE);
            rawDepth.set(0);
            frame(nanos);
            assertEquals(FramePrivacyDecision.Status.REGIONAL_SAFE,
                    decision(nanos).status());
            assertEquals(SessionState.LIVE, coordinator.snapshot().state());
        }

    }

    private static final class FakeCamera implements LiveSessionCoordinator.CameraGraph {
        private BindingListener listener;

        @Override
        public void bind(BindingListener updatedListener) {
            listener = updatedListener;
        }

        private void bound() {
            listener.onBound();
        }

        @Override
        public void close() {
        }
    }

    private static final class NoOpPreview
            implements LiveSessionCoordinator.SanitizedPreviewPort {
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

    private static final class RegistrySessionUi
            implements LiveSessionCoordinator.SessionUiPort {
        @Override
        public void onSessionStarted(SessionState state, Runnable stopAction) {
            LiveSessionUiRegistry.activate(state, stopAction);
        }

        @Override
        public void onSessionStateChanged(SessionState state) {
            LiveSessionUiRegistry.update(state);
        }
    }
}
