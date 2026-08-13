package com.liveshield.app.session;

import com.liveshield.app.setup.SelectableFace;
import com.liveshield.app.setup.SetupUiListener;
import com.liveshield.app.setup.SetupView;
import com.liveshield.privacy.decision.FrameDecisionStore;
import com.liveshield.privacy.decision.FramePrivacyDecision;
import com.liveshield.privacy.host.HostSelectionController;
import com.liveshield.privacy.host.HostSelectionResult;
import com.liveshield.privacy.model.FaceTrackSnapshot;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedPoint;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.session.LiveSession;
import com.liveshield.privacy.session.LiveSessionStateMachine;
import com.liveshield.privacy.session.SessionState;
import com.liveshield.privacy.session.SessionHealth;
import com.liveshield.privacy.policy.PrivacyPolicyEngine;
import com.liveshield.privacy.policy.SessionPrivacyConfigurationView;
import com.liveshield.video.analysis.FaceAnalysisCoordinator;
import com.liveshield.video.output.SanitizedVideoOutput;
import com.liveshield.video.render.PrivacySurfaceProcessor;
import com.liveshield.transport.destination.StreamDestination;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Serializes protected-session UI and lifecycle state without owning a raw camera surface.
 *
 * <p>The injected preview port may attach only the CameraX Preview output that is downstream of the
 * exact {@link PrivacySurfaceProcessor} effect. Publication is isolated behind a video-only port
 * that exposes only configured/health/lifecycle control, never endpoints or secrets.</p>
 */
