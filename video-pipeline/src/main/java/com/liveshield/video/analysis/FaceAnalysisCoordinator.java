package com.liveshield.video.analysis;

import com.liveshield.privacy.model.ConfidenceClass;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FaceTrackSnapshot;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.model.ProtectionAction;
import com.liveshield.privacy.host.HostSelectionController;
import com.liveshield.privacy.policy.FacePrivacyPolicy;
import com.liveshield.vision.face.FaceTrackAssociator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Converts offline face observations into bounded session tracks and fail-private face policy.
 *
 * <p>Association uses only geometry, time, velocity, scale, and optional detector hints. A selected
 * host remains visible only while the exact session track is fresh and unambiguous. Entry,
 * prediction, crossings, merge/split, expiry, and replacements otherwise remain protected.</p>
 */
public final class FaceAnalysisCoordinator {
    private static final long DEFAULT_PREDICTION_EXPIRY_NANOS = 400_000_000L;
    private final FaceTrackAssociator associator;
    private final FacePrivacyPolicy facePolicy;
    private FrameTimestamp latestAcceptedTimestamp;
    private boolean selectionMustBeRevoked;

    public FaceAnalysisCoordinator() {
        this(new FaceTrackAssociator(new FaceTrackAssociator.Configuration(
                DEFAULT_PREDICTION_EXPIRY_NANOS,
                0.10,
                0.30,
                2.0,
                0.30)), new FacePrivacyPolicy());
    }

    FaceAnalysisCoordinator(FaceTrackAssociator associator, FacePrivacyPolicy facePolicy) {
        this.associator = Objects.requireNonNull(associator, "associator");
        this.facePolicy = Objects.requireNonNull(facePolicy, "facePolicy");
    }

    /** Produces one immutable face state for the analyzer snapshot's exact timestamp. */
    public synchronized FaceFrameState accept(
            DetectorSnapshot snapshot, OptionalLong selectedHostTrackId) {
        return acceptInternal(snapshot, selectedHostTrackId, null);
    }

    /** Reconciles host permission atomically with the same association frame used for policy. */
    public synchronized FaceFrameState accept(
            DetectorSnapshot snapshot, HostSelectionController hostSelection) {
        Objects.requireNonNull(hostSelection, "hostSelection");
        return acceptInternal(snapshot, hostSelection.selectedTrackId(), hostSelection);
    }

    private FaceFrameState acceptInternal(
            DetectorSnapshot snapshot,
            OptionalLong selectedHostTrackId,
            HostSelectionController hostSelection) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(selectedHostTrackId, "selectedHostTrackId");
        FrameTimestamp timestamp = snapshot.sourceTimestamp();
        if (isOutOfOrder(timestamp)) {
            return FaceFrameState.fullShield(timestamp, selectedHostTrackId.isPresent());
        }
        if (snapshot.lane() != DetectorLane.FACE || snapshot.failure().isPresent()) {
            associator.resetSession();
            selectionMustBeRevoked |= selectedHostTrackId.isPresent();
            if (hostSelection != null) {
                hostSelection.revokeSelection();
            }
            return FaceFrameState.fullShield(timestamp, selectedHostTrackId.isPresent());
        }

        try {
            if (selectedHostTrackId.isEmpty()) {
                selectionMustBeRevoked = false;
            }
            OptionalLong effectiveSelection = selectionMustBeRevoked
                    ? OptionalLong.empty() : selectedHostTrackId;
            FaceTrackAssociator.AssociationFrame associated = associator.update(
                    timestamp, snapshot.observations());
            FacePrivacyPolicy.Result policy = facePolicy.apply(
                    associated.tracks(),
                    effectiveSelection,
                    associated.continuityLostTrackIds(),
                    associated.expiredTrackIds());
            if (hostSelection != null) {
                hostSelection.reconcileHostContinuity(
                        policy.tracks(),
                        associated.continuityLostTrackIds(),
                        associated.expiredTrackIds());
            }
            latestAcceptedTimestamp = timestamp;
            return new FaceFrameState(
                    timestamp,
                    policy.tracks(),
                    protectedRegions(policy.tracks()),
                    false,
                    policy.hostContinuityLost()
                            || (selectionMustBeRevoked && selectedHostTrackId.isPresent()));
        } catch (RuntimeException unsafeAssociation) {
            associator.resetSession();
            selectionMustBeRevoked |= selectedHostTrackId.isPresent();
            if (hostSelection != null) {
                hostSelection.revokeSelection();
            }
            latestAcceptedTimestamp = timestamp;
            return FaceFrameState.fullShield(timestamp, selectedHostTrackId.isPresent());
        }
    }

    /** Clears every association, prediction, detector hint, and timestamp at session end. */
    public synchronized void resetSession() {
        associator.resetSession();
        latestAcceptedTimestamp = null;
        selectionMustBeRevoked = false;
    }

    private boolean isOutOfOrder(FrameTimestamp timestamp) {
        return latestAcceptedTimestamp != null && timestamp.compareTo(latestAcceptedTimestamp) <= 0;
    }

    private static List<ProtectedRegion> protectedRegions(List<FaceTrackSnapshot> tracks) {
        List<ProtectedRegion> regions = new ArrayList<>();
        for (FaceTrackSnapshot track : tracks) {
            if (track.policy() != FaceTrackSnapshot.Policy.HOST_VISIBLE) {
                regions.add(new ProtectedRegion(
                        FindingCategory.FACE,
                        List.of(track.bounds()),
                        confidence(track.confidenceState()),
                        ProtectionAction.MOSAIC));
            }
        }
        return List.copyOf(regions);
    }

    private static ConfidenceClass confidence(FaceTrackSnapshot.ConfidenceState state) {
        return state == FaceTrackSnapshot.ConfidenceState.FRESH
                ? ConfidenceClass.VALIDATED : ConfidenceClass.UNCERTAIN;
    }

    /** Immutable result consumed by host selection and the frame privacy policy. */
    public record FaceFrameState(
            FrameTimestamp timestamp,
            List<FaceTrackSnapshot> tracks,
            List<ProtectedRegion> protectedRegions,
            boolean fullShieldRequired,
            boolean hostContinuityLost) {
        public FaceFrameState {
            Objects.requireNonNull(timestamp, "timestamp");
            tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks"));
            protectedRegions = List.copyOf(
                    Objects.requireNonNull(protectedRegions, "protectedRegions"));
            if (fullShieldRequired && (!tracks.isEmpty() || !protectedRegions.isEmpty())) {
                throw new IllegalArgumentException("A full-shield face state cannot expose regions");
            }
        }

        private static FaceFrameState fullShield(
                FrameTimestamp timestamp, boolean hostContinuityLost) {
            return new FaceFrameState(
                    timestamp, List.of(), List.of(), true, hostContinuityLost);
        }
    }
}
