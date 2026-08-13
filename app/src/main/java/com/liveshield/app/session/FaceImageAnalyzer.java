package com.liveshield.app.session;

import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import android.util.Size;
import com.google.common.util.concurrent.ListenableFuture;
import com.liveshield.privacy.host.HostSelectionController;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.video.analysis.FaceAnalysisCoordinator;
import com.liveshield.video.geometry.CameraGeometry;
import com.liveshield.video.geometry.FrameTransform;
import com.liveshield.vision.contract.AnalysisFrameHandle;
import com.liveshield.vision.contract.VisionAnalyzer;
import com.liveshield.vision.face.ImageProxyFaceAnalysisFrame;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/** CameraX-to-vision bridge that transfers one ImageProxy owner and emits metadata-only state. */
public final class FaceImageAnalyzer implements ImageAnalysis.Analyzer, AutoCloseable {
    private final FrameAdapterFactory frameFactory;
    private final VisionAnalyzer analyzer;
    private final FaceAnalysisCoordinator faceCoordinator;
    private final HostSelectionController hostSelection;
    private final FaceStateListener listener;
    private final Executor completionExecutor;
    private final AtomicReference<CameraGeometry> cameraGeometry = new AtomicReference<>();
    private boolean closed;

    public FaceImageAnalyzer(
            VisionAnalyzer analyzer,
            FaceAnalysisCoordinator faceCoordinator,
            HostSelectionController hostSelection,
            FaceStateListener listener,
            Executor completionExecutor) {
        this(
                analyzer,
                faceCoordinator,
                hostSelection,
                listener,
                completionExecutor,
                ImageProxyFaceAnalysisFrame::new);
    }

    FaceImageAnalyzer(
            VisionAnalyzer analyzer,
            FaceAnalysisCoordinator faceCoordinator,
            HostSelectionController hostSelection,
            FaceStateListener listener,
            Executor completionExecutor,
            FrameAdapterFactory frameFactory) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.faceCoordinator = Objects.requireNonNull(faceCoordinator, "faceCoordinator");
        this.hostSelection = Objects.requireNonNull(hostSelection, "hostSelection");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.completionExecutor = Objects.requireNonNull(completionExecutor, "completionExecutor");
        this.frameFactory = Objects.requireNonNull(frameFactory, "frameFactory");
    }

    /** Must be supplied from the selected CameraInfo before analysis can authorize regions. */
    public void updateCameraGeometry(CameraGeometry geometry) {
        cameraGeometry.set(Objects.requireNonNull(geometry, "geometry"));
    }

    @Override
    public synchronized void analyze(ImageProxy image) {
        Objects.requireNonNull(image, "image");
        if (closed) {
            image.close();
            return;
        }
        long timestampNanos = image.getImageInfo().getTimestamp();
        CameraGeometry geometry = cameraGeometry.get();
        if (timestampNanos < 0L || geometry == null) {
            image.close();
            listener.onAnalysisUnavailable();
            return;
        }
        CoordinateTransform transform;
        try {
            transform = FrameTransform.normalizePixelSensorToBuffer(
                    geometry,
                    new Size(image.getWidth(), image.getHeight()),
                    image.getImageInfo().getSensorToBufferTransformMatrix());
        } catch (RuntimeException exception) {
            image.close();
            listener.onAnalysisUnavailable();
            return;
        }
        AnalysisFrameHandle frame = frameFactory.create(image);
        ListenableFuture<DetectorSnapshot> result;
        try {
            result = analyzer.analyze(
                    frame,
                    FrameTimestamp.ofNanos(timestampNanos),
                    image.getImageInfo().getRotationDegrees(),
                    transform);
        } catch (RuntimeException exception) {
            frame.close();
            listener.onAnalysisUnavailable();
            return;
        }
        result.addListener(() -> complete(result), completionExecutor);
    }

    private void complete(ListenableFuture<DetectorSnapshot> result) {
        try {
            DetectorSnapshot snapshot = result.get();
            listener.onFaceState(faceCoordinator.accept(snapshot, hostSelection));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            listener.onAnalysisUnavailable();
        } catch (ExecutionException | RuntimeException exception) {
            listener.onAnalysisUnavailable();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        analyzer.cancelPending();
        faceCoordinator.resetSession();
        if (analyzer instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                listener.onAnalysisUnavailable();
            }
        }
    }

    public interface FaceStateListener {
        void onFaceState(FaceAnalysisCoordinator.FaceFrameState faceState);

        void onAnalysisUnavailable();
    }

    interface FrameAdapterFactory {
        AnalysisFrameHandle create(ImageProxy image);
    }
}
