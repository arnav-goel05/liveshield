package com.liveshield.video.camera;

import android.os.Looper;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.camera.core.CameraEffect;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCase;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoOutput;
import androidx.lifecycle.LifecycleOwner;
import com.google.common.util.concurrent.ListenableFuture;
import com.liveshield.video.output.SanitizedVideoOutput;
import com.liveshield.video.render.PrivacySurfaceProcessor;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Binds analysis plus privacy-effect downstream preview and video as one lifecycle-owned group.
 *
 * <p>The mandatory effect must target both Preview and VideoCapture. There is deliberately no
 * overload that binds either downstream use case without that effect.</p>
 */
public final class CameraSessionController implements AutoCloseable {
    private static final int PRIVACY_EFFECT_TARGETS =
            CameraEffect.PREVIEW | CameraEffect.VIDEO_CAPTURE;

    private final CameraBindingBackend backend;
    private final LifecycleOwner lifecycleOwner;
    private final CameraSelector cameraSelector;
    private final Executor bindingExecutor;
    private final Preview preview;
    private final ImageAnalysis imageAnalysis;
    private final VideoCapture<VideoOutput> videoCapture;
    private final UseCase[] ownedUseCases;
    private final UseCaseGroup useCaseGroup;
    private State state = State.UNBOUND;

    /**
     * Creates a production CameraX controller.
     *
     * <p>Construction and {@link #close()} must run on the application main thread. The supplied
     * binding executor must also dispatch on that thread because ProcessCameraProvider lifecycle
     * binding is a main-thread CameraX API.</p>
     */
    public CameraSessionController(
            ProcessCameraProvider cameraProvider,
            LifecycleOwner lifecycleOwner,
            CameraSelector cameraSelector,
            CameraEffect privacyEffect,
            Preview.SurfaceProvider previewSurfaceProvider,
            ImageAnalysis.Analyzer analyzer,
            SanitizedVideoOutput sanitizedVideoOutput,
            Executor bindingExecutor,
            Executor analysisExecutor) {
        this(
                new ProcessCameraProviderBackend(cameraProvider),
                lifecycleOwner,
                cameraSelector,
                preparePrivacyEffect(cameraProvider, cameraSelector, privacyEffect),
                previewSurfaceProvider,
                analyzer,
                requireAuthorizedOutput(privacyEffect, sanitizedVideoOutput),
                bindingExecutor,
                analysisExecutor);
    }

    CameraSessionController(
            CameraBindingBackend backend,
            LifecycleOwner lifecycleOwner,
            CameraSelector cameraSelector,
            CameraEffect privacyEffect,
            Preview.SurfaceProvider previewSurfaceProvider,
            ImageAnalysis.Analyzer analyzer,
            VideoOutput sanitizedVideoOutput,
            Executor bindingExecutor,
            Executor analysisExecutor) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.lifecycleOwner = Objects.requireNonNull(lifecycleOwner, "lifecycleOwner");
        this.cameraSelector = Objects.requireNonNull(cameraSelector, "cameraSelector");
        this.bindingExecutor = Objects.requireNonNull(bindingExecutor, "bindingExecutor");
        if (backend instanceof ProcessCameraProviderBackend) {
            requireMainThread("CameraSessionController construction");
        }
        Objects.requireNonNull(previewSurfaceProvider, "previewSurfaceProvider");
        Objects.requireNonNull(analyzer, "analyzer");
        Objects.requireNonNull(sanitizedVideoOutput, "sanitizedVideoOutput");
        Objects.requireNonNull(analysisExecutor, "analysisExecutor");
        validatePrivacyEffect(privacyEffect);

        preview = new Preview.Builder()
                .setTargetName("LiveShield-Privacy-Preview")
                .build();
        preview.setSurfaceProvider(previewSurfaceProvider);

        imageAnalysis = new ImageAnalysis.Builder()
                .setTargetName("LiveShield-OnDevice-Analysis")
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        imageAnalysis.setAnalyzer(analysisExecutor, analyzer);

