package com.liveshield.transport.rtmp;

import android.media.MediaCodec;
import com.pedro.common.ConnectChecker;
import com.pedro.common.VideoCodec;
import com.pedro.rtmp.rtmp.RtmpClient;
import java.nio.ByteBuffer;
import java.util.Objects;

/** Package-private RootEncoder adapter exposing only low-level video operations. */
final class RootEncoderVideoClient implements RtmpStreamPublisher.VideoClient {
    private final RtmpClient client;
    private Listener listener;

    RootEncoderVideoClient() {
        client = new RtmpClient(new ConnectionCallbacks());
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public void configureVideoOnly(int width, int height, int framesPerSecond) {
        client.setLogs(false);
        client.setOnlyVideo(true);
        client.setVideoCodec(VideoCodec.H264);
        client.setVideoResolution(width, height);
        client.setFps(framesPerSecond);
    }

    @Override
    public void setVideoConfiguration(byte[] sequenceParameterSet, byte[] pictureParameterSet) {
        client.setVideoInfo(
                ByteBuffer.wrap(sequenceParameterSet.clone()),
                ByteBuffer.wrap(pictureParameterSet.clone()),
                null);
    }

    @Override
    public void connect(String endpoint) {
        client.connect(endpoint);
    }

    @Override
    public boolean isStreaming() {
        return client.isStreaming();
    }

    @Override
    public void sendVideo(byte[] payload, MediaCodec.BufferInfo bufferInfo) {
        byte[] copied = payload.clone();
        MediaCodec.BufferInfo copiedInfo = new MediaCodec.BufferInfo();
        copiedInfo.offset = bufferInfo.offset;
        copiedInfo.size = bufferInfo.size;
        copiedInfo.presentationTimeUs = bufferInfo.presentationTimeUs;
        copiedInfo.flags = bufferInfo.flags;
        client.sendVideo(ByteBuffer.wrap(copied), copiedInfo);
    }

    @Override
    public void disconnect() {
        client.disconnect();
    }

    private final class ConnectionCallbacks implements ConnectChecker {
        @Override
        public void onConnectionStarted(String endpoint) {
            Listener current = listener;
            if (current != null) {
                current.onConnectionStarted();
            }
        }

        @Override
        public void onConnectionSuccess() {
            Listener current = listener;
            if (current != null) {
                current.onConnectionSuccess();
            }
        }

        @Override
        public void onConnectionFailed(String reason) {
            Listener current = listener;
            if (current != null) {
                current.onConnectionFailed();
            }
        }

        @Override
        public void onDisconnect() {
            Listener current = listener;
            if (current != null) {
                current.onDisconnected();
            }
        }

        @Override
        public void onAuthError() {
            Listener current = listener;
            if (current != null) {
                current.onAuthenticationFailed();
            }
        }

        @Override
        public void onAuthSuccess() {
        }
    }
}
