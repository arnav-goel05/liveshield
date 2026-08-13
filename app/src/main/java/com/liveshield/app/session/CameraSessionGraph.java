package com.liveshield.app.session;

import com.google.common.util.concurrent.ListenableFuture;
import com.liveshield.video.camera.CameraSessionController;
import com.liveshield.video.output.SanitizedVideoOutput;
import com.liveshield.video.render.PrivacySurfaceProcessor;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Adapts CameraX bind completion without exposing any camera surface to the coordinator. */
public final class CameraSessionGraph implements LiveSessionCoordinator.CameraGraph {
    private final CameraSessionController controller;
    private final PrivacySurfaceProcessor processor;
    private final SanitizedVideoOutput output;
    private final BooleanSupplier transformReady;
    private final Executor callbackExecutor;

    public CameraSessionGraph(
            CameraSessionController controller,
            PrivacySurfaceProcessor processor,
            SanitizedVideoOutput output,
            BooleanSupplier transformReady,
            Executor callbackExecutor) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.output = Objects.requireNonNull(output, "output");
        this.transformReady = Objects.requireNonNull(transformReady, "transformReady");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
    }

    /** Returns payload-free milestones; no camera surface, frame, or encoded data is exposed. */
    public ReadinessSnapshot readinessSnapshot() {
        return new ReadinessSnapshot(
                controller.state(),
                transformReady.getAsBoolean(),
                processor.readiness(),
                output.state(),
                output.isEncoderReady());
    }

    @Override
    public void bind(BindingListener listener) {
        Objects.requireNonNull(listener, "listener");
        ListenableFuture<Void> binding = controller.bindAsync();
        binding.addListener(() -> reportCompletion(binding, listener), callbackExecutor);
    }

    private static void reportCompletion(
            ListenableFuture<Void> binding, BindingListener listener) {
        try {
            binding.get();
            listener.onBound();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            listener.onFailure(exception);
        } catch (CancellationException | ExecutionException exception) {
            Throwable cause = exception instanceof ExecutionException
                    && exception.getCause() != null ? exception.getCause() : exception;
            listener.onFailure(cause);
        }
    }

    @Override
    public void close() {
        controller.close();
    }

    /** Immutable metadata-only runtime evidence for integration diagnostics. */
    public record ReadinessSnapshot(
            CameraSessionController.State cameraState,
            boolean transformReady,
            PrivacySurfaceProcessor.Readiness rendererReadiness,
            SanitizedVideoOutput.State encoderState,
            boolean encoderReady) {
    }
}
