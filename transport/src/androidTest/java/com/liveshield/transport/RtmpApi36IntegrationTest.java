package com.liveshield.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.annotation.SuppressLint;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.liveshield.transport.destination.StreamDestination;
import java.io.File;
import java.nio.ByteBuffer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/** API 36 production-publisher half of the host-orchestrated MediaMTX integration proof. */
@RunWith(AndroidJUnit4.class)
public final class RtmpApi36IntegrationTest {
    private static final String OPT_IN_ARGUMENT = "liveshield.rtmp.integration";
    private static final int WIDTH = 160;
    private static final int HEIGHT = 90;
    private static final int FPS = 5;
    private static final long CONNECT_TIMEOUT_MILLIS = 12_000L;
    private static final long RTMP_PROTOCOL_SETTLE_MILLIS = 3_000L;
    // This is one continuous Base64 fixture; whitespace would change the decoded H.264 bytes.
    @SuppressLint("TextConcatSpace")
    private static final String SYNTHETIC_H264 =
            "AAAAAWdCwB7aCjfkwEQAAAMABAAAAwAqPFi6gAAAAAFozg/IAAABBgX//03cRem95tlIt5Ys"
            + "2CDZI+7veDI2NCAtIGNvcmUgMTY1IHIzMjIyIGIzNTYwNWEgLSBILjI2NC9NUEVHLTQgQV"
            + "ZDIGNvZGVjIC0gQ29weWxlZnQgMjAwMy0yMDI1IC0gaHR0cDovL3d3dy52aWRlb2xhbi5v"
            + "cmcveDI2NC5odG1sIC0gb3B0aW9uczogY2FiYWM9MCByZWY9MSBkZWJsb2NrPTA6MDowIG"
            + "FuYWx5c2U9MDowIG1lPWRpYSBzdWJtZT0wIHBzeT0xIHBzeV9yZD0xLjAwOjAuMDAgbWl4"
            + "ZWRfcmVmPTAgbWVfcmFuZ2U9MTYgY2hyb21hX21lPTEgdHJlbGxpcz0wIDh4OGRjdD0wIG"
            + "NxbT0wIGRlYWR6b25lPTIxLDExIGZhc3RfcHNraXA9MSBjaHJvbWFfcXBfb2Zmc2V0PTAg"
            + "dGhyZWFkcz0xIGxvb2thaGVhZF90aHJlYWRzPTEgc2xpY2VkX3RocmVhZHM9MCBucj0wIG"
            + "RlY2ltYXRlPTEgaW50ZXJsYWNlZD0wIGJsdXJheV9jb21wYXQ9MCBjb25zdHJhaW5lZF9p"
            + "bnRyYT0wIGJmcmFtZXM9MCB3ZWlnaHRwPTAga2V5aW50PTUga2V5aW50X21pbj0zIHNjZW"
            + "5lY3V0PTAgaW50cmFfcmVmcmVzaD0wIHJjPWNyZiBtYnRyZWU9MCBjcmY9MjMuMCBxY29t"
            + "cD0wLjYwIHFwbWluPTAgcXBtYXg9NjkgcXBzdGVwPTQgaXBfcmF0aW89MS40MCBhcT0wAI"
            + "AAA AFliIQ6JigACQLJycnJycnJycnXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
            + "XXXXXXXXXXXXgAAAAFBmiARoHsAAAABQZpAEqB7AAAAAUGaYBKgewAAAAFBmoASoHs=";

    @Test
    public void publishesSyntheticSanitizedH264ToExplicitHostRelay() throws Exception {
        Assume.assumeTrue("Host orchestrator opt-in is required",
                "true".equals(InstrumentationRegistry.getArguments()
                        .getString(OPT_IN_ARGUMENT)));
        assertEquals("This fidelity test is frozen to API 36", 36, Build.VERSION.SDK_INT);

        StreamDestination destination = StreamDestination.sessionScoped(
                StreamDestination.Kind.TIKTOK_EXTERNAL,
                "API 36 host relay",
                URI.create("rtmp://10.0.2.2:1935"),
                "liveshield".toCharArray());
        try (StreamSessionController controller = new StreamSessionController(
                destination, 4_000_000L, WIDTH, HEIGHT, FPS, () -> { })) {
            controller.connect();
            awaitPublishing(controller);
            publishSyntheticVideo(controller);
            StreamSessionController.Health health = controller.health();
            assertEquals(StreamSessionController.State.PUBLISHING, health.state());
            assertEquals(StreamSessionController.FailureCode.NONE, health.failureCode());
            assertTrue("Expected delayed units to reach the publisher", health.releasedUnits() > 0);
            assertTrue("No unit may bypass the configured two-second delay",
                    health.minimumReleaseDelayNanos() >= DelayedAccessUnitQueue.VIDEO_DELAY_NANOS);
            android.util.Log.i("LiveShieldT084",
                    "delay configured_ns=" + DelayedAccessUnitQueue.VIDEO_DELAY_NANOS
                            + " count=" + health.releasedUnits()
                            + " min_ns=" + health.minimumReleaseDelayNanos()
                            + " mean_ns=" + health.meanReleaseDelayNanos()
                            + " max_ns=" + health.maximumReleaseDelayNanos());
        }
    }

