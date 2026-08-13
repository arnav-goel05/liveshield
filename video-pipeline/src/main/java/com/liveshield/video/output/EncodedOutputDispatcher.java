package com.liveshield.video.output;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Copies codec-owned buffers and enforces monotonic media timestamps before dispatch. */
final class EncodedOutputDispatcher {
    private final SanitizedVideoSink sink;
    private final int codecConfigurationFlag;
    private long lastMediaPresentationTimeUs = -1L;

    EncodedOutputDispatcher(SanitizedVideoSink sink, int codecConfigurationFlag) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.codecConfigurationFlag = codecConfigurationFlag;
    }

    void dispatch(ByteBuffer codecBuffer, int offset, int size, long presentationTimeUs, int flags) {
        Objects.requireNonNull(codecBuffer, "codecBuffer");
        if (offset < 0 || size < 0 || offset > codecBuffer.limit() - size) {
            throw new IllegalArgumentException("Invalid codec output range");
        }
        if (presentationTimeUs < 0) {
            throw new IllegalArgumentException("Codec PTS must be non-negative");
        }
        boolean codecConfiguration = (flags & codecConfigurationFlag) != 0;
        if (!codecConfiguration && size > 0) {
            if (presentationTimeUs < lastMediaPresentationTimeUs) {
                throw new IllegalStateException("Codec produced regressing media PTS");
            }
            lastMediaPresentationTimeUs = presentationTimeUs;
        }
        ByteBuffer selected = codecBuffer.duplicate();
        selected.position(offset);
        selected.limit(offset + size);
        byte[] copied = new byte[size];
        selected.get(copied);
        sink.onAccessUnit(new SanitizedH264AccessUnit(copied, presentationTimeUs, flags));
    }
}
