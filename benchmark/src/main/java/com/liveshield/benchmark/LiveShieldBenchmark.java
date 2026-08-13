package com.liveshield.benchmark;

import androidx.benchmark.macro.CompilationMode;
import androidx.benchmark.macro.FrameTimingMetric;
import androidx.benchmark.macro.StartupMode;
import androidx.benchmark.macro.StartupTimingMetric;
import androidx.benchmark.macro.TraceSectionMetric;
import androidx.benchmark.macro.junit4.MacrobenchmarkRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import java.util.List;
import kotlin.Unit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Physical-device macrobenchmarks; results are evidence only after a recorded device run. */
@LargeTest
@RunWith(AndroidJUnit4.class)
public final class LiveShieldBenchmark {
    private static final String TARGET_PACKAGE = "com.liveshield.app";
    private static final String SETUP_CREATE_TRACE = "LiveShieldSetupCreate";
    private static final int STARTUP_ITERATIONS = 10;
    private static final int FRAME_ITERATIONS = 10;

    @Rule
    public final MacrobenchmarkRule benchmarkRule = new MacrobenchmarkRule();

    /** Measures a true process-cold launcher start without granting camera permission. */
    @Test
    public void coldStart() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                List.of(new StartupTimingMetric()),
                new CompilationMode.None(),
                StartupMode.COLD,
                STARTUP_ITERATIONS,
                scope -> {
                    scope.pressHome();
                    return Unit.INSTANCE;
                },
                scope -> {
                    scope.startActivityAndWait();
                    return Unit.INSTANCE;
                });
    }

    /** Captures rendered-frame timing plus the app-owned setup-construction trace section. */
    @Test
    public void setupFrameTimingAndTrace() {
        benchmarkRule.measureRepeated(
                TARGET_PACKAGE,
                List.of(
                        new FrameTimingMetric(),
                        new TraceSectionMetric(SETUP_CREATE_TRACE)),
                new CompilationMode.None(),
                StartupMode.COLD,
                FRAME_ITERATIONS,
                scope -> {
                    scope.pressHome();
                    return Unit.INSTANCE;
                },
                scope -> {
                    scope.startActivityAndWait();
                    scope.getDevice().waitForIdle();
                    return Unit.INSTANCE;
                });
    }
}
