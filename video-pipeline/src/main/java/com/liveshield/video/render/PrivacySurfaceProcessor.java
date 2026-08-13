package com.liveshield.video.render;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Size;
import android.view.Surface;
import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.CameraEffect;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.ProcessingException;
import androidx.camera.core.SurfaceOutput;
import androidx.camera.core.SurfaceProcessor;
import androidx.camera.core.SurfaceRequest;
import androidx.core.util.Consumer;
import com.liveshield.privacy.decision.FrameDecisionStore;
import com.liveshield.privacy.decision.FramePrivacyDecision;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.session.SessionHealth;
import com.liveshield.video.buffer.GlBufferedFrameProcessor;
import com.liveshield.video.contract.RawTextureHandle;
import com.liveshield.video.contract.RedactionRenderer;
import com.liveshield.video.contract.SanitizedRender;
import com.liveshield.video.diagnostics.VideoDiagnostics;
import com.liveshield.video.geometry.CameraGeometry;
import com.liveshield.video.geometry.FrameTransform;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The sole CameraX raw-surface owner and bridge to sanitized preview/video surfaces.
 *
 * <p>Each incoming external texture is copied into a renderer-owned bounded texture pool before it
 * can be presented downstream. Every copied timestamp receives an exact decision; a missing,
 * stale, invalid, or failed decision produces a full shield. No API exposes an input texture or
 * input surface.</p>
 */
public final class PrivacySurfaceProcessor implements SurfaceProcessor, AutoCloseable {
    public static final int MAX_RAW_TEXTURES = 12;
    private static final int BYTES_PER_FLOAT = 4;
    private static final long RAW_DECISION_DEADLINE_NANOS = 100_000_000L;

    private final Executor executor;
    private final FrameDecisionStore decisionStore;
    private final Consumer<Throwable> errorListener;
    private final ReadinessListener readinessListener;
    private final TransformListener transformListener;
    private final DecisionMaterializer decisionMaterializer;
    private final CameraEffect cameraEffect;
    private final SanitizedOutputCapability capability;
    private final ScheduledExecutorService deadlineScheduler;
    private final Object lock = new Object();
    private FrameTransform frameTransform;
    private GlPipeline pipeline;
    private GlBufferedFrameProcessor frameProcessor;
    private boolean inputProvided;
    private boolean closed;
    private boolean transformReady;
    private Readiness readiness = Readiness.NOT_READY;
    private SurfaceRequest activeInputRequest;
    private CameraGeometry selectedCameraGeometry;
    private SessionHealth.RecoveryState recoveryState = SessionHealth.RecoveryState.SAFE;
    private boolean bufferedRendererFailure;
    private VideoDiagnostics.RenderMode lastRenderMode;

    public PrivacySurfaceProcessor(
            Executor executor,
            FrameDecisionStore decisionStore,
            FrameTransform initialTransform,
            Consumer<Throwable> errorListener) {
        this(executor, decisionStore, initialTransform, errorListener, ignored -> { }, ignored -> { });
    }

    public PrivacySurfaceProcessor(
            Executor executor,
            FrameDecisionStore decisionStore,
            FrameTransform initialTransform,
            Consumer<Throwable> errorListener,
            ReadinessListener readinessListener) {
        this(executor, decisionStore, initialTransform, errorListener, readinessListener,
                ignored -> { });
    }

    public PrivacySurfaceProcessor(
            Executor executor,
            FrameDecisionStore decisionStore,
            FrameTransform initialTransform,
            Consumer<Throwable> errorListener,
            ReadinessListener readinessListener,
            TransformListener transformListener) {
        this(executor, decisionStore, initialTransform, errorListener, readinessListener,
                transformListener, ignored -> { });
    }

