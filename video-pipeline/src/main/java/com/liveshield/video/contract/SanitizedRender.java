package com.liveshield.video.contract;

import com.liveshield.privacy.model.FrameTimestamp;

/** Attestation for a renderer-owned output that has crossed the privacy boundary. */
public record SanitizedRender(FrameTimestamp timestamp) {
    public SanitizedRender {
        if (timestamp == null) {
            throw new NullPointerException("timestamp");
        }
    }
}
