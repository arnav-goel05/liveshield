package com.liveshield.video.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Size;
import android.view.Surface;
import androidx.camera.core.SurfaceRequest;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.liveshield.privacy.decision.BoundedFrameDecisionStore;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.video.geometry.FrameTransform;
import com.liveshield.video.render.PrivacySurfaceProcessor;
import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class DebugSanitizedRecorderTest {
    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    @Test
    @SuppressLint("RestrictedApi") // Test owns both ends of the synthetic CameraX request.
    public void codecSurfaceProducesOneAvcVideoTrackAndZeroAudioTracks() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File file = new File(context.getCacheDir(), "sanitized-video-only.mp4");
        if (file.exists()) {
            assertTrue(file.delete());
        }
        PrivacySurfaceProcessor renderer = privacyProcessor();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        List<SanitizedVideoOutput.State> states = new CopyOnWriteArrayList<>();
        DebugSanitizedRecorder recorder = new DebugSanitizedRecorder(file);
        SanitizedVideoOutput output = new SanitizedVideoOutput(
                renderer.sanitizedOutputCapability(),
                recorder,
                ignored -> failed.countDown(),
                new SanitizedVideoOutput.EncoderSettings(250_000, 15, 1),
                (state, encoderReady) -> {
                    states.add(state);
                    if (encoderReady) {
                        ready.countDown();
                    }
                });
        SurfaceRequest request = new SurfaceRequest(
                new Size(WIDTH, HEIGHT), null, () -> { });
        try {
            assertTrue(output.isAuthorizedBy(renderer));
            output.onSurfaceRequested(request);
            Surface codecSurface = request.getDeferrableSurface().getSurface().get(
                    5, TimeUnit.SECONDS);
            try (EglFrameProducer producer = new EglFrameProducer(codecSurface)) {
                for (int frame = 0; frame < 12; frame++) {
                    producer.draw(frame * 66_666_667L);
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertTrue(output.isEncoderReady());
            assertFalse(failed.await(100, TimeUnit.MILLISECONDS));
            request.getDeferrableSurface().close();
            awaitState(output, SanitizedVideoOutput.State.IDLE);
        } finally {
            output.close();
            renderer.close();
        }

        assertTrue(states.contains(SanitizedVideoOutput.State.CONFIGURING));
        assertTrue(states.contains(SanitizedVideoOutput.State.RUNNING));
        assertTrue(states.contains(SanitizedVideoOutput.State.STOPPING));
        assertTrue(states.contains(SanitizedVideoOutput.State.IDLE));
        assertEquals(SanitizedVideoOutput.State.CLOSED, output.state());
        inspectVideoOnlyFile(file);
        assertTrue(!file.exists() || file.delete());
    }

    private static void awaitState(
            SanitizedVideoOutput output, SanitizedVideoOutput.State expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (output.state() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertEquals(expected, output.state());
    }

    private static void inspectVideoOnlyFile(File file) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            assertEquals(1, extractor.getTrackCount());
            MediaFormat format = extractor.getTrackFormat(0);
            String mime = format.getString(MediaFormat.KEY_MIME);
            assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, mime);
            assertFalse(mime.startsWith("audio/"));
            extractor.selectTrack(0);
            assertTrue(extractor.getSampleTime() >= 0);
            assertTrue((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0);
        } finally {
            extractor.release();
        }
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

    private static final class EglFrameProducer implements AutoCloseable {
        private final EGLDisplay display;
        private final EGLContext context;
        private final EGLSurface surface;

        private EglFrameProducer(Surface target) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] versions = new int[2];
            assertTrue(EGL14.eglInitialize(display, versions, 0, versions, 1));
            int[] attributes = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            assertTrue(EGL14.eglChooseConfig(
                    display, attributes, 0, configs, 0, 1, count, 0));
            assertEquals(1, count[0]);
            context = EGL14.eglCreateContext(
                    display,
                    configs[0],
                    EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE},
                    0);
            surface = EGL14.eglCreateWindowSurface(
                    display, configs[0], target, new int[]{EGL14.EGL_NONE}, 0);
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));
        }

        private void draw(long presentationTimeNanos) {
            GLES20.glViewport(0, 0, WIDTH, HEIGHT);
            GLES20.glClearColor(0.05f, 0.25f, 0.75f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeNanos);
            assertTrue(EGL14.eglSwapBuffers(display, surface));
        }

        @Override
        public void close() {
            EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }
}
