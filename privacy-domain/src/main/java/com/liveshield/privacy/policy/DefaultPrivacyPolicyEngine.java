package com.liveshield.privacy.policy;

import com.liveshield.privacy.decision.FramePrivacyDecision;
import com.liveshield.privacy.model.ConfidenceClass;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FaceTrackSnapshot;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.model.ProtectionAction;
import com.liveshield.privacy.session.SessionHealth;
import com.liveshield.privacy.session.SessionState;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Stateful, deterministic policy that never converts uncertainty into untreated output. */
public final class DefaultPrivacyPolicyEngine implements PrivacyPolicyEngine {
    private final PrivacyPolicyConfiguration configuration;
    private List<ProtectedRegion> lastFreshRegions = List.of();
    private FrameTimestamp lastFreshTimestamp;
    private boolean unsafeRecoveryLatched;
    private boolean thermalRecoveryLatched;
    private int thermalRecoveryProgress;

    public DefaultPrivacyPolicyEngine() {
        this(PrivacyPolicyConfiguration.faceOnlyDefaults());
    }

    public DefaultPrivacyPolicyEngine(PrivacyPolicyConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    @Override
    public synchronized FramePrivacyDecision decide(
            FrameTimestamp frameTimestamp,
            List<DetectorSnapshot> detectorSnapshots,
            List<FaceTrackSnapshot> activeTracks,
            SessionPrivacyConfigurationView sessionConfiguration,
            SessionHealth health) {
        Objects.requireNonNull(frameTimestamp, "frameTimestamp");
        Objects.requireNonNull(detectorSnapshots, "detectorSnapshots");
        Objects.requireNonNull(activeTracks, "activeTracks");
        Objects.requireNonNull(sessionConfiguration, "sessionConfiguration");
        Objects.requireNonNull(health, "health");

        if (!canRenderProtectedOutput(health.state())) {
            return latchUnsafe(frameTimestamp, FramePrivacyDecision.Basis.ERROR, false);
        }
        if (!sessionConfiguration.zonesSafelyTransformed()) {
            return latchUnsafe(frameTimestamp, FramePrivacyDecision.Basis.ERROR, false);
        }
        if (health.rendererState() == SessionHealth.RendererState.FAILED) {
            return latchUnsafe(frameTimestamp, FramePrivacyDecision.Basis.ERROR, false);
        }
        if (health.rawQueueDepth() >= configuration.rawQueueCapacity()) {
            return latchUnsafe(frameTimestamp, FramePrivacyDecision.Basis.TIMEOUT, false);
        }
        if (health.thermalState() == SessionHealth.ThermalState.SEVERE) {
            return latchUnsafe(frameTimestamp, FramePrivacyDecision.Basis.ERROR, true);
        }
        if (health.recoveryState() == SessionHealth.RecoveryState.UNSAFE) {
            return latchUnsafe(frameTimestamp, FramePrivacyDecision.Basis.ERROR, false);
        }

        LaneAssessment assessment = assessRequiredLanes(frameTimestamp, detectorSnapshots, health);
        if (assessment.failureBasis != null) {
            if (assessment.failureBasis == FramePrivacyDecision.Basis.ERROR) {
                clearCarryState();
            }
            resetInterruptedThermalRecovery();
            return FramePrivacyDecision.fullShield(frameTimestamp, assessment.failureBasis);
        }
        if (!assessment.allFresh) {
            resetInterruptedThermalRecovery();
            if (health.sceneState() == SessionHealth.SceneState.CHANGED) {
                clearCarryState();
                return FramePrivacyDecision.fullShield(
                        frameTimestamp, FramePrivacyDecision.Basis.STALE);
            }
            return decideFromCarry(frameTimestamp);
        }

        List<ProtectedRegion> freshRegions = collectFreshRegions(
                frameTimestamp, detectorSnapshots, activeTracks, sessionConfiguration);
        if (unsafeRecoveryLatched) {
            if (health.recoveryState() != SessionHealth.RecoveryState.VERIFIED) {
                resetInterruptedThermalRecovery();
                return FramePrivacyDecision.fullShield(
                        frameTimestamp, FramePrivacyDecision.Basis.ERROR);
            }
            if (thermalRecoveryLatched) {
                if (health.thermalState() != SessionHealth.ThermalState.NOMINAL) {
                    thermalRecoveryProgress = 0;
                    return FramePrivacyDecision.fullShield(
                            frameTimestamp, FramePrivacyDecision.Basis.ERROR);
                }
                thermalRecoveryProgress++;
                if (thermalRecoveryProgress
                        < configuration.thermalRecoveryFreshDecisions()) {
                    return FramePrivacyDecision.fullShield(
                            frameTimestamp, FramePrivacyDecision.Basis.ERROR);
                }
            }
            unsafeRecoveryLatched = false;
            thermalRecoveryLatched = false;
            thermalRecoveryProgress = 0;
        }

        lastFreshRegions = List.copyOf(freshRegions);
        lastFreshTimestamp = frameTimestamp;
        if (health.thermalState() == SessionHealth.ThermalState.WARNING) {
            return regional(frameTimestamp, expand(freshRegions),
                    FramePrivacyDecision.Basis.EXPANDED);
        }
        return regional(frameTimestamp, freshRegions, FramePrivacyDecision.Basis.FRESH);
    }

    /** Clears all state that could otherwise authorize carry into a new session. */
    public synchronized void reset() {
        clearCarryState();
        unsafeRecoveryLatched = false;
        thermalRecoveryLatched = false;
        thermalRecoveryProgress = 0;
    }

    private LaneAssessment assessRequiredLanes(
            FrameTimestamp frameTimestamp,
            List<DetectorSnapshot> snapshots,
            SessionHealth health) {
        Map<DetectorLane, DetectorSnapshot> latest = latestAtOrBefore(frameTimestamp, snapshots);
        for (DetectorLane lane : configuration.requiredLanes()) {
            DetectorSnapshot snapshot = latest.get(lane);
            if (snapshot == null) {
                boolean hasFuture = snapshots.stream().anyMatch(candidate ->
                        candidate.lane() == lane
                                && candidate.sourceTimestamp().compareTo(frameTimestamp) > 0);
                return LaneAssessment.failed(hasFuture
                        ? FramePrivacyDecision.Basis.FUTURE
                        : FramePrivacyDecision.Basis.MISSING);
            }
            if (snapshot.failure().isPresent()) {
                return LaneAssessment.failed(FramePrivacyDecision.Basis.ERROR);
            }
            long sourceAge = frameTimestamp.nanos() - snapshot.sourceTimestamp().nanos();
            Long measuredAge = health.detectorLaneAgesNanos().get(lane);
            boolean ageIsFresh = sourceAge <= configuration.maximumDetectorAgeNanos()
                    && (measuredAge == null
                            || measuredAge <= configuration.maximumDetectorAgeNanos());
            if (!snapshot.isFreshAt(frameTimestamp) || !ageIsFresh) {
                return LaneAssessment.stale();
            }
        }
        return LaneAssessment.fresh();
    }

    private static Map<DetectorLane, DetectorSnapshot> latestAtOrBefore(
            FrameTimestamp frameTimestamp, List<DetectorSnapshot> snapshots) {
        Map<DetectorLane, DetectorSnapshot> latest = new EnumMap<>(DetectorLane.class);
        for (DetectorSnapshot snapshot : snapshots) {
            Objects.requireNonNull(snapshot, "detector snapshot");
            if (snapshot.sourceTimestamp().compareTo(frameTimestamp) <= 0) {
                DetectorSnapshot previous = latest.get(snapshot.lane());
                if (previous == null || snapshot.sourceTimestamp().compareTo(
                        previous.sourceTimestamp()) > 0) {
                    latest.put(snapshot.lane(), snapshot);
                }
            }
        }
        return latest;
    }

    private List<ProtectedRegion> collectFreshRegions(
            FrameTimestamp frameTimestamp,
            List<DetectorSnapshot> snapshots,
            List<FaceTrackSnapshot> activeTracks,
            SessionPrivacyConfigurationView sessionConfiguration) {
        List<ProtectedRegion> regions = new ArrayList<>();
        for (DetectorSnapshot snapshot : latestAtOrBefore(frameTimestamp, snapshots).values()) {
            if (snapshot.isFreshAt(frameTimestamp) && snapshot.failure().isEmpty()) {
                boolean faceTracksOwnPolicy = snapshot.lane() == DetectorLane.FACE
                        && !activeTracks.isEmpty();
                if (!faceTracksOwnPolicy) {
                    regions.addAll(snapshot.findings());
                }
            }
        }
        for (FaceTrackSnapshot track : activeTracks) {
            Objects.requireNonNull(track, "active track");
            boolean visibleHost = track.policy() == FaceTrackSnapshot.Policy.HOST_VISIBLE
                    && track.confidenceState() == FaceTrackSnapshot.ConfidenceState.FRESH;
            if (!visibleHost) {
                regions.add(new ProtectedRegion(
                        FindingCategory.FACE,
                        List.of(track.bounds()),
                        ConfidenceClass.VALIDATED,
                        ProtectionAction.MOSAIC));
            }
        }
        for (NormalizedRect zone : sessionConfiguration.activePrivacyZones()) {
            regions.add(new ProtectedRegion(
                    FindingCategory.PRIVACY_ZONE,
                    List.of(Objects.requireNonNull(zone, "privacy zone")),
                    ConfidenceClass.VALIDATED,
                    ProtectionAction.OPAQUE));
        }
        return List.copyOf(regions);
    }

    private FramePrivacyDecision decideFromCarry(FrameTimestamp frameTimestamp) {
        if (lastFreshTimestamp == null) {
            return FramePrivacyDecision.fullShield(
                    frameTimestamp, FramePrivacyDecision.Basis.STALE);
        }
        if (lastFreshRegions.isEmpty()) {
            clearCarryState();
            return FramePrivacyDecision.fullShield(
                    frameTimestamp, FramePrivacyDecision.Basis.STALE);
        }
        long age = frameTimestamp.nanos() - lastFreshTimestamp.nanos();
        if (age <= configuration.carryWindowNanos()) {
            return regional(frameTimestamp, lastFreshRegions,
                    FramePrivacyDecision.Basis.CARRIED);
        }
        if (age <= configuration.expansionWindowNanos()) {
            return regional(frameTimestamp, expand(lastFreshRegions),
                    FramePrivacyDecision.Basis.EXPANDED);
        }
        clearCarryState();
        return FramePrivacyDecision.fullShield(
                frameTimestamp, FramePrivacyDecision.Basis.STALE);
    }

    private FramePrivacyDecision regional(
            FrameTimestamp timestamp,
            List<ProtectedRegion> regions,
            FramePrivacyDecision.Basis basis) {
        try {
            return FramePrivacyDecision.regionalSafe(
                    timestamp,
                    regions,
                    basis,
                    timestamp.plusNanos(configuration.decisionValidityNanos()));
        } catch (ArithmeticException overflow) {
            return latchUnsafe(timestamp, FramePrivacyDecision.Basis.ERROR, false);
        }
    }

    private List<ProtectedRegion> expand(List<ProtectedRegion> regions) {
        return regions.stream().map(region -> new ProtectedRegion(
                region.category(),
                region.bounds().stream().map(this::expand).toList(),
                region.confidenceClass(),
                strengthen(region.action()))).toList();
    }

    private NormalizedRect expand(NormalizedRect bounds) {
        double horizontal = bounds.right() - bounds.left();
        double vertical = bounds.bottom() - bounds.top();
        double fraction = configuration.expansionFraction();
        return new NormalizedRect(
                Math.max(0.0, bounds.left() - horizontal * fraction),
                Math.max(0.0, bounds.top() - vertical * fraction),
                Math.min(1.0, bounds.right() + horizontal * fraction),
                Math.min(1.0, bounds.bottom() + vertical * fraction));
    }

    private static ProtectionAction strengthen(ProtectionAction action) {
        return action == ProtectionAction.BLUR ? ProtectionAction.MOSAIC : action;
    }

    private FramePrivacyDecision latchUnsafe(
            FrameTimestamp timestamp,
            FramePrivacyDecision.Basis basis,
            boolean thermal) {
        clearCarryState();
        unsafeRecoveryLatched = true;
        thermalRecoveryLatched |= thermal;
        thermalRecoveryProgress = 0;
        return FramePrivacyDecision.fullShield(timestamp, basis);
    }

    private void resetInterruptedThermalRecovery() {
        if (thermalRecoveryLatched) {
            thermalRecoveryProgress = 0;
        }
    }

    private void clearCarryState() {
        lastFreshRegions = List.of();
        lastFreshTimestamp = null;
    }

    private static boolean canRenderProtectedOutput(SessionState state) {
        return state == SessionState.READY
                || state == SessionState.LIVE
                || state == SessionState.DEGRADED
                || state == SessionState.SHIELDING;
    }

    private static final class LaneAssessment {
        private final boolean allFresh;
        private final FramePrivacyDecision.Basis failureBasis;

        private LaneAssessment(
                boolean allFresh, FramePrivacyDecision.Basis failureBasis) {
            this.allFresh = allFresh;
            this.failureBasis = failureBasis;
        }

        private static LaneAssessment fresh() {
            return new LaneAssessment(true, null);
        }

        private static LaneAssessment stale() {
            return new LaneAssessment(false, null);
        }

        private static LaneAssessment failed(FramePrivacyDecision.Basis basis) {
            return new LaneAssessment(false, basis);
        }
    }
}
