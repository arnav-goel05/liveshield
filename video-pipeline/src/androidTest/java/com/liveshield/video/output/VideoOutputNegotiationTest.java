package com.liveshield.video.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.annotation.SuppressLint;
import android.media.MediaFormat;
import androidx.camera.video.MediaSpec;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.liveshield.privacy.decision.BoundedFrameDecisionStore;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.video.geometry.FrameTransform;
import com.liveshield.video.render.PrivacySurfaceProcessor;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

/** CameraX 1.6.1 negotiation contract for the custom always-on video output. */
@SuppressLint("RestrictedApi") // The test verifies VideoOutput's restricted negotiation hooks.
@RunWith(AndroidJUnit4.class)
public final class VideoOutputNegotiationTest {
    @Test
    public void suppliesStableNonNullAvcSpecAndRequestsSourceUntilClosed() throws Exception {
        PrivacySurfaceProcessor processor = privacyProcessor();
        SanitizedVideoOutput output = new SanitizedVideoOutput(
                processor.sanitizedOutputCapability(),
                new NoOpSink(),
                ignored -> { },
                new SanitizedVideoOutput.EncoderSettings(750_000, 24, 2));
        try {
            MediaSpec first = output.getMediaSpec().fetchData().get(1, TimeUnit.SECONDS);
            MediaSpec second = output.getMediaSpec().fetchData().get(1, TimeUnit.SECONDS);

            assertSame(first, second);
            assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, first.getVideoSpec().getMimeType());
            assertEquals(750_000, first.getVideoSpec().getBitrate());
            assertEquals(24, first.getVideoSpec().getEncodeFrameRate());
            assertTrue(output.isSourceStreamRequired().fetchData().get(1, TimeUnit.SECONDS));
        } finally {
            output.close();
            processor.close();
        }

        assertFalse(output.isSourceStreamRequired().fetchData().get(1, TimeUnit.SECONDS));
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

    private static final class NoOpSink implements SanitizedVideoSink {
        @Override
        public void onCodecConfiguration(H264CodecConfiguration configuration) {
        }

        @Override
        public void onAccessUnit(SanitizedH264AccessUnit accessUnit) {
        }
    }
}
