package com.liveshield.app.diagnostics;

import android.util.Log;
import com.liveshield.app.BuildConfig;
import java.util.Objects;

/** Payload-free composition and setup diagnostics; never accepts user or media strings. */
public final class AppDiagnostics {
    private static final String TAG = "LiveShieldApp";

    private AppDiagnostics() {
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

    public static void states(Event event, Enum<?> component, Enum<?> state) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        writeInfo("event=" + Objects.requireNonNull(event, "event").name()
                + " component=" + Objects.requireNonNull(component, "component").name()
                + " state=" + Objects.requireNonNull(state, "state").name());
    }

    public static void failure(Event event, Throwable failure) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        writeError("event=" + Objects.requireNonNull(event, "event").name()
                + " failure_type="
                + Objects.requireNonNull(failure, "failure").getClass().getSimpleName());
    }

    public static void dimensions(Event event, int width, int height) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        writeInfo("event=" + Objects.requireNonNull(event, "event").name()
                + " width=" + width + " height=" + height);
    }

    public static void stateCount(Event event, Enum<?> state, int count) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        writeInfo("event=" + Objects.requireNonNull(event, "event").name()
                + " state=" + Objects.requireNonNull(state, "state").name()
                + " count=" + count);
    }

    public static void bounds(
            Event event, long id, double left, double top, double right, double bottom) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        writeInfo("event=" + Objects.requireNonNull(event, "event").name()
                + " id=" + id
                + " left_bp=" + basisPoints(left)
                + " top_bp=" + basisPoints(top)
                + " right_bp=" + basisPoints(right)
                + " bottom_bp=" + basisPoints(bottom));
    }

    public static void matrix(Event event, double[] matrix) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        Objects.requireNonNull(matrix, "matrix");
        if (matrix.length != 9) {
            return;
        }
        StringBuilder message = new StringBuilder("event=")
                .append(Objects.requireNonNull(event, "event").name());
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
        CAMERA_PERMISSION_GRANTED,
        CAMERA_PERMISSION_DENIED,
        SESSION_FACTORY_REQUESTED,
        CAMERA_PROVIDER_READY,
        SESSION_GRAPH_COMPOSE_STARTED,
        SESSION_GRAPH_COMPOSED,
        DETECTOR_SNAPSHOT,
        DETECTOR_FAILURE,
        FACE_SENSOR_BOUNDS,
        BARCODE_SENSOR_BOUNDS,
        FACE_OVERLAY_BOUNDS,
        FACE_REGION_TAP_HIT,
        FACE_REGION_TAP_MISS,
        HOST_SELECTED,
        HOST_DESELECTED,
        PRIVACY_ZONE_EDIT_REJECTED,
        ANALYSIS_RESOLUTION,
        ANALYSIS_SENSOR_TO_BUFFER,
        DECISION_BASIS,
        THERMAL_STATE,
        SCENE_CHANGED,
        SESSION_FACTORY_CREATED,
        SESSION_FACTORY_FAILED,
        COORDINATOR_BEGIN,
        COORDINATOR_CAMERA_BOUND,
        COORDINATOR_CAMERA_FAILED,
        COORDINATOR_RENDERER_STATE,
        COORDINATOR_ENCODER_STATE,
        COORDINATOR_COMPONENT_FAILED,
        COORDINATOR_STOPPED,
        PREVIEW_ATTACHED,
        PREVIEW_CLOSED
    }
}
