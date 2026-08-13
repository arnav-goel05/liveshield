package com.liveshield.app.session;

import androidx.camera.core.CameraSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.liveshield.app.setup.SetupActivity;
import com.liveshield.app.diagnostics.AppDiagnostics;
import com.liveshield.privacy.decision.BoundedFrameDecisionStore;
import com.liveshield.privacy.decision.FrameDecisionStore;
import com.liveshield.privacy.host.DefaultHostSelectionController;
import com.liveshield.privacy.host.HostSelectionController;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.policy.DefaultPrivacyPolicyEngine;
import com.liveshield.privacy.policy.PriorityTwoPolicy;
import com.liveshield.privacy.policy.PriorityTwoPrivacyPolicyEngine;
import com.liveshield.privacy.policy.SensitiveFindingPolicy;
import com.liveshield.privacy.session.LiveSession;
import com.liveshield.privacy.session.LiveSessionStateMachine;
import com.liveshield.privacy.session.SessionHealth;
import com.liveshield.privacy.telemetry.PrivacySafeTelemetry;
import com.liveshield.transport.SanitizedAccessUnitBridge;
import com.liveshield.video.analysis.FaceAnalysisCoordinator;
import com.liveshield.video.analysis.VisionScheduler;
import com.liveshield.video.camera.CameraSessionController;
import com.liveshield.video.geometry.FrameTransform;
import com.liveshield.video.output.SanitizedVideoOutput;
import com.liveshield.video.render.PrivacySurfaceProcessor;
import com.liveshield.video.thermal.ThermalSafetyController;
import com.liveshield.vision.face.OfflineFaceAnalyzer;
import com.liveshield.vision.pii.OfflineBarcodeAnalyzer;
import com.liveshield.vision.pii.OfflineTextAnalyzer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Production, local-only composition root for the protected-start graph. */
public final class SetupSessionFactory {
    private static final long MAX_AGE_NANOS = 100_000_000L;

    private SetupSessionFactory() {
    }

    public static PendingCreation create(SetupActivity activity, CreationListener listener) {
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(listener, "listener");
        AtomicBoolean cancelled = new AtomicBoolean();
        AppDiagnostics.info(AppDiagnostics.Event.SESSION_FACTORY_REQUESTED);
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(activity);
        Executor mainExecutor = ContextCompat.getMainExecutor(activity);
        future.addListener(() -> {
            if (cancelled.get()) {
                return;
            }
            try {
                AppDiagnostics.info(AppDiagnostics.Event.CAMERA_PROVIDER_READY);
                listener.onCreated(compose(activity, future.get(), mainExecutor));
            } catch (Exception exception) {
                AppDiagnostics.failure(AppDiagnostics.Event.SESSION_FACTORY_FAILED, exception);
                listener.onFailure(exception);
            }
        }, mainExecutor);
        return () -> cancelled.set(true);
    }

    private static LiveSessionCoordinator compose(
            SetupActivity activity,
            ProcessCameraProvider provider,
            Executor mainExecutor) {
        ExecutorService renderExecutor = singleThread("LiveShield-Privacy-Render");
        ExecutorService analysisExecutor = singleThread("LiveShield-Face-Analysis");
        try {
            AppDiagnostics.info(AppDiagnostics.Event.SESSION_GRAPH_COMPOSE_STARTED);
            return composeGraph(
                    activity, provider, mainExecutor, renderExecutor, analysisExecutor);
        } catch (RuntimeException exception) {
            analysisExecutor.shutdown();
            renderExecutor.shutdown();
            throw exception;
        }
    }

