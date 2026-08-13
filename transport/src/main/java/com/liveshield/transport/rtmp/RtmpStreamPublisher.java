package com.liveshield.transport.rtmp;

import android.media.MediaCodec;
import com.liveshield.transport.EncodedAccessUnit;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Low-level video-only RTMP publisher for already encoded sanitized H.264 units. */
public final class RtmpStreamPublisher implements AutoCloseable {
    private final VideoClient client;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile FailureCode lastFailure = FailureCode.NONE;
    private volatile ConnectionState connectionState = ConnectionState.IDLE;
    private volatile boolean codecConfigured;
    private volatile HealthListener healthListener = HealthListener.NO_OP;
    private final AtomicInteger pendingIntentionalDisconnectCallbacks = new AtomicInteger();
    private volatile ConnectionState deliveredState;
    private volatile FailureCode deliveredFailure;

    public RtmpStreamPublisher(int width, int height, int framesPerSecond) {
        this(new RootEncoderVideoClient(), width, height, framesPerSecond);
    }

    RtmpStreamPublisher(
            VideoClient client,
            int width,
            int height,
            int framesPerSecond) {
        if (width <= 0 || height <= 0 || framesPerSecond <= 0) {
            throw new IllegalArgumentException("Video settings must be positive");
        }
        this.client = Objects.requireNonNull(client, "client");
        client.setListener(new ClientListener());
        client.configureVideoOnly(width, height, framesPerSecond);
    }

    public void connect(String endpoint) {
        ensureOpen();
        codecConfigured = false;
        lastFailure = FailureCode.NONE;
        connectionState = ConnectionState.CONNECTING;
        notifyHealth();
        client.connect(requireRtmpEndpoint(endpoint));
    }

    /** Installs a payload-free connection callback; replacing it never replays endpoint data. */
    public void setHealthListener(HealthListener listener) {
        healthListener = Objects.requireNonNull(listener, "listener");
        deliveredState = null;
        deliveredFailure = null;
        notifyHealth();
    }

    public boolean isPublishing() {
        return !closed.get() && client.isStreaming();
    }

    public void publish(EncodedAccessUnit accessUnit) {
        ensureOpen();
        EncodedAccessUnit unit = Objects.requireNonNull(accessUnit, "accessUnit");
        if (unit.trackType() != EncodedAccessUnit.TrackType.VIDEO
                || unit.codec() != EncodedAccessUnit.Codec.H264
                || unit.privacyAttestation()
                != EncodedAccessUnit.PrivacyAttestation.SANITIZED) {
            throw new IllegalArgumentException("Only sanitized H.264 video is publishable");
        }
        if (unit.flags().contains(EncodedAccessUnit.Flag.CODEC_CONFIGURATION)) {
            configureCodec(unit.payload());
            return;
        }
        if (!codecConfigured) {
            throw new IllegalStateException("H.264 codec configuration is required first");
        }
        if (!client.isStreaming()) {
            throw new IllegalStateException("RTMP publisher is not connected");
        }
        byte[] payload = unit.payload();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.offset = 0;
        info.size = payload.length;
        info.presentationTimeUs = unit.presentationTimeUs();
        info.flags = mediaCodecFlags(unit);
        client.sendVideo(payload, info);
    }

    public FailureCode lastFailure() {
        return lastFailure;
    }

    /** Payload-free state reported by the underlying RTMP connection callbacks. */
    public ConnectionState connectionState() {
        return connectionState;
    }

    /** Disconnects the current RTMP session while keeping this publisher reusable for reconnect. */
    public void disconnect() {
        if (!closed.get()) {
            codecConfigured = false;
            pendingIntentionalDisconnectCallbacks.incrementAndGet();
            connectionState = ConnectionState.DISCONNECTED;
            client.disconnect();
            notifyHealth();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            codecConfigured = false;
            client.disconnect();
            connectionState = ConnectionState.CLOSED;
            notifyHealth();
        }
    }

    private void notifyHealth() {
        if (connectionState != deliveredState || lastFailure != deliveredFailure) {
            deliveredState = connectionState;
            deliveredFailure = lastFailure;
            healthListener.onHealthChanged(connectionState, lastFailure);
        }
    }

