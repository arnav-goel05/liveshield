package com.liveshield.privacy.policy;

import com.liveshield.privacy.decision.FramePrivacyDecision;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FaceTrackSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.session.SessionHealth;
import java.util.List;

/** Pure policy contract with no camera, Android, or network access. */
public interface PrivacyPolicyEngine {
    FramePrivacyDecision decide(
            FrameTimestamp frameTimestamp,
            List<DetectorSnapshot> detectorSnapshots,
            List<FaceTrackSnapshot> activeTracks,
            SessionPrivacyConfigurationView configuration,
            SessionHealth health);
}
