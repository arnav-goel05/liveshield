package com.liveshield.video.output;

import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Size;
import android.view.Surface;
import androidx.camera.core.SurfaceProcessor;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.core.impl.MutableStateObservable;
import androidx.camera.core.impl.Observable;
import androidx.camera.video.MediaSpec;
import androidx.camera.video.VideoOutput;
import androidx.core.util.Consumer;
import com.liveshield.video.diagnostics.VideoDiagnostics;
import com.liveshield.video.render.PrivacySurfaceProcessor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CameraX video output whose H.264 surface is authorized only behind the privacy renderer.
 *
 * <p>The supplied sink is the only encoded-output seam. Encoder-owned buffers are copied before
 * dispatch, and the sink receives no input surface, raw pixel handle, or audio value.</p>
 */
@SuppressLint("RestrictedApi") // Implementing VideoOutput's advanced negotiation contract.
public final class SanitizedVideoOutput implements VideoOutput, AutoCloseable {
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;

    private final PrivacySurfaceProcessor.SanitizedOutputCapability capability;
    private final SanitizedVideoSink sink;
    private final Consumer<Throwable> errorListener;
    private final LifecycleListener lifecycleListener;
    private final EncoderSettings settings;
    private final Observable<MediaSpec> mediaSpec;
    private final MutableStateObservable<Boolean> sourceStreamRequired =
            MutableStateObservable.withInitialState(true);
    private final OutputLifecycle lifecycle = new OutputLifecycle();
    private final Object lock = new Object();
    private EncoderSession activeSession;
    private SurfaceRequest activeRequest;
    private boolean codecConfigured;

    public SanitizedVideoOutput(
            PrivacySurfaceProcessor.SanitizedOutputCapability capability,
            SanitizedVideoSink sink,
            Consumer<Throwable> errorListener,
            EncoderSettings settings) {
        this(capability, sink, errorListener, settings, (state, encoderReady) -> { });
    }

    public SanitizedVideoOutput(
            PrivacySurfaceProcessor.SanitizedOutputCapability capability,
            SanitizedVideoSink sink,
            Consumer<Throwable> errorListener,
            EncoderSettings settings,
            LifecycleListener lifecycleListener) {
        this.capability = Objects.requireNonNull(capability, "capability");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.errorListener = Objects.requireNonNull(errorListener, "errorListener");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.lifecycleListener = Objects.requireNonNull(lifecycleListener, "lifecycleListener");
        mediaSpec = MutableStateObservable.withInitialState(createMediaSpec(settings));
    }

    /** Confirms that this output and the supplied effect processor share one unforgeable owner. */
    public boolean isAuthorizedBy(SurfaceProcessor processor) {
        return processor instanceof PrivacySurfaceProcessor privacyProcessor
                && capability.authorizes(privacyProcessor);
    }

    /** Stable CameraX negotiation hints for this H.264-only output. */
    @Override
    public Observable<MediaSpec> getMediaSpec() {
        return mediaSpec;
    }

    /** This output starts encoding as soon as CameraX supplies its sanitized surface. */
    @Override
    public Observable<Boolean> isSourceStreamRequired() {
        return sourceStreamRequired;
    }

    @Override
    public void onSurfaceRequested(SurfaceRequest request) {
        Objects.requireNonNull(request, "request");
        VideoDiagnostics.info(VideoDiagnostics.Event.ENCODER_SURFACE_REQUESTED);
        synchronized (lock) {
            try {
                lifecycle.beginRequest();
            } catch (RuntimeException rejection) {
                request.willNotProvideSurface();
                reportFailure(rejection);
                return;
            }
            activeRequest = request;
        }
        notifyLifecycleListener();

        EncoderSession session = null;
        try {
            Size resolution = request.getResolution();
            session = EncoderSession.create(
                    resolution.getWidth(), resolution.getHeight(), settings, sink,
                    this::onCodecConfigured, this::onEncoderFailure);
            VideoDiagnostics.info(VideoDiagnostics.Event.ENCODER_SESSION_CREATED);
            synchronized (lock) {
                activeSession = session;
                lifecycle.started();
            }
            notifyLifecycleListener();
            session.start();
            VideoDiagnostics.info(VideoDiagnostics.Event.ENCODER_STARTED);
            EncoderSession providedSession = session;
            request.provideSurface(
                    session.inputSurface(),
                    Runnable::run,
                    result -> onSurfaceDetached(request, providedSession));
            VideoDiagnostics.info(VideoDiagnostics.Event.ENCODER_SURFACE_PROVIDED);
        } catch (RuntimeException | IOException exception) {
            request.willNotProvideSurface();
            if (session != null) {
                session.close();
            }
            RuntimeException failure = asRuntimeException(exception);
            synchronized (lock) {
                activeRequest = null;
                activeSession = null;
                codecConfigured = false;
                lifecycle.failed(failure);
            }
            sourceStreamRequired.setState(false);
            notifyLifecycleListener();
            reportFailure(failure);
        }
    }

