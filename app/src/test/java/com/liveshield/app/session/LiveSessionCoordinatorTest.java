package com.liveshield.app.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.liveshield.app.setup.SelectableFace;
import com.liveshield.app.setup.SetupView;
import com.liveshield.privacy.decision.BoundedFrameDecisionStore;
import com.liveshield.privacy.host.DefaultHostSelectionController;
import com.liveshield.privacy.model.FaceTrackSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.session.LiveSession;
import com.liveshield.privacy.session.LiveSessionStateMachine;
import com.liveshield.privacy.session.SessionState;
import com.liveshield.transport.destination.StreamDestination;
import com.liveshield.video.analysis.FaceAnalysisCoordinator;
import com.liveshield.video.output.SanitizedVideoOutput;
import com.liveshield.video.render.PrivacySurfaceProcessor;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class LiveSessionCoordinatorTest {
    private static final NormalizedRect FACE_BOUNDS =
            new NormalizedRect(0.1, 0.2, 0.4, 0.6);

    @Test
    public void startRemainsBlockedUntilFreshHostAndRealPipelineReadiness() {
        Fixture fixture = fixture();
        fixture.coordinator.begin();
        fixture.camera.bound();
        fixture.coordinator.onFaceState(faceState(freshTrack(7L), false));
        fixture.coordinator.onHostSelectionRequested(7L);
        fixture.coordinator.onRendererReadiness(PrivacySurfaceProcessor.Readiness.READY);

        assertFalse(fixture.view.ready);
        assertEquals(SessionState.SETUP, fixture.coordinator.snapshot().state());

        fixture.coordinator.onEncoderState(SanitizedVideoOutput.State.RUNNING, true);

        assertTrue(fixture.view.ready);
        assertEquals(SessionState.READY, fixture.coordinator.snapshot().state());

        fixture.coordinator.onStartRequested();

        assertEquals(SessionState.LIVE, fixture.coordinator.snapshot().state());
        assertFalse(fixture.view.ready);
        assertEquals(List.of(SessionState.LIVE), fixture.sessionUi.startedStates);
    }

    @Test
    public void privateUiLaunchesOnlyAfterSuccessfulStartAndStopCallbackSafelyCleans() {
        FakePublication publication = new FakePublication(new ArrayList<>());
        publication.configured = true;
        Fixture fixture = readyFixture(publication);

        assertTrue(fixture.sessionUi.startedStates.isEmpty());
        fixture.coordinator.onStartRequested();
        assertEquals(List.of(SessionState.LIVE), fixture.sessionUi.startedStates);

        fixture.sessionUi.requestStop();

        assertEquals(SessionState.ENDED, fixture.coordinator.snapshot().state());
        assertEquals(SessionState.ENDED,
                fixture.sessionUi.changedStates.get(fixture.sessionUi.changedStates.size() - 1));
        assertEquals(1, publication.closeCount);
        assertEquals(List.of("camera", "output", "renderer", "preview", "faces"),
                fixture.cleanupOrder);
    }

    @Test
    public void failedStartNeverLaunchesPrivateUiButReportsTerminalFailure() {
        FakePublication publication = new FakePublication(new ArrayList<>());
        publication.configured = true;
        publication.failStart = true;
        Fixture fixture = readyFixture(publication);

        fixture.coordinator.onStartRequested();

        assertTrue(fixture.sessionUi.startedStates.isEmpty());
        assertEquals(SessionState.FAILED,
                fixture.sessionUi.changedStates.get(fixture.sessionUi.changedStates.size() - 1));
    }

    @Test
    public void warningDegradesAndSevereOrSceneChangeShieldsPrivateLiveState() {
        FakePublication publication = new FakePublication(new ArrayList<>());
        publication.configured = true;
        Fixture fixture = readyFixture(publication);
        fixture.coordinator.onStartRequested();

        fixture.safety.thermal = com.liveshield.privacy.session.SessionHealth.ThermalState.WARNING;
        fixture.coordinator.onSafetyHealthChanged();
        assertEquals(SessionState.DEGRADED, fixture.coordinator.snapshot().state());
        assertEquals(SessionState.DEGRADED,
                fixture.sessionUi.changedStates.get(fixture.sessionUi.changedStates.size() - 1));

        fixture.safety.scene = com.liveshield.privacy.session.SessionHealth.SceneState.CHANGED;
        fixture.coordinator.onSafetyHealthChanged();
        assertEquals(SessionState.SHIELDING, fixture.coordinator.snapshot().state());
        assertEquals(0, publication.stopCount);
    }

    @Test
    public void unsafeRecoveryAndRawQueueCapacityShieldWithoutPayload() {
        FakePublication publication = new FakePublication(new ArrayList<>());
        publication.configured = true;
        Fixture fixture = readyFixture(publication);
        fixture.coordinator.onStartRequested();

        fixture.safety.rawDepth = PrivacySurfaceProcessor.MAX_RAW_TEXTURES;
        fixture.safety.recovery =
                com.liveshield.privacy.session.SessionHealth.RecoveryState.UNSAFE;
        fixture.coordinator.onSafetyHealthChanged();

        assertEquals(SessionState.SHIELDING, fixture.coordinator.snapshot().state());
        assertEquals(0, publication.stopCount);
    }

    @Test
    public void staleOrUnknownOverlayTrackCannotBecomeHost() {
        Fixture fixture = fixture();
        fixture.coordinator.begin();
        fixture.camera.bound();
        FaceTrackSnapshot stale = new FaceTrackSnapshot(
                7L, FACE_BOUNDS, FrameTimestamp.ofNanos(90L),
                FaceTrackSnapshot.ConfidenceState.PREDICTED,
                FaceTrackSnapshot.Policy.PROTECTED);
        fixture.coordinator.onFaceState(faceState(stale, false));
        fixture.coordinator.onHostSelectionRequested(7L);
        fixture.coordinator.onRendererReadiness(PrivacySurfaceProcessor.Readiness.READY);
        fixture.coordinator.onEncoderState(SanitizedVideoOutput.State.RUNNING, true);

        assertFalse(fixture.coordinator.snapshot().hostTrackId().isPresent());
        assertEquals(SessionState.SETUP, fixture.coordinator.snapshot().state());
        assertFalse(fixture.view.ready);
    }

    @Test
    public void rendererLossDuringLiveEntersShieldingAndRevokesUiReadiness() {
        FakePublication publication = new FakePublication(new ArrayList<>());
        publication.configured = true;
        Fixture fixture = readyFixture(publication);
        fixture.coordinator.onStartRequested();

        fixture.coordinator.onRendererReadiness(PrivacySurfaceProcessor.Readiness.UNAVAILABLE);

        assertEquals(SessionState.SHIELDING, fixture.coordinator.snapshot().state());
        assertFalse(fixture.view.ready);
        assertEquals(1, publication.stopCount);
    }

    @Test
    public void publicationStartsOnlyAfterPrivacyReadyAndDestinationConfigured() {
        FakePublication publication = new FakePublication(new ArrayList<>());
        Fixture fixture = readyFixture(publication);

        fixture.coordinator.onStartRequested();

        assertEquals(SessionState.READY, fixture.coordinator.snapshot().state());
        assertEquals(0, publication.startCount);

        StreamDestination destination = destination();
        fixture.coordinator.configurePublication(destination);
        fixture.coordinator.onStartRequested();

        assertEquals(SessionState.LIVE, fixture.coordinator.snapshot().state());
        assertEquals(1, publication.startCount);

        fixture.coordinator.close();

        assertEquals(StreamDestination.State.CLEARED, destination.state());
    }

    @Test
    public void configuredPublicationCannotStartBeforePrivacyReady() {
        FakePublication publication = new FakePublication(new ArrayList<>());
        publication.configured = true;
        Fixture fixture = fixture(publication, new ArrayList<>());

        fixture.coordinator.onStartRequested();

        assertEquals(SessionState.SETUP, fixture.coordinator.snapshot().state());
        assertEquals(0, publication.startCount);
    }

    @Test
    public void privateNetworkHealthDegradesUntilFreshPublishingEpoch() {
        FakePublication publication = new FakePublication(new ArrayList<>());
        publication.configured = true;
        Fixture fixture = readyFixture(publication);
        fixture.coordinator.onStartRequested();

        fixture.coordinator.onPublisherNetworkDisconnected();

        assertEquals(SessionState.DEGRADED, fixture.coordinator.snapshot().state());
        assertEquals(LiveSessionCoordinator.PublisherState.RECONNECTING,
                fixture.coordinator.publisherHealth().state());
        assertEquals(1, publication.disconnectCount);

        publication.emit(new LiveSessionCoordinator.PublisherHealth(
                LiveSessionCoordinator.PublisherState.PUBLISHING,
                LiveSessionCoordinator.PublisherFailure.NONE, 0, 0L, false));
        assertEquals(SessionState.DEGRADED, fixture.coordinator.snapshot().state());

        publication.emit(new LiveSessionCoordinator.PublisherHealth(
                LiveSessionCoordinator.PublisherState.PUBLISHING,
                LiveSessionCoordinator.PublisherFailure.NONE, 0, 0L, true));
        assertEquals(SessionState.LIVE, fixture.coordinator.snapshot().state());
    }

    @Test
    public void asyncAuthenticationFailureSafelyStopsAndReportsPrivateFailure() {
        List<String> cleanupOrder = new ArrayList<>();
        FakePublication publication = new FakePublication(cleanupOrder);
        publication.configured = true;
        Fixture fixture = readyFixture(publication, cleanupOrder);
        fixture.coordinator.onStartRequested();

        publication.emit(new LiveSessionCoordinator.PublisherHealth(
                LiveSessionCoordinator.PublisherState.FAILED,
                LiveSessionCoordinator.PublisherFailure.AUTHENTICATION, 0, 0L, false));

        assertEquals(SessionState.ENDED, fixture.coordinator.snapshot().state());
        assertEquals(SessionState.FAILED,
                fixture.sessionUi.changedStates.get(fixture.sessionUi.changedStates.size() - 1));
        assertEquals(List.of("publisher", "camera", "output", "renderer", "preview", "faces"),
                fixture.cleanupOrder);
    }

    @Test
    public void congestionDegradesAndQueueFailureStopsWithoutUntreatedFallback() {
        FakePublication publication = new FakePublication(new ArrayList<>());
        publication.configured = true;
        Fixture fixture = readyFixture(publication);
        fixture.coordinator.onStartRequested();

        publication.emit(new LiveSessionCoordinator.PublisherHealth(
                LiveSessionCoordinator.PublisherState.RECONNECTING,
                LiveSessionCoordinator.PublisherFailure.CONGESTION, 4, 2L, false));
        assertEquals(SessionState.DEGRADED, fixture.coordinator.snapshot().state());

        publication.emit(new LiveSessionCoordinator.PublisherHealth(
                LiveSessionCoordinator.PublisherState.FAILED,
                LiveSessionCoordinator.PublisherFailure.QUEUE, 0, 3L, false));
        assertEquals(SessionState.ENDED, fixture.coordinator.snapshot().state());
        assertEquals(SessionState.FAILED,
                fixture.sessionUi.changedStates.get(fixture.sessionUi.changedStates.size() - 1));
    }

    @Test
    public void privatePublisherHealthCannotCarryEndpointSecretOrPayloadTypes() {
        for (Field field : LiveSessionCoordinator.PublisherHealth.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(name.contains("endpoint"));
            assertFalse(name.contains("secret"));
            assertFalse(name.contains("payload"));
            assertFalse(field.getType().equals(String.class));
            assertFalse(field.getType().equals(URI.class));
            assertFalse(field.getType().isArray());
        }
    }

    @Test
    public void publisherStartFailureStopsPublisherBeforeProtectedGraph() {
        List<String> cleanupOrder = new ArrayList<>();
        FakePublication publication = new FakePublication(cleanupOrder);
        publication.configured = true;
        publication.failStart = true;
        Fixture fixture = readyFixture(publication, cleanupOrder);

        fixture.coordinator.onStartRequested();

        assertEquals(SessionState.ENDED, fixture.coordinator.snapshot().state());
        assertEquals(List.of("publisher", "camera", "output", "renderer", "preview", "faces"),
                fixture.cleanupOrder);
    }

    @Test
    public void encoderFailureStopsPublicationBeforeClosingProtectedGraph() {
        List<String> cleanupOrder = new ArrayList<>();
        FakePublication publication = new FakePublication(cleanupOrder);
        publication.configured = true;
        Fixture fixture = readyFixture(publication, cleanupOrder);
        fixture.coordinator.onStartRequested();

        fixture.coordinator.onEncoderState(SanitizedVideoOutput.State.FAILED, false);

        assertEquals(SessionState.ENDED, fixture.coordinator.snapshot().state());
        assertEquals(1, publication.stopCount);
        assertEquals(List.of("publisher", "camera", "output", "renderer", "preview", "faces"),
                fixture.cleanupOrder);
    }

    @Test
    public void hostContinuityLossRevokesPermissionWithoutTransfer() {
        Fixture fixture = readyFixture();
        fixture.coordinator.onStartRequested();

        fixture.coordinator.onFaceState(new FaceAnalysisCoordinator.FaceFrameState(
                FrameTimestamp.ofNanos(101L),
                List.of(freshTrack(9L)),
                List.of(),
                false,
                true));

        assertEquals(SessionState.SHIELDING, fixture.coordinator.snapshot().state());
        assertFalse(fixture.coordinator.snapshot().hostTrackId().isPresent());
        assertFalse(fixture.view.ready);
        assertTrue(fixture.view.reselectionRequired);
    }

    @Test
    public void reselectionRejectsUnknownThenFreshExplicitTapResumesProtectedLive() {
        Fixture fixture = readyFixture();
        fixture.coordinator.onStartRequested();
        fixture.coordinator.onFaceState(new FaceAnalysisCoordinator.FaceFrameState(
                FrameTimestamp.ofNanos(101L),
                List.of(freshTrack(9L)),
                List.of(),
                false,
                true));

        fixture.coordinator.onHostSelectionRequested(7L);

        assertEquals(SessionState.SHIELDING, fixture.coordinator.snapshot().state());
        assertTrue(fixture.view.reselectionRequired);
        assertFalse(fixture.view.ready);

        fixture.coordinator.onHostSelectionRequested(9L);

        assertEquals(SessionState.LIVE, fixture.coordinator.snapshot().state());
        assertEquals(9L, fixture.coordinator.snapshot().hostTrackId().orElseThrow());
        assertFalse(fixture.view.reselectionRequired);
        assertFalse(fixture.view.ready);
    }

    @Test
    public void bindingFailureStopsAndCleansEverySessionResource() {
        Fixture fixture = fixture();
        fixture.coordinator.begin();

        fixture.camera.failed();

        assertEquals(SessionState.ENDED, fixture.coordinator.snapshot().state());
        assertEquals(List.of("camera", "output", "renderer", "preview", "faces"),
                fixture.cleanupOrder);
        assertFalse(fixture.view.ready);
    }

    @Test
    public void lifecycleCloseIsIdempotentAndClearsEphemeralState() {
        Fixture fixture = readyFixture();

        fixture.coordinator.close();
        fixture.coordinator.close();

        assertEquals(SessionState.ENDED, fixture.coordinator.snapshot().state());
        assertFalse(fixture.coordinator.snapshot().hostTrackId().isPresent());
        assertEquals(List.of("camera", "output", "renderer", "preview", "faces"),
                fixture.cleanupOrder);
        assertTrue(fixture.view.faces.isEmpty());
        assertFalse(fixture.view.reselectionRequired);
    }

    @Test
    public void duplicateFaceTimestampFailsPrivateAndStopsWithoutOverwritingDecision() {
        Fixture fixture = fixture();
        fixture.coordinator.begin();
        fixture.camera.bound();
        fixture.coordinator.onFaceState(faceState(freshTrack(7L), false));

        fixture.coordinator.onFaceState(faceState(freshTrack(8L), false));

        assertEquals(SessionState.ENDED, fixture.coordinator.snapshot().state());
        assertFalse(fixture.view.ready);
        assertEquals(List.of("camera", "output", "renderer", "preview", "faces"),
                fixture.cleanupOrder);
    }

    private static Fixture readyFixture() {
        Fixture fixture = fixture();
        makeReady(fixture);
        return fixture;
    }

    private static Fixture readyFixture(FakePublication publication) {
        return readyFixture(publication, new ArrayList<>());
    }

    private static Fixture readyFixture(
            FakePublication publication, List<String> cleanupOrder) {
        Fixture fixture = fixture(publication, cleanupOrder);
        makeReady(fixture);
        return fixture;
    }

    private static void makeReady(Fixture fixture) {
        fixture.coordinator.begin();
        fixture.camera.bound();
        fixture.coordinator.onFaceState(faceState(freshTrack(7L), false));
        fixture.coordinator.onHostSelectionRequested(7L);
        fixture.coordinator.onRendererReadiness(PrivacySurfaceProcessor.Readiness.READY);
        fixture.coordinator.onEncoderState(SanitizedVideoOutput.State.RUNNING, true);
    }

    private static Fixture fixture() {
        List<String> cleanupOrder = new ArrayList<>();
        return fixture(null, cleanupOrder);
    }

    private static Fixture fixture(
            FakePublication publication, List<String> cleanupOrder) {
        FakeView view = new FakeView();
        FakePreview preview = new FakePreview(cleanupOrder);
        FakeCamera camera = new FakeCamera(cleanupOrder);
        FakeSessionUi sessionUi = new FakeSessionUi();
        MutableSafety safety = new MutableSafety();
        LiveSessionStateMachine machine = new LiveSessionStateMachine(
                LiveSession.setup("coordinator-test", Set.of(), List.of()));
        LiveSessionCoordinator coordinator = new LiveSessionCoordinator(
                        machine,
                        new DefaultHostSelectionController(20L),
                        new BoundedFrameDecisionStore(12, 100_000_000L),
                        view,
                        preview,
                        camera,
                        resource("output", cleanupOrder),
                        resource("renderer", cleanupOrder),
                        () -> cleanupOrder.add("faces"),
                        () -> 100L,
                        Runnable::run,
                        () -> null,
                        publication == null
                                ? LiveSessionCoordinator.PublicationPort.NO_OP : publication,
                        null,
                        null,
                        () -> { },
                        sessionUi,
                        safety::snapshot);
        return new Fixture(coordinator, view, camera, cleanupOrder, sessionUi, safety);
    }

    private static StreamDestination destination() {
        return StreamDestination.sessionScoped(
                StreamDestination.Kind.TIKTOK_EXTERNAL,
                "Live destination",
                URI.create("rtmps://live.example.test/app"),
                "session-key".toCharArray());
    }

    private static LiveSessionCoordinator.SessionResource resource(
            String name, List<String> cleanupOrder) {
        return () -> cleanupOrder.add(name);
    }

    private static FaceTrackSnapshot freshTrack(long trackId) {
        return new FaceTrackSnapshot(
                trackId, FACE_BOUNDS, FrameTimestamp.ofNanos(90L),
                FaceTrackSnapshot.ConfidenceState.FRESH,
                FaceTrackSnapshot.Policy.PROTECTED);
    }

    private static FaceAnalysisCoordinator.FaceFrameState faceState(
            FaceTrackSnapshot track, boolean hostContinuityLost) {
        return new FaceAnalysisCoordinator.FaceFrameState(
                FrameTimestamp.ofNanos(100L),
                List.of(track),
                List.<ProtectedRegion>of(),
                false,
                hostContinuityLost);
    }

    private record Fixture(
            LiveSessionCoordinator coordinator,
            FakeView view,
            FakeCamera camera,
            List<String> cleanupOrder,
            FakeSessionUi sessionUi,
            MutableSafety safety) {
    }

    private static final class MutableSafety {
        private int rawDepth;
        private com.liveshield.privacy.session.SessionHealth.RecoveryState recovery =
                com.liveshield.privacy.session.SessionHealth.RecoveryState.SAFE;
        private com.liveshield.privacy.session.SessionHealth.ThermalState thermal =
                com.liveshield.privacy.session.SessionHealth.ThermalState.NOMINAL;
        private com.liveshield.privacy.session.SessionHealth.SceneState scene =
                com.liveshield.privacy.session.SessionHealth.SceneState.STABLE;

        private LiveSessionCoordinator.SafetyHealthSnapshot snapshot() {
            return new LiveSessionCoordinator.SafetyHealthSnapshot(
                    rawDepth, recovery, thermal, scene);
        }
    }

    private static final class FakeSessionUi implements LiveSessionCoordinator.SessionUiPort {
        private final List<SessionState> startedStates = new ArrayList<>();
        private final List<SessionState> changedStates = new ArrayList<>();
        private Runnable stopAction;

        @Override
        public void onSessionStarted(SessionState state, Runnable requestedStop) {
            startedStates.add(state);
            stopAction = requestedStop;
        }

        @Override
        public void onSessionStateChanged(SessionState state) {
            changedStates.add(state);
        }

        private void requestStop() {
            stopAction.run();
        }
    }

    private static final class FakeView implements SetupView {
        private List<SelectableFace> faces = List.of();
        private boolean ready;
        private boolean reselectionRequired;

        @Override
        public void showSelectableFaces(List<SelectableFace> newFaces, Long selectedTrack) {
            faces = List.copyOf(newFaces);
        }

        @Override
        public void showPrivacyReady(boolean newReady) {
            ready = newReady;
        }

        @Override
        public void showHostReselectionRequired(boolean required) {
            reselectionRequired = required;
        }
    }

    private static final class FakePreview implements LiveSessionCoordinator.SanitizedPreviewPort {
        private final List<String> cleanupOrder;

        private FakePreview(List<String> cleanupOrder) {
            this.cleanupOrder = cleanupOrder;
        }

        @Override
        public void attach() {
        }

        @Override
        public void close() {
            cleanupOrder.add("preview");
        }
    }

    private static final class FakeCamera implements LiveSessionCoordinator.CameraGraph {
        private final List<String> cleanupOrder;
        private BindingListener listener;

        private FakeCamera(List<String> cleanupOrder) {
            this.cleanupOrder = cleanupOrder;
        }

        @Override
        public void bind(BindingListener newListener) {
            listener = newListener;
        }

        private void bound() {
            listener.onBound();
        }

        private void failed() {
            listener.onFailure(new IllegalStateException("camera bind failed"));
        }

        @Override
        public void close() {
            cleanupOrder.add("camera");
        }
    }

    private static final class FakePublication implements LiveSessionCoordinator.PublicationPort {
        private final List<String> cleanupOrder;
        private StreamDestination destination;
        private boolean configured;
        private boolean failStart;
        private int startCount;
        private int stopCount;
        private int disconnectCount;
        private int closeCount;
        private LiveSessionCoordinator.PublisherHealth health =
                LiveSessionCoordinator.PublisherHealth.unconfigured();
        private LiveSessionCoordinator.PublisherHealthListener healthListener =
                LiveSessionCoordinator.PublisherHealthListener.NO_OP;

        private FakePublication(List<String> cleanupOrder) {
            this.cleanupOrder = cleanupOrder;
        }

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public void configure(StreamDestination newDestination) {
            destination = newDestination;
            configured = true;
            health = new LiveSessionCoordinator.PublisherHealth(
                    LiveSessionCoordinator.PublisherState.CONFIGURED,
                    LiveSessionCoordinator.PublisherFailure.NONE, 0, 0L);
        }

        @Override
        public void start() {
            startCount++;
            if (failStart) {
                throw new IllegalStateException("redacted publisher start failure");
            }
            health = new LiveSessionCoordinator.PublisherHealth(
                    LiveSessionCoordinator.PublisherState.PUBLISHING,
                    LiveSessionCoordinator.PublisherFailure.NONE, 0, 0L);
        }

        @Override
        public LiveSessionCoordinator.PublisherHealth health() {
            return health;
        }

        @Override
        public void onNetworkDisconnected() {
            disconnectCount++;
            health = new LiveSessionCoordinator.PublisherHealth(
                    LiveSessionCoordinator.PublisherState.RECONNECTING,
                    LiveSessionCoordinator.PublisherFailure.NETWORK, 0, 0L);
        }

        @Override
        public void onCongestion() {
        }

        @Override
        public void setHealthListener(
                LiveSessionCoordinator.PublisherHealthListener listener) {
            healthListener = listener;
            listener.onHealthChanged(health);
        }

        private void emit(LiveSessionCoordinator.PublisherHealth updatedHealth) {
            health = updatedHealth;
            healthListener.onHealthChanged(updatedHealth);
        }

        @Override
        public void stop() {
            stopCount++;
        }

        @Override
        public void close() {
            closeCount++;
            cleanupOrder.add("publisher");
            if (destination != null) {
                destination.close();
            }
        }
    }
}