    @Test
    public void republishesExactPriorityTwoSanitizedH264ToHostRelay() throws Exception {
        Assume.assumeTrue("Host orchestrator opt-in is required",
                "true".equals(InstrumentationRegistry.getArguments()
                        .getString("liveshield.priority2.mediamtx")));
        assertEquals(36, Build.VERSION.SDK_INT);
        File source = new File(InstrumentationRegistry.getArguments()
                .getString("piiSanitizedMp4"));
        assertTrue("Missing staged sanitized Priority 2 MP4", source.isFile());
        StreamDestination destination = StreamDestination.sessionScoped(
                StreamDestination.Kind.TIKTOK_EXTERNAL, "T101 host relay",
                URI.create("rtmp://10.0.2.2:1935"), "liveshield".toCharArray());
        try (StreamSessionController controller = new StreamSessionController(
                destination, 8_000_000L, 192, 128, 8, () -> { })) {
            controller.connect();
            awaitPublishing(controller);
            // RootEncoder's isStreaming/PUBLISHING state precedes completion of the RTMP
            // command/track handshake. This bounded settle is part of the proven API 36 path.
            SystemClock.sleep(RTMP_PROTOCOL_SETTLE_MILLIS);
            Mp4Units units = readMp4(source);
            PrimingOutcome priming = publishPrimingUntilHostReaderReady(controller, units);
            publishEvaluationFrames(controller, units, priming.nextPresentationTimeUs());
            tickFor(controller, 2_500L);
            assertEquals(0, controller.health().queuedUnits());
            assertTrue("Priming/config and all evaluation units must be released",
                    controller.health().releasedUnits()
                            >= priming.releasedUnitsBeforeEvaluation() + 105L);
        }
    }

    @Test
    public void diagnosesConfiguredRelaySequence() throws Exception {
        Assume.assumeTrue("Host diagnostic opt-in is required",
                "true".equals(InstrumentationRegistry.getArguments()
                        .getString("liveshield.priority2.diagnostic")));
        assertEquals(36, Build.VERSION.SDK_INT);
        File source = new File(InstrumentationRegistry.getArguments()
                .getString("piiSanitizedMp4"));
        assertTrue("Missing staged sanitized Priority 2 MP4", source.isFile());
        Mp4Units units = readMp4(source);
        String mode = InstrumentationRegistry.getArguments().getString("diagnosticMode");
        assertTrue("diagnosticMode must be UNIQUE or PRIMING",
                "UNIQUE".equals(mode) || "PRIMING".equals(mode));
        StreamSessionController.Health health = diagnoseSequence(
                units, "PRIMING".equals(mode));
        android.util.Log.i("LiveShield-T101-Diagnostic", "mode=" + mode + " " + health);

        assertTrue("Diagnostic sequence must reach actual publisher calls",
                health.releasedConfigurationUnits() > 0
                        && health.releasedKeyFrameUnits() > 0
                        && health.releasedMediaUnits() > 0);
    }

    private static StreamSessionController.Health diagnoseSequence(
            Mp4Units units, boolean repeatPrimingGop) {
        StreamDestination destination = StreamDestination.sessionScoped(
                StreamDestination.Kind.TIKTOK_EXTERNAL, "T101 diagnostic",
                URI.create("rtmp://10.0.2.2:1935"), "liveshield".toCharArray());
        try (StreamSessionController controller = new StreamSessionController(
                destination, 8_000_000L, 192, 128, 8, () -> { })) {
            controller.connect();
            awaitPublishing(controller);
            SystemClock.sleep(RTMP_PROTOCOL_SETTLE_MILLIS);
            long baseUs = System.nanoTime() / 1_000L;
            try {
                if (repeatPrimingGop) {
                    publishBoundedPrimingDiagnostic(controller, units, baseUs);
                } else {
                    publishEvaluationFrames(controller, units, baseUs);
                }
                tickFor(controller, 2_500L);
            } catch (IllegalStateException terminalFailure) {
                // Health below retains the typed failure and payload-free publish counters.
            }
            return controller.health();
        }
    }

