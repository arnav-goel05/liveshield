package com.liveshield.video.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.graphics.SurfaceTexture;
import android.os.Looper;
import android.view.Surface;
import androidx.camera.core.CameraEffect;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.core.ProcessingException;
import androidx.camera.core.SurfaceOutput;
import androidx.camera.core.SurfaceProcessor;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.core.UseCase;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoOutput;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import com.liveshield.privacy.decision.BoundedFrameDecisionStore;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.video.geometry.FrameTransform;
import com.liveshield.video.output.H264CodecConfiguration;
import com.liveshield.video.output.SanitizedH264AccessUnit;
import com.liveshield.video.output.SanitizedVideoOutput;
import com.liveshield.video.output.SanitizedVideoSink;
import com.liveshield.video.render.PrivacySurfaceProcessor;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CameraBindingTest {
    // These fakes define the downstream surface boundary without fabricating CameraX requests.
    // SurfaceRequest ownership and release are exercised by the T038 processor and T043 output.
    private static final long TIMEOUT_SECONDS = 5;
    private final ExecutorService bindingExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();

    @After
    public void tearDown() throws InterruptedException {
        bindingExecutor.shutdownNow();
        analysisExecutor.shutdownNow();
        bindingExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        analysisExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    public void bindsPreviewAnalysisAndVideoAsOnePrivacyEffectGroup() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        TestPrivacyEffect privacyEffect = TestPrivacyEffect.forPreviewAndVideo();
        FakePreviewSurfaceProvider previewSurface = new FakePreviewSurfaceProvider();
        FakeVideoOutput videoOutput = new FakeVideoOutput();
        CameraSessionController controller = controller(
                backend, privacyEffect, previewSurface, videoOutput);

        controller.bindAsync().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(CameraSessionController.State.BOUND, controller.state());
        assertEquals(1, backend.bindCount);
        assertSame(privacyEffect, backend.group.getEffects().get(0));
        assertEquals(
                CameraEffect.PREVIEW | CameraEffect.VIDEO_CAPTURE,
                privacyEffect.getTargets());
        assertUseCaseTypes(backend.group.getUseCases());
        closeOnMain(controller);
        assertEquals(1, backend.unbindCount());
        previewSurface.close();
        videoOutput.close();
    }

    @Test
    public void bindFutureCompletesOnlyAfterBackendFinishes() throws Exception {
        BlockingBackend backend = new BlockingBackend();
        CameraSessionController controller = controller(
                backend,
                TestPrivacyEffect.forPreviewAndVideo(),
                new FakePreviewSurfaceProvider(),
                new FakeVideoOutput());

        ListenableFuture<Void> binding = controller.bindAsync();
        assertTrue(backend.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(CameraSessionController.State.BINDING, controller.state());
        assertFalse(binding.isDone());

        backend.release.countDown();
        binding.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(CameraSessionController.State.BOUND, controller.state());
        closeOnMain(controller);
    }

    @Test
    public void closeDuringBindingClosesImmediatelyAndCleansUpLateBind() throws Exception {
        BlockingBackend backend = new BlockingBackend();
        CameraSessionController controller = controller(
                backend,
                TestPrivacyEffect.forPreviewAndVideo(),
                new FakePreviewSurfaceProvider(),
                new FakeVideoOutput());

        ListenableFuture<Void> binding = controller.bindAsync();
        assertTrue(backend.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        closeOnMain(controller);

        assertEquals(CameraSessionController.State.CLOSED, controller.state());
        assertFalse(binding.isDone());
        backend.release.countDown();
        assertThrows(Exception.class,
                () -> binding.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, backend.unbindCount());
    }

    @Test
    public void failedBindUnbindsOwnedUseCasesAndLeavesNoBoundState() {
        RecordingBackend backend = new RecordingBackend();
        backend.failure = new IllegalStateException("synthetic bind failure");
        CameraSessionController controller = controller(
                backend,
                TestPrivacyEffect.forPreviewAndVideo(),
                new FakePreviewSurfaceProvider(),
                new FakeVideoOutput());

        assertThrows(Exception.class,
                () -> controller.bindAsync().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        assertEquals(CameraSessionController.State.UNBOUND, controller.state());
        assertEquals(1, backend.unbindCount());
        closeOnMain(controller);
    }

    @Test
    public void closeIsIdempotentAndPreventsRebinding() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        CameraSessionController controller = controller(
                backend,
                TestPrivacyEffect.forPreviewAndVideo(),
                new FakePreviewSurfaceProvider(),
                new FakeVideoOutput());
        controller.bindAsync().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        closeOnMain(controller);
        closeOnMain(controller);

        assertEquals(CameraSessionController.State.CLOSED, controller.state());
        assertEquals(1, backend.unbindCount());
        assertThrows(IllegalStateException.class, controller::bindAsync);
    }

    @Test
    public void effectMissingVideoTargetIsRejectedBeforeAnyBind() {
        RecordingBackend backend = new RecordingBackend();

        assertThrows(IllegalArgumentException.class,
                () -> controller(
                        backend,
                        TestPrivacyEffect.previewOnly(),
                        new FakePreviewSurfaceProvider(),
                        new FakeVideoOutput()));

        assertEquals(0, backend.bindCount);
    }

    @Test
    public void productionOutputFromDifferentPrivacyProcessorIsRejected() {
        PrivacySurfaceProcessor effectOwner = privacyProcessor();
        PrivacySurfaceProcessor outputOwner = privacyProcessor();
        SanitizedVideoOutput output = new SanitizedVideoOutput(
                outputOwner.sanitizedOutputCapability(),
                new SanitizedVideoSink() {
                    @Override
                    public void onCodecConfiguration(H264CodecConfiguration configuration) {
                    }

                    @Override
                    public void onAccessUnit(SanitizedH264AccessUnit accessUnit) {
                    }
                },
                ignored -> { },
                SanitizedVideoOutput.EncoderSettings.defaults());
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> CameraSessionController.requireAuthorizedOutput(
                            effectOwner.cameraEffect(), output));
        } finally {
            output.close();
            effectOwner.close();
            outputOwner.close();
        }
    }

    private CameraSessionController controller(
            CameraSessionController.CameraBindingBackend backend,
            CameraEffect effect,
            Preview.SurfaceProvider previewSurfaceProvider,
            VideoOutput videoOutput) {
        AtomicReference<CameraSessionController> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(
                new CameraSessionController(
                        backend,
                        new StartedLifecycleOwner(),
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        effect,
                        previewSurfaceProvider,
                        image -> image.close(),
                        videoOutput,
                        bindingExecutor,
                        analysisExecutor)));
        return result.get();
    }

    private static void closeOnMain(CameraSessionController controller) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(controller::close);
    }

    private static void assertUseCaseTypes(List<UseCase> useCases) {
        assertEquals(3, useCases.size());
        assertEquals(1, useCases.stream().filter(Preview.class::isInstance).count());
        assertEquals(1, useCases.stream().filter(ImageAnalysis.class::isInstance).count());
        assertEquals(1, useCases.stream().filter(VideoCapture.class::isInstance).count());
    }

    private static PrivacySurfaceProcessor privacyProcessor() {
        FrameTransform transform = FrameTransform.fromCameraMetadata(
                CoordinateTransform.identity(),
                new NormalizedRect(0.0, 0.0, 1.0, 1.0),
                0,
                false);
        return new PrivacySurfaceProcessor(
                Runnable::run,
                new BoundedFrameDecisionStore(12, 1_000_000_000L),
                transform,
                ignored -> { });
    }

    private static class RecordingBackend
            implements CameraSessionController.CameraBindingBackend {
        private int bindCount;
        private int unbindCount;
        private UseCaseGroup group;
        private RuntimeException failure;

        @Override
        public void bind(
                LifecycleOwner lifecycleOwner,
                CameraSelector cameraSelector,
                UseCaseGroup useCaseGroup) {
            bindCount++;
            group = useCaseGroup;
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void unbind(UseCase... useCases) {
            unbindCount++;
        }

        final int unbindCount() {
            return unbindCount;
        }
    }

    private static final class BlockingBackend extends RecordingBackend {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void bind(
                LifecycleOwner lifecycleOwner,
                CameraSelector cameraSelector,
                UseCaseGroup useCaseGroup) {
            entered.countDown();
            try {
                if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for deterministic release");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Binding interrupted", exception);
            }
            super.bind(lifecycleOwner, cameraSelector, useCaseGroup);
        }
    }

    private static final class StartedLifecycleOwner implements LifecycleOwner {
        private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);

        private StartedLifecycleOwner() {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                throw new IllegalStateException("Test lifecycle must be created on the main thread");
            }
            lifecycle.markState(Lifecycle.State.STARTED);
        }

        @Override
        public Lifecycle getLifecycle() {
            return lifecycle;
        }
    }

    private static final class FakePreviewSurfaceProvider
            implements Preview.SurfaceProvider, AutoCloseable {
        private final FakeSurface surface = new FakeSurface();

        @Override
        public void onSurfaceRequested(SurfaceRequest request) {
            surface.provideTo(request);
        }

        @Override
        public void close() {
            surface.close();
        }
    }

    private static final class FakeVideoOutput implements VideoOutput, AutoCloseable {
        private final FakeSurface surface = new FakeSurface();

        @Override
        public void onSurfaceRequested(SurfaceRequest request) {
            surface.provideTo(request);
        }

        @Override
        public void close() {
            surface.close();
        }
    }

    private static final class FakeSurface implements AutoCloseable {
        private final SurfaceTexture texture = new SurfaceTexture(0);
        private final Surface surface;
        private final AtomicBoolean closed = new AtomicBoolean();

        private FakeSurface() {
            texture.setDefaultBufferSize(16, 16);
            surface = new Surface(texture);
        }

        private void provideTo(SurfaceRequest request) {
            request.provideSurface(surface, Runnable::run, ignored -> close());
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                surface.release();
                texture.release();
            }
        }
    }

    private static final class TestPrivacyEffect extends CameraEffect {
        private TestPrivacyEffect(int targets) {
            super(targets, Runnable::run, new RejectingSurfaceProcessor(), ignored -> { });
        }

        private static TestPrivacyEffect forPreviewAndVideo() {
            return new TestPrivacyEffect(CameraEffect.PREVIEW | CameraEffect.VIDEO_CAPTURE);
        }

        private static TestPrivacyEffect previewOnly() {
            return new TestPrivacyEffect(CameraEffect.PREVIEW);
        }
    }

    private static final class RejectingSurfaceProcessor implements SurfaceProcessor {
        @Override
        public void onInputSurface(SurfaceRequest request) throws ProcessingException {
            request.willNotProvideSurface();
        }

        @Override
        public void onOutputSurface(SurfaceOutput surfaceOutput) throws ProcessingException {
            surfaceOutput.close();
        }
    }
}
