package com.liveshield.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.graphics.Bitmap;
import android.graphics.Color;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.camera.core.CameraEffect;
import com.liveshield.privacy.decision.BoundedFrameDecisionStore;
import com.liveshield.privacy.decision.FramePrivacyDecision;
import com.liveshield.privacy.model.ConfidenceClass;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.model.ProtectionAction;
import com.liveshield.video.geometry.FrameTransform;
import com.liveshield.video.render.GlRedactionRenderer;
import com.liveshield.video.render.PrivacySurfaceProcessor;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Device GPU pixel evidence for the only raw-to-sanitized rendering boundary. */
@RunWith(AndroidJUnit4.class)
public final class RedactionRendererTest {
    private static final int SIZE = 64;
    private static final FrameTimestamp TIMESTAMP = FrameTimestamp.ofNanos(100);

    @Test
    public void regionalDecisionPreservesVisiblePixelsAndCoversProtectedPixels() {
        Bitmap source = sentinelBitmap();
        FramePrivacyDecision decision = regional(new NormalizedRect(0.30, 0.30, 0.70, 0.70));

        Bitmap result = GlRedactionRenderer.renderForTest(
                source, decision, identityTransform());

        assertEquals(GlRedactionRenderer.OPAQUE_MASK_COLOR, result.getPixel(32, 32));
        assertNotEquals(GlRedactionRenderer.OPAQUE_MASK_COLOR, result.getPixel(4, 4));
        assertRgbNear(source.getPixel(4, 4), result.getPixel(4, 4), 1);
    }

