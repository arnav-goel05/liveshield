package com.liveshield.video.output;

/** Receives only copied video codec metadata and renderer-downstream encoded units. */
public interface SanitizedVideoSink extends AutoCloseable {
    void onCodecConfiguration(H264CodecConfiguration configuration);

    void onAccessUnit(SanitizedH264AccessUnit accessUnit);

    @Override
    default void close() {
    }
}