    private void onSurfaceDetached(SurfaceRequest request, EncoderSession session) {
        VideoDiagnostics.info(VideoDiagnostics.Event.ENCODER_SURFACE_DETACHED);
        synchronized (lock) {
            if (activeRequest == request) {
                lifecycle.surfaceDetached();
                activeRequest = null;
                codecConfigured = false;
            }
        }
        notifyLifecycleListener();
        session.close();
        synchronized (lock) {
            if (activeSession == session) {
                activeSession = null;
                lifecycle.stopped();
            }
        }
        notifyLifecycleListener();
    }

    private void onEncoderFailure(RuntimeException failure) {
        SurfaceRequest request;
        synchronized (lock) {
            lifecycle.failed(failure);
            codecConfigured = false;
            request = activeRequest;
        }
        sourceStreamRequired.setState(false);
        notifyLifecycleListener();
        if (request != null) {
            request.invalidate();
        }
        reportFailure(failure);
    }

    private void onCodecConfigured() {
        synchronized (lock) {
            if (lifecycle.state() == OutputLifecycle.State.RUNNING) {
                codecConfigured = true;
            }
        }
        VideoDiagnostics.info(VideoDiagnostics.Event.ENCODER_CODEC_CONFIGURED);
        notifyLifecycleListener();
    }

    private void reportFailure(Throwable failure) {
        // Synchronous startup failures use CameraX's request thread. Asynchronous codec failures
        // use the dedicated LiveShield-H264-Output drain thread. Listeners must hand off UI work.
        VideoDiagnostics.failure(VideoDiagnostics.Event.ENCODER_FAILURE, failure);
        errorListener.accept(Objects.requireNonNull(failure, "failure"));
    }

    @Override
    public void close() {
        EncoderSession session;
        SurfaceRequest request;
        synchronized (lock) {
            if (!lifecycle.beginStop()) {
                return;
            }
            session = activeSession;
            request = activeRequest;
            activeSession = null;
            activeRequest = null;
            codecConfigured = false;
            lifecycle.closed();
        }
        sourceStreamRequired.setState(false);
        notifyLifecycleListener();
        if (request != null) {
            request.invalidate();
        }
        if (session != null) {
            session.close();
        }
        sink.close();
        VideoDiagnostics.info(VideoDiagnostics.Event.ENCODER_CLOSED);
    }

    /** True only after the hardware codec has emitted a valid AVC output configuration. */
    public boolean isEncoderReady() {
        synchronized (lock) {
            return codecConfigured && lifecycle.state() == OutputLifecycle.State.RUNNING;
        }
    }

    /** Requests a fresh decoder-safe H.264 key frame without exposing the encoder or its surface. */
    public boolean requestKeyFrame() {
        EncoderSession session;
        synchronized (lock) {
            if (!codecConfigured || lifecycle.state() != OutputLifecycle.State.RUNNING) {
                return false;
            }
            session = activeSession;
        }
        if (session == null) {
            return false;
        }
        try {
            session.requestKeyFrame();
            return true;
        } catch (RuntimeException failure) {
            onEncoderFailure(failure);
            return false;
        }
    }

    /** Non-sensitive lifecycle state for coordinator readiness and failure handling. */
    public State state() {
        return State.valueOf(lifecycle.state().name());
    }

    private void notifyLifecycleListener() {
        State snapshotState;
        boolean snapshotReady;
        synchronized (lock) {
            snapshotState = State.valueOf(lifecycle.state().name());
            snapshotReady = codecConfigured
                    && lifecycle.state() == OutputLifecycle.State.RUNNING;
        }
        try {
            lifecycleListener.onStateChanged(snapshotState, snapshotReady);
        } catch (RuntimeException listenerFailure) {
            reportFailure(listenerFailure);
        }
    }

    private static RuntimeException asRuntimeException(Exception exception) {
        return exception instanceof RuntimeException runtimeException
                ? runtimeException
                : new IllegalStateException("Unable to start H.264 output", exception);
    }

    private static MediaSpec createMediaSpec(EncoderSettings settings) {
        return MediaSpec.builder()
                .configureVideo(video -> video
                        .setMimeType(MIME_TYPE)
                        .setBitrate(settings.bitrate())
                        .setEncodeFrameRate(settings.frameRate()))
                .build();
    }

    /** Immutable video-only codec settings; there is deliberately no audio configuration. */
    public record EncoderSettings(int bitrate, int frameRate, int keyFrameIntervalSeconds) {
        public EncoderSettings {
            if (bitrate <= 0 || frameRate <= 0 || keyFrameIntervalSeconds <= 0) {
                throw new IllegalArgumentException("Encoder settings must be positive");
            }
        }