    private void configureCodec(byte[] payload) {
        byte[][] parameterSets = splitParameterSets(payload);
        client.setVideoConfiguration(parameterSets[0], parameterSets[1]);
        codecConfigured = true;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("RTMP publisher is closed");
        }
    }

    private static String requireRtmpEndpoint(String endpoint) {
        String checked = Objects.requireNonNull(endpoint, "endpoint");
        try {
            URI uri = new URI(checked);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("rtmp")
                    || scheme.equalsIgnoreCase("rtmps"))
                    || uri.getHost() == null
                    || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("Endpoint must be an absolute RTMP URI");
            }
            return checked;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Endpoint must be a valid RTMP URI", exception);
        }
    }

    private static int mediaCodecFlags(EncodedAccessUnit unit) {
        int flags = 0;
        if (unit.flags().contains(EncodedAccessUnit.Flag.KEY_FRAME)) {
            flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
        }
        if (unit.flags().contains(EncodedAccessUnit.Flag.END_OF_STREAM)) {
            flags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
        }
        return flags;
    }

    private static byte[][] splitParameterSets(byte[] payload) {
        byte[] sps = null;
        byte[] pps = null;
        int start = findStartCode(payload, 0);
        while (start >= 0) {
            int prefix = startCodeLength(payload, start);
            int next = findStartCode(payload, start + prefix);
            int end = next < 0 ? payload.length : next;
            if (start + prefix >= end) {
                throw new IllegalArgumentException("Empty H.264 parameter set");
            }
            int type = payload[start + prefix] & 0x1f;
            byte[] nal = Arrays.copyOfRange(payload, start, end);
            if (type == 7) {
                sps = nal;
            } else if (type == 8) {
                pps = nal;
            }
            start = next;
        }
        if (sps == null || pps == null) {
            throw new IllegalArgumentException("H.264 configuration requires SPS and PPS");
        }
        return new byte[][]{sps, pps};
    }

    private static int findStartCode(byte[] payload, int from) {
        for (int index = from; index + 2 < payload.length; index++) {
            if (payload[index] == 0 && payload[index + 1] == 0
                    && (payload[index + 2] == 1
                    || (index + 3 < payload.length
                    && payload[index + 2] == 0 && payload[index + 3] == 1))) {
                return index;
            }
        }
        return -1;
    }

    private static int startCodeLength(byte[] payload, int offset) {
        return payload[offset + 2] == 1 ? 3 : 4;
    }

    public enum FailureCode {
        NONE,
        CONNECTION_FAILED,
        AUTHENTICATION_FAILED,
        DISCONNECTED
    }

    public enum ConnectionState {
        IDLE,
        CONNECTING,
        CONNECTED,
        FAILED,
        DISCONNECTED,
        CLOSED
    }

    @FunctionalInterface
    public interface HealthListener {
        HealthListener NO_OP = (state, failure) -> { };

        void onHealthChanged(ConnectionState state, FailureCode failure);
    }

    interface VideoClient {
        void setListener(Listener listener);

        void configureVideoOnly(int width, int height, int framesPerSecond);

        void setVideoConfiguration(byte[] sequenceParameterSet, byte[] pictureParameterSet);

        void connect(String endpoint);

        boolean isStreaming();

        void sendVideo(byte[] payload, MediaCodec.BufferInfo bufferInfo);

        void disconnect();

        interface Listener {
            void onConnectionStarted();

            void onConnectionSuccess();

            void onConnectionFailed();

            void onAuthenticationFailed();

            void onDisconnected();
        }
    }

    private final class ClientListener implements VideoClient.Listener {
        @Override
        public void onConnectionStarted() {
            if (closed.get()) {
                return;
            }
            connectionState = ConnectionState.CONNECTING;
            notifyHealth();
        }

        @Override
        public void onConnectionSuccess() {
            if (closed.get()) {
                return;
            }
            connectionState = ConnectionState.CONNECTED;
            notifyHealth();
        }

        @Override
        public void onConnectionFailed() {
            if (closed.get()) {
                return;
            }
            connectionState = ConnectionState.FAILED;
            lastFailure = FailureCode.CONNECTION_FAILED;
            notifyHealth();
        }

        @Override
        public void onAuthenticationFailed() {
            if (closed.get()) {
                return;
            }
            connectionState = ConnectionState.FAILED;
            lastFailure = FailureCode.AUTHENTICATION_FAILED;
            notifyHealth();
        }

        @Override
        public void onDisconnected() {
            if (!closed.get()) {
                connectionState = ConnectionState.DISCONNECTED;
                if (!consumeIntentionalDisconnectCallback()) {
                    lastFailure = FailureCode.DISCONNECTED;
                }
                notifyHealth();
            }
        }
    }

    private boolean consumeIntentionalDisconnectCallback() {
        while (true) {
            int pending = pendingIntentionalDisconnectCallbacks.get();
            if (pending == 0) {
                return false;
            }
            if (pendingIntentionalDisconnectCallbacks.compareAndSet(pending, pending - 1)) {
                return true;
            }
        }
    }
}
