package com.liveshield.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.liveshield.privacy.decision.FramePrivacyDecision;
import com.liveshield.privacy.model.ConfidenceClass;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.model.ProtectionAction;
import com.liveshield.video.geometry.FrameTransform;
import com.liveshield.video.render.DecodedOutputStrengthGate;
import com.liveshield.video.render.GlRedactionRenderer;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real H.264 encode/decode evidence for mask coverage and deterministic escalation. */
@RunWith(AndroidJUnit4.class)
public final class RedactionStrengthGateTest {
    private static final int SIZE = 64;
    private static final int TOLERANCE = 28;
    private static final double REQUIRED_COVERAGE = 0.985;

    @Test
    public void paddedInteriorAndFrameEdgeSurviveH264RoundTrip() throws Exception {
        List<NormalizedRect> regions = List.of(
                new NormalizedRect(0.25, 0.25, 0.55, 0.55),
                new NormalizedRect(0.82, 0.15, 1.0, 0.45));
        Bitmap sanitized = GlRedactionRenderer.renderForTest(
                sentinelBitmap(), regional(regions), identityTransform());

        Bitmap decoded = roundTripH264(sanitized);
        DecodedOutputStrengthGate.Result result =
                DecodedOutputStrengthGate.inspectOpaqueCoverage(
                        decoded, regions, TOLERANCE, REQUIRED_COVERAGE);

        assertTrue(result.inspectedPixels() > 0);
        assertTrue("Decoded opaque coverage was " + result.coverage()
                        + " (" + result.coveredPixels() + "/" + result.inspectedPixels() + ")",
                result.coverage() >= REQUIRED_COVERAGE);
        assertEquals(DecodedOutputStrengthGate.Outcome.CERTIFIED_OPAQUE, result.outcome());
    }

    @Test
    public void compressionDamageEscalatesDeterministicallyToFullShield() throws Exception {
        List<NormalizedRect> regions = List.of(new NormalizedRect(0.25, 0.25, 0.75, 0.75));
        Bitmap sanitized = GlRedactionRenderer.renderForTest(
                sentinelBitmap(), regional(regions), identityTransform());
        Bitmap decoded = roundTripH264(sanitized).copy(Bitmap.Config.ARGB_8888, true);
        for (int y = 20; y < 44; y++) {
            for (int x = 20; x < 44; x++) {
                decoded.setPixel(x, y, Color.WHITE);
            }
        }

        DecodedOutputStrengthGate.Result result =
                DecodedOutputStrengthGate.inspectOpaqueCoverage(
                        decoded, regions, TOLERANCE, REQUIRED_COVERAGE);
        FramePrivacyDecision escalation = DecodedOutputStrengthGate.fullShieldOnFailure(
                FrameTimestamp.ofNanos(500));

        assertEquals(DecodedOutputStrengthGate.Outcome.ESCALATE_FULL_SHIELD, result.outcome());
        assertEquals(FramePrivacyDecision.Status.FULL_SHIELD, escalation.status());
        assertEquals(FramePrivacyDecision.Basis.ERROR, escalation.basis());
    }

    private static Bitmap roundTripH264(Bitmap frame) throws Exception {
        File output = File.createTempFile(
                "liveshield-sanitized-", ".mp4",
                InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        try {
            encodeH264(frame, output);
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(output.getAbsolutePath());
                Bitmap decoded = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (decoded == null) {
                    throw new IllegalStateException("H.264 decoder returned no frame");
                }
                return Bitmap.createScaledBitmap(decoded, SIZE, SIZE, false);
            } finally {
                retriever.release();
            }
        } finally {
            if (!output.delete() && output.exists()) {
                throw new IllegalStateException("Unable to delete sanitized test recording");
            }
        }
    }

