package com.liveshield.privacy.decision;

import com.liveshield.privacy.model.FrameTimestamp;

/** Timestamp-exact bounded decision storage with shield-on-miss semantics. */
public interface FrameDecisionStore {
    void store(FramePrivacyDecision decision);

    FramePrivacyDecision lookup(FrameTimestamp frameTimestamp, FrameTimestamp lookupTimestamp);

    int size();

    void clear();
}
