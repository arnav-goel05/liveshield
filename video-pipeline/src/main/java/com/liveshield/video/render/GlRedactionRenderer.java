package com.liveshield.video.render;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import com.liveshield.privacy.decision.FramePrivacyDecision;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.video.diagnostics.VideoDiagnostics;
import com.liveshield.video.geometry.FrameTransform;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * GPU renderer that either applies certified opaque coverage or replaces the complete frame.
 *
 * <p>Blur is intentionally not implemented. Every regional action, including a requested blur,
 * is strengthened to opaque coverage.</p>
 */
public final class GlRedactionRenderer {
    private static final int BYTES_PER_FLOAT = 4;
    public static final int OPAQUE_MASK_COLOR = Color.rgb(8, 8, 12);
    public static final int FULL_SHIELD_COLOR = Color.rgb(16, 32, 48);
    public static final double CERTIFIED_PADDING = 0.02;
    public static final double COMPRESSION_GUARD_PADDING = 0.04;
    public static final int MAX_PROTECTED_BOUNDS = 32;

    private static final float[] QUAD = {
        -1.0f, -1.0f, 0.0f, 1.0f,
        1.0f, -1.0f, 1.0f, 1.0f,
        -1.0f, 1.0f, 0.0f, 0.0f,
        1.0f, 1.0f, 1.0f, 0.0f
    };
    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n"
                    + "attribute vec2 aTexCoord;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main() { gl_Position = vec4(aPosition, 0.0, 1.0);"
                    + " vTexCoord = aTexCoord; }\n";
    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n"
                    + "uniform sampler2D uTexture;\n"
                    + "varying vec2 vTexCoord;\n"
                    + "void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }\n";

    private GlRedactionRenderer() {
    }

    /** Renders through a real GLES framebuffer; intended for deterministic device pixel evidence. */
    public static Bitmap renderForTest(
            Bitmap rawFrame,
            FramePrivacyDecision decision,
            FrameTransform frameTransform) {
        Objects.requireNonNull(rawFrame, "rawFrame");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(frameTransform, "frameTransform");
        if (rawFrame.getWidth() <= 0 || rawFrame.getHeight() <= 0) {
            throw new IllegalArgumentException("rawFrame must be non-empty");
        }
        try (OffscreenSession session = new OffscreenSession(
                rawFrame.getWidth(), rawFrame.getHeight())) {
            session.draw(rawFrame);
            applyDecision(decision, frameTransform, rawFrame.getWidth(), rawFrame.getHeight());
            return readBitmap(rawFrame.getWidth(), rawFrame.getHeight());
        }
    }

