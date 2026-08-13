package com.liveshield.privacy.host;

import com.liveshield.privacy.model.FaceTrackSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/** Manual host selection that grants permission only to one fresh, tapped session track. */
public final class DefaultHostSelectionController implements HostSelectionController {
    private final long maximumFreshAgeNanos;
    private Long selectedTrackId;

    public DefaultHostSelectionController(long maximumFreshAgeNanos) {
        if (maximumFreshAgeNanos < 0) {
            throw new IllegalArgumentException("maximumFreshAgeNanos must be non-negative");
        }
        this.maximumFreshAgeNanos = maximumFreshAgeNanos;
    }

    @Override
    public synchronized HostSelectionResult selectHost(
            NormalizedPoint creatorTap,
            List<FaceTrackSnapshot> currentTracks,
            FrameTimestamp selectionTimestamp) {
        Objects.requireNonNull(creatorTap, "creatorTap");
        Objects.requireNonNull(currentTracks, "currentTracks");
        Objects.requireNonNull(selectionTimestamp, "selectionTimestamp");

        List<FaceTrackSnapshot> hits = new ArrayList<>();
        for (FaceTrackSnapshot track : currentTracks) {
            Objects.requireNonNull(track, "track");
            if (track.bounds().contains(creatorTap)) {
                hits.add(track);
            }
        }
        if (hits.isEmpty()) {
            revokeSelection();
            return rejected(HostSelectionResult.Status.NO_FACE_AT_TAP);
        }
        if (hits.size() != 1) {
            revokeSelection();
            return rejected(HostSelectionResult.Status.AMBIGUOUS);
        }

        FaceTrackSnapshot candidate = hits.get(0);
        long ageNanos;
        try {
            ageNanos = Math.subtractExact(
                    selectionTimestamp.nanos(), candidate.lastDetected().nanos());
        } catch (ArithmeticException exception) {
            revokeSelection();
            return rejected(HostSelectionResult.Status.STALE_FACE);
        }
        if (candidate.confidenceState() != FaceTrackSnapshot.ConfidenceState.FRESH
                || ageNanos < 0
                || ageNanos > maximumFreshAgeNanos) {
            revokeSelection();
            return rejected(HostSelectionResult.Status.STALE_FACE);
        }

        selectedTrackId = candidate.trackId();
        return new HostSelectionResult(
                HostSelectionResult.Status.SELECTED,
                OptionalLong.of(candidate.trackId()));
    }

    @Override
    public synchronized void revokeSelection() {
        selectedTrackId = null;
    }

    @Override
    public synchronized OptionalLong selectedTrackId() {
        return selectedTrackId == null
                ? OptionalLong.empty() : OptionalLong.of(selectedTrackId);
    }

    @Override
    public synchronized void reconcileHostContinuity(
            List<FaceTrackSnapshot> currentTracks,
            Set<Long> continuityLostTrackIds,
            Set<Long> expiredTrackIds) {
        Objects.requireNonNull(currentTracks, "currentTracks");
        Objects.requireNonNull(continuityLostTrackIds, "continuityLostTrackIds");
        Objects.requireNonNull(expiredTrackIds, "expiredTrackIds");
        if (selectedTrackId == null) {
            return;
        }
        if (continuityLostTrackIds.contains(selectedTrackId)
                || expiredTrackIds.contains(selectedTrackId)) {
            revokeSelection();
            return;
        }
        FaceTrackSnapshot selected = null;
        for (FaceTrackSnapshot track : currentTracks) {
            Objects.requireNonNull(track, "track");
            if (track.trackId() == selectedTrackId) {
                selected = track;
                break;
            }
        }
        if (selected == null
                || selected.confidenceState() != FaceTrackSnapshot.ConfidenceState.FRESH) {
            revokeSelection();
        }
    }

    @Override
    public synchronized void resetSession() {
        revokeSelection();
    }

    private static HostSelectionResult rejected(HostSelectionResult.Status status) {
        return new HostSelectionResult(status, OptionalLong.empty());
    }
}
