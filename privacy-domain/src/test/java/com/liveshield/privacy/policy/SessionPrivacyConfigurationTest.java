package com.liveshield.privacy.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.NormalizedRect;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** Tests-first contract for session-local watchlists and complete fixed privacy zones. */
public final class SessionPrivacyConfigurationTest {
    private static final double TOLERANCE = 0.000_001;
    private static final NormalizedRect COMPLETE_ZONE =
            new NormalizedRect(0.2, 0.3, 0.4, 0.6);

    @Test
    public void watchlistUsesNfkcRootCaseFoldAndCollapsedWhitespace() {
        SessionPrivacyConfiguration configuration = configuration(
                Set.of("  ＡCMÉ\u00a0  School  "), List.of());

        assertEquals(Set.of("acmé school"), configuration.normalizedWatchlistTerms());
        assertTrue(configuration.isConfiguredWatchlistTerm("Ａcmé\tSCHOOL"));
    }

    @Test
    public void exactWatchlistCandidateRejectsUnicodeWordBoundaryNearMatches() {
        SessionPrivacyConfiguration configuration = configuration(
                Set.of("Ann", "Ann-Marie"), List.of());

        assertTrue(configuration.isConfiguredWatchlistTerm("ann"));
        assertTrue(configuration.isConfiguredWatchlistTerm("ＡＮＮ－ＭＡＲＩＥ"));
        assertFalse(configuration.isConfiguredWatchlistTerm("Joann"));
        assertFalse(configuration.isConfiguredWatchlistTerm("Annette"));
    }

