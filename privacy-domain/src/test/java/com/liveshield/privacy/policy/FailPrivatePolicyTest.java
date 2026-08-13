package com.liveshield.privacy.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.liveshield.privacy.decision.FramePrivacyDecision;
import com.liveshield.privacy.model.ConfidenceClass;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.model.ProtectionAction;
import com.liveshield.privacy.model.TypedFailure;
import com.liveshield.privacy.session.SessionHealth;
import com.liveshield.privacy.session.SessionState;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public final class FailPrivatePolicyTest {
    private static final SessionPrivacyConfigurationView NO_CONFIGURATION =
            new SessionPrivacyConfigurationView() {
                @Override
                public Set<String> normalizedWatchlistTerms() {
                    return Set.of();
                }

                @Override
                public List<NormalizedRect> activePrivacyZones() {
                    return List.of();
                }

                @Override
                public boolean zonesSafelyTransformed() {
                    return true;
                }
            };
    private PrivacyPolicyConfiguration policyConfiguration;
    private DefaultPrivacyPolicyEngine engine;

    @Before
    public void setUp() {
        policyConfiguration = new PrivacyPolicyConfiguration(
                Set.of(DetectorLane.FACE), 10, 20, 40, 0.25, 3, 2, 5);
        engine = new DefaultPrivacyPolicyEngine(policyConfiguration);
    }

    @Test
    public void freshRequiredLaneProducesFreshRegionalDecision() {
        FramePrivacyDecision result = decide(100, List.of(success(DetectorLane.FACE, 95, 110)),
                health());

        assertRegional(result, FramePrivacyDecision.Basis.FRESH);
        assertEquals(FrameTimestamp.ofNanos(105), result.expiresAt());
    }

    @Test
    public void staleLaneCarriesThenExpandsThenShields() {
        decide(100, List.of(success(DetectorLane.FACE, 95, 105)), health());

        FramePrivacyDecision carried = decide(115,
                List.of(success(DetectorLane.FACE, 95, 105)), health());
        FramePrivacyDecision expanded = decide(125,
                List.of(success(DetectorLane.FACE, 95, 105)), health());
        FramePrivacyDecision timedOut = decide(141,
                List.of(success(DetectorLane.FACE, 95, 105)), health());

        assertRegional(carried, FramePrivacyDecision.Basis.CARRIED);
        assertRegional(expanded, FramePrivacyDecision.Basis.EXPANDED);
        assertShield(timedOut, FramePrivacyDecision.Basis.STALE);
    }

    @Test
    public void staleLaneWithNoExistingMaskShieldsInsteadOfAllowingRawFrame() {
        DetectorSnapshot empty = DetectorSnapshot.success(
                DetectorLane.FACE,
                FrameTimestamp.ofNanos(100),
                FrameTimestamp.ofNanos(105),
                List.of());
        assertRegional(decide(100, List.of(empty), health()),
                FramePrivacyDecision.Basis.FRESH);

        FramePrivacyDecision result = decide(110, List.of(empty), health());

        assertShield(result, FramePrivacyDecision.Basis.STALE);
    }

    @Test
    public void verifiedEmptyAssessmentUsesBoundedFreshnessBeforeShielding() {
        engine = new DefaultPrivacyPolicyEngine(new PrivacyPolicyConfiguration(
                Set.of(DetectorLane.FACE), 550, 250, 400, 0.25, 3, 2, 5));
        DetectorSnapshot empty = DetectorSnapshot.success(
                DetectorLane.FACE,
                FrameTimestamp.ofNanos(100),
                FrameTimestamp.ofNanos(650),
                List.of());

        FramePrivacyDecision withinMeasuredGap = decide(600, List.of(empty), health());
        FramePrivacyDecision expired = decide(651, List.of(empty), health());

        assertRegional(withinMeasuredGap, FramePrivacyDecision.Basis.FRESH);
        assertTrue(withinMeasuredGap.regions().isEmpty());
        assertShield(expired, FramePrivacyDecision.Basis.STALE);
    }

    @Test
    public void expandedMaskGrowsAndClampsAtFrameBounds() {
        decide(100, List.of(success(DetectorLane.FACE, 95, 105)), health());

        ProtectedRegion expanded = decide(125,
                List.of(success(DetectorLane.FACE, 95, 105)), health()).regions().get(0);
        NormalizedRect bounds = expanded.bounds().get(0);

        assertEquals(0.0, bounds.left(), 0.0);
        assertEquals(0.05, bounds.top(), 0.000_001);
        assertEquals(0.25, bounds.right(), 0.000_001);
        assertEquals(0.35, bounds.bottom(), 0.000_001);
    }

    @Test
    public void changedSceneCannotCarryAnUnavailableAssessment() {
        decide(100, List.of(success(DetectorLane.FACE, 95, 105)), health());

        FramePrivacyDecision result = decide(110,
                List.of(success(DetectorLane.FACE, 95, 105)),
                health().sceneState(SessionHealth.SceneState.CHANGED));

        assertShield(result, FramePrivacyDecision.Basis.STALE);
    }

    @Test
    public void measuredLaneAgeOverridesNominallyValidSnapshot() {
        decide(100, List.of(success(DetectorLane.FACE, 100, 110)), health());

        FramePrivacyDecision result = decide(105,
                List.of(success(DetectorLane.FACE, 105, 115)),
                health().detectorLaneAgeNanos(DetectorLane.FACE, 11));

        assertRegional(result, FramePrivacyDecision.Basis.CARRIED);
    }

    @Test
    public void changedSceneRecoversOnlyWithFreshRequiredAssessment() {
        FramePrivacyDecision result = decide(100,
                List.of(success(DetectorLane.FACE, 100, 110)),
                health().sceneState(SessionHealth.SceneState.CHANGED));

        assertRegional(result, FramePrivacyDecision.Basis.FRESH);
    }

    @Test
    public void configuredFuturePriorityTwoLaneBecomesRequiredWithoutChangingApi() {
        engine = new DefaultPrivacyPolicyEngine(new PrivacyPolicyConfiguration(
                Set.of(DetectorLane.FACE, DetectorLane.TEXT),
                10, 20, 40, 0.25, 3, 2, 5));

        FramePrivacyDecision missing = decide(100,
                List.of(success(DetectorLane.FACE, 100, 110)), health());
        FramePrivacyDecision fresh = decide(101,
                List.of(success(DetectorLane.FACE, 101, 110),
                        success(DetectorLane.TEXT, 101, 110)), health());

        assertShield(missing, FramePrivacyDecision.Basis.MISSING);
        assertRegional(fresh, FramePrivacyDecision.Basis.FRESH);
    }

    @Test
    public void queueAtCapacityShieldsAndRequiresVerifiedRecovery() {
        FramePrivacyDecision pressure = decide(100,
                List.of(success(DetectorLane.FACE, 100, 110)), health().rawQueueDepth(3));
        FramePrivacyDecision unsafeRecovery = decide(101,
                List.of(success(DetectorLane.FACE, 101, 111)), health());
        FramePrivacyDecision verified = decide(102,
                List.of(success(DetectorLane.FACE, 102, 112)),
                health().recoveryState(SessionHealth.RecoveryState.VERIFIED));

        assertShield(pressure, FramePrivacyDecision.Basis.TIMEOUT);
        assertShield(unsafeRecovery, FramePrivacyDecision.Basis.ERROR);
        assertRegional(verified, FramePrivacyDecision.Basis.FRESH);
    }

    @Test
    public void rendererFailureShieldsAndClearsCarryState() {
        decide(100, List.of(success(DetectorLane.FACE, 100, 110)), health());
        FramePrivacyDecision failure = decide(101,
                List.of(success(DetectorLane.FACE, 101, 111)),
                health().rendererState(SessionHealth.RendererState.FAILED));
        FramePrivacyDecision verifiedButStale = decide(115,
                List.of(success(DetectorLane.FACE, 101, 111)),
                health().recoveryState(SessionHealth.RecoveryState.VERIFIED));

        assertShield(failure, FramePrivacyDecision.Basis.ERROR);
        assertShield(verifiedButStale, FramePrivacyDecision.Basis.STALE);
    }

    @Test
    public void detectorFailureNeverAuthorizesCarry() {
        decide(100, List.of(success(DetectorLane.FACE, 100, 110)), health());
        TypedFailure failure = new TypedFailure(
                TypedFailure.Code.ANALYZER_ERROR, FrameTimestamp.ofNanos(101));

        FramePrivacyDecision result = decide(101,
                List.of(DetectorSnapshot.failure(DetectorLane.FACE,
                        FrameTimestamp.ofNanos(101), failure)), health());

        assertShield(result, FramePrivacyDecision.Basis.ERROR);
    }

    @Test
    public void thermalWarningExpandsFreshProtection() {
        FramePrivacyDecision result = decide(100,
                List.of(success(DetectorLane.FACE, 100, 110)),
                health().thermalState(SessionHealth.ThermalState.WARNING));

        assertRegional(result, FramePrivacyDecision.Basis.EXPANDED);
        assertTrue(result.regions().get(0).bounds().get(0).right() > 0.2);
    }

    @Test
    public void severeThermalStateRequiresVerifiedNominalHysteresis() {
        FramePrivacyDecision severe = decide(100,
                List.of(success(DetectorLane.FACE, 100, 110)),
                health().thermalState(SessionHealth.ThermalState.SEVERE));
        FramePrivacyDecision first = decide(101,
                List.of(success(DetectorLane.FACE, 101, 111)),
                health().recoveryState(SessionHealth.RecoveryState.VERIFIED));
        FramePrivacyDecision second = decide(102,
                List.of(success(DetectorLane.FACE, 102, 112)),
                health().recoveryState(SessionHealth.RecoveryState.VERIFIED));

        assertShield(severe, FramePrivacyDecision.Basis.ERROR);
        assertShield(first, FramePrivacyDecision.Basis.ERROR);
        assertRegional(second, FramePrivacyDecision.Basis.FRESH);
    }

    @Test
    public void interruptedThermalRecoveryRestartsHysteresis() {
        decide(100, List.of(success(DetectorLane.FACE, 100, 110)),
                health().thermalState(SessionHealth.ThermalState.SEVERE));
        decide(101, List.of(success(DetectorLane.FACE, 101, 111)),
                health().recoveryState(SessionHealth.RecoveryState.VERIFIED));
        decide(102, List.of(success(DetectorLane.FACE, 102, 112)),
                health().thermalState(SessionHealth.ThermalState.WARNING)
                        .recoveryState(SessionHealth.RecoveryState.VERIFIED));

        FramePrivacyDecision restarted = decide(103,
                List.of(success(DetectorLane.FACE, 103, 113)),
                health().recoveryState(SessionHealth.RecoveryState.VERIFIED));

        assertShield(restarted, FramePrivacyDecision.Basis.ERROR);
    }

    @Test
    public void unsafeRecoveryCannotReleaseFreshFrames() {
        FramePrivacyDecision result = decide(100,
                List.of(success(DetectorLane.FACE, 100, 110)),
                health().recoveryState(SessionHealth.RecoveryState.UNSAFE));

        assertShield(result, FramePrivacyDecision.Basis.ERROR);
    }

    @Test
    public void resetPreventsPriorSessionCarry() {
        decide(100, List.of(success(DetectorLane.FACE, 100, 110)), health());
        engine.reset();

        FramePrivacyDecision result = decide(115,
                List.of(success(DetectorLane.FACE, 100, 110)), health());

        assertShield(result, FramePrivacyDecision.Basis.STALE);
    }

    @Test
    public void endedSessionCannotProduceRegionalOutput() {
        FramePrivacyDecision result = decide(100,
                List.of(success(DetectorLane.FACE, 100, 110)),
                SessionHealth.builder(SessionState.ENDED));

        assertShield(result, FramePrivacyDecision.Basis.ERROR);
    }

    @Test
    public void unsafeZoneTransformAlwaysShields() {
        SessionPrivacyConfigurationView unsafeZones = new SessionPrivacyConfigurationView() {
            @Override
            public Set<String> normalizedWatchlistTerms() {
                return Set.of();
            }

            @Override
            public List<NormalizedRect> activePrivacyZones() {
                return List.of(new NormalizedRect(0.5, 0.5, 0.9, 0.9));
            }

            @Override
            public boolean zonesSafelyTransformed() {
                return false;
            }
        };

        FramePrivacyDecision result = engine.decide(
                FrameTimestamp.ofNanos(100),
                List.of(success(DetectorLane.FACE, 100, 110)),
                List.of(), unsafeZones, health().build());

        assertShield(result, FramePrivacyDecision.Basis.ERROR);
    }

    private FramePrivacyDecision decide(
            long timestamp,
            List<DetectorSnapshot> snapshots,
            SessionHealth.Builder health) {
        return engine.decide(FrameTimestamp.ofNanos(timestamp), snapshots, List.of(),
                NO_CONFIGURATION, health.build());
    }

    private static DetectorSnapshot success(
            DetectorLane lane, long sourceTimestamp, long validUntil) {
        ProtectedRegion region = new ProtectedRegion(
                FindingCategory.FACE,
                List.of(new NormalizedRect(0.0, 0.1, 0.2, 0.3)),
                ConfidenceClass.VALIDATED,
                ProtectionAction.MOSAIC);
        return DetectorSnapshot.success(lane,
                FrameTimestamp.ofNanos(sourceTimestamp),
                FrameTimestamp.ofNanos(validUntil),
                List.of(region));
    }

    private static SessionHealth.Builder health() {
        return SessionHealth.builder(SessionState.LIVE);
    }

    private static void assertRegional(
            FramePrivacyDecision decision, FramePrivacyDecision.Basis basis) {
        assertEquals(FramePrivacyDecision.Status.REGIONAL_SAFE, decision.status());
        assertEquals(basis, decision.basis());
    }

    private static void assertShield(
            FramePrivacyDecision decision, FramePrivacyDecision.Basis basis) {
        assertEquals(FramePrivacyDecision.Status.FULL_SHIELD, decision.status());
        assertEquals(basis, decision.basis());
    }
}
