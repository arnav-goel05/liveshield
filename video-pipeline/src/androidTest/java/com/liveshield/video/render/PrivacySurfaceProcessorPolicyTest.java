package com.liveshield.video.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.util.Size;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.liveshield.privacy.decision.BoundedFrameDecisionStore;
import com.liveshield.privacy.decision.FramePrivacyDecision;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.NormalizedPoint;
import com.liveshield.video.geometry.FrameTransform;
import com.liveshield.video.geometry.CameraGeometry;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Pure policy evidence that camera-transform bootstrap cannot release regional pixels. */
@RunWith(AndroidJUnit4.class)
public final class PrivacySurfaceProcessorPolicyTest {
    @Test
    public void bootstrapWithoutCameraTransformForcesFullShield() {
        FrameTimestamp timestamp = FrameTimestamp.ofNanos(100);
        FramePrivacyDecision regional = FramePrivacyDecision.regionalSafe(
                timestamp,
                List.of(),
                FramePrivacyDecision.Basis.FRESH,
                FrameTimestamp.ofNanos(200));

        FramePrivacyDecision selected = PrivacySurfaceProcessor.selectDecisionForTransform(
                false, timestamp, regional);

        assertEquals(FramePrivacyDecision.Status.FULL_SHIELD, selected.status());
        assertEquals(FramePrivacyDecision.Basis.MISSING, selected.basis());
    }

    @Test
    public void validatedCameraTransformAllowsExactRegionalDecision() {
        FrameTimestamp timestamp = FrameTimestamp.ofNanos(100);
        FramePrivacyDecision regional = FramePrivacyDecision.regionalSafe(
                timestamp,
                List.of(),
                FramePrivacyDecision.Basis.FRESH,
                FrameTimestamp.ofNanos(200));

        assertSame(regional, PrivacySurfaceProcessor.selectDecisionForTransform(
                true, timestamp, regional));
    }

    @Test
    public void cameraXPixelMatrixIsNormalizedAcrossOffsetSensorAndBuffer() {
        Matrix sensorToBuffer = new Matrix();
        sensorToBuffer.setValues(new float[]{
            0.25f, 0.0f, -25.0f,
            0.0f, 0.25f, -50.0f,
            0.0f, 0.0f, 1.0f
        });
        FrameTransform transform = PrivacySurfaceProcessor.fromCameraXTransformation(
                new CameraGeometry(100, 200, 4100, 3200),
                new Size(1000, 750),
                sensorToBuffer,
                new Rect(0, 0, 1000, 750),
                0,
                false);

        assertEquals(new NormalizedPoint(0.2, 0.3),
                transform.mapSensorToOutput(new NormalizedPoint(0.2, 0.3)));
    }

    @Test
    public void cameraXCropAndMirrorAreAppliedAfterNormalizedSensorMapping() {
        Matrix sensorToBuffer = new Matrix();
        sensorToBuffer.setValues(new float[]{
            0.25f, 0.0f, 0.0f,
            0.0f, 0.25f, 0.0f,
            0.0f, 0.0f, 1.0f
        });
        FrameTransform transform = PrivacySurfaceProcessor.fromCameraXTransformation(
                new CameraGeometry(0, 0, 4000, 3000),
                new Size(1000, 750),
                sensorToBuffer,
                new Rect(250, 0, 750, 750),
                0,
                true);

        assertEquals(new NormalizedPoint(0.75, 0.5),
                transform.mapSensorToOutput(new NormalizedPoint(0.375, 0.5)));
    }

    @Test
    public void incomingFrameCallbackAfterCloseDoesNotReachTerminatedExecutor() {
        List<Throwable> failures = new ArrayList<>();
        AtomicBoolean terminated = new AtomicBoolean();
        PrivacySurfaceProcessor processor = new PrivacySurfaceProcessor(
                command -> {
                    if (terminated.get()) {
                        throw new RejectedExecutionException("executor terminated");
                    }
                    command.run();
                },
                new BoundedFrameDecisionStore(12, 1_000_000_000L),
                FrameTransform.fromCameraMetadata(
                        CoordinateTransform.identity(),
                        new NormalizedRect(0.0, 0.0, 1.0, 1.0),
                        0,
                        false),
                failures::add);

        processor.close();
        terminated.set(true);
        processor.dispatchIncomingFrame();

        assertEquals(List.of(), failures);
        assertEquals(PrivacySurfaceProcessor.Readiness.UNAVAILABLE, processor.readiness());
    }
}