        public static EncoderSettings defaults() {
            return new EncoderSettings(4_000_000, 30, 2);
        }
    }

    public enum State {
        IDLE,
        CONFIGURING,
        RUNNING,
        STOPPING,
        FAILED,
        CLOSED
    }

    /** Payload-free callback; callers marshal CameraX/codec-thread events to their UI executor. */
    @FunctionalInterface
    public interface LifecycleListener {
        void onStateChanged(State state, boolean encoderReady);
    }

    private static final class EncoderSession implements AutoCloseable {
        private final MediaCodec codec;
        private final Surface inputSurface;
        private final SanitizedVideoSink sink;
        private final Consumer<RuntimeException> failureListener;
        private final Runnable configurationListener;
        private final int width;
        private final int height;
        private final AtomicBoolean stopRequested = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();
        private final Thread drainThread;

        private EncoderSession(
                MediaCodec codec,
                Surface inputSurface,
                int width,
                int height,
                SanitizedVideoSink sink,
                Runnable configurationListener,
                Consumer<RuntimeException> failureListener) {
            this.codec = codec;
            this.inputSurface = inputSurface;
            this.width = width;
            this.height = height;
            this.sink = sink;
            this.configurationListener = configurationListener;
            this.failureListener = failureListener;
            drainThread = new Thread(this::drain, "LiveShield-H264-Output");
        }

        static EncoderSession create(
                int width,
                int height,
                EncoderSettings settings,
                SanitizedVideoSink sink,
                Runnable configurationListener,
                Consumer<RuntimeException> failureListener) throws IOException {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Encoder dimensions must be positive");
            }
            MediaCodec codec = MediaCodec.createEncoderByType(MIME_TYPE);
            try {
                MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrate());
                format.setInteger(MediaFormat.KEY_FRAME_RATE, settings.frameRate());
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,
                        settings.keyFrameIntervalSeconds());
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                Surface surface = codec.createInputSurface();
                return new EncoderSession(
                        codec, surface, width, height, sink,
                        configurationListener, failureListener);
            } catch (RuntimeException exception) {
                codec.release();
                throw exception;
            }
        }

        Surface inputSurface() {
            return inputSurface;
        }

        void start() {
            codec.start();
            requestKeyFrame();
            drainThread.start();
        }

        void requestKeyFrame() {
            Bundle parameters = new Bundle();
            parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(parameters);
        }

        private void drain() {
            EncodedOutputDispatcher dispatcher = new EncodedOutputDispatcher(
                    sink, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean endSignalled = false;
            try {
                while (true) {
                    if (stopRequested.get() && !endSignalled) {
                        codec.signalEndOfInputStream();
                        endSignalled = true;
                    }
                    int index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        dispatchConfiguration(codec.getOutputFormat());
                    } else if (index >= 0) {
                        ByteBuffer output = codec.getOutputBuffer(index);
                        if (output == null) {
                            throw new IllegalStateException("Codec returned no output buffer");
                        }
                        if (info.size > 0) {
                            dispatcher.dispatch(
                                    output, info.offset, info.size,
                                    info.presentationTimeUs, info.flags);
                        }
                        boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        codec.releaseOutputBuffer(index, false);
                        if (end) {
                            break;
                        }
                    }
                }
            } catch (RuntimeException exception) {
                if (!stopRequested.get()) {
                    failureListener.accept(exception);
                }
            } finally {
                releaseCodec();
            }
        }

        private void dispatchConfiguration(MediaFormat format) {
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (!MIME_TYPE.equals(mime)) {
                throw new IllegalStateException("Encoder output is not H.264 video");
            }
            ByteBuffer sps = requireCodecSpecificData(format, "csd-0");
            ByteBuffer pps = requireCodecSpecificData(format, "csd-1");
            sink.onCodecConfiguration(new H264CodecConfiguration(
                    width, height, copyRemaining(sps), copyRemaining(pps)));
            configurationListener.run();
        }

        private static ByteBuffer requireCodecSpecificData(MediaFormat format, String key) {
            ByteBuffer data = format.getByteBuffer(key);
            if (data == null || !data.hasRemaining()) {
                throw new IllegalStateException("Encoder omitted " + key);
            }
            return data;
        }

        private static byte[] copyRemaining(ByteBuffer source) {
            ByteBuffer selected = source.duplicate();
            byte[] copy = new byte[selected.remaining()];
            selected.get(copy);
            return copy;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                stopRequested.set(true);
                if (Thread.currentThread() != drainThread) {
                    try {
                        drainThread.join(2_000L);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
                releaseCodec();
            }
        }

        private void releaseCodec() {
            if (released.compareAndSet(false, true)) {
                RuntimeException cleanupFailure = ResourceCleanup.runAll(
                        codec::stop, codec::release, inputSurface::release);
                if (cleanupFailure != null) {
                    failureListener.accept(cleanupFailure);
                }
            }
        }
    }
}
