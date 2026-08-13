package com.liveshield.privacy.host;

import com.liveshield.privacy.model.FaceTrackSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedPoint;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

/** Explicit session-only host selection without identity recognition. */
public interface HostSelectionController {
    HostSelectionResult selectHost(
            NormalizedPoint creatorTap,
            List<FaceTrackSnapshot> currentTracks,
            FrameTimestamp selectionTimestamp);

    void revokeSelection();

    OptionalLong selectedTrackId();

    /**
     * Reconciles selected-host permission with non-biometric association continuity.
     *
     * <p>An ambiguous, continuity-lost, replaced, or expired selected track must revoke
     * visibility; a later track may become visible only through another successful
     * {@link #selectHost} call.</p>
     */
    void reconcileHostContinuity(
            List<FaceTrackSnapshot> currentTracks,
            Set<Long> continuityLostTrackIds,
            Set<Long> expiredTrackIds);

    void resetSession();
}