    static void applyDecision(
            FramePrivacyDecision decision,
            FrameTransform frameTransform,
            int width,
            int height) {
        if (decision.status() == FramePrivacyDecision.Status.FULL_SHIELD) {
            clearColor(FULL_SHIELD_COLOR, width, height);
            return;
        }

        List<NormalizedRect> outputBounds = paddedOutputBounds(decision, frameTransform);
        if (outputBounds == null) {
            clearColor(FULL_SHIELD_COLOR, width, height);
            return;
        }
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        setClearColor(OPAQUE_MASK_COLOR);
        for (NormalizedRect bounds : outputBounds) {
            int left = clampPixel((int) Math.floor(
                    bounds.left() * width), width);
            int right = clampPixel((int) Math.ceil(
                    bounds.right() * width), width);
            int top = clampPixel((int) Math.floor(
                    bounds.top() * height), height);
            int bottom = clampPixel((int) Math.ceil(
                    bounds.bottom() * height), height);
            if (right <= left || bottom <= top) {
                clearColor(FULL_SHIELD_COLOR, width, height);
                return;
            }
            GLES20.glScissor(left, height - bottom, right - left, bottom - top);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        checkGl("apply privacy decision");
    }

    static List<NormalizedRect> paddedOutputBounds(
            FramePrivacyDecision decision, FrameTransform transform) {
        List<NormalizedRect> result = new ArrayList<>();
        try {
            for (ProtectedRegion region : decision.regions()) {
                for (NormalizedRect bounds : region.bounds()) {
                    VideoDiagnostics.bounds(
                            VideoDiagnostics.Event.MASK_SENSOR_BOUNDS,
                            region.category(),
                            bounds.left(), bounds.top(), bounds.right(), bounds.bottom());
                    NormalizedRect output = transform.mapSensorRectToOutput(bounds);
                    VideoDiagnostics.bounds(
                            VideoDiagnostics.Event.MASK_OUTPUT_BOUNDS,
                            region.category(),
                            output.left(), output.top(), output.right(), output.bottom());
                    double padding = region.category() == FindingCategory.PRIVACY_ZONE
                            ? 0.0 : COMPRESSION_GUARD_PADDING;
                    result.add(new NormalizedRect(
                            Math.max(0.0, output.left() - padding),
                            Math.max(0.0, output.top() - padding),
                            Math.min(1.0, output.right() + padding),
                            Math.min(1.0, output.bottom() + padding)));
                    if (result.size() > MAX_PROTECTED_BOUNDS) {
                        return null;
                    }
                }
            }
        } catch (IllegalArgumentException unsafeTransform) {
            return null;
        }
        return result;
    }

    private static void clearColor(int color, int width, int height) {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glViewport(0, 0, width, height);
        setClearColor(color);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        checkGl("render full shield");
    }

    private static void setClearColor(int color) {
        GLES20.glClearColor(
                Color.red(color) / 255.0f,
                Color.green(color) / 255.0f,
                Color.blue(color) / 255.0f,
                1.0f);
    }

    private static Bitmap readBitmap(int width, int height) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4)
                .order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
        checkGl("read sanitized pixels");
        int[] argb = new int[width * height];
        for (int glY = 0; glY < height; glY++) {
            int bitmapY = height - 1 - glY;
            for (int x = 0; x < width; x++) {
                int index = (glY * width + x) * 4;
                int red = pixels.get(index) & 0xff;
                int green = pixels.get(index + 1) & 0xff;
                int blue = pixels.get(index + 2) & 0xff;
                int alpha = pixels.get(index + 3) & 0xff;
                argb[bitmapY * width + x] = Color.argb(alpha, red, green, blue);
            }
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
    }

    private static int clampPixel(int value, int extent) {
        return Math.max(0, Math.min(extent, value));
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
            throw new IllegalStateException("Unable to compile privacy shader: " + log);
        }
        return shader;
    }

    private static int linkProgram() {
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
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
            throw new IllegalStateException("Unable to link privacy shader: " + log);
        }
        return program;
    }

    private static void checkGl(String operation) {
        int error = GLES20.glGetError();
        if (error != GLES20.GL_NO_ERROR) {
            throw new IllegalStateException(operation + " failed with GLES error 0x"
                    + Integer.toHexString(error));
        }
    }

    private static final class OffscreenSession implements AutoCloseable {
        private final EGLDisplay display;
        private final EGLContext context;
        private final EGLSurface surface;
        private final int program;
        private final int texture;
        private final FloatBuffer quad;

        private OffscreenSession(int width, int height) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] versions = new int[2];
            if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, versions, 0, versions, 1)) {
                throw new IllegalStateException("Unable to initialize EGL display");
            }
            int[] configAttributes = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0)
                    || count[0] == 0) {
                throw new IllegalStateException("Unable to choose privacy EGL config");
            }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[]{EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE
                    || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw new IllegalStateException("Unable to create privacy EGL context");
            }
            program = linkProgram();
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            texture = textures[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            quad = ByteBuffer.allocateDirect(QUAD.length * BYTES_PER_FLOAT)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(QUAD).position(0);
            checkGl("initialize privacy renderer");
        }

        private void draw(Bitmap bitmap) {
            GLES20.glViewport(0, 0, bitmap.getWidth(), bitmap.getHeight());
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
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
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(position);
            GLES20.glDisableVertexAttribArray(textureCoordinate);
            checkGl("draw raw texture into renderer-owned framebuffer");
        }

        @Override
        public void close() {
            GLES20.glDeleteTextures(1, new int[]{texture}, 0);
            GLES20.glDeleteProgram(program);
            EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }
}
