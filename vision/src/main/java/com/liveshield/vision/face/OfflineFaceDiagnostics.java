package com.liveshield.vision.face;

import android.util.Log;
import java.util.Objects;

/** Payload-free failure diagnostics for offline face preprocessing and native inference. */
final class OfflineFaceDiagnostics {
    private static final String TAG = "OfflineFaceDiagnostic";

    private OfflineFaceDiagnostics() {
    }

    static void report(Stage stage, RuntimeException failure) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(failure, "failure");
        Log.w(TAG, "stage=" + stage + " exception=" + failure.getClass().getSimpleName());
    }

    static void reportMalformedOutput(int rows, int columns, int channels, int type) {
        Log.w(TAG, malformedOutputMessage(rows, columns, channels, type));
    }

    static String malformedOutputMessage(int rows, int columns, int channels, int type) {
        return "stage=" + Stage.OUTPUT_PARSE
                + " exception=MalformedOutput"
                + " rows=" + rows
                + " columns=" + columns
                + " channels=" + channels
                + " type=" + type;
    }

    enum Stage {
        BITMAP,
        BITMAP_TO_MAT,
        COLOR_CONVERSION,
        INPUT_RESIZE,
        DETECTOR_CONFIGURATION,
        DETECTOR_INFERENCE,
        OUTPUT_PARSE
    }
}