    private static LiveSessionCoordinator composeGraph(
            SetupActivity activity,
            ProcessCameraProvider provider,
            Executor mainExecutor,
            ExecutorService renderExecutor,
            ExecutorService analysisExecutor) {
        List<AutoCloseable> constructed = new ArrayList<>();
        try {
        FrameDecisionStore decisions = new BoundedFrameDecisionStore(
                PrivacySurfaceProcessor.MAX_RAW_TEXTURES, MAX_AGE_NANOS);
        HostSelectionController hostSelection =
                new DefaultHostSelectionController(MAX_AGE_NANOS);
        FaceAnalysisCoordinator faces = new FaceAnalysisCoordinator();
        AtomicReference<LiveSessionCoordinator> coordinatorRef = new AtomicReference<>();
        AtomicBoolean transformReady = new AtomicBoolean();
        AtomicReference<FrameTransform> previewTransform = new AtomicReference<>();
        activity.markPrivacyZoneTransformUnsafe();
        FrameTransform shieldOnlyPlaceholder = FrameTransform.fromCameraMetadata(
                CoordinateTransform.identity(), new NormalizedRect(0.0, 0.0, 1.0, 1.0),
                0, false);
        PrivacySurfaceProcessor processor = new PrivacySurfaceProcessor(
                renderExecutor,
                decisions,
                shieldOnlyPlaceholder,
                failure -> forwardFailure(coordinatorRef,
                        LiveSessionStateMachine.RequiredComponent.RENDERER, failure),
                readiness -> withCoordinator(coordinatorRef,
                        coordinator -> coordinator.onRendererReadiness(readiness)),
                transform -> {
                    previewTransform.set(transform);
                    activity.acceptVerifiedPrivacyZoneTransform();
                    transformReady.set(true);
                },
                timestamp -> {
                    LiveSessionCoordinator coordinator = coordinatorRef.get();
                    if (coordinator == null) {
                        throw new IllegalStateException("Coordinator unavailable");
                    }
                    coordinator.materializeRendererDecision(timestamp);
                });
        constructed.add(processor);
        ProductionSafetyHealth safetyHealth = new ProductionSafetyHealth(
                processor::rawQueueDepth, processor::recoveryState);
        ThermalSafetyController thermal = new ThermalSafetyController(
                activity,
                mainExecutor,
                new PrivacySafeTelemetry(64),
                state -> {
                    AppDiagnostics.state(AppDiagnostics.Event.THERMAL_STATE, state);
                    safetyHealth.updateThermal(state);
                    if (state == SessionHealth.ThermalState.SEVERE) {
                        processor.invalidateForSafety();
                    }
                    withCoordinator(coordinatorRef,
                            LiveSessionCoordinator::onSafetyHealthChanged);
                });
        constructed.add(thermal);
        AtomicReference<SanitizedVideoOutput> outputReference = new AtomicReference<>();
        SessionPublicationPort publication = new SessionPublicationPort(
                SessionPublicationPort.DEFAULT_MAX_QUEUE_BYTES,
                1280,
                720,
                SanitizedVideoOutput.EncoderSettings.defaults().frameRate(),
                () -> {
                    SanitizedVideoOutput current = outputReference.get();
                    if (current != null) {
                        current.requestKeyFrame();
                    }
                });
        constructed.add(publication);
        SanitizedAccessUnitBridge transportBridge = new SanitizedAccessUnitBridge(
                processor.sanitizedOutputCapability(), processor, publication);
        SanitizedVideoOutput output = new SanitizedVideoOutput(
                processor.sanitizedOutputCapability(),
                transportBridge,
                failure -> forwardFailure(coordinatorRef,
                        LiveSessionStateMachine.RequiredComponent.ENCODER, failure),
                SanitizedVideoOutput.EncoderSettings.defaults(),
                (state, ready) -> withCoordinator(coordinatorRef,
                        coordinator -> coordinator.onEncoderState(state, ready)));
        outputReference.set(output);
        constructed.add(output);
        OfflineFaceAnalyzer faceAnalyzer = new OfflineFaceAnalyzer(activity);
        constructed.add(faceAnalyzer);
        OfflineTextAnalyzer textAnalyzer = new OfflineTextAnalyzer(
                activity,
                OfflineTextAnalyzer.Configuration.defaults(
                        activity.sessionPrivacyConfiguration().normalizedWatchlistTerms()));
        activity.setPrivacyConfigurationListener(textAnalyzer::updateNormalizedWatchlistTerms);
        constructed.add(textAnalyzer);
        OfflineBarcodeAnalyzer barcodeAnalyzer = new OfflineBarcodeAnalyzer();
        constructed.add(barcodeAnalyzer);
        PriorityTwoPrivacyPolicyEngine policy = priorityTwoPolicy();
        VisionScheduler scheduler = new VisionScheduler(
                // OCR/watchlist recognition is formally unsupported after T119 and is excluded
                // from production authorization. Do not spend the live 100 ms face budget on a
                // detector whose output cannot authorize regional rendering.
                VisionScheduler.Configuration.defaults().withEnabledLanes(
                        Set.of(DetectorLane.FACE, DetectorLane.BARCODE)),
                new VisionAnalyzerLaneAdapter(faceAnalyzer, Runnable::run),
                new VisionAnalyzerLaneAdapter(textAnalyzer, Runnable::run),
                new VisionAnalyzerLaneAdapter(barcodeAnalyzer, Runnable::run),
                snapshot -> {
                    if (snapshot.failure().isPresent()) {
                        AppDiagnostics.state(
                                AppDiagnostics.Event.DETECTOR_FAILURE, snapshot.lane());
                    } else {
                        AppDiagnostics.stateCount(
                                AppDiagnostics.Event.DETECTOR_SNAPSHOT,
                                snapshot.lane(), snapshot.observations().size());
                    }
                    withCoordinator(coordinatorRef, value -> value.onDetectorSnapshot(snapshot));
                    if (snapshot.lane() == DetectorLane.FACE) {
                        withCoordinator(coordinatorRef, value -> value.onFaceState(
                                faces.accept(snapshot, hostSelection)));
                    }
                },
                (event, lane, timestamp) -> { },
                System::nanoTime);
        ScheduledImageAnalyzer analyzer = new ScheduledImageAnalyzer(
                scheduler,
                () -> withCoordinator(
                        coordinatorRef, LiveSessionCoordinator::onAnalysisUnavailable),
                safetyHealth::thermalState,
                state -> {
                    safetyHealth.updateScene(state);
                    if (state == SessionHealth.SceneState.CHANGED) {
                        AppDiagnostics.state(AppDiagnostics.Event.SCENE_CHANGED, state);
                        withCoordinator(coordinatorRef,
                                LiveSessionCoordinator::onSafetyHealthChanged);
                    }
                });
        constructed.add(analyzer);
        RendererOwnedPreview preview =
                new RendererOwnedPreview(activity.sanitizedPreviewContainer());
        CameraSessionController camera = new CameraSessionController(
                provider, activity, CameraSelector.DEFAULT_FRONT_CAMERA,
                processor.cameraEffect(), preview.surfaceProvider(), analyzer, output,
                mainExecutor, analysisExecutor);
        constructed.add(camera);
        analyzer.updateCameraGeometry(processor.selectedCameraGeometry());
        CameraSessionGraph cameraGraph = new CameraSessionGraph(
                camera, processor, output, transformReady::get, mainExecutor);
        LiveSessionCoordinator coordinator = new LiveSessionCoordinator(
                new LiveSessionStateMachine(LiveSession.setup(
                        UUID.randomUUID().toString(), Set.of(), List.of())),
                hostSelection,
                decisions,
                new OutputMappedSetupView(activity, previewTransform::get),
                preview,
                cameraGraph,
                output::close,
                () -> {
                    thermal.close();
                    processor.close();
                    renderExecutor.shutdown();
                },
                () -> {
                    activity.setPrivacyConfigurationListener(null);
                    analyzer.close();
                    faceAnalyzer.close();
                    textAnalyzer.close();
                    barcodeAnalyzer.close();
                    faces.resetSession();
                    analysisExecutor.shutdown();
                },
                System::nanoTime,
                mainExecutor,
                cameraGraph::readinessSnapshot,
                publication,
                policy,
                activity::sessionPrivacyConfiguration,
                policy::reset,
                new ProductionLiveSessionUi(activity),
                safetyHealth);
        coordinatorRef.set(coordinator);
        AppDiagnostics.info(AppDiagnostics.Event.SESSION_GRAPH_COMPOSED);
        return coordinator;
        } catch (RuntimeException exception) {
            AppDiagnostics.failure(AppDiagnostics.Event.SESSION_FACTORY_FAILED, exception);
            activity.setPrivacyConfigurationListener(null);
            for (int index = constructed.size() - 1; index >= 0; index--) {
                closeQuietly(constructed.get(index));
            }
            throw exception;
        }
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception ignored) {
            // The original construction failure remains authoritative.
        }
    }

    private static ExecutorService singleThread(String name) {
        return Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, name));
    }

    private static PriorityTwoPrivacyPolicyEngine priorityTwoPolicy() {
        SensitiveFindingPolicy findings = new SensitiveFindingPolicy(
                new SensitiveFindingPolicy.Configuration(
                        // Offline text recognition is an explicitly unsupported boundary after
                        // the frozen DEVELOPMENT evaluation. Do not let that unavailable lane
                        // make supported face, barcode, and configured-zone preview unusable.
                        Set.of(DetectorLane.BARCODE),
                        750_000_000L,
                        1_000_000_000L,
                        0.25,
                        256,
                        512,
                        512,
                        16));
        return new PriorityTwoPrivacyPolicyEngine(
                new DefaultPrivacyPolicyEngine(),
                new PriorityTwoPolicy(findings));
    }

    private static void forwardFailure(
            AtomicReference<LiveSessionCoordinator> reference,
            LiveSessionStateMachine.RequiredComponent component,
            Throwable failure) {
        withCoordinator(reference, coordinator ->
                coordinator.onComponentFailure(component, failure));
    }

    private static void withCoordinator(
            AtomicReference<LiveSessionCoordinator> reference,
            java.util.function.Consumer<LiveSessionCoordinator> action) {
        LiveSessionCoordinator coordinator = reference.get();
        if (coordinator != null) {
            action.accept(coordinator);
        }
    }

    public interface CreationListener {
        void onCreated(LiveSessionCoordinator coordinator);

        void onFailure(Throwable failure);
    }

    public interface PendingCreation {
        void cancel();
    }

}
