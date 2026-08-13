package com.liveshield.video.diagnostics;

import android.util.Log;
import com.liveshield.video.BuildConfig;
import java.util.Objects;

/** Payload-free lifecycle diagnostics for the privacy-owned camera and encoder graph. */
public final class VideoDiagnostics {
    private static final String TAG = "LiveShieldVideo";

    private VideoDiagnostics() {
    }

    public static void info(Event event) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        writeInfo("event=" + Objects.requireNonNull(event, "event").name());
    }

    public static void state(Event event, Enum<?> state) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        writeInfo("event=" + Objects.requireNonNull(event, "event").name()
                + " state=" + Objects.requireNonNull(state, "state").name());
    }

    public static void result(Event event, int resultCode) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        writeInfo("event=" + Objects.requireNonNull(event, "event").name()
                + " result_code=" + resultCode);
    }

    public static void failure(Event event, Throwable failure) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        writeError("event=" + Objects.requireNonNull(event, "event").name()
                + " failure_type="
                + Objects.requireNonNull(failure, "failure").getClass().getSimpleName());
    }

    public static void bounds(
            Event event, Enum<?> category,
            double left, double top, double right, double bottom) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        writeInfo("event=" + Objects.requireNonNull(event, "event").name()
                + " category=" + Objects.requireNonNull(category, "category").name()
                + " left_bp=" + basisPoints(left)
                + " top_bp=" + basisPoints(top)
                + " right_bp=" + basisPoints(right)
                + " bottom_bp=" + basisPoints(bottom));
    }

    public static void transform(
            Event event, int rotationDegrees, boolean mirrored, double[] matrix) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        Objects.requireNonNull(matrix, "matrix");
        if (matrix.length != 9) {
            return;
        }
        StringBuilder message = new StringBuilder("event=")
                .append(Objects.requireNonNull(event, "event").name())
                .append(" rotation=").append(rotationDegrees)
                .append(" mirrored=").append(mirrored);
        for (int index = 0; index < matrix.length; index++) {
            message.append(" m").append(index).append("_bp=")
                    .append(basisPoints(matrix[index]));
        }
        writeInfo(message.toString());
    }

    private static int basisPoints(double value) {
        return (int) Math.round(value * 10_000.0);
    }

    private static void writeInfo(String message) {
        try {
            Log.i(TAG, message);
        } catch (RuntimeException unavailableBackend) {
            // Diagnostics must never alter app behavior, including in host JVM tests.
        }
    }

    private static void writeError(String message) {
        try {
            Log.e(TAG, message);
        } catch (RuntimeException unavailableBackend) {
            // Diagnostics must never alter app behavior, including in host JVM tests.
        }
    }

    public enum Event {
        CAMERA_BIND_REQUESTED,
        CAMERA_BOUND,
        CAMERA_BIND_FAILED,
        CAMERA_CLOSED,
        PROCESSOR_INPUT_REQUESTED,
        PROCESSOR_INPUT_DUPLICATE,
        PROCESSOR_INPUT_CREATED,
        PROCESSOR_INPUT_PROVIDED,
        PROCESSOR_INPUT_RESULT,
        PROCESSOR_TRANSFORM_READY,
        PROCESSOR_SENSOR_TO_OUTPUT,
        PROCESSOR_SENSOR_TO_DISPLAY,
        PROCESSOR_TRANSFORM_FAILED,
        PROCESSOR_OUTPUT_REQUESTED,
        PROCESSOR_OUTPUT_TEXTURE_MATRIX,
        PROCESSOR_OUTPUT_ADDED,
        PROCESSOR_OUTPUT_REMOVED,
        PROCESSOR_FRAME_FAILED,
        PROCESSOR_DEADLINE_FAILED,
        PROCESSOR_CAMERAX_FAILED,
        PROCESSOR_BUFFER_FAILED,
        PROCESSOR_RENDER_MODE,
        MASK_SENSOR_BOUNDS,
        MASK_OUTPUT_BOUNDS,
        PROCESSOR_RECOVERY_STATE,
        PROCESSOR_READINESS_CHANGED,
        PROCESSOR_FAILURE,
        PROCESSOR_CLOSED,
        ENCODER_SURFACE_REQUESTED,
        ENCODER_SESSION_CREATED,
        ENCODER_STARTED,
        ENCODER_SURFACE_PROVIDED,
        ENCODER_SURFACE_DETACHED,
        ENCODER_CODEC_CONFIGURED,
        ENCODER_FAILURE,
        ENCODER_CLOSED
    }

    public enum FrameStage {
        RECOVERY,
        COPY,
        ACCEPT,
        PROCESS,
        DEADLINE
    }

    public enum RenderMode {
        REGIONAL,
        FULL_SHIELD
    }
}