    @Test
    public void immutableSnapshotIsDetachedFromCallerAndLaterSessionMutation() {
        Set<String> terms = new LinkedHashSet<>(Set.of("Private Employer"));
        List<NormalizedRect> zones = new ArrayList<>(List.of(COMPLETE_ZONE));
        SessionPrivacyConfiguration configuration = configuration(terms, zones);
        SessionPrivacyConfigurationView snapshot = configuration.snapshot();

        terms.clear();
        zones.clear();
        configuration.clearSession();

        assertEquals(Set.of("private employer"), snapshot.normalizedWatchlistTerms());
        assertEquals(List.of(COMPLETE_ZONE), snapshot.activePrivacyZones());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.normalizedWatchlistTerms().add("leak"));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.activePrivacyZones().clear());
    }

    @Test
    public void lifecycleClearRemovesTermsZonesAndUnsafeGeometryLatch() {
        SessionPrivacyConfiguration configuration = configuration(
                Set.of("Fictional Name"), List.of(COMPLETE_ZONE));
        configuration.markZoneTransformUnsafe(
                SessionPrivacyConfiguration.GeometryChange.CAMERA_CHANGE);

        configuration.clearSession();

        assertTrue(configuration.normalizedWatchlistTerms().isEmpty());
        assertTrue(configuration.activePrivacyZones().isEmpty());
        assertTrue(configuration.zonesSafelyTransformed());
        assertFalse(configuration.isConfiguredWatchlistTerm("fictional name"));
    }

    @Test
    public void ocrRegionCanAddProtectionButCannotNarrowCompleteZone() {
        SessionPrivacyConfiguration configuration = configuration(
                Set.of(), List.of(COMPLETE_ZONE));
        NormalizedRect smallerOcrRegion = new NormalizedRect(0.25, 0.4, 0.3, 0.45);

        List<NormalizedRect> protection =
                configuration.protectionZonesIncluding(List.of(smallerOcrRegion));

        assertTrue(protection.contains(COMPLETE_ZONE));
        assertTrue(protection.contains(smallerOcrRegion));
        assertEquals(List.of(COMPLETE_ZONE), configuration.activePrivacyZones());
    }

    @Test
    public void verifiedRotationRecomputesCompleteZoneFromCanonicalCoordinates() {
        SessionPrivacyConfiguration configuration = configuration(
                Set.of(), List.of(COMPLETE_ZONE));

        configuration.applyVerifiedZoneTransform(
                SessionPrivacyConfiguration.GeometryChange.ROTATION,
                transform(new double[]{
                    0.0, -1.0, 1.0,
                    1.0, 0.0, 0.0,
                    0.0, 0.0, 1.0
                }));

        assertSingleZone(configuration, new NormalizedRect(0.4, 0.2, 0.7, 0.4));
    }

    @Test
    public void verifiedCropRecomputesAndClipsCompleteZone() {
        SessionPrivacyConfiguration configuration = configuration(
                Set.of(), List.of(COMPLETE_ZONE));

        configuration.applyVerifiedZoneTransform(
                SessionPrivacyConfiguration.GeometryChange.CROP,
                transform(new double[]{
                    2.0, 0.0, -0.2,
                    0.0, 2.0, -0.4,
                    0.0, 0.0, 1.0
                }));

        assertSingleZone(configuration, new NormalizedRect(0.2, 0.2, 0.6, 0.8));
    }

    @Test
    public void verifiedMirrorRecomputesCompleteZoneWithoutCumulativeDrift() {
        SessionPrivacyConfiguration configuration = configuration(
                Set.of(), List.of(COMPLETE_ZONE));
        CoordinateTransform mirror = transform(new double[]{
            -1.0, 0.0, 1.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0
        });

        configuration.applyVerifiedZoneTransform(
                SessionPrivacyConfiguration.GeometryChange.MIRROR, mirror);
        configuration.applyVerifiedZoneTransform(
                SessionPrivacyConfiguration.GeometryChange.MIRROR, mirror);

        assertSingleZone(configuration, new NormalizedRect(0.6, 0.3, 0.8, 0.6));
    }

    @Test
    public void verifiedCameraChangeUsesExplicitCanonicalMapping() {
        SessionPrivacyConfiguration configuration = configuration(
                Set.of(), List.of(COMPLETE_ZONE));

        configuration.applyVerifiedZoneTransform(
                SessionPrivacyConfiguration.GeometryChange.CAMERA_CHANGE,
                transform(new double[]{
                    0.5, 0.0, 0.1,
                    0.0, 0.5, 0.2,
                    0.0, 0.0, 1.0
                }));

        assertSingleZone(configuration, new NormalizedRect(0.2, 0.35, 0.3, 0.5));
    }

    @Test
    public void unsafeTransformRetainsZoneObligationButFailsPrivate() {
        SessionPrivacyConfiguration configuration = configuration(
                Set.of(), List.of(COMPLETE_ZONE));

        configuration.markZoneTransformUnsafe(
                SessionPrivacyConfiguration.GeometryChange.CAMERA_CHANGE);

        assertFalse(configuration.zonesSafelyTransformed());
        assertEquals(List.of(COMPLETE_ZONE), configuration.activePrivacyZones());
    }

    @Test
    public void configurationStringNeverContainsWatchlistPayload() {
        SessionPrivacyConfiguration configuration = configuration(
                Set.of("Fictional Secret Name"), List.of(COMPLETE_ZONE));

        assertEquals("SessionPrivacyConfiguration[session-private]", configuration.toString());
        assertFalse(configuration.toString().contains("Fictional Secret Name"));
    }

    private static SessionPrivacyConfiguration configuration(
            Set<String> terms, List<NormalizedRect> zones) {
        return new SessionPrivacyConfiguration(terms, zones);
    }

    private static CoordinateTransform transform(double[] matrix) {
        return new CoordinateTransform(matrix);
    }

    private static void assertSingleZone(
            SessionPrivacyConfiguration configuration, NormalizedRect expected) {
        assertTrue(configuration.zonesSafelyTransformed());
        assertEquals(1, configuration.activePrivacyZones().size());
        NormalizedRect actual = configuration.activePrivacyZones().get(0);
        assertEquals(expected.left(), actual.left(), TOLERANCE);
        assertEquals(expected.top(), actual.top(), TOLERANCE);
        assertEquals(expected.right(), actual.right(), TOLERANCE);
        assertEquals(expected.bottom(), actual.bottom(), TOLERANCE);
    }
}
