package com.liveshield.app.session;

import android.util.Size;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import com.liveshield.app.diagnostics.AppDiagnostics;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.session.SessionHealth;
import com.liveshield.video.analysis.VisionScheduler;
import com.liveshield.video.geometry.CameraGeometry;
import com.liveshield.video.geometry.FrameTransform;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** CameraX bridge into the bounded three-lane scheduler; no pixels leave detector leases. */
final class ScheduledImageAnalyzer implements ImageAnalysis.Analyzer, AutoCloseable {
    private final VisionScheduler scheduler;
    private final Runnable unavailableCallback;
    private final AtomicReference<CameraGeometry> cameraGeometry = new AtomicReference<>();
    private final Supplier<SessionHealth.ThermalState> thermalState;
    private final Consumer<SessionHealth.SceneState> sceneListener;
    private final SceneChangeDetector sceneDetector;
    private final AtomicBoolean resolutionReported = new AtomicBoolean();
    private final AtomicBoolean transformReported = new AtomicBoolean();
    private boolean closed;

    ScheduledImageAnalyzer(VisionScheduler scheduler, Runnable unavailableCallback) {
        this(scheduler, unavailableCallback, () -> SessionHealth.ThermalState.SEVERE,
                ignored -> { });
    }

    ScheduledImageAnalyzer(
            VisionScheduler scheduler,
            Runnable unavailableCallback,
            Supplier<SessionHealth.ThermalState> thermalState,
            Consumer<SessionHealth.SceneState> sceneListener) {
        this(scheduler, unavailableCallback, thermalState, sceneListener,
                new SceneChangeDetector());
    }

    ScheduledImageAnalyzer(
            VisionScheduler scheduler,
            Runnable unavailableCallback,
            Supplier<SessionHealth.ThermalState> thermalState,
            Consumer<SessionHealth.SceneState> sceneListener,
            SceneChangeDetector sceneDetector) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.unavailableCallback = Objects.requireNonNull(
                unavailableCallback, "unavailableCallback");
        this.thermalState = Objects.requireNonNull(thermalState, "thermalState");
        this.sceneListener = Objects.requireNonNull(sceneListener, "sceneListener");
        this.sceneDetector = Objects.requireNonNull(sceneDetector, "sceneDetector");
    }

    void updateCameraGeometry(CameraGeometry geometry) {
        cameraGeometry.set(Objects.requireNonNull(geometry, "geometry"));
    }

    @Override
    public synchronized void analyze(ImageProxy image) {
        Objects.requireNonNull(image, "image");
        CameraGeometry geometry = cameraGeometry.get();
        long nanos = image.getImageInfo().getTimestamp();
        if (closed || geometry == null || nanos < 0L) {
            image.close();
            unavailableCallback.run();
            return;
        }
        ImageProxyVisionFrame frame = null;
        try {
            if (resolutionReported.compareAndSet(false, true)) {
                AppDiagnostics.dimensions(
                        AppDiagnostics.Event.ANALYSIS_RESOLUTION,
                        image.getWidth(), image.getHeight());
            }
            CoordinateTransform transform = FrameTransform.normalizePixelSensorToBuffer(
                    geometry,
                    new Size(image.getWidth(), image.getHeight()),
                    image.getImageInfo().getSensorToBufferTransformMatrix());
            if (transformReported.compareAndSet(false, true)) {
                AppDiagnostics.matrix(
                        AppDiagnostics.Event.ANALYSIS_SENSOR_TO_BUFFER,
                        transform.matrix());
            }
            frame = new ImageProxyVisionFrame(
                    image,
                    FrameTimestamp.ofNanos(nanos),
                    image.getImageInfo().getRotationDegrees(),
                    transform);
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            SessionHealth.SceneState scene = sceneDetector.observe(
                    plane.getBuffer(), image.getWidth(), image.getHeight(),
                    plane.getRowStride(), plane.getPixelStride());
            sceneListener.accept(scene);
            scheduler.submit(
                    frame,
                    thermalState.get(),
                    scene);
        } catch (RuntimeException failure) {
            if (frame == null) {
                image.close();
            } else {
                frame.close();
            }
            unavailableCallback.run();
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            sceneDetector.reset();
            scheduler.close();
        }
    }
}
