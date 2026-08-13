package com.liveshield.transport;

import android.media.MediaCodec;
import com.liveshield.video.output.H264CodecConfiguration;
import com.liveshield.video.output.SanitizedH264AccessUnit;
import com.liveshield.video.output.SanitizedVideoSink;
import com.liveshield.video.render.PrivacySurfaceProcessor;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Renderer-authorized one-way bridge from the H.264 encoder into transport values. */
public final class SanitizedAccessUnitBridge implements SanitizedVideoSink {
    private final EncodedAccessUnitSink sink;
    private final AtomicBoolean closed = new AtomicBoolean();

    public SanitizedAccessUnitBridge(
            PrivacySurfaceProcessor.SanitizedOutputCapability capability,
            PrivacySurfaceProcessor processor,
            EncodedAccessUnitSink sink) {
        Objects.requireNonNull(capability, "capability");
        if (!capability.authorizes(Objects.requireNonNull(processor, "processor"))) {
            throw new IllegalArgumentException("Transport bridge is not renderer-authorized");
        }
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public void onCodecConfiguration(H264CodecConfiguration configuration) {
        ensureOpen();
        H264CodecConfiguration copied = Objects.requireNonNull(configuration, "configuration");
        byte[] sps = copied.sequenceParameterSet();
        byte[] pps = copied.pictureParameterSet();
        byte[] payload = new byte[sps.length + pps.length];
        System.arraycopy(sps, 0, payload, 0, sps.length);
        System.arraycopy(pps, 0, payload, sps.length, pps.length);
        sink.onAccessUnit(EncodedAccessUnit.copySanitizedH264(
                payload, 0L, Set.of(EncodedAccessUnit.Flag.CODEC_CONFIGURATION)));
    }

    @Override
    public void onAccessUnit(SanitizedH264AccessUnit accessUnit) {
        ensureOpen();
        SanitizedH264AccessUnit source = Objects.requireNonNull(accessUnit, "accessUnit");
        sink.onAccessUnit(EncodedAccessUnit.copySanitizedH264(
                source.payload(),
                source.presentationTimeUs(),
                mapFlags(source.codecFlags())));
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            sink.close();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Sanitized access-unit bridge is closed");
        }
    }

    private static Set<EncodedAccessUnit.Flag> mapFlags(int codecFlags) {
        EnumSet<EncodedAccessUnit.Flag> mapped =
                EnumSet.noneOf(EncodedAccessUnit.Flag.class);
        if ((codecFlags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            mapped.add(EncodedAccessUnit.Flag.CODEC_CONFIGURATION);
        }
        if ((codecFlags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
            mapped.add(EncodedAccessUnit.Flag.KEY_FRAME);
        }
        if ((codecFlags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            mapped.add(EncodedAccessUnit.Flag.END_OF_STREAM);
        }
        return mapped;
    }
}