    public PrivacySurfaceProcessor(
            Executor executor,
            FrameDecisionStore decisionStore,
            FrameTransform initialTransform,
            Consumer<Throwable> errorListener,
            ReadinessListener readinessListener,
            TransformListener transformListener,
            DecisionMaterializer decisionMaterializer) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.decisionStore = Objects.requireNonNull(decisionStore, "decisionStore");
        this.frameTransform = Objects.requireNonNull(initialTransform, "initialTransform");
        this.errorListener = Objects.requireNonNull(errorListener, "errorListener");
        this.readinessListener = Objects.requireNonNull(readinessListener, "readinessListener");
        this.transformListener = Objects.requireNonNull(transformListener, "transformListener");
        this.decisionMaterializer = Objects.requireNonNull(
                decisionMaterializer, "decisionMaterializer");
        this.cameraEffect = new PrivacyCameraEffect(executor, this, failure -> {
            VideoDiagnostics.failure(
                    VideoDiagnostics.Event.PROCESSOR_CAMERAX_FAILED, failure);
            reportFailure(failure);
        });
        this.capability = new SanitizedOutputCapability(this);
        this.deadlineScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "LiveShield-Raw-Deadline");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Returns the only effect authorized for this processor's preview and video outputs. */
    public CameraEffect cameraEffect() {
        return cameraEffect;
    }

    /** Issues an identity-bound, non-constructible token for an encoder downstream of this effect. */
    public SanitizedOutputCapability sanitizedOutputCapability() {
        return capability;
    }

    /** Returns non-pixel readiness state; construction and surface attachment remain NOT_READY. */
    public Readiness readiness() {
        synchronized (lock) {
            return readiness;
        }
    }

    /** Non-pixel recovery evidence for the fail-private policy input. */
    public SessionHealth.RecoveryState recoveryState() {
        synchronized (lock) {
            return recoveryState;
        }
    }

    /** Current bounded raw queue depth; contains no texture, pixel, or region information. */
    public int rawQueueDepth() {
        GlBufferedFrameProcessor current;
        synchronized (lock) {
            current = frameProcessor;
        }
        return current == null ? 0 : current.queuedFrameCount();
    }

    /** Flushes queued raw work and latches verified recovery before regional output may resume. */
    public void invalidateForSafety() {
        try {
            executor.execute(() -> {
                GlBufferedFrameProcessor current;
                synchronized (lock) {
                    current = frameProcessor;
                }
                if (current != null) {
                    current.invalidateForSafety();
                }
            });
        } catch (RejectedExecutionException rejection) {
            reportFailure(rejection);
        }
    }

    public void updateFrameTransform(FrameTransform transform) {
        FrameTransform validated = Objects.requireNonNull(transform, "transform");
        synchronized (lock) {
            ensureOpen();
            frameTransform = validated;
            transformReady = true;
        }
        transformListener.onTransformAvailable(validated);
    }

    /** Supplies selected-camera metadata through CameraX's supported Camera2 interop API. */
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    public void configureCameraInfo(CameraInfo cameraInfo) {
        Objects.requireNonNull(cameraInfo, "cameraInfo");
        Rect activeArray = Camera2CameraInfo.from(cameraInfo).getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (activeArray == null || activeArray.width() <= 0 || activeArray.height() <= 0) {
            throw new IllegalArgumentException("Selected camera has no valid active sensor array");
        }
        synchronized (lock) {
            ensureOpen();
            selectedCameraGeometry = CameraGeometry.fromRect(activeArray);
            transformReady = false;
        }
    }

    /** Returns immutable selected-camera geometry after production camera configuration. */
    public CameraGeometry selectedCameraGeometry() {
        synchronized (lock) {
            if (selectedCameraGeometry == null) {
                throw new IllegalStateException("Selected camera geometry is not configured");
            }
            return selectedCameraGeometry;
        }
    }

    @Override
    public void onInputSurface(SurfaceRequest request) throws ProcessingException {
        Objects.requireNonNull(request, "request");
        VideoDiagnostics.info(VideoDiagnostics.Event.PROCESSOR_INPUT_REQUESTED);
        synchronized (lock) {
            ensureOpenForCameraX();
            if (inputProvided) {
                VideoDiagnostics.info(VideoDiagnostics.Event.PROCESSOR_INPUT_DUPLICATE);
                request.willNotProvideSurface();
                reportFailure(new IllegalStateException(
                        "Privacy processor accepts exactly one raw input surface"));
                throw new ProcessingException();
            }
            inputProvided = true;
            activeInputRequest = request;
        }
        try {
            GlPipeline created = new GlPipeline(
                    request.getResolution().getWidth(),
                    request.getResolution().getHeight(),
                    transformListener);
            GlBufferedFrameProcessor createdProcessor = new GlBufferedFrameProcessor(
                    MAX_RAW_TEXTURES,
                    decisionStore,
                    new PipelineRenderer(created),
                    this::onRecoveryState,
                    this::onBufferedFrameFailure);
            VideoDiagnostics.info(VideoDiagnostics.Event.PROCESSOR_INPUT_CREATED);
            synchronized (lock) {
                ensureOpenForCameraX();
                pipeline = created;
                frameProcessor = createdProcessor;
            }
            created.surfaceTexture.setOnFrameAvailableListener(
                    ignored -> dispatchIncomingFrame());
            request.setTransformationInfoListener(executor,
                    info -> applyCameraTransform(request, info));
            request.provideSurface(created.inputSurface, executor, result -> {
                VideoDiagnostics.result(
                        VideoDiagnostics.Event.PROCESSOR_INPUT_RESULT,
                        result.getResultCode());
                close();
            });
            VideoDiagnostics.info(VideoDiagnostics.Event.PROCESSOR_INPUT_PROVIDED);
        } catch (RuntimeException exception) {
            synchronized (lock) {
                inputProvided = false;
                activeInputRequest = null;
            }
            request.willNotProvideSurface();
            reportFailure(exception);
            throw new ProcessingException();
        }
    }

    private void applyCameraTransform(
            SurfaceRequest request,
            SurfaceRequest.TransformationInfo transformationInfo) {
        try {
            CameraGeometry sensorGeometry;
            synchronized (lock) {
                sensorGeometry = selectedCameraGeometry;
            }
            if (sensorGeometry == null) {
                throw new IllegalStateException(
                        "Selected camera metadata must be configured before surface binding");
            }
            FrameTransform inputTransform = fromCameraXTransformation(
                    sensorGeometry,
                    request.getResolution(),
                    transformationInfo.getSensorToBufferTransform(),
                    transformationInfo.getCropRect(),
                    transformationInfo.getRotationDegrees(),
                    transformationInfo.isMirroring());
            synchronized (lock) {
                ensureOpen();
                frameTransform = inputTransform;
            }
            VideoDiagnostics.info(VideoDiagnostics.Event.PROCESSOR_TRANSFORM_READY);
        } catch (RuntimeException exception) {
            VideoDiagnostics.failure(
                    VideoDiagnostics.Event.PROCESSOR_TRANSFORM_FAILED, exception);
            reportFailure(exception);
            signalReadiness(Readiness.UNAVAILABLE);
        }
    }

    static FrameTransform fromCameraXTransformation(
            CameraGeometry sensorGeometry,
            Size bufferSize,
            Matrix cameraMatrix,
            Rect crop,
            int rotationDegrees,
            boolean mirrored) {
        Objects.requireNonNull(sensorGeometry, "sensorGeometry");
        Objects.requireNonNull(bufferSize, "bufferSize");
        Objects.requireNonNull(cameraMatrix, "cameraMatrix");
        Objects.requireNonNull(crop, "crop");
        if (bufferSize.getWidth() <= 0 || bufferSize.getHeight() <= 0) {
            throw new IllegalArgumentException("Camera sensor and buffer must be non-empty");
        }
        double bufferWidth = bufferSize.getWidth();
        double bufferHeight = bufferSize.getHeight();
        if (crop.width() <= 0 || crop.height() <= 0
                || crop.left < 0 || crop.top < 0
                || crop.right > bufferSize.getWidth() || crop.bottom > bufferSize.getHeight()) {
            throw new IllegalArgumentException("CameraX crop must be inside the input buffer");
        }
        NormalizedRect normalizedCrop = new NormalizedRect(
                crop.left / bufferWidth,
                crop.top / bufferHeight,
                crop.right / bufferWidth,
                crop.bottom / bufferHeight);
        FrameTransform transform = FrameTransform.fromCameraMetadata(
                FrameTransform.normalizePixelSensorToBuffer(
                        sensorGeometry, bufferSize, cameraMatrix),
                normalizedCrop,
                rotationDegrees,
                mirrored);
        VideoDiagnostics.transform(
                VideoDiagnostics.Event.PROCESSOR_SENSOR_TO_OUTPUT,
                rotationDegrees,
                mirrored,
                transform.sensorToOutput().matrix());
        return transform;
    }

    @Override
    public void onOutputSurface(SurfaceOutput output) throws ProcessingException {
        Objects.requireNonNull(output, "output");
        VideoDiagnostics.info(VideoDiagnostics.Event.PROCESSOR_OUTPUT_REQUESTED);
        GlPipeline current;
        CameraGeometry sensorGeometry;
        synchronized (lock) {
            ensureOpenForCameraX();
            current = pipeline;
            sensorGeometry = selectedCameraGeometry;
        }
        if (current == null || sensorGeometry == null) {
            output.close();
            throw new ProcessingException();
        }
        try {
            FrameTransform outputTransform = fromCameraXOutput(
                    sensorGeometry,
                    output.getSize(),
                    output.getSensorToBufferTransform());
            Surface surface = output.getSurface(executor, event -> {
                if (event.getEventCode() == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                    executor.execute(() -> removeOutput(event.getSurfaceOutput()));
                }
            });
            current.addOutput(output, surface, outputTransform);
            if ((output.getTargets() & CameraEffect.PREVIEW) != 0) {
                updateFrameTransform(outputTransform);
            }
            VideoDiagnostics.info(VideoDiagnostics.Event.PROCESSOR_OUTPUT_ADDED);
        } catch (RuntimeException exception) {
            output.close();
            reportFailure(exception);
            throw new ProcessingException();
        }
    }

    static FrameTransform fromCameraXOutput(
            CameraGeometry sensorGeometry, Size outputSize, Matrix sensorToOutputBuffer) {
        CoordinateTransform normalized = FrameTransform.normalizePixelSensorToBuffer(
                sensorGeometry, outputSize, sensorToOutputBuffer);
        FrameTransform transform = FrameTransform.fromCameraMetadata(
                normalized,
                new NormalizedRect(0.0, 0.0, 1.0, 1.0),
                0,
                false);
        VideoDiagnostics.transform(
                VideoDiagnostics.Event.PROCESSOR_SENSOR_TO_OUTPUT,
                0,
                false,
                transform.sensorToOutput().matrix());
        return transform;
    }

    /**
     * Maps sensor coordinates into the user-visible top-left preview coordinates.
     *
     * <p>{@link SurfaceOutput#updateTransformMatrix(float[], float[])} returns the inverse
     * texture lookup used by the shader: output GL coordinates to input GL coordinates. Overlay
     * geometry needs the opposite direction, with Android's top-left Y axis, so both Y axes are
     * converted and the affine mapping is inverted before composition with sensor-to-buffer.</p>
     */
    static FrameTransform toDisplayedOutput(
            FrameTransform sensorToOutputBuffer, float[] outputTextureMatrix) {
        Objects.requireNonNull(sensorToOutputBuffer, "sensorToOutputBuffer");
        Objects.requireNonNull(outputTextureMatrix, "outputTextureMatrix");
        if (outputTextureMatrix.length != 16) {
            throw new IllegalArgumentException("Texture transform must contain 16 values");
        }
        double a = outputTextureMatrix[0];
        double b = outputTextureMatrix[4];
        double c = outputTextureMatrix[12];
        double d = outputTextureMatrix[1];
        double e = outputTextureMatrix[5];
        double f = outputTextureMatrix[13];
        CoordinateTransform displayToBuffer = new CoordinateTransform(new double[]{
            a, -b, b + c,
            -d, e, 1.0 - e - f,
            0.0, 0.0, 1.0
        });
        double[] sensorToDisplay = multiply3(
                displayToBuffer.inverse().matrix(),
                sensorToOutputBuffer.sensorToOutput().matrix());
        FrameTransform displayed = FrameTransform.fromCameraMetadata(
                new CoordinateTransform(sensorToDisplay),
                new NormalizedRect(0.0, 0.0, 1.0, 1.0),
                0,
                false);
        VideoDiagnostics.transform(
                VideoDiagnostics.Event.PROCESSOR_SENSOR_TO_DISPLAY,
                0,
                false,
                displayed.sensorToOutput().matrix());
        return displayed;
    }

    private static double[] multiply3(double[] left, double[] right) {
        double[] result = new double[9];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                result[row * 3 + column] = left[row * 3] * right[column]
                        + left[row * 3 + 1] * right[3 + column]
                        + left[row * 3 + 2] * right[6 + column];
            }
        }
        return result;
    }

    private void consumeFrame() {
        GlPipeline current;
        GlBufferedFrameProcessor buffer;
        boolean canMapRegions;
        synchronized (lock) {
            if (closed || pipeline == null || frameProcessor == null) {
                return;
            }
            current = pipeline;
            buffer = frameProcessor;
            canMapRegions = transformReady;
        }
        VideoDiagnostics.FrameStage stage = VideoDiagnostics.FrameStage.RECOVERY;
        try {
            if (buffer.requiresVerifiedRecovery()
                    && !bufferedRendererFailure && current.hasOutputs()) {
                buffer.verifyRecovery();
            }
            stage = VideoDiagnostics.FrameStage.COPY;
            RawTextureHandle raw = current.copyLatestRawFrame();
            FrameTimestamp timestamp = ((GlRawTexture) raw).timestamp;
            FrameTimestamp deadline = safeDeadline(timestamp);
            decisionMaterializer.materialize(timestamp);
            stage = VideoDiagnostics.FrameStage.ACCEPT;
            buffer.accept(raw, timestamp, deadline, canMapRegions);
            long lookupNanos = Math.max(timestamp.nanos(), System.nanoTime());
            stage = VideoDiagnostics.FrameStage.PROCESS;
            buffer.processReady(FrameTimestamp.ofNanos(lookupNanos));
            stage = VideoDiagnostics.FrameStage.DEADLINE;
            scheduleDeadline(buffer, deadline);
        } catch (RuntimeException exception) {
            VideoDiagnostics.state(VideoDiagnostics.Event.PROCESSOR_FRAME_FAILED, stage);
            try {
                buffer.fail(exception);
                if (current.hasOutputs()) {
                    signalReadiness(Readiness.READY);
                } else {
                    signalReadiness(Readiness.UNAVAILABLE);
                }
            } catch (RuntimeException shieldFailure) {
                exception.addSuppressed(shieldFailure);
                signalReadiness(Readiness.UNAVAILABLE);
                close();
            }
        }
    }

    void dispatchIncomingFrame() {
        synchronized (lock) {
            if (closed) {
                return;
            }
        }
        try {
            executor.execute(this::consumeFrame);
        } catch (RejectedExecutionException rejection) {
            synchronized (lock) {
                if (closed) {
                    return;
                }
            }
            reportFailure(rejection);
        }
    }

    private static FrameTimestamp safeDeadline(FrameTimestamp timestamp) {
        try {
            return timestamp.plusNanos(RAW_DECISION_DEADLINE_NANOS);
        } catch (ArithmeticException overflow) {
            return timestamp;
        }
    }

    private void scheduleDeadline(
            GlBufferedFrameProcessor buffer, FrameTimestamp deadline) {
        deadlineScheduler.schedule(
                () -> executor.execute(() -> processDeadline(buffer, deadline)),
                RAW_DECISION_DEADLINE_NANOS,
                TimeUnit.NANOSECONDS);
    }

    private void processDeadline(
            GlBufferedFrameProcessor expected, FrameTimestamp deadline) {
        synchronized (lock) {
            if (closed || frameProcessor != expected) {
                return;
            }
        }
        try {
            expected.processDeadline(deadline);
        } catch (RuntimeException failure) {
            VideoDiagnostics.failure(
                    VideoDiagnostics.Event.PROCESSOR_DEADLINE_FAILED, failure);
            onBufferedFrameFailure(failure);
            signalReadiness(Readiness.UNAVAILABLE);
        }
    }

    private void onRecoveryState(SessionHealth.RecoveryState state) {
        VideoDiagnostics.state(VideoDiagnostics.Event.PROCESSOR_RECOVERY_STATE, state);
        synchronized (lock) {
            recoveryState = state;
            if (state == SessionHealth.RecoveryState.VERIFIED) {
                bufferedRendererFailure = false;
            }
        }
    }

    private void onBufferedFrameFailure(Throwable failure) {
        VideoDiagnostics.failure(VideoDiagnostics.Event.PROCESSOR_BUFFER_FAILED, failure);
        synchronized (lock) {
            bufferedRendererFailure = true;
        }
        reportFailure(failure);
        signalReadiness(Readiness.UNAVAILABLE);
    }

    private void removeOutput(SurfaceOutput output) {
        GlPipeline current;
        synchronized (lock) {
            current = pipeline;
        }
        if (current != null) {
            current.removeOutput(output);
            VideoDiagnostics.info(VideoDiagnostics.Event.PROCESSOR_OUTPUT_REMOVED);
            if (!current.hasOutputs()) {
                signalReadiness(Readiness.UNAVAILABLE);
            }
        } else {
            output.close();
        }
    }

    static FramePrivacyDecision selectDecisionForTransform(
            boolean transformReady,
            FrameTimestamp timestamp,
            FramePrivacyDecision exactDecision) {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(exactDecision, "exactDecision");
        return transformReady
                ? exactDecision
                : FramePrivacyDecision.fullShield(timestamp, FramePrivacyDecision.Basis.MISSING);
    }

    private void reportFailure(Throwable failure) {
        VideoDiagnostics.failure(VideoDiagnostics.Event.PROCESSOR_FAILURE, failure);
        errorListener.accept(Objects.requireNonNull(failure, "failure"));
    }

    private void signalReadiness(Readiness next) {
        boolean changed;
        synchronized (lock) {
            changed = readiness != next;
            readiness = next;
        }
        if (changed) {
            VideoDiagnostics.state(
                    VideoDiagnostics.Event.PROCESSOR_READINESS_CHANGED, next);
            readinessListener.onReadinessChanged(next);
        }
    }

    private void signalRenderMode(VideoDiagnostics.RenderMode next) {
        boolean changed;
        synchronized (lock) {
            changed = lastRenderMode != next;
            lastRenderMode = next;
        }
        if (changed) {
            VideoDiagnostics.state(VideoDiagnostics.Event.PROCESSOR_RENDER_MODE, next);
        }
    }

    @Override
    public void close() {
        executor.execute(this::closeOnExecutor);
    }

    private void closeOnExecutor() {
        GlPipeline toClose;
        GlBufferedFrameProcessor bufferToClose;
        SurfaceRequest request;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            toClose = pipeline;
            pipeline = null;
            bufferToClose = frameProcessor;
            frameProcessor = null;
            request = activeInputRequest;
            activeInputRequest = null;
        }
        if (request != null) {
            request.clearTransformationInfoListener();
        }
        if (bufferToClose != null) {
            bufferToClose.close();
        }
        if (toClose != null) {
            toClose.close();
        }
        deadlineScheduler.shutdownNow();
        signalReadiness(Readiness.UNAVAILABLE);
        VideoDiagnostics.info(VideoDiagnostics.Event.PROCESSOR_CLOSED);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Privacy surface processor is closed");
        }
    }

    private void ensureOpenForCameraX() throws ProcessingException {
        if (closed) {
            throw new ProcessingException();
        }
    }

    /** Capability whose constructor is inaccessible and whose authority is owner-identity based. */
    public static final class SanitizedOutputCapability {
        private final PrivacySurfaceProcessor owner;

        private SanitizedOutputCapability(PrivacySurfaceProcessor owner) {
            this.owner = owner;
        }

        public boolean authorizes(PrivacySurfaceProcessor candidate) {
            return owner == candidate;
        }

    }

    /** Non-pixel processor health delivered serially on the processor executor. */
    public enum Readiness {
        NOT_READY,
        READY,
        UNAVAILABLE
    }

    @FunctionalInterface
    public interface ReadinessListener {
        void onReadinessChanged(Readiness readiness);
    }

    /** Valid CameraX metadata only, delivered serially on the processor executor without pixels. */
    @FunctionalInterface
    public interface TransformListener {
        void onTransformAvailable(FrameTransform transform);
    }

    /** Creates an exact decision for the renderer timestamp from payload-free policy state. */
    public interface DecisionMaterializer {
        void materialize(FrameTimestamp timestamp);
    }

    private static final class PrivacyCameraEffect extends CameraEffect {
        private PrivacyCameraEffect(
                Executor executor,
                SurfaceProcessor processor,
                Consumer<Throwable> errorListener) {
            super(PREVIEW | VIDEO_CAPTURE, executor, processor, errorListener);
        }
    }

    private final class PipelineRenderer implements RedactionRenderer {
        private final GlPipeline owner;

        private PipelineRenderer(GlPipeline owner) {
            this.owner = owner;
        }

        @Override
        public SanitizedRender render(
                RawTextureHandle rawTexture, FramePrivacyDecision privacyDecision) {
            if (!(rawTexture instanceof GlRawTexture raw) || raw.owner != owner) {
                throw new IllegalArgumentException(
                        "Only this renderer's owned raw texture may cross the privacy boundary");
            }
            if (owner.renderRaw(raw, privacyDecision)) {
                signalRenderMode(VideoDiagnostics.RenderMode.REGIONAL);
                signalReadiness(Readiness.READY);
            }
            return new SanitizedRender(privacyDecision.timestamp());
        }

        @Override
        public SanitizedRender renderShield(FramePrivacyDecision privacyDecision) {
            if (privacyDecision.status() != FramePrivacyDecision.Status.FULL_SHIELD) {
                throw new IllegalArgumentException("Shield renderer requires a full-shield decision");
            }
            if (owner.renderShieldToAll(privacyDecision.timestamp())) {
                signalRenderMode(VideoDiagnostics.RenderMode.FULL_SHIELD);
                signalReadiness(Readiness.READY);
            }
            return new SanitizedRender(privacyDecision.timestamp());
        }
    }

    private static final class GlRawTexture implements RawTextureHandle {
        private final GlPipeline owner;
        private final int texture;
        private final FrameTimestamp timestamp;
        private final AtomicBoolean closed = new AtomicBoolean();

        private GlRawTexture(
                GlPipeline owner,
                int texture,
                FrameTimestamp timestamp) {
            this.owner = owner;
            this.texture = texture;
            this.timestamp = timestamp;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.releaseTexture(texture);
            }
        }
    }

    private static final class GlPipeline implements AutoCloseable {
        private static final float[] QUAD = {
            -1.0f, -1.0f, 0.0f, 0.0f,
            1.0f, -1.0f, 1.0f, 0.0f,
            -1.0f, 1.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 1.0f, 1.0f
        };
        private static final float[] IDENTITY_MATRIX = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
        };
        private static final String VERTEX_SHADER =
                "attribute vec2 aPosition; attribute vec2 aTexCoord;"
                        + "uniform mat4 uTexMatrix; varying vec2 vTexCoord;"
                        + "void main(){gl_Position=vec4(aPosition,0.0,1.0);"
                        + "vTexCoord=(uTexMatrix*vec4(aTexCoord,0.0,1.0)).xy;}";
        private static final String TEXTURE_FRAGMENT_SHADER =
                "precision mediump float; uniform sampler2D uTexture; varying vec2 vTexCoord;"
                        + "void main(){gl_FragColor=texture2D(uTexture,vTexCoord);}";
        private static final String EXTERNAL_FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n"
                        + "precision mediump float; uniform samplerExternalOES uTexture;"
                        + "varying vec2 vTexCoord;"
                        + "void main(){gl_FragColor=texture2D(uTexture,vTexCoord);}";

        private final int width;
        private final int height;
        private final EGLDisplay display;
        private final EGLConfig config;
        private final EGLContext context;
        private final EGLSurface pbuffer;
        private final int externalTexture;
        private final int externalProgram;
        private final int textureProgram;
        private final int frameBuffer;
        private final FloatBuffer quad;
        private final ArrayDeque<Integer> availableTextures = new ArrayDeque<>();
        private final Map<SurfaceOutput, OutputTarget> outputs = new IdentityHashMap<>();
        private final SurfaceTexture surfaceTexture;
        private final Surface inputSurface;
        private final TransformListener displayTransformListener;

        private GlPipeline(
                int width, int height, TransformListener displayTransformListener) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Camera input size must be positive");
            }
            this.width = width;
            this.height = height;
            this.displayTransformListener = Objects.requireNonNull(
                    displayTransformListener, "displayTransformListener");
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] versions = new int[2];
            if (display == EGL14.EGL_NO_DISPLAY
                    || !EGL14.eglInitialize(display, versions, 0, versions, 1)) {
                throw new IllegalStateException("Unable to initialize privacy EGL display");
            }
            int[] attributes = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
                    || count[0] == 0) {
                throw new IllegalStateException("Unable to choose privacy EGL config");
            }
            config = configs[0];
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            pbuffer = EGL14.eglCreatePbufferSurface(display, config,
                    new int[]{EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE}, 0);
            makeCurrent(pbuffer);
            externalProgram = linkProgram(EXTERNAL_FRAGMENT_SHADER);
            textureProgram = linkProgram(TEXTURE_FRAGMENT_SHADER);
            int[] generated = new int[1];
            GLES20.glGenTextures(1, generated, 0);
            externalTexture = generated[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture);
            setTextureParameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            GLES20.glGenFramebuffers(1, generated, 0);
            frameBuffer = generated[0];
            for (int index = 0; index < MAX_RAW_TEXTURES; index++) {
                GLES20.glGenTextures(1, generated, 0);
                int texture = generated[0];
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
                setTextureParameters(GLES20.GL_TEXTURE_2D);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                        width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                availableTextures.add(texture);
            }
            quad = ByteBuffer.allocateDirect(QUAD.length * BYTES_PER_FLOAT)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(QUAD).position(0);
            surfaceTexture = new SurfaceTexture(externalTexture);
            surfaceTexture.setDefaultBufferSize(width, height);
            inputSurface = new Surface(surfaceTexture);
            checkGl("initialize bounded privacy texture pool");
        }

        private RawTextureHandle copyLatestRawFrame() {
            makeCurrent(pbuffer);
            surfaceTexture.updateTexImage();
            float[] transform = new float[16];
            surfaceTexture.getTransformMatrix(transform);
            int destination = acquireRawTexture();
            try {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffer);
                GLES20.glFramebufferTexture2D(
                        GLES20.GL_FRAMEBUFFER,
                        GLES20.GL_COLOR_ATTACHMENT0,
                        GLES20.GL_TEXTURE_2D,
                        destination,
                        0);
                if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                        != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException(
                            "Renderer-owned raw framebuffer is incomplete");
                }
                drawTexture(externalProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                        externalTexture, transform, width, height);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                FrameTimestamp timestamp = FrameTimestamp.ofNanos(
                        Math.max(0L, surfaceTexture.getTimestamp()));
                return new GlRawTexture(this, destination, timestamp);
            } catch (RuntimeException failure) {
                availableTextures.addLast(destination);
                throw failure;
            }
        }

        private int acquireRawTexture() {
            Integer available = availableTextures.pollFirst();
            if (available == null) {
                throw new IllegalStateException(
                        "Bounded raw texture pool exhausted before privacy shielding");
            }
            return available;
        }

        private boolean renderRaw(
                GlRawTexture raw, FramePrivacyDecision decision) {
            if (outputs.isEmpty()) {
                return false;
            }
            for (OutputTarget target : outputs.values()) {
                makeCurrent(target.eglSurface);
                target.output.updateTransformMatrix(target.transform, IDENTITY_MATRIX);
                if (!target.transformLogged) {
                    target.transformLogged = true;
                    VideoDiagnostics.transform(
                            VideoDiagnostics.Event.PROCESSOR_OUTPUT_TEXTURE_MATRIX,
                            0,
                            false,
                            new double[]{
                                target.transform[0], target.transform[4], target.transform[12],
                                target.transform[1], target.transform[5], target.transform[13],
                                0.0, 0.0, 1.0
                            });
                    if ((target.output.getTargets() & CameraEffect.PREVIEW) != 0) {
                        displayTransformListener.onTransformAvailable(
                                toDisplayedOutput(target.frameTransform, target.transform));
                    }
                }
                drawTexture(textureProgram, GLES20.GL_TEXTURE_2D, raw.texture,
                        target.transform, target.width, target.height);
                GlRedactionRenderer.applyDecision(
                        decision, target.frameTransform, target.width, target.height);
                EGLExt.eglPresentationTimeANDROID(
                        display, target.eglSurface, raw.timestamp.nanos());
                if (!EGL14.eglSwapBuffers(display, target.eglSurface)) {
                    throw new IllegalStateException("Unable to publish sanitized GL frame");
                }
            }
            return true;
        }

        private boolean renderShieldToAll(FrameTimestamp timestamp) {
            if (outputs.isEmpty()) {
                return false;
            }
            for (OutputTarget target : outputs.values()) {
                makeCurrent(target.eglSurface);
                GLES20.glViewport(0, 0, target.width, target.height);
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
                int color = GlRedactionRenderer.FULL_SHIELD_COLOR;
                GLES20.glClearColor(
                        android.graphics.Color.red(color) / 255.0f,
                        android.graphics.Color.green(color) / 255.0f,
                        android.graphics.Color.blue(color) / 255.0f,
                        1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                EGLExt.eglPresentationTimeANDROID(
                        display, target.eglSurface, timestamp.nanos());
                if (!EGL14.eglSwapBuffers(display, target.eglSurface)) {
                    throw new IllegalStateException("Unable to publish privacy shield");
                }
            }
            return true;
        }

        private void releaseTexture(int texture) {
            availableTextures.addLast(texture);
        }

        private void addOutput(
                SurfaceOutput output, Surface surface, FrameTransform frameTransform) {
            makeCurrent(pbuffer);
            EGLSurface eglSurface = EGL14.eglCreateWindowSurface(
                    display, config, surface, new int[]{EGL14.EGL_NONE}, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                surface.release();
                throw new IllegalStateException("Unable to create sanitized EGL output");
            }
            OutputTarget replaced = outputs.put(output, new OutputTarget(
                    output, surface, eglSurface,
                    output.getSize().getWidth(), output.getSize().getHeight(), frameTransform));
            if (replaced != null) {
                replaced.close(display);
            }
        }

        private void removeOutput(SurfaceOutput output) {
            OutputTarget target = outputs.remove(output);
            if (target != null) {
                makeCurrent(pbuffer);
                target.close(display);
            } else {
                output.close();
            }
        }

        private boolean hasOutputs() {
            return !outputs.isEmpty();
        }

        private void drawTexture(
                int program,
                int target,
                int texture,
                float[] transform,
                int outputWidth,
                int outputHeight) {
            GLES20.glViewport(0, 0, outputWidth, outputHeight);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(target, texture);
            int position = GLES20.glGetAttribLocation(program, "aPosition");
            int textureCoordinate = GLES20.glGetAttribLocation(program, "aTexCoord");
            quad.position(0);
            GLES20.glVertexAttribPointer(
                    position, 2, GLES20.GL_FLOAT, false, 4 * BYTES_PER_FLOAT, quad);
            GLES20.glEnableVertexAttribArray(position);
            quad.position(2);
            GLES20.glVertexAttribPointer(
                    textureCoordinate, 2, GLES20.GL_FLOAT, false, 4 * BYTES_PER_FLOAT, quad);
            GLES20.glEnableVertexAttribArray(textureCoordinate);
            GLES20.glUniformMatrix4fv(
                    GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, transform, 0);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(position);
            GLES20.glDisableVertexAttribArray(textureCoordinate);
            checkGl("draw renderer-owned texture");
        }

        private void makeCurrent(EGLSurface surface) {
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw new IllegalStateException("Unable to make privacy EGL surface current");
            }
        }

        private static int linkProgram(String fragmentSource) {
            int vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) {
                String log = GLES20.glGetProgramInfoLog(program);
                GLES20.glDeleteProgram(program);
                throw new IllegalStateException("Unable to link privacy GL program: " + log);
            }
            return program;
        }

        private static int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new IllegalStateException("Unable to compile privacy GL shader: " + log);
            }
            return shader;
        }

        private static void setTextureParameters(int target) {
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        private static void checkGl(String operation) {
            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) {
                throw new IllegalStateException(operation + " failed with GLES error 0x"
                        + Integer.toHexString(error));
            }
        }

        @Override
        public void close() {
            makeCurrent(pbuffer);
            for (OutputTarget output : outputs.values()) {
                output.close(display);
            }
            outputs.clear();
            inputSurface.release();
            surfaceTexture.release();
            int[] textures = new int[availableTextures.size() + 1];
            int index = 0;
            while (!availableTextures.isEmpty()) {
                textures[index++] = availableTextures.removeFirst();
            }
            textures[index] = externalTexture;
            GLES20.glDeleteTextures(textures.length, textures, 0);
            GLES20.glDeleteFramebuffers(1, new int[]{frameBuffer}, 0);
            GLES20.glDeleteProgram(externalProgram);
            GLES20.glDeleteProgram(textureProgram);
            EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, pbuffer);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }

        private static final class OutputTarget {
            private final SurfaceOutput output;
            private final Surface surface;
            private final EGLSurface eglSurface;
            private final int width;
            private final int height;
            private final FrameTransform frameTransform;
            private final float[] transform = IDENTITY_MATRIX.clone();
            private boolean transformLogged;

            private OutputTarget(
                    SurfaceOutput output,
                    Surface surface,
                    EGLSurface eglSurface,
                    int width,
                    int height,
                    FrameTransform frameTransform) {
                this.output = output;
                this.surface = surface;
                this.eglSurface = eglSurface;
                this.width = width;
                this.height = height;
                this.frameTransform = Objects.requireNonNull(frameTransform, "frameTransform");
            }

            private void close(EGLDisplay display) {
                EGL14.eglDestroySurface(display, eglSurface);
                surface.release();
                output.close();
            }
        }
    }
}