    private static void encodeH264(Bitmap frame, File output) throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, SIZE, SIZE);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 600_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaMuxer muxer = new MediaMuxer(
                output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        boolean muxerStarted = false;
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            byte[] yuv = toI420(frame);
            long presentationTimeUs = 0;
            for (int index = 0; index < 4; index++) {
                int inputIndex = dequeueInput(encoder);
                ByteBuffer input = encoder.getInputBuffer(inputIndex);
                if (input == null) {
                    throw new IllegalStateException("Encoder returned a null input buffer");
                }
                input.clear();
                input.put(yuv);
                encoder.queueInputBuffer(inputIndex, 0, yuv.length, presentationTimeUs, 0);
                presentationTimeUs += 33_333;
            }
            int eosIndex = dequeueInput(encoder);
            encoder.queueInputBuffer(eosIndex, 0, 0, presentationTimeUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int track = -1;
            boolean finished = false;
            while (!finished) {
                int outputIndex = encoder.dequeueOutputBuffer(info, 100_000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (outputIndex >= 0) {
                    ByteBuffer encoded = encoder.getOutputBuffer(outputIndex);
                    if (encoded == null) {
                        throw new IllegalStateException("Encoder returned a null output buffer");
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        if (!muxerStarted || track < 0) {
                            throw new IllegalStateException("Encoded frame arrived before format");
                        }
                        encoded.position(info.offset);
                        encoded.limit(info.offset + info.size);
                        muxer.writeSampleData(track, encoded, info);
                    }
                    finished = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(outputIndex, false);
                }
            }
        } finally {
            try {
                encoder.stop();
            } finally {
                encoder.release();
            }
            if (muxerStarted) {
                muxer.stop();
            }
            muxer.release();
        }
    }

    private static int dequeueInput(MediaCodec encoder) {
        for (int attempt = 0; attempt < 100; attempt++) {
            int index = encoder.dequeueInputBuffer(100_000);
            if (index >= 0) {
                return index;
            }
        }
        throw new IllegalStateException("Timed out waiting for H.264 encoder input");
    }

    private static byte[] toI420(Bitmap bitmap) {
        int frameSize = SIZE * SIZE;
        byte[] output = new byte[frameSize * 3 / 2];
        int uvIndex = frameSize;
        int vIndex = frameSize + frameSize / 4;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int color = bitmap.getPixel(x, y);
                int red = Color.red(color);
                int green = Color.green(color);
                int blue = Color.blue(color);
                int luminance = ((66 * red + 129 * green + 25 * blue + 128) >> 8) + 16;
                output[y * SIZE + x] = (byte) clampByte(luminance);
                if ((x & 1) == 0 && (y & 1) == 0) {
                    int blueDifference = ((-38 * red - 74 * green + 112 * blue + 128) >> 8) + 128;
                    int redDifference = ((112 * red - 94 * green - 18 * blue + 128) >> 8) + 128;
                    output[uvIndex++] = (byte) clampByte(blueDifference);
                    output[vIndex++] = (byte) clampByte(redDifference);
                }
            }
        }
        return output;
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static FramePrivacyDecision regional(List<NormalizedRect> bounds) {
        ProtectedRegion region = new ProtectedRegion(
                FindingCategory.FACE,
                bounds,
                ConfidenceClass.VALIDATED,
                ProtectionAction.OPAQUE);
        return FramePrivacyDecision.regionalSafe(
                FrameTimestamp.ofNanos(100), List.of(region), FramePrivacyDecision.Basis.FRESH,
                FrameTimestamp.ofNanos(200));
    }

    private static FrameTransform identityTransform() {
        return FrameTransform.fromCameraMetadata(
                CoordinateTransform.identity(),
                new NormalizedRect(0.0, 0.0, 1.0, 1.0),
                0,
                false);
    }

    private static Bitmap sentinelBitmap() {
        Bitmap bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                bitmap.setPixel(x, y, Color.rgb((x * 4) & 0xff, (y * 4) & 0xff,
                        ((x * 3 + y * 5) & 0xff)));
            }
        }
        return bitmap;
    }
}
