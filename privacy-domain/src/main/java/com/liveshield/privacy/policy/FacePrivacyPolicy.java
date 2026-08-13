package com.liveshield.privacy.policy;

import com.liveshield.privacy.model.FaceTrackSnapshot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/** Maps session-local association state to conservative face visibility policy. */
public final class FacePrivacyPolicy {
    /**
     * Grants visibility only to the exact selected track while its continuity remains fresh.
     *
     * <p>Prediction, ambiguity, expiry, disappearance, and replacement never transfer the grant.
     * The returned snapshots are new immutable values; association input remains unchanged.</p>
     */
    public Result apply(
            List<FaceTrackSnapshot> tracks,
            OptionalLong selectedHostTrackId,
            Set<Long> continuityLostTrackIds,
            Set<Long> expiredTrackIds) {
        List<FaceTrackSnapshot> safeTracks = List.copyOf(
                Objects.requireNonNull(tracks, "tracks"));
        Objects.requireNonNull(selectedHostTrackId, "selectedHostTrackId");
        Set<Long> continuityLost = Set.copyOf(
                Objects.requireNonNull(continuityLostTrackIds, "continuityLostTrackIds"));
        Set<Long> expired = Set.copyOf(
                Objects.requireNonNull(expiredTrackIds, "expiredTrackIds"));

        Long selectedId = selectedHostTrackId.isPresent()
                ? selectedHostTrackId.getAsLong() : null;
        boolean selectedFresh = false;
        Set<Long> seenIds = new HashSet<>();
        for (FaceTrackSnapshot track : safeTracks) {
            Objects.requireNonNull(track, "track");
            if (!seenIds.add(track.trackId())) {
                throw new IllegalArgumentException("Duplicate session track identifier");
            }
            if (selectedId != null
                    && track.trackId() == selectedId
                    && track.confidenceState() == FaceTrackSnapshot.ConfidenceState.FRESH
                    && !continuityLost.contains(selectedId)
                    && !expired.contains(selectedId)) {
                selectedFresh = true;
            }
        }

        boolean hostContinuityLost = selectedId != null && !selectedFresh;
        List<FaceTrackSnapshot> mapped = new ArrayList<>(safeTracks.size());
        for (FaceTrackSnapshot track : safeTracks) {
            boolean visible = selectedFresh && track.trackId() == selectedId;
            mapped.add(new FaceTrackSnapshot(
                    track.trackId(),
                    track.bounds(),
                    track.lastDetected(),
                    track.confidenceState(),
                    visible
                            ? FaceTrackSnapshot.Policy.HOST_VISIBLE
                            : FaceTrackSnapshot.Policy.PROTECTED));
        }
        return new Result(mapped, hostContinuityLost);
    }

    /** Immutable mapping result for downstream face policy and explicit host revocation. */
    public record Result(List<FaceTrackSnapshot> tracks, boolean hostContinuityLost) {
        public Result {
            tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks"));
        }
    }
}