        videoCapture = VideoCapture.withOutput(sanitizedVideoOutput);
        ownedUseCases = new UseCase[]{preview, imageAnalysis, videoCapture};
        UseCaseGroup.Builder groupBuilder = new UseCaseGroup.Builder();
        for (UseCase useCase : ownedUseCases) {
            groupBuilder.addUseCase(useCase);
        }
        useCaseGroup = groupBuilder.addEffect(privacyEffect).build();
    }

    public synchronized ListenableFuture<Void> bindAsync() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Camera session controller is closed");
        }
        if (state != State.UNBOUND) {
            throw new IllegalStateException("Camera session is already binding or bound");
        }
        state = State.BINDING;
        return CallbackToFutureAdapter.getFuture(completer -> {
            try {
                bindingExecutor.execute(() -> performBind(completer));
            } catch (RuntimeException exception) {
                state = State.UNBOUND;
                completer.setException(exception);
            }
            return "LiveShield CameraX privacy-group binding";
        });
    }

    private void performBind(CallbackToFutureAdapter.Completer<Void> result) {
        synchronized (this) {
            if (state == State.CLOSED) {
                result.setException(
                        new IllegalStateException("Camera session closed before binding"));
                return;
            }
        }
        try {
            backend.bind(lifecycleOwner, cameraSelector, useCaseGroup);
        } catch (RuntimeException exception) {
            try {
                backend.unbind(ownedUseCases);
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            synchronized (this) {
                if (state != State.CLOSED) {
                    state = State.UNBOUND;
                }
            }
            result.setException(exception);
            return;
        }

        boolean closedDuringBinding;
        synchronized (this) {
            closedDuringBinding = state == State.CLOSED;
            if (!closedDuringBinding) {
                state = State.BOUND;
            }
        }
        if (closedDuringBinding) {
            IllegalStateException closedFailure =
                    new IllegalStateException("Camera session closed while binding");
            try {
                backend.unbind(ownedUseCases);
            } catch (RuntimeException cleanupFailure) {
                closedFailure.addSuppressed(cleanupFailure);
            }
            result.setException(closedFailure);
            return;
        }
        result.set(null);
    }

    public synchronized State state() {
        return state;
    }

    @Override
    public void close() {
        if (backend instanceof ProcessCameraProviderBackend) {
            requireMainThread("CameraSessionController.close");
        }
        boolean unbindNow;
        synchronized (this) {
            if (state == State.CLOSED) {
                return;
            }
            unbindNow = state == State.BOUND;
            state = State.CLOSED;
        }
        try {
            if (unbindNow) {
                backend.unbind(ownedUseCases);
            }
        } finally {
            imageAnalysis.clearAnalyzer();
        }
    }

    private static void validatePrivacyEffect(CameraEffect privacyEffect) {
        Objects.requireNonNull(privacyEffect, "privacyEffect");
        if ((privacyEffect.getTargets() & PRIVACY_EFFECT_TARGETS) != PRIVACY_EFFECT_TARGETS) {
            throw new IllegalArgumentException(
                    "Privacy effect must target both Preview and VideoCapture");
        }
        if (privacyEffect.getSurfaceProcessor() == null) {
            throw new IllegalArgumentException("Privacy effect must own a SurfaceProcessor");
        }
    }

    static SanitizedVideoOutput requireAuthorizedOutput(
            CameraEffect privacyEffect, SanitizedVideoOutput sanitizedVideoOutput) {
        Objects.requireNonNull(sanitizedVideoOutput, "sanitizedVideoOutput");
        if (!sanitizedVideoOutput.isAuthorizedBy(privacyEffect.getSurfaceProcessor())) {
            throw new IllegalArgumentException(
                    "Video output must be authorized by the exact privacy surface processor");
        }
        return sanitizedVideoOutput;
    }

    private static CameraEffect preparePrivacyEffect(
            ProcessCameraProvider cameraProvider,
            CameraSelector cameraSelector,
            CameraEffect privacyEffect) {
        Objects.requireNonNull(cameraProvider, "cameraProvider");
        Objects.requireNonNull(cameraSelector, "cameraSelector");
        Objects.requireNonNull(privacyEffect, "privacyEffect");
        if (!(privacyEffect.getSurfaceProcessor() instanceof PrivacySurfaceProcessor processor)) {
            throw new IllegalArgumentException(
                    "Production privacy effect must own a PrivacySurfaceProcessor");
        }
        processor.configureCameraInfo(cameraProvider.getCameraInfo(cameraSelector));
        return privacyEffect;
    }

    private static void requireMainThread(String operation) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException(operation + " must run on the application main thread");
        }
    }

    /** Narrow seam for deterministic tests without depending on unpublished CameraX test APIs. */
    interface CameraBindingBackend {
        void bind(
                LifecycleOwner lifecycleOwner,
                CameraSelector cameraSelector,
                UseCaseGroup useCaseGroup);

        void unbind(UseCase... useCases);
    }

    private static final class ProcessCameraProviderBackend implements CameraBindingBackend {
        private final ProcessCameraProvider cameraProvider;

        private ProcessCameraProviderBackend(ProcessCameraProvider cameraProvider) {
            this.cameraProvider = Objects.requireNonNull(cameraProvider, "cameraProvider");
        }

        @Override
        public void bind(
                LifecycleOwner lifecycleOwner,
                CameraSelector cameraSelector,
                UseCaseGroup useCaseGroup) {
            requireMainThread("ProcessCameraProvider.bindToLifecycle");
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, useCaseGroup);
        }

        @Override
        public void unbind(UseCase... useCases) {
            requireMainThread("ProcessCameraProvider.unbind");
            cameraProvider.unbind(useCases);
        }
    }

    public enum State {
        UNBOUND,
        BINDING,
        BOUND,
        CLOSED
    }
}
