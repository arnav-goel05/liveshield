package com.liveshield.transport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.media.MediaCodec;
import com.liveshield.privacy.decision.BoundedFrameDecisionStore;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.video.geometry.FrameTransform;
import com.liveshield.video.output.H264CodecConfiguration;
import com.liveshield.video.output.SanitizedH264AccessUnit;
import com.liveshield.video.render.PrivacySurfaceProcessor;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Cross-module checks for the renderer-authorized H.264 transport handoff. */
public final class SanitizedAccessUnitBridgeTest {
    @Test
    public void authorizationRejectsCapabilityFromAnotherRenderer() {
        try (PrivacySurfaceProcessor owner = processor();
             PrivacySurfaceProcessor other = processor()) {
            assertThrows(IllegalArgumentException.class,
                    () -> new SanitizedAccessUnitBridge(
                            owner.sanitizedOutputCapability(), other, ignored -> { }));
        }
    }

    @Test
    public void configurationIsCopiedAndMarkedSanitizedH264Video() {
        try (PrivacySurfaceProcessor renderer = processor()) {
            CollectingSink sink = new CollectingSink();
            SanitizedAccessUnitBridge bridge = bridge(renderer, sink);
            byte[] sps = {1, 2};
            byte[] pps = {3, 4};

            bridge.onCodecConfiguration(new H264CodecConfiguration(720, 1280, sps, pps));
            sps[0] = 9;
            pps[0] = 9;

            EncodedAccessUnit unit = sink.units.get(0);
            assertArrayEquals(new byte[]{1, 2, 3, 4}, unit.payload());
            assertEquals(EncodedAccessUnit.TrackType.VIDEO, unit.trackType());
            assertEquals(EncodedAccessUnit.Codec.H264, unit.codec());
            assertEquals(EncodedAccessUnit.PrivacyAttestation.SANITIZED,
                    unit.privacyAttestation());
            assertEquals(0L, unit.presentationTimeUs());
            assertEquals(java.util.Set.of(EncodedAccessUnit.Flag.CODEC_CONFIGURATION),
                    unit.flags());
        }
    }

    @Test
    public void codecBufferValueMapsPtsFlagsAndDoesNotAliasBytes() throws Exception {
        try (PrivacySurfaceProcessor renderer = processor()) {
            CollectingSink sink = new CollectingSink();
            SanitizedAccessUnitBridge bridge = bridge(renderer, sink);
            byte[] codecBytes = {5, 6, 7};
            SanitizedH264AccessUnit source = sanitizedUnit(
                    codecBytes,
                    42L,
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                            | MediaCodec.BUFFER_FLAG_END_OF_STREAM);

            bridge.onAccessUnit(source);
            codecBytes[0] = 0;

            EncodedAccessUnit unit = sink.units.get(0);
            assertArrayEquals(new byte[]{5, 6, 7}, unit.payload());
            assertEquals(42L, unit.presentationTimeUs());
            assertEquals(
                    java.util.Set.of(
                            EncodedAccessUnit.Flag.KEY_FRAME,
                            EncodedAccessUnit.Flag.END_OF_STREAM),
                    unit.flags());
        }
    }

    @Test
    public void closeIsIdempotentClosesSinkAndRejectsLaterOutput() {
        try (PrivacySurfaceProcessor renderer = processor()) {
            CollectingSink sink = new CollectingSink();
            SanitizedAccessUnitBridge bridge = bridge(renderer, sink);

            bridge.close();
            bridge.close();

            assertEquals(1, sink.closeCount);
            assertThrows(IllegalStateException.class,
                    () -> bridge.onCodecConfiguration(
                            new H264CodecConfiguration(
                                    1, 1, new byte[]{1}, new byte[]{2})));
        }
    }

    @Test
    public void sinkFailurePropagatesWithoutPayloadLoggingOrRetention() {
        try (PrivacySurfaceProcessor renderer = processor()) {
            RuntimeException failure = new IllegalStateException("publisher unavailable");
            SanitizedAccessUnitBridge bridge = bridge(renderer, accessUnit -> {
                throw failure;
            });

            RuntimeException actual = assertThrows(RuntimeException.class,
                    () -> bridge.onCodecConfiguration(
                            new H264CodecConfiguration(
                                    1, 1, new byte[]{1}, new byte[]{2})));

            assertTrue(actual == failure);
        }
    }

    @Test
    public void transportPublicBoundaryHasNoCameraXSignature() {
        assertNoCameraXTypes(SanitizedAccessUnitBridge.class.getConstructors());
        assertNoCameraXTypes(EncodedAccessUnit.class.getDeclaredMethods());
        assertNoCameraXTypes(EncodedAccessUnitSink.class.getDeclaredMethods());
        assertNoCameraXTypes(
                PrivacySurfaceProcessor.SanitizedOutputCapability.class.getDeclaredMethods());
    }

    private static SanitizedAccessUnitBridge bridge(
            PrivacySurfaceProcessor renderer,
            EncodedAccessUnitSink sink) {
        return new SanitizedAccessUnitBridge(
                renderer.sanitizedOutputCapability(), renderer, sink);
    }

    private static PrivacySurfaceProcessor processor() {
        return new PrivacySurfaceProcessor(
                Runnable::run,
                new BoundedFrameDecisionStore(2, 100L),
                FrameTransform.fromCameraMetadata(
                        CoordinateTransform.identity(),
                        new NormalizedRect(0.0, 0.0, 1.0, 1.0),
                        0,
                        false),
                ignored -> { });
    }

    private static SanitizedH264AccessUnit sanitizedUnit(
            byte[] payload,
            long presentationTimeUs,
            int flags) throws Exception {
        Constructor<SanitizedH264AccessUnit> constructor =
                SanitizedH264AccessUnit.class.getDeclaredConstructor(
                        byte[].class, long.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(payload, presentationTimeUs, flags);
    }

    private static void assertNoCameraXTypes(Constructor<?>[] constructors) {
        for (Constructor<?> constructor : constructors) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertFalseCameraX(parameter);
            }
        }
    }

    private static void assertNoCameraXTypes(Method[] methods) {
        for (Method method : methods) {
            assertFalseCameraX(method.getReturnType());
            for (Class<?> parameter : method.getParameterTypes()) {
                assertFalseCameraX(parameter);
            }
        }
    }

    private static void assertFalseCameraX(Class<?> type) {
        assertTrue("CameraX leaked into transport API: " + type.getName(),
                !type.getName().startsWith("androidx.camera."));
    }

    private static final class CollectingSink implements EncodedAccessUnitSink {
        private final List<EncodedAccessUnit> units = new ArrayList<>();
        private int closeCount;

        @Override
        public void onAccessUnit(EncodedAccessUnit accessUnit) {
            units.add(accessUnit);
        }

        @Override
        public void close() {
            closeCount++;
        }
    }
}
