package com.liveshield.video.contract;

import com.liveshield.privacy.model.FrameTimestamp;

/** Owns each raw texture until sanitized rendering or conservative release. */
public interface BufferedFrameProcessor extends AutoCloseable {
    void accept(
            RawTextureHandle rawTexture,
            FrameTimestamp cameraTimestamp,
            FrameTimestamp deadline);

    void processReady(FrameTimestamp now);

    @Override
    void close();
}
