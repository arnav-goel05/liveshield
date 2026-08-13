package com.liveshield.video.contract;

import com.liveshield.privacy.decision.FramePrivacyDecision;

/** Sole raw-to-sanitized pixel boundary for preview and encoder output. */
public interface RedactionRenderer {
    SanitizedRender render(
            RawTextureHandle rawTexture, FramePrivacyDecision privacyDecision);

    SanitizedRender renderShield(FramePrivacyDecision privacyDecision);
}