public final class LiveSessionCoordinator
        implements SetupUiListener, AutoCloseable {
    private static final long FACE_DECISION_VALIDITY_NANOS = 100_000_000L;

    private final LiveSessionStateMachine stateMachine;
    private final HostSelectionController hostSelection;
    private final FrameDecisionStore decisionStore;
    private final SetupView setupView;
    private final SanitizedPreviewPort sanitizedPreview;
    private final CameraGraph cameraGraph;
    private final SessionResource sanitizedOutput;
    private final SessionResource privacyRenderer;
    private final SessionReset faceSession;
    private final MonotonicClock clock;
    private final Executor serializedExecutor;
    private final ReadinessProbe readinessProbe;
    private final PublicationPort publication;
    private final PrivacyPolicyEngine privacyPolicy;
    private final Supplier<SessionPrivacyConfigurationView> privacyConfiguration;
    private final SessionReset policySession;
    private final SessionUiPort sessionUi;
    private final SafetyHealthProbe safetyHealth;
    private final HostReselectionController hostReselection =
            new HostReselectionController();
    private List<FaceTrackSnapshot> latestTracks = List.of();
    private FrameTimestamp latestFaceTimestamp = FrameTimestamp.ofNanos(0L);
    private boolean hasFaceState;
    private boolean rendererReady;
    private boolean publisherDegraded;
    private final Map<DetectorLane, DetectorSnapshot> detectorSnapshots =
            new EnumMap<>(DetectorLane.class);
    private boolean closed;

    public LiveSessionCoordinator(
            LiveSessionStateMachine stateMachine,
            HostSelectionController hostSelection,
            FrameDecisionStore decisionStore,
            SetupView setupView,
            SanitizedPreviewPort sanitizedPreview,
            CameraGraph cameraGraph,
            SessionResource sanitizedOutput,
            SessionResource privacyRenderer,
            SessionReset faceSession,
            MonotonicClock clock,
            Executor serializedExecutor) {
        this(stateMachine, hostSelection, decisionStore, setupView, sanitizedPreview,
                cameraGraph, sanitizedOutput, privacyRenderer, faceSession, clock,
                serializedExecutor, () -> null, PublicationPort.NO_OP);
    }

    public LiveSessionCoordinator(
            LiveSessionStateMachine stateMachine,
            HostSelectionController hostSelection,
            FrameDecisionStore decisionStore,
            SetupView setupView,
            SanitizedPreviewPort sanitizedPreview,
            CameraGraph cameraGraph,
            SessionResource sanitizedOutput,
            SessionResource privacyRenderer,
            SessionReset faceSession,
            MonotonicClock clock,
            Executor serializedExecutor,
            ReadinessProbe readinessProbe) {
        this(stateMachine, hostSelection, decisionStore, setupView, sanitizedPreview,
                cameraGraph, sanitizedOutput, privacyRenderer, faceSession, clock,
                serializedExecutor, readinessProbe, PublicationPort.NO_OP);
    }

    LiveSessionCoordinator(
            LiveSessionStateMachine stateMachine,
            HostSelectionController hostSelection,
            FrameDecisionStore decisionStore,
            SetupView setupView,
            SanitizedPreviewPort sanitizedPreview,
            CameraGraph cameraGraph,
            SessionResource sanitizedOutput,
            SessionResource privacyRenderer,
            SessionReset faceSession,
            MonotonicClock clock,
            Executor serializedExecutor,
            ReadinessProbe readinessProbe,
            PublicationPort publication) {
        this(stateMachine, hostSelection, decisionStore, setupView, sanitizedPreview,
                cameraGraph, sanitizedOutput, privacyRenderer, faceSession, clock,
                serializedExecutor, readinessProbe, publication, null, null, () -> { },
                SessionUiPort.NO_OP, SafetyHealthProbe.NOMINAL);
    }

    LiveSessionCoordinator(
            LiveSessionStateMachine stateMachine,
            HostSelectionController hostSelection,
            FrameDecisionStore decisionStore,
            SetupView setupView,
            SanitizedPreviewPort sanitizedPreview,
            CameraGraph cameraGraph,
            SessionResource sanitizedOutput,
            SessionResource privacyRenderer,
            SessionReset faceSession,
            MonotonicClock clock,
            Executor serializedExecutor,
            ReadinessProbe readinessProbe,
            PublicationPort publication,
            PrivacyPolicyEngine privacyPolicy,
            Supplier<SessionPrivacyConfigurationView> privacyConfiguration,
            SessionReset policySession) {
        this(stateMachine, hostSelection, decisionStore, setupView, sanitizedPreview, cameraGraph,
                sanitizedOutput, privacyRenderer, faceSession, clock, serializedExecutor,
                readinessProbe, publication, privacyPolicy, privacyConfiguration, policySession,
                SessionUiPort.NO_OP, SafetyHealthProbe.NOMINAL);
    }

    LiveSessionCoordinator(
            LiveSessionStateMachine stateMachine,
            HostSelectionController hostSelection,
            FrameDecisionStore decisionStore,
            SetupView setupView,
            SanitizedPreviewPort sanitizedPreview,
            CameraGraph cameraGraph,
            SessionResource sanitizedOutput,
            SessionResource privacyRenderer,
            SessionReset faceSession,
            MonotonicClock clock,
            Executor serializedExecutor,
            ReadinessProbe readinessProbe,
            PublicationPort publication,
            PrivacyPolicyEngine privacyPolicy,
            Supplier<SessionPrivacyConfigurationView> privacyConfiguration,
            SessionReset policySession,
            SessionUiPort sessionUi) {
        this(stateMachine, hostSelection, decisionStore, setupView, sanitizedPreview, cameraGraph,
                sanitizedOutput, privacyRenderer, faceSession, clock, serializedExecutor,
                readinessProbe, publication, privacyPolicy, privacyConfiguration, policySession,
                sessionUi, SafetyHealthProbe.NOMINAL);
    }

    LiveSessionCoordinator(
            LiveSessionStateMachine stateMachine,
            HostSelectionController hostSelection,
            FrameDecisionStore decisionStore,
            SetupView setupView,
            SanitizedPreviewPort sanitizedPreview,
            CameraGraph cameraGraph,
            SessionResource sanitizedOutput,
            SessionResource privacyRenderer,
            SessionReset faceSession,
            MonotonicClock clock,
            Executor serializedExecutor,
            ReadinessProbe readinessProbe,
            PublicationPort publication,
            PrivacyPolicyEngine privacyPolicy,
            Supplier<SessionPrivacyConfigurationView> privacyConfiguration,
            SessionReset policySession,
            SessionUiPort sessionUi,
            SafetyHealthProbe safetyHealth) {
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.hostSelection = Objects.requireNonNull(hostSelection, "hostSelection");
        this.decisionStore = Objects.requireNonNull(decisionStore, "decisionStore");
        this.setupView = Objects.requireNonNull(setupView, "setupView");
        this.sanitizedPreview = Objects.requireNonNull(sanitizedPreview, "sanitizedPreview");
        this.cameraGraph = Objects.requireNonNull(cameraGraph, "cameraGraph");
        this.sanitizedOutput = Objects.requireNonNull(sanitizedOutput, "sanitizedOutput");
        this.privacyRenderer = Objects.requireNonNull(privacyRenderer, "privacyRenderer");
        this.faceSession = Objects.requireNonNull(faceSession, "faceSession");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.serializedExecutor = Objects.requireNonNull(serializedExecutor, "serializedExecutor");
        this.readinessProbe = Objects.requireNonNull(readinessProbe, "readinessProbe");
        this.publication = Objects.requireNonNull(publication, "publication");
        this.privacyPolicy = privacyPolicy;
        this.privacyConfiguration = privacyConfiguration;
        this.policySession = Objects.requireNonNull(policySession, "policySession");
        this.sessionUi = Objects.requireNonNull(sessionUi, "sessionUi");
        this.safetyHealth = Objects.requireNonNull(safetyHealth, "safetyHealth");
        if ((privacyPolicy == null) != (privacyConfiguration == null)) {
            throw new IllegalArgumentException("Policy and configuration must be installed together");
        }
        publication.setHealthListener(this::onPublisherHealthSnapshot);
    }

    /** Attaches the sanitized preview target before binding the privacy-effect camera graph. */
    public void begin() {
        dispatch(() -> {
            if (closed) {
                return;
            }
            setupView.showPrivacyReady(false);
            try {
                sanitizedPreview.attach();
                cameraGraph.bind(new CameraGraph.BindingListener() {
                    @Override
                    public void onBound() {
                        dispatch(() -> setComponentReady(
                                LiveSessionStateMachine.RequiredComponent.CAMERA, true));
                    }

                    @Override
                    public void onFailure(Throwable failure) {
                        dispatch(() -> failAndStop(
                                LiveSessionStateMachine.RequiredComponent.CAMERA, failure));
                    }
                });
            } catch (RuntimeException exception) {
                failAndStop(LiveSessionStateMachine.RequiredComponent.CAMERA, exception);
            }
        });
    }

    /** Consumes an immutable analysis snapshot; analyzer internals and pixels never enter the app. */
    public void onFaceState(FaceAnalysisCoordinator.FaceFrameState faceState) {
        Objects.requireNonNull(faceState, "faceState");
        dispatch(() -> applyFaceState(faceState));
    }

    /** Records one payload-free detector snapshot for the next exact face-frame policy decision. */
    public void onDetectorSnapshot(DetectorSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        dispatch(() -> {
            if (!closed) {
                detectorSnapshots.put(snapshot.lane(), snapshot);
            }
        });
    }

    /** Keeps startup or a dropped analysis frame shielded without making recovery impossible. */
    public void onAnalysisUnavailable() {
        dispatch(() -> setComponentReady(
                LiveSessionStateMachine.RequiredComponent.ANALYSIS, false));
    }

    /** Applies payload-free thermal/scene/recovery evidence immediately to the private lifecycle. */
    public void onSafetyHealthChanged() {
        dispatch(() -> {
            if (closed) {
                return;
            }
            SafetyHealthSnapshot safety = safetyHealth.snapshot();
            if (safety.thermalState() == SessionHealth.ThermalState.SEVERE
                    || safety.recoveryState() == SessionHealth.RecoveryState.UNSAFE
                    || safety.rawQueueDepth() >= PrivacySurfaceProcessor.MAX_RAW_TEXTURES
                    || safety.sceneState() == SessionHealth.SceneState.CHANGED) {
                stateMachine.enterShielding(now());
            } else if (safety.thermalState() == SessionHealth.ThermalState.WARNING) {
                stateMachine.enterDegraded(now());
            }
            sessionUi.onSessionStateChanged(stateMachine.snapshot().state());
        });
    }

    /** Callback target for the renderer's non-pixel readiness listener. */
    public void onRendererReadiness(PrivacySurfaceProcessor.Readiness readiness) {
        Objects.requireNonNull(readiness, "readiness");
        dispatch(() -> {
            boolean ready = readiness == PrivacySurfaceProcessor.Readiness.READY;
            rendererReady = ready;
            stopPublicationOnPrivacyLoss(ready);
            setComponentReady(LiveSessionStateMachine.RequiredComponent.RENDERER, ready);
        });
    }

    /** Callback target for the encoder's payload-free lifecycle listener. */
    public void onEncoderState(SanitizedVideoOutput.State state, boolean encoderReady) {
        Objects.requireNonNull(state, "state");
        dispatch(() -> {
            stopPublicationOnPrivacyLoss(encoderReady);
            setComponentReady(LiveSessionStateMachine.RequiredComponent.ENCODER, encoderReady);
            if (state == SanitizedVideoOutput.State.FAILED) {
                failAndStop(
                        LiveSessionStateMachine.RequiredComponent.ENCODER,
                        new IllegalStateException("Sanitized encoder failed"));
            }
        });
    }

    /** Any component error is handled without retaining its potentially sensitive detail in UI. */
    public void onComponentFailure(
            LiveSessionStateMachine.RequiredComponent component, Throwable failure) {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(failure, "failure");
        dispatch(() -> failAndStop(component, failure));
    }

    @Override
    public void onHostSelectionRequested(long trackId) {
        dispatch(() -> selectFreshHost(trackId));
    }

    @Override
    public void onStartRequested() {
        dispatch(() -> {
            if (closed
                    || stateMachine.snapshot().state() != SessionState.READY
                    || !publication.isConfigured()) {
                setupView.showPrivacyReady(false);
                return;
            }
            try {
                publication.start();
                stateMachine.start(now());
                applyPublisherHealth(publication.health());
                if (closed) {
                    return;
                }
                sessionUi.onSessionStarted(stateMachine.snapshot().state(), this::close);
                refreshReadiness();
            } catch (RuntimeException failure) {
                safeStop(true);
            }
        });
    }

    /** Transfers session-scoped destination ownership without exposing it through health/UI. */
    public void configurePublication(StreamDestination destination) {
        Objects.requireNonNull(destination, "destination");
        dispatch(() -> {
            if (closed) {
                destination.close();
                return;
            }
            publication.configure(destination);
        });
    }

    /** Private publisher status, kept separate from privacy readiness and session health. */
    PublisherHealth publisherHealth() {
        return publication.health();
    }

    private void onPublisherHealthSnapshot(PublisherHealth health) {
        Objects.requireNonNull(health, "health");
        dispatch(() -> applyPublisherHealth(health));
    }

    private void applyPublisherHealth(PublisherHealth health) {
        if (closed) {
            return;
        }
        sessionUi.onPublisherHealthChanged(health);
        SessionState current = stateMachine.snapshot().state();
        if (current != SessionState.LIVE && current != SessionState.DEGRADED) {
            return;
        }
        if (health.state() == PublisherState.FAILED
                || health.state() == PublisherState.STOPPED) {
            safeStop(true);
            return;
        }
        if (health.state() == PublisherState.CONNECTING
                || health.state() == PublisherState.RECONNECTING
                || health.failure() == PublisherFailure.NETWORK
                || health.failure() == PublisherFailure.CONGESTION
                || !health.freshMediaReady()) {
            publisherDegraded = true;
            stateMachine.enterDegraded(now());
            sessionUi.onSessionStateChanged(stateMachine.snapshot().state());
            return;
        }
        if (publisherDegraded
                && health.state() == PublisherState.PUBLISHING
                && health.failure() == PublisherFailure.NONE
                && health.freshMediaReady()
                && publisherRecoveryIsSafe()) {
            stateMachine.recoverDegraded(now());
            publisherDegraded = false;
            sessionUi.onSessionStateChanged(stateMachine.snapshot().state());
        }
    }

    private boolean publisherRecoveryIsSafe() {
        SafetyHealthSnapshot safety = safetyHealth.snapshot();
        return safety.rawQueueDepth() < PrivacySurfaceProcessor.MAX_RAW_TEXTURES
                && safety.recoveryState() != SessionHealth.RecoveryState.UNSAFE
                && safety.thermalState() == SessionHealth.ThermalState.NOMINAL
                && safety.sceneState() == SessionHealth.SceneState.STABLE;
    }

    public void onPublisherNetworkDisconnected() {
        dispatch(() -> {
            if (!closed && stateMachine.snapshot().state() == SessionState.LIVE) {
                publication.onNetworkDisconnected();
                applyPublisherHealth(publication.health());
            }
        });
    }

    public void onPublisherCongestion() {
        dispatch(() -> {
            if (!closed && stateMachine.snapshot().state() == SessionState.LIVE) {
                publication.onCongestion();
                applyPublisherHealth(publication.health());
            }
        });
    }

    public LiveSession snapshot() {
        return stateMachine.snapshot();
    }

    /** Test-only payload-free integration milestones; production readiness still uses callbacks. */
    public CameraSessionGraph.ReadinessSnapshot readinessSnapshotForTest() {
        return readinessProbe.snapshot();
    }

    @Override
    public void close() {
        dispatch(this::safeStop);
    }

    private void applyFaceState(FaceAnalysisCoordinator.FaceFrameState faceState) {
        if (closed) {
            return;
        }
        if (hasFaceState && faceState.timestamp().compareTo(latestFaceTimestamp) <= 0) {
            failAndStop(
                    LiveSessionStateMachine.RequiredComponent.ANALYSIS,
                    new IllegalStateException("Duplicate or out-of-order face state"));
            return;
        }
        hasFaceState = true;
        latestFaceTimestamp = faceState.timestamp();
        if (faceState.fullShieldRequired()) {
            decisionStore.store(FramePrivacyDecision.fullShield(
                    faceState.timestamp(), FramePrivacyDecision.Basis.ERROR));
            detectorSnapshots.clear();
            policySession.reset();
            latestTracks = List.of();
            boolean hostWasSelected = hostSelection.selectedTrackId().isPresent();
            hostSelection.revokeSelection();
            stateMachine.revokeHost();
            if (hostWasSelected || faceState.hostContinuityLost()) {
                requireHostReselection();
            }
            setComponentReady(LiveSessionStateMachine.RequiredComponent.ANALYSIS, false);
            setupView.showSelectableFaces(List.of(), null);
            return;
        }
        FramePrivacyDecision decision = privacyPolicy == null
                ? FramePrivacyDecision.regionalSafe(
                        faceState.timestamp(),
                        faceState.protectedRegions(),
                        FramePrivacyDecision.Basis.FRESH,
                        faceState.timestamp().plusNanos(FACE_DECISION_VALIDITY_NANOS))
                : privacyPolicy.decide(
                        faceState.timestamp(),
                        List.copyOf(detectorSnapshots.values()),
                        faceState.tracks(),
                        privacyConfiguration.get(),
                        currentHealth(faceState.timestamp()));
        decisionStore.store(decision);
        latestTracks = faceState.tracks();
        if (faceState.hostContinuityLost()) {
            hostSelection.revokeSelection();
            stateMachine.revokeHost();
            requireHostReselection();
        }
        setComponentReady(
                LiveSessionStateMachine.RequiredComponent.ANALYSIS,
                decision.status() == FramePrivacyDecision.Status.REGIONAL_SAFE);
        if (decision.status() == FramePrivacyDecision.Status.REGIONAL_SAFE
                && stateMachine.canResumeLive()) {
            stateMachine.resumeLive(now());
        }
        OptionalLong selected = hostSelection.selectedTrackId();
        Long selectedTrack = selected.isPresent() ? selected.getAsLong() : null;
        setupView.showSelectableFaces(toSelectableFaces(latestTracks), selectedTrack);
        refreshReadiness();
    }

    private void selectFreshHost(long trackId) {
        if (closed) {
            return;
        }
        FaceTrackSnapshot selected = latestTracks.stream()
                .filter(track -> track.trackId() == trackId)
                .filter(track -> track.confidenceState() == FaceTrackSnapshot.ConfidenceState.FRESH)
                .findFirst()
                .orElse(null);
        HostSelectionResult result;
        if (selected == null) {
            hostSelection.revokeSelection();
            result = new HostSelectionResult(
                    HostSelectionResult.Status.STALE_FACE, OptionalLong.empty());
        } else {
            result = hostSelection.selectHost(
                    center(selected.bounds()), latestTracks, latestFaceTimestamp);
        }
        stateMachine.acceptHostSelection(result);
        hostReselection.onSelectionResult(result);
        setupView.showHostReselectionRequired(
                hostReselection.state() == HostReselectionController.State.REQUIRED);
        if (result.status() == HostSelectionResult.Status.SELECTED
                && stateMachine.canResumeLive()) {
            stateMachine.resumeLive(now());
        }
        Long selectedTrack = result.selectedTrackId().isPresent()
                ? result.selectedTrackId().getAsLong() : null;
        setupView.showSelectableFaces(toSelectableFaces(latestTracks), selectedTrack);
        refreshReadiness();
    }

    private void setComponentReady(
            LiveSessionStateMachine.RequiredComponent component, boolean ready) {
        if (closed) {
            return;
        }
        if (ready) {
            stateMachine.markComponentReady(component);
        } else {
            stateMachine.markComponentUnavailable(component);
        }
        refreshReadiness();
    }

    private void refreshReadiness() {
        if (closed) {
            setupView.showPrivacyReady(false);
            return;
        }
        stateMachine.tryBecomeReady(now());
        setupView.showPrivacyReady(stateMachine.snapshot().state() == SessionState.READY);
        sessionUi.onSessionStateChanged(stateMachine.snapshot().state());
    }

    private void failAndStop(
            LiveSessionStateMachine.RequiredComponent component, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (closed) {
            return;
        }
        stateMachine.markComponentUnavailable(component);
        safeStop(true);
    }

    private void stopPublicationOnPrivacyLoss(boolean componentReady) {
        if (!componentReady && stateMachine.snapshot().state() == SessionState.LIVE) {
            publication.stop();
        }
    }

    private void safeStop() {
        safeStop(false);
    }

    private void safeStop(boolean failed) {
        if (closed) {
            return;
        }
        closed = true;
        setupView.showPrivacyReady(false);
        RuntimeException cleanupFailure = null;
        try {
            stateMachine.stop(now());
        } catch (RuntimeException exception) {
            cleanupFailure = exception;
        }
        cleanupFailure = closeResource(publication, cleanupFailure);
        cleanupFailure = closeResource(cameraGraph, cleanupFailure);
        cleanupFailure = closeResource(sanitizedOutput, cleanupFailure);
        cleanupFailure = closeResource(privacyRenderer, cleanupFailure);
        cleanupFailure = closeResource(sanitizedPreview, cleanupFailure);
        try {
            faceSession.reset();
        } catch (RuntimeException exception) {
            cleanupFailure = combine(cleanupFailure, exception);
        }
        try {
            policySession.reset();
        } catch (RuntimeException exception) {
            cleanupFailure = combine(cleanupFailure, exception);
        }
        try {
            hostSelection.resetSession();
        } catch (RuntimeException exception) {
            cleanupFailure = combine(cleanupFailure, exception);
        }
        latestTracks = List.of();
        hasFaceState = false;
        rendererReady = false;
        detectorSnapshots.clear();
        decisionStore.clear();
        setupView.showSelectableFaces(List.of(), null);
        hostReselection.resetSession();
        setupView.showHostReselectionRequired(false);
        if (cleanupFailure != null) {
            setupView.onSafeStopFailure();
        }
        sessionUi.onSessionStateChanged(
                failed || cleanupFailure != null ? SessionState.FAILED : SessionState.ENDED);
    }

    private void dispatch(Runnable work) {
        serializedExecutor.execute(work);
    }

    private void requireHostReselection() {
        hostReselection.onContinuityLost();
        setupView.showHostReselectionRequired(true);
        setupView.showPrivacyReady(false);
    }

    private FrameTimestamp now() {
        return FrameTimestamp.ofNanos(Math.max(0L, clock.nanoTime()));
    }

    private SessionHealth currentHealth(FrameTimestamp timestamp) {
        SafetyHealthSnapshot safety = safetyHealth.snapshot();
        SessionState current = stateMachine.snapshot().state();
        SessionHealth.Builder health = SessionHealth.builder(
                        current == SessionState.SETUP ? SessionState.SHIELDING : current)
                .rendererState(rendererReady
                        ? SessionHealth.RendererState.READY
                        : SessionHealth.RendererState.FAILED)
                .rawQueueDepth(safety.rawQueueDepth())
                .recoveryState(safety.recoveryState())
                .thermalState(safety.thermalState())
                .sceneState(safety.sceneState());
        for (DetectorSnapshot snapshot : detectorSnapshots.values()) {
            long age = timestamp.nanos() - snapshot.sourceTimestamp().nanos();
            if (age >= 0L) {
                health.detectorLaneAgeNanos(snapshot.lane(), age);
            }
        }
        return health.build();
    }

    private static NormalizedPoint center(NormalizedRect bounds) {
        return new NormalizedPoint(
                (bounds.left() + bounds.right()) / 2.0,
                (bounds.top() + bounds.bottom()) / 2.0);
    }

    private static List<SelectableFace> toSelectableFaces(List<FaceTrackSnapshot> tracks) {
        return tracks.stream().map(track -> new SelectableFace(
                track.trackId(), track.bounds(),
                track.confidenceState() == FaceTrackSnapshot.ConfidenceState.FRESH)).toList();
    }

    private static RuntimeException closeResource(
            AutoCloseable resource, RuntimeException previous) {
        try {
            resource.close();
            return previous;
        } catch (Exception exception) {
            RuntimeException runtime = exception instanceof RuntimeException runtimeException
                    ? runtimeException : new IllegalStateException("Session cleanup failed", exception);
            return combine(previous, runtime);
        }
    }

    private static RuntimeException combine(
            RuntimeException previous, RuntimeException next) {
        if (previous == null) {
            return next;
        }
        previous.addSuppressed(next);
        return previous;
    }

    public interface SanitizedPreviewPort extends AutoCloseable {
        void attach();

        @Override
        void close();
    }

    public interface CameraGraph extends AutoCloseable {
        void bind(BindingListener listener);

        @Override
        void close();

        interface BindingListener {
            void onBound();

            void onFailure(Throwable failure);
        }
    }

    public interface SessionResource extends AutoCloseable {
        @Override
        void close();
    }

    public interface SessionReset {
        void reset();
    }

    interface SessionUiPort {
        SessionUiPort NO_OP = new SessionUiPort() {
            @Override
            public void onSessionStarted(SessionState state, Runnable stopAction) {
            }

            @Override
            public void onSessionStateChanged(SessionState state) {
            }
        };

        void onSessionStarted(SessionState state, Runnable stopAction);

        void onSessionStateChanged(SessionState state);

        default void onPublisherHealthChanged(PublisherHealth health) {
        }
    }

    record SafetyHealthSnapshot(
            int rawQueueDepth,
            SessionHealth.RecoveryState recoveryState,
            SessionHealth.ThermalState thermalState,
            SessionHealth.SceneState sceneState) {
    }

    @FunctionalInterface
    interface SafetyHealthProbe {
        SafetyHealthProbe NOMINAL = () -> new SafetyHealthSnapshot(
                0,
                SessionHealth.RecoveryState.SAFE,
                SessionHealth.ThermalState.NOMINAL,
                SessionHealth.SceneState.STABLE);

        SafetyHealthSnapshot snapshot();
    }

    public interface MonotonicClock {
        long nanoTime();
    }

    record PublisherHealth(
            PublisherState state,
            PublisherFailure failure,
            int queuedUnits,
            long droppedUnits,
            boolean freshMediaReady) {
        PublisherHealth(
                PublisherState state,
                PublisherFailure failure,
                int queuedUnits,
                long droppedUnits) {
            this(state, failure, queuedUnits, droppedUnits,
                    state == PublisherState.PUBLISHING);
        }

        public static PublisherHealth unconfigured() {
            return new PublisherHealth(
                    PublisherState.UNCONFIGURED,
                    PublisherFailure.NONE,
                    0,
                    0L,
                    false);
        }
    }

    enum PublisherState {
        UNCONFIGURED,
        CONFIGURED,
        CONNECTING,
        PUBLISHING,
        RECONNECTING,
        FAILED,
        STOPPED
    }

    enum PublisherFailure {
        NONE,
        CONNECTION,
        AUTHENTICATION,
        NETWORK,
        CONGESTION,
        QUEUE,
        PUBLICATION
    }

    interface PublicationPort extends AutoCloseable {
        PublicationPort NO_OP = new PublicationPort() {
            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public void configure(StreamDestination destination) {
                destination.close();
            }

            @Override
            public void start() {
            }

            @Override
            public PublisherHealth health() {
                return new PublisherHealth(
                        PublisherState.PUBLISHING, PublisherFailure.NONE, 0, 0L, true);
            }

            @Override
            public void onNetworkDisconnected() {
            }

            @Override
            public void onCongestion() {
            }

            @Override
            public void setHealthListener(PublisherHealthListener listener) {
                listener.onHealthChanged(health());
            }

            @Override
            public void stop() {
            }

            @Override
            public void close() {
            }
        };

        boolean isConfigured();

        void configure(StreamDestination destination);

        void start();

        PublisherHealth health();

        void onNetworkDisconnected();

        void onCongestion();

        default void setHealthListener(PublisherHealthListener listener) {
            listener.onHealthChanged(health());
        }

        /** Stops transport immediately without granting any privacy-health transition. */
        void stop();

        @Override
        void close();
    }

    @FunctionalInterface
    interface PublisherHealthListener {
        PublisherHealthListener NO_OP = health -> { };

        void onHealthChanged(PublisherHealth health);
    }

    @FunctionalInterface
    public interface ReadinessProbe {
        CameraSessionGraph.ReadinessSnapshot snapshot();
    }
}
