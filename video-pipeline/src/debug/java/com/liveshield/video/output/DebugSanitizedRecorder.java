package com.liveshield.video.output;

import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/** Explicit debug-only MP4 sink containing exactly one renderer-downstream H.264 video track. */
public final class DebugSanitizedRecorder implements SanitizedVideoSink {
    private final MediaMuxer muxer;
    private boolean started;
    private boolean closed;
    private int videoTrack = -1;
    private long lastPresentationTimeUs = -1L;

    public DebugSanitizedRecorder(File outputFile) throws IOException {
        Objects.requireNonNull(outputFile, "outputFile");
        muxer = new MediaMuxer(
                outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    @Override
    public synchronized void onCodecConfiguration(H264CodecConfiguration configuration) {
        requireOpen();
        Objects.requireNonNull(configuration, "configuration");
        if (started) {
            throw new IllegalStateException("Debug recorder already has its only video track");
        }
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                configuration.width(),
                configuration.height());
        format.setByteBuffer(
                "csd-0", ByteBuffer.wrap(configuration.sequenceParameterSet()));
        format.setByteBuffer(
                "csd-1", ByteBuffer.wrap(configuration.pictureParameterSet()));
        videoTrack = muxer.addTrack(format);
        muxer.start();
        started = true;
    }

    @Override
    @SuppressLint("WrongConstant") // Codec flags are copied intact from MediaCodec.BufferInfo.
    public synchronized void onAccessUnit(SanitizedH264AccessUnit accessUnit) {
        requireOpen();
        Objects.requireNonNull(accessUnit, "accessUnit");
        if ((accessUnit.codecFlags() & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                || accessUnit.size() == 0) {
            return;
        }
        if (!started) {
            throw new IllegalStateException("Codec configuration must precede video samples");
        }
        if (accessUnit.presentationTimeUs() < lastPresentationTimeUs) {
            throw new IllegalArgumentException("MP4 samples must have monotonic PTS");
        }
        byte[] payload = accessUnit.payload();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, payload.length, accessUnit.presentationTimeUs(), accessUnit.codecFlags());
        muxer.writeSampleData(videoTrack, ByteBuffer.wrap(payload), info);
        lastPresentationTimeUs = accessUnit.presentationTimeUs();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException stopFailure = null;
        if (started) {
            try {
                muxer.stop();
            } catch (RuntimeException exception) {
                stopFailure = exception;
            }
        }
        try {
            muxer.release();
        } catch (RuntimeException exception) {
            if (stopFailure == null) {
                stopFailure = exception;
            } else {
                stopFailure.addSuppressed(exception);
            }
        }
        if (stopFailure != null) {
            throw stopFailure;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Debug recorder is closed");
        }
    }
}
