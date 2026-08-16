package com.liveshield.privacy.policy;

import com.liveshield.privacy.decision.FramePrivacyDecision;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FaceTrackSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.session.SessionHealth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Production policy composition for face continuity plus Priority 2 findings and fixed zones. */
public final class PriorityTwoPrivacyPolicyEngine implements PrivacyPolicyEngine {
    private final DefaultPrivacyPolicyEngine facePolicy;
    private final PriorityTwoPolicy priorityTwoPolicy;
    private final Consumer<DecisionSource> decisionObserver;

    public PriorityTwoPrivacyPolicyEngine(
            DefaultPrivacyPolicyEngine facePolicy,
            PriorityTwoPolicy priorityTwoPolicy) {
        this(facePolicy, priorityTwoPolicy, ignored -> { });
    }

    public PriorityTwoPrivacyPolicyEngine(
            DefaultPrivacyPolicyEngine facePolicy,
            PriorityTwoPolicy priorityTwoPolicy,
            Consumer<DecisionSource> decisionObserver) {
        this.facePolicy = Objects.requireNonNull(facePolicy, "facePolicy");
        this.priorityTwoPolicy = Objects.requireNonNull(priorityTwoPolicy, "priorityTwoPolicy");
        this.decisionObserver = Objects.requireNonNull(decisionObserver, "decisionObserver");
    }

    @Override
    public synchronized FramePrivacyDecision decide(
            FrameTimestamp frameTimestamp,
            List<DetectorSnapshot> detectorSnapshots,
            List<FaceTrackSnapshot> activeTracks,
            SessionPrivacyConfigurationView configuration,
            SessionHealth health) {
        List<DetectorSnapshot> faceSnapshots = detectorSnapshots.stream()
                .filter(snapshot -> snapshot.lane() == DetectorLane.FACE)
                .toList();
        FramePrivacyDecision faceDecision = facePolicy.decide(
                frameTimestamp, faceSnapshots, activeTracks, EMPTY_CONFIGURATION, health);
        if (faceDecision.status() == FramePrivacyDecision.Status.FULL_SHIELD) {
            priorityTwoPolicy.reset();
            decisionObserver.accept(DecisionSource.FACE_SHIELD);
            return faceDecision;
        }
        PriorityTwoPolicy.Result priorityTwo = priorityTwoPolicy.evaluate(
                frameTimestamp,
                detectorSnapshots,
                configuration,
                health.sceneState() == SessionHealth.SceneState.CHANGED);
        if (priorityTwo.basis() == SensitiveFindingPolicy.Basis.SHIELD_REQUIRED) {
            decisionObserver.accept(DecisionSource.PRIORITY_TWO_SHIELD);
            return FramePrivacyDecision.fullShield(
                    frameTimestamp, FramePrivacyDecision.Basis.ERROR);
        }
        decisionObserver.accept(DecisionSource.REGIONAL);
        ArrayList<ProtectedRegion> combined = new ArrayList<>(faceDecision.regions());
        combined.addAll(priorityTwo.regions());
        return FramePrivacyDecision.regionalSafe(
                frameTimestamp,
                combined,
                stronger(faceDecision.basis(), priorityTwo.basis()),
                faceDecision.expiresAt());
    }

    /** Clears carry state at every session boundary. */
    public synchronized void reset() {
        facePolicy.reset();
        priorityTwoPolicy.reset();
    }

    private static FramePrivacyDecision.Basis stronger(
            FramePrivacyDecision.Basis face,
            SensitiveFindingPolicy.Basis priorityTwo) {
        return switch (priorityTwo) {
            case FRESH -> face;
            case CARRIED -> face == FramePrivacyDecision.Basis.FRESH
                    ? FramePrivacyDecision.Basis.CARRIED : face;
            case EXPANDED -> face == FramePrivacyDecision.Basis.FRESH
                            || face == FramePrivacyDecision.Basis.CARRIED
                    ? FramePrivacyDecision.Basis.EXPANDED : face;
            case SHIELD_REQUIRED -> FramePrivacyDecision.Basis.ERROR;
        };
    }

    private static final SessionPrivacyConfigurationView EMPTY_CONFIGURATION =
            new SessionPrivacyConfigurationView() {
                @Override
                public Set<String> normalizedWatchlistTerms() {
                    return Set.of();
                }

                @Override
                public List<com.liveshield.privacy.model.NormalizedRect> activePrivacyZones() {
                    return List.of();
                }

                @Override
                public boolean zonesSafelyTransformed() {
                    return true;
                }
            };

    /** Payload-free branch marker for debug diagnostics and deterministic policy tests. */
    public enum DecisionSource {
        FACE_SHIELD,
        PRIORITY_TWO_SHIELD,
        REGIONAL
    }
}
