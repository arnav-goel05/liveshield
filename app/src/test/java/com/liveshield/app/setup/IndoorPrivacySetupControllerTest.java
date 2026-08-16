package com.liveshield.app.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.liveshield.privacy.model.NormalizedRect;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class IndoorPrivacySetupControllerTest {
    @Test
    public void termsUseUnicodeNormalizationRootCaseFoldAndCollapsedWhitespace() {
        IndoorPrivacySetupController controller = new IndoorPrivacySetupController();

        assertTrue(controller.addWatchlistTerm("  ＡCMÉ\tSchool  "));
        assertFalse(controller.addWatchlistTerm("acmé school"));

        assertEquals(
                Set.of("acmé school"),
                controller.snapshot().normalizedWatchlistTerms());
    }

    @Test
    public void clearingRenderedPrivateWordMaskRemovesAllSessionTerms() {
        IndoorPrivacySetupController controller = new IndoorPrivacySetupController();
        controller.addWatchlistTerm("samsung");
        controller.addWatchlistTerm("private name");

        controller.clearWatchlistTerms();

        assertTrue(controller.snapshot().normalizedWatchlistTerms().isEmpty());
    }

    @Test
    public void barcodeProtectionDefaultsOnTogglesImmediatelyAndResetsWithSession() {
        IndoorPrivacySetupController controller = new IndoorPrivacySetupController();

        assertTrue(controller.snapshot().automaticBarcodeProtectionEnabled());
        controller.setAutomaticBarcodeProtectionEnabled(false);
        assertFalse(controller.snapshot().automaticBarcodeProtectionEnabled());

        controller.clearSession();

        assertTrue(controller.snapshot().automaticBarcodeProtectionEnabled());
    }

    @Test
    public void termNormalizationPreservesPunctuationForLaterExactWordBoundaryMatching() {
        IndoorPrivacySetupController controller = new IndoorPrivacySetupController();

        controller.addWatchlistTerm("Ann");
        controller.addWatchlistTerm("Ann-Marie");

        assertEquals(Set.of("ann", "ann-marie"),
                controller.snapshot().normalizedWatchlistTerms());
        assertFalse(controller.snapshot().normalizedWatchlistTerms().contains("joann"));
    }

    @Test
    public void blankControlsOverlongAndLimitOverflowAreRejected() {
        IndoorPrivacySetupController controller = new IndoorPrivacySetupController();

        assertThrows(IllegalArgumentException.class, () -> controller.addWatchlistTerm("  "));
        assertThrows(IllegalArgumentException.class,
                () -> controller.addWatchlistTerm("safe\u0000unsafe"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.addWatchlistTerm("x".repeat(65)));
        for (int index = 0; index < IndoorPrivacySetupController.MAX_WATCHLIST_TERMS; index++) {
            controller.addWatchlistTerm("term " + index);
        }
        assertThrows(IllegalStateException.class,
                () -> controller.addWatchlistTerm("one too many"));
    }

    @Test
    public void overlappingZonesMergeToOneConservativeCompleteArea() {
        IndoorPrivacySetupController controller = new IndoorPrivacySetupController();
        controller.addPrivacyZone(rect(0.1, 0.1, 0.4, 0.4));
        controller.addPrivacyZone(rect(0.3, 0.2, 0.7, 0.6));

        assertEquals(
                List.of(rect(0.1, 0.1, 0.7, 0.6)),
                controller.configuredPrivacyZones());
        assertFalse(controller.snapshot().zonesSafelyTransformed());
        assertTrue(controller.snapshot().activePrivacyZones().isEmpty());

        controller.applySafelyTransformedZones(
                List.of(rect(0.1, 0.1, 0.7, 0.6)));
        assertEquals(List.of(rect(0.1, 0.1, 0.7, 0.6)),
                controller.snapshot().activePrivacyZones());
    }

    @Test
    public void tinyZoneAndZoneLimitOverflowAreRejected() {
        IndoorPrivacySetupController controller = new IndoorPrivacySetupController();
        assertThrows(IllegalArgumentException.class,
                () -> controller.addPrivacyZone(rect(0.0, 0.0, 0.001, 0.001)));
        for (int index = 0; index < IndoorPrivacySetupController.MAX_PRIVACY_ZONES; index++) {
            double left = index * 0.1;
            controller.addPrivacyZone(rect(left, 0.1, left + 0.05, 0.2));
        }
        assertThrows(IllegalStateException.class,
                () -> controller.addPrivacyZone(rect(0.85, 0.1, 0.90, 0.2)));
    }

    @Test
    public void unsafeTransformLatchesUntilEveryFullZoneIsSafelyTransformed() {
        IndoorPrivacySetupController controller = new IndoorPrivacySetupController();
        controller.addPrivacyZone(rect(0.1, 0.2, 0.4, 0.6));
        IndoorPrivacySetupController.Configuration before = controller.snapshot();

        controller.markZoneTransformUnsafe();

        assertFalse(controller.snapshot().zonesSafelyTransformed());
        assertEquals(before.activePrivacyZones(), controller.snapshot().activePrivacyZones());
        assertThrows(IllegalArgumentException.class,
                () -> controller.applySafelyTransformedZones(List.of()));

        NormalizedRect mirrored = rect(0.6, 0.2, 0.9, 0.6);
        controller.applySafelyTransformedZones(List.of(mirrored));
        assertTrue(controller.snapshot().zonesSafelyTransformed());
        assertEquals(List.of(mirrored), controller.snapshot().activePrivacyZones());
    }

    @Test
    public void zoneEditsRemainUnsafeUntilCompleteTransformedGeometryIsApplied() {
        IndoorPrivacySetupController controller = new IndoorPrivacySetupController();
        NormalizedRect first = rect(0.1, 0.1, 0.3, 0.3);
        NormalizedRect second = rect(0.6, 0.6, 0.9, 0.9);
        NormalizedRect replacement = rect(0.2, 0.2, 0.5, 0.5);
        controller.addPrivacyZone(first);
        controller.addPrivacyZone(second);
        controller.applySafelyTransformedZones(List.of(first, second));

        controller.replacePrivacyZone(0, replacement);
        assertEquals(List.of(replacement, second), controller.configuredPrivacyZones());
        assertFalse(controller.snapshot().zonesSafelyTransformed());
        assertEquals(List.of(first, second), controller.snapshot().activePrivacyZones());

        controller.applySafelyTransformedZones(List.of(replacement, second));

        controller.removePrivacyZone(1);
        assertEquals(List.of(replacement), controller.configuredPrivacyZones());
        assertFalse(controller.snapshot().zonesSafelyTransformed());
        assertEquals(List.of(replacement, second),
                controller.snapshot().activePrivacyZones());

        controller.applySafelyTransformedZones(List.of(replacement));
        assertTrue(controller.snapshot().zonesSafelyTransformed());
        assertEquals(List.of(replacement), controller.snapshot().activePrivacyZones());
        assertThrows(IndexOutOfBoundsException.class,
                () -> controller.removePrivacyZone(1));
    }

    @Test
    public void snapshotsAreImmutableDetachedAndDoNotStringifyPrivateTerms() {
        IndoorPrivacySetupController controller = new IndoorPrivacySetupController();
        controller.addWatchlistTerm("Private Employer");
        NormalizedRect first = rect(0.1, 0.2, 0.4, 0.6);
        controller.addPrivacyZone(first);
        controller.applySafelyTransformedZones(List.of(first));
        IndoorPrivacySetupController.Configuration snapshot = controller.snapshot();

        controller.addWatchlistTerm("School");
        controller.addPrivacyZone(rect(0.6, 0.2, 0.9, 0.6));

        assertEquals(Set.of("private employer"), snapshot.normalizedWatchlistTerms());
        assertEquals(1, snapshot.activePrivacyZones().size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.normalizedWatchlistTerms().add("leak"));
        assertFalse(snapshot.toString().contains("private employer"));
    }

    @Test
    public void clearSessionRemovesTermsZonesAndUnsafeTransformState() {
        IndoorPrivacySetupController controller = new IndoorPrivacySetupController();
        controller.addWatchlistTerm("Name");
        controller.addPrivacyZone(rect(0.1, 0.2, 0.4, 0.6));
        controller.markZoneTransformUnsafe();

        controller.close();

        assertTrue(controller.snapshot().normalizedWatchlistTerms().isEmpty());
        assertTrue(controller.snapshot().activePrivacyZones().isEmpty());
        assertTrue(controller.snapshot().zonesSafelyTransformed());
    }

    private static NormalizedRect rect(
            double left, double top, double right, double bottom) {
        return new NormalizedRect(left, top, right, bottom);
    }
}