    @Test
    public void fullShieldUnconditionallyReplacesEveryRawPixel() {
        Bitmap result = GlRedactionRenderer.renderForTest(
                sentinelBitmap(),
                FramePrivacyDecision.fullShield(TIMESTAMP, FramePrivacyDecision.Basis.ERROR),
                identityTransform());

        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                assertEquals(GlRedactionRenderer.FULL_SHIELD_COLOR, result.getPixel(x, y));
            }
        }
    }

    @Test
    public void cropMapsProtectionToOutputPixels() {
        FrameTransform transform = FrameTransform.fromCameraMetadata(
                CoordinateTransform.identity(),
                new NormalizedRect(0.25, 0.25, 0.75, 0.75),
                0,
                false);
        Bitmap result = GlRedactionRenderer.renderForTest(
                sentinelBitmap(),
                regional(new NormalizedRect(0.25, 0.25, 0.50, 0.50)),
                transform);

        assertEquals(GlRedactionRenderer.OPAQUE_MASK_COLOR, result.getPixel(16, 16));
        assertNotEquals(GlRedactionRenderer.OPAQUE_MASK_COLOR, result.getPixel(52, 52));
    }

    @Test
    public void clockwiseRotationMapsProtectionToOutputPixels() {
        FrameTransform transform = FrameTransform.fromCameraMetadata(
                CoordinateTransform.identity(), fullCrop(), 90, false);
        Bitmap result = GlRedactionRenderer.renderForTest(
                sentinelBitmap(), regional(new NormalizedRect(0.10, 0.10, 0.30, 0.30)), transform);

        assertEquals(GlRedactionRenderer.OPAQUE_MASK_COLOR, result.getPixel(51, 13));
        assertNotEquals(GlRedactionRenderer.OPAQUE_MASK_COLOR, result.getPixel(13, 13));
    }

    @Test
    public void frontCameraMirrorMapsProtectionToOutputPixels() {
        FrameTransform transform = FrameTransform.fromCameraMetadata(
                CoordinateTransform.identity(), fullCrop(), 0, true);
        Bitmap result = GlRedactionRenderer.renderForTest(
                sentinelBitmap(), regional(new NormalizedRect(0.10, 0.35, 0.30, 0.65)), transform);

        assertEquals(GlRedactionRenderer.OPAQUE_MASK_COLOR, result.getPixel(51, 32));
        assertNotEquals(GlRedactionRenderer.OPAQUE_MASK_COLOR, result.getPixel(13, 32));
    }

    @Test
    public void blurRequestIsStrengthenedToCertifiedOpaqueCoverage() {
        ProtectedRegion region = new ProtectedRegion(
                FindingCategory.FACE,
                List.of(new NormalizedRect(0.35, 0.35, 0.65, 0.65)),
                ConfidenceClass.VALIDATED,
                ProtectionAction.BLUR);
        FramePrivacyDecision decision = FramePrivacyDecision.regionalSafe(
                TIMESTAMP, List.of(region), FramePrivacyDecision.Basis.FRESH,
                FrameTimestamp.ofNanos(200));

        Bitmap result = GlRedactionRenderer.renderForTest(
                sentinelBitmap(), decision, identityTransform());

        assertEquals(GlRedactionRenderer.OPAQUE_MASK_COLOR, result.getPixel(32, 32));
    }

    @Test
    public void outputCapabilityAuthorizesOnlyItsExactPrivacyProcessor() {
        PrivacySurfaceProcessor owner = processor();
        PrivacySurfaceProcessor other = processor();
        try {
            assertEquals(
                    CameraEffect.PREVIEW | CameraEffect.VIDEO_CAPTURE,
                    owner.cameraEffect().getTargets());
            assertEquals(owner, owner.cameraEffect().getSurfaceProcessor());
            assertEquals(true, owner.sanitizedOutputCapability().authorizes(owner));
            assertEquals(false, owner.sanitizedOutputCapability().authorizes(other));
        } finally {
            owner.close();
            other.close();
        }
    }

    @Test
    public void constructionDoesNotClaimRendererReadinessAndCloseSignalsUnavailableOnce() {
        List<PrivacySurfaceProcessor.Readiness> events = new ArrayList<>();
        PrivacySurfaceProcessor processor = new PrivacySurfaceProcessor(
                Runnable::run,
                new BoundedFrameDecisionStore(12, 1_000_000_000L),
                identityTransform(),
                ignored -> { },
                events::add);

        assertEquals(PrivacySurfaceProcessor.Readiness.NOT_READY, processor.readiness());
        assertEquals(List.of(), events);

        processor.close();
        processor.close();

        assertEquals(PrivacySurfaceProcessor.Readiness.UNAVAILABLE, processor.readiness());
        assertEquals(List.of(PrivacySurfaceProcessor.Readiness.UNAVAILABLE), events);
    }

    private static FramePrivacyDecision regional(NormalizedRect bounds) {
        ProtectedRegion region = new ProtectedRegion(
                FindingCategory.FACE,
                List.of(bounds),
                ConfidenceClass.VALIDATED,
                ProtectionAction.OPAQUE);
        return FramePrivacyDecision.regionalSafe(
                TIMESTAMP, List.of(region), FramePrivacyDecision.Basis.FRESH,
                FrameTimestamp.ofNanos(200));
    }

    private static FrameTransform identityTransform() {
        return FrameTransform.fromCameraMetadata(
                CoordinateTransform.identity(), fullCrop(), 0, false);
    }

    private static PrivacySurfaceProcessor processor() {
        return new PrivacySurfaceProcessor(
                Runnable::run,
                new BoundedFrameDecisionStore(12, 1_000_000_000L),
                identityTransform(),
                ignored -> { });
    }

    private static NormalizedRect fullCrop() {
        return new NormalizedRect(0.0, 0.0, 1.0, 1.0);
    }

    private static Bitmap sentinelBitmap() {
        Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                bitmap.setPixel(x, y, Color.rgb((x * 3) & 0xff, (y * 3) & 0xff,
                        ((x + y) * 2) & 0xff));
            }
        }
        return bitmap;
    }

    private static void assertRgbNear(int expected, int actual, int tolerance) {
        assertEquals(Color.red(expected), Color.red(actual), tolerance);
        assertEquals(Color.green(expected), Color.green(actual), tolerance);
        assertEquals(Color.blue(expected), Color.blue(actual), tolerance);
    }
}