    private static void publishBoundedPrimingDiagnostic(
            StreamSessionController controller, Mp4Units units, long baseUs) {
        controller.onAccessUnit(EncodedAccessUnit.copySanitizedH264(
                units.configuration(), baseUs,
                Set.of(EncodedAccessUnit.Flag.CODEC_CONFIGURATION)));
        List<Mp4Frame> gop = units.frames().subList(0, 8);
        for (int index = 0; index < 80; index++) {
            Mp4Frame frame = gop.get(index % gop.size());
            Set<EncodedAccessUnit.Flag> flags = frame.keyFrame()
                    ? Set.of(EncodedAccessUnit.Flag.KEY_FRAME) : Set.of();
            controller.onAccessUnit(EncodedAccessUnit.copySanitizedH264(
                    frame.payload(), baseUs + index * 125_000L, flags));
            tickFor(controller, 125L);
        }
    }

    private static boolean hostReaderReady() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("10.0.2.2", 18765), 500);
            socket.setSoTimeout(500);
            return socket.getInputStream().read() == 1;
        } catch (java.io.IOException notReady) {
            return false;
        }
    }

    private static PrimingOutcome publishPrimingUntilHostReaderReady(
            StreamSessionController controller, Mp4Units units) {
        long deadline = SystemClock.elapsedRealtime() + 20_000L;
        long nextPresentationTimeUs = System.nanoTime() / 1_000L;
        int frameCount = 0;
        List<Mp4Frame> gop = units.frames().subList(0, 8);
        controller.onAccessUnit(EncodedAccessUnit.copySanitizedH264(
                units.configuration(), nextPresentationTimeUs,
                Set.of(EncodedAccessUnit.Flag.CODEC_CONFIGURATION)));
        try (DebugHostReadinessPoller readiness =
                     new DebugHostReadinessPoller(RtmpApi36IntegrationTest::hostReaderReady)) {
            while (SystemClock.elapsedRealtime() < deadline && frameCount < 160) {
                Mp4Frame frame = gop.get(frameCount % gop.size());
                Set<EncodedAccessUnit.Flag> flags = frame.keyFrame()
                        ? Set.of(EncodedAccessUnit.Flag.KEY_FRAME) : Set.of();
                controller.onAccessUnit(EncodedAccessUnit.copySanitizedH264(
                        frame.payload(), nextPresentationTimeUs, flags));
                frameCount++;
                nextPresentationTimeUs += 125_000L;
                tickFor(controller, 125L);
                if (readiness.isReady()) {
                    return new PrimingOutcome(
                            nextPresentationTimeUs, controller.health().releasedUnits());
                }
                if (readiness.isFinished()) {
                    fail("Host readiness probe ended without a reader-ready milestone");
                }
            }
        }
        fail("MediaMTX reader did not confirm receipt of a bounded priming GOP");
        throw new AssertionError("unreachable");
    }

    private static Mp4Units readMp4(File source) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(source.getAbsolutePath());
            assertEquals(1, extractor.getTrackCount());
            MediaFormat format = extractor.getTrackFormat(0);
            assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, format.getString(MediaFormat.KEY_MIME));
            byte[] configuration = concatenate(List.of(
                    annexB(format.getByteBuffer("csd-0")),
                    annexB(format.getByteBuffer("csd-1"))));
            extractor.selectTrack(0);
            ByteBuffer buffer = ByteBuffer.allocate(1_000_000);
            List<Mp4Frame> frames = new ArrayList<>();
            long firstPts = extractor.getSampleTime();
            while (extractor.getSampleTime() >= 0) {
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                byte[] payload = new byte[size];
                buffer.position(0);
                buffer.get(payload);
                frames.add(new Mp4Frame(
                        payload,
                        extractor.getSampleTime() - firstPts,
                        (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0));
                extractor.advance();
            }
            assertEquals(104, frames.size());
            assertTrue("Evaluation segment must begin at an IDR", frames.get(0).keyFrame());
            return new Mp4Units(configuration, frames);
        } finally {
            extractor.release();
        }
    }

    private static void publishEvaluationFrames(
            StreamSessionController controller, Mp4Units units, long baseUs) {
        controller.onAccessUnit(EncodedAccessUnit.copySanitizedH264(
                units.configuration(), baseUs,
                Set.of(EncodedAccessUnit.Flag.CODEC_CONFIGURATION)));
        for (Mp4Frame frame : units.frames()) {
            Set<EncodedAccessUnit.Flag> flags = frame.keyFrame()
                    ? Set.of(EncodedAccessUnit.Flag.KEY_FRAME) : Set.of();
            controller.onAccessUnit(EncodedAccessUnit.copySanitizedH264(
                    frame.payload(), baseUs + frame.relativePtsUs(), flags));
            tickFor(controller, 125L);
        }
    }

    private record Mp4Units(byte[] configuration, List<Mp4Frame> frames) {
        private Mp4Units {
            configuration = configuration.clone();
            frames = List.copyOf(frames);
        }
    }

    private record Mp4Frame(byte[] payload, long relativePtsUs, boolean keyFrame) {
        private Mp4Frame {
            payload = payload.clone();
        }
    }

    private record PrimingOutcome(
            long nextPresentationTimeUs, long releasedUnitsBeforeEvaluation) {
    }

    private static byte[] annexB(ByteBuffer source) {
        ByteBuffer copy = source.duplicate();
        byte[] value = new byte[copy.remaining()];
        copy.get(value);
        if (value.length >= 4 && value[0] == 0 && value[1] == 0
                && (value[2] == 1 || (value[2] == 0 && value[3] == 1))) {
            return value;
        }
        byte[] prefixed = new byte[value.length + 4];
        prefixed[3] = 1;
        System.arraycopy(value, 0, prefixed, 4, value.length);
        return prefixed;
    }

    private static void awaitPublishing(StreamSessionController controller) {
        long deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MILLIS;
        while (SystemClock.elapsedRealtime() < deadline) {
            controller.tick();
            StreamSessionController.Health health = controller.health();
            if (health.state() == StreamSessionController.State.PUBLISHING) {
                return;
            }
            if (health.state() == StreamSessionController.State.FAILED) {
                fail("RTMP connection failed: " + health.failureCode());
            }
            SystemClock.sleep(50L);
        }
        fail("Publisher did not connect to the explicit host relay within 12 seconds");
    }

    private static void publishSyntheticVideo(StreamSessionController controller) {
        List<byte[]> nals = splitAnnexB(Base64.getDecoder().decode(
                SYNTHETIC_H264.replace(" ", "")));
        byte[] configuration = concatenate(nals.stream()
                .filter(nal -> nalType(nal) == 7 || nalType(nal) == 8)
                .toList());
        List<byte[]> pictures = nals.stream()
                .filter(nal -> nalType(nal) == 5 || nalType(nal) == 1)
                .toList();
        assertFalse("Synthetic fixture must contain SPS/PPS", configuration.length == 0);
        assertEquals("Synthetic fixture must contain one five-frame GOP", 5, pictures.size());

        long presentationTimeUs = System.nanoTime() / 1_000L;
        controller.onAccessUnit(EncodedAccessUnit.copySanitizedH264(
                configuration, presentationTimeUs,
                Set.of(EncodedAccessUnit.Flag.CODEC_CONFIGURATION)));
        // Keep the identical frozen GOP available while the host observes relay readiness.
        for (int repetition = 0; repetition < 15; repetition++) {
            for (byte[] picture : pictures) {
                Set<EncodedAccessUnit.Flag> flags = nalType(picture) == 5
                        ? Set.of(EncodedAccessUnit.Flag.KEY_FRAME) : Set.of();
                controller.onAccessUnit(EncodedAccessUnit.copySanitizedH264(
                        picture, presentationTimeUs, flags));
                presentationTimeUs += 1_000_000L / FPS;
                tickFor(controller, 1_000L / FPS);
            }
        }
        tickFor(controller, 2_500L);
    }

    private static void tickFor(StreamSessionController controller, long durationMillis) {
        long deadline = SystemClock.elapsedRealtime() + durationMillis;
        while (SystemClock.elapsedRealtime() < deadline) {
            controller.tick();
            SystemClock.sleep(20L);
        }
    }

    private static List<byte[]> splitAnnexB(byte[] stream) {
        List<byte[]> units = new ArrayList<>();
        int start = findStartCode(stream, 0);
        while (start >= 0) {
            int next = findStartCode(stream, start + startCodeLength(stream, start));
            int end = next < 0 ? stream.length : next;
            units.add(java.util.Arrays.copyOfRange(stream, start, end));
            start = next;
        }
        return List.copyOf(units);
    }

    private static int findStartCode(byte[] bytes, int from) {
        for (int index = from; index + 3 < bytes.length; index++) {
            if (bytes[index] == 0 && bytes[index + 1] == 0
                    && (bytes[index + 2] == 1
                    || (bytes[index + 2] == 0 && bytes[index + 3] == 1))) {
                return index;
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] bytes, int offset) {
        return bytes[offset + 2] == 1 ? 3 : 4;
    }

    private static int nalType(byte[] annexB) {
        return annexB[startCodeLength(annexB, 0)] & 0x1f;
    }

    private static byte[] concatenate(List<byte[]> arrays) {
        int length = arrays.stream().mapToInt(array -> array.length).sum();
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }
}
