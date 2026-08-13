package com.liveshield.transport.rtmp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.media.MediaCodec;
import com.liveshield.transport.EncodedAccessUnit;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;

/** Video-only publisher mapping tests using a fake low-level client. */
public final class RtmpStreamPublisherTest {
    @Test
    public void configuresOnlyVideoAndPublishesCopiedH264Metadata() throws Exception {
        FakeVideoClient client = new FakeVideoClient();
        RtmpStreamPublisher publisher = new RtmpStreamPublisher(client, 1280, 720, 30);
        client.streaming = true;
        byte[] configuration = {
            0, 0, 0, 1, 0x67, 1, 2,
            0, 0, 0, 1, 0x68, 3
        };
        publisher.publish(unit(
                configuration,
                0L,
                Set.of(EncodedAccessUnit.Flag.CODEC_CONFIGURATION)));
        byte[] frame = {0, 0, 1, 0x65, 9};
        publisher.publish(unit(
                frame,
                42L,
                Set.of(
                        EncodedAccessUnit.Flag.KEY_FRAME,
                        EncodedAccessUnit.Flag.END_OF_STREAM)));
        frame[4] = 0;

        assertEquals(1, client.configureCalls);
        assertEquals(1280, client.width);
        assertEquals(720, client.height);
        assertEquals(30, client.framesPerSecond);
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x67, 1, 2}, client.sps);
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x68, 3}, client.pps);
        assertEquals(1, client.payloads.size());
        assertArrayEquals(new byte[]{0, 0, 1, 0x65, 9}, client.payloads.get(0));
        MediaCodec.BufferInfo info = client.infos.get(0);
        assertEquals(0, info.offset);
        assertEquals(5, info.size);
        assertEquals(42L, info.presentationTimeUs);
        assertTrue((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
        assertTrue((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0);
    }

    @Test
    public void requiresConfigurationAndConnectionBeforeMedia() throws Exception {
        FakeVideoClient client = new FakeVideoClient();
        RtmpStreamPublisher publisher = new RtmpStreamPublisher(client, 1, 1, 1);
        EncodedAccessUnit frame = unit(new byte[]{1}, 0L, Set.of());

        assertThrows(IllegalStateException.class, () -> publisher.publish(frame));
        publisher.publish(configuration());
        assertThrows(IllegalStateException.class, () -> publisher.publish(frame));
        client.streaming = true;
        publisher.publish(frame);

        assertEquals(1, client.payloads.size());
    }

    @Test
    public void rejectsMalformedConfigurationAndNonRtmpEndpoints() throws Exception {
        FakeVideoClient client = new FakeVideoClient();
        RtmpStreamPublisher publisher = new RtmpStreamPublisher(client, 1, 1, 1);

        assertThrows(IllegalArgumentException.class,
                () -> publisher.connect("https://example.test/live"));
        assertThrows(IllegalArgumentException.class,
                () -> publisher.publish(unit(
                        new byte[]{0, 0, 1, 0x67, 1},
                        0L,
                        Set.of(EncodedAccessUnit.Flag.CODEC_CONFIGURATION))));
    }

    @Test
    public void callbacksExposeOnlyTypedPayloadFreeFailureAndCloseIsIdempotent() {
        FakeVideoClient client = new FakeVideoClient();
        RtmpStreamPublisher publisher = new RtmpStreamPublisher(client, 1, 1, 1);
        publisher.connect("rtmps://example.test/live/secret-value");
        assertEquals("rtmps://example.test/live/secret-value", client.endpoint);
        assertEquals(RtmpStreamPublisher.ConnectionState.CONNECTING,
                publisher.connectionState());

        client.listener.onConnectionStarted();
        assertEquals(RtmpStreamPublisher.ConnectionState.CONNECTING,
                publisher.connectionState());
        client.listener.onConnectionSuccess();
        assertEquals(RtmpStreamPublisher.ConnectionState.CONNECTED,
                publisher.connectionState());

        client.listener.onConnectionFailed();
        assertEquals(RtmpStreamPublisher.FailureCode.CONNECTION_FAILED,
                publisher.lastFailure());
        assertEquals(RtmpStreamPublisher.ConnectionState.FAILED,
                publisher.connectionState());
        client.listener.onAuthenticationFailed();
        assertEquals(RtmpStreamPublisher.FailureCode.AUTHENTICATION_FAILED,
                publisher.lastFailure());
        client.listener.onDisconnected();
        assertEquals(RtmpStreamPublisher.FailureCode.DISCONNECTED,
                publisher.lastFailure());

        publisher.close();
        publisher.close();
        assertEquals(1, client.disconnectCalls);
        assertEquals(RtmpStreamPublisher.ConnectionState.CLOSED,
                publisher.connectionState());
        assertFalse(publisher.isPublishing());
        assertThrows(IllegalStateException.class,
                () -> publisher.connect("rtmp://example.test/live"));
    }

    @Test
    public void intentionalDisconnectIsNotReportedAsNetworkFailureOrDuplicated() {
        FakeVideoClient client = new FakeVideoClient();
        RtmpStreamPublisher publisher = new RtmpStreamPublisher(client, 1, 1, 1);
        List<String> events = new ArrayList<>();
        publisher.setHealthListener((state, failure) -> events.add(state + ":" + failure));
        publisher.connect("rtmps://example.test/live/fictional-key");
        client.listener.onConnectionStarted();
        client.listener.onConnectionSuccess();

        publisher.disconnect();
        client.listener.onDisconnected();

        assertEquals(RtmpStreamPublisher.ConnectionState.DISCONNECTED,
                publisher.connectionState());
        assertEquals(RtmpStreamPublisher.FailureCode.NONE,
                publisher.lastFailure());
        assertEquals(1, events.stream()
                .filter(event -> event.equals("DISCONNECTED:NONE")).count());
        assertEquals(0, events.stream()
                .filter(event -> event.equals("DISCONNECTED:DISCONNECTED")).count());
        assertFalse(events.toString().contains("fictional-key"));
    }

    @Test
    public void unsolicitedDisconnectIsNetworkFailureAndCloseSuppressesLateCallback() {
        FakeVideoClient client = new FakeVideoClient();
        RtmpStreamPublisher publisher = new RtmpStreamPublisher(client, 1, 1, 1);
        List<String> events = new ArrayList<>();
        publisher.setHealthListener((state, failure) -> events.add(state + ":" + failure));
        publisher.connect("rtmps://example.test/live/fictional-key");
        client.listener.onConnectionSuccess();

        client.listener.onDisconnected();
        assertEquals(RtmpStreamPublisher.FailureCode.DISCONNECTED,
                publisher.lastFailure());
        assertEquals(1, events.stream()
                .filter(event -> event.equals("DISCONNECTED:DISCONNECTED")).count());

        publisher.close();
        int afterClose = events.size();
        client.listener.onDisconnected();
        client.listener.onAuthenticationFailed();
        assertEquals(afterClose, events.size());
        assertFalse(events.toString().contains("fictional-key"));
    }

    @Test
    public void delayedIntentionalDisconnectCallbackAfterReconnectCannotBecomeNetworkFailure() {
        FakeVideoClient client = new FakeVideoClient();
        RtmpStreamPublisher publisher = new RtmpStreamPublisher(client, 1, 1, 1);
        List<String> events = new ArrayList<>();
        publisher.setHealthListener((state, failure) -> events.add(state + ":" + failure));
        publisher.connect("rtmps://example.test/live/fictional-key");
        client.listener.onConnectionSuccess();

        publisher.disconnect();
        publisher.connect("rtmps://example.test/live/fictional-key");
        client.listener.onConnectionStarted();
        client.listener.onConnectionSuccess();
        client.listener.onDisconnected();

        assertEquals(RtmpStreamPublisher.FailureCode.NONE, publisher.lastFailure());
        assertEquals(0, events.stream()
                .filter(event -> event.endsWith(":DISCONNECTED")).count());
        assertFalse(events.toString().contains("fictional-key"));
    }

    @Test
    public void publicAndFakeableApiExposeNoAudioCaptureEncoderOrGenericPayload() {
        for (Method method : RtmpStreamPublisher.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                assertNoForbiddenApi(method);
            }
        }
        for (Method method : RtmpStreamPublisher.VideoClient.class.getDeclaredMethods()) {
            assertNoForbiddenApi(method);
        }
    }

    @Test
    public void rootEncoderAdapterEnablesOnlyVideoAndCallsNoAudioApi() throws Exception {
        String source = readProjectFile(
                "src/main/java/com/liveshield/transport/rtmp/RootEncoderVideoClient.java");

        assertTrue(source.contains("client.setOnlyVideo(true)"));
        assertTrue(source.contains("client.setVideoCodec(VideoCodec.H264)"));
        assertTrue(source.contains("client.sendVideo("));
        assertFalse(source.contains("sendAudio("));
        assertFalse(source.contains("setAudio"));
        assertFalse(source.contains("AudioCodec"));
    }

    private static void assertNoForbiddenApi(Method method) {
        String name = method.getName().toLowerCase(Locale.ROOT);
        assertFalse(name.contains("audio"));
        assertFalse(name.contains("capture"));
        assertFalse(name.contains("microphone"));
        assertFalse(name.contains("encoder"));
        for (Class<?> type : method.getParameterTypes()) {
            assertFalse(type == Object.class);
            assertFalse(type.getName().contains("Surface"));
            assertFalse(type.getName().contains("Image"));
        }
    }

    private static EncodedAccessUnit configuration() throws Exception {
        return unit(
                new byte[]{0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68, 2},
                0L,
                Set.of(EncodedAccessUnit.Flag.CODEC_CONFIGURATION));
    }

    private static String readProjectFile(String relativePath) throws Exception {
        File direct = new File(relativePath);
        File file = direct.isFile() ? direct : new File("transport", relativePath);
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static EncodedAccessUnit unit(
            byte[] payload,
            long presentationTimeUs,
            Set<EncodedAccessUnit.Flag> flags) throws Exception {
        Method factory = EncodedAccessUnit.class.getDeclaredMethod(
                "copySanitizedH264", byte[].class, long.class, Set.class);
        factory.setAccessible(true);
        return (EncodedAccessUnit) factory.invoke(null, payload, presentationTimeUs, flags);
    }

    private static final class FakeVideoClient implements RtmpStreamPublisher.VideoClient {
        private final List<byte[]> payloads = new ArrayList<>();
        private final List<MediaCodec.BufferInfo> infos = new ArrayList<>();
        private Listener listener;
        private int configureCalls;
        private int width;
        private int height;
        private int framesPerSecond;
        private byte[] sps;
        private byte[] pps;
        private String endpoint;
        private boolean streaming;
        private int disconnectCalls;

        @Override
        public void setListener(Listener listener) {
            this.listener = listener;
        }

        @Override
        public void configureVideoOnly(int width, int height, int framesPerSecond) {
            configureCalls++;
            this.width = width;
            this.height = height;
            this.framesPerSecond = framesPerSecond;
        }

        @Override
        public void setVideoConfiguration(byte[] sps, byte[] pps) {
            this.sps = sps.clone();
            this.pps = pps.clone();
        }

        @Override
        public void connect(String endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public boolean isStreaming() {
            return streaming;
        }

        @Override
        public void sendVideo(byte[] payload, MediaCodec.BufferInfo bufferInfo) {
            payloads.add(payload.clone());
            MediaCodec.BufferInfo copy = new MediaCodec.BufferInfo();
            copy.offset = bufferInfo.offset;
            copy.size = bufferInfo.size;
            copy.presentationTimeUs = bufferInfo.presentationTimeUs;
            copy.flags = bufferInfo.flags;
            infos.add(copy);
        }

        @Override
        public void disconnect() {
            disconnectCalls++;
            streaming = false;
        }
    }
}
