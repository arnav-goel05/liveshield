package com.liveshield.vision.pii;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.NormalizedPoint;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class OcrPrivacyClassifierTest {
    private static final OcrRegionMapper.MappingOptions OPTIONS =
            new OcrRegionMapper.MappingOptions(0.01, 0.05, 0.80);
    private final OcrPrivacyClassifier classifier = new OcrPrivacyClassifier();

    @Test
    public void classifiesStructuredAndWatchlistRangesWithoutMaskingSafeMiddleText() {
        String text = "creator@example.org safe Private Employer";
        List<OcrRegionMapper.OcrElement> elements = List.of(
                element(0, 19, 0.05, 0.10, 0.35, 0.20, 0.99, true),
                element(20, 24, 0.40, 0.10, 0.50, 0.20, 0.99, true),
                element(25, text.length(), 0.60, 0.10, 0.90, 0.20, 0.99, true));

        List<OcrRegionMapper.MappedRegion> regions = classifier.classify(
                text,
                elements,
                "SG",
                Set.of("private employer"),
                CoordinateTransform.identity(),
                OPTIONS);

        assertEquals(2, regions.size());
        assertEquals(FindingCategory.AUTO_EMAIL, regions.get(0).category());
        assertEquals(FindingCategory.WATCHLIST_MATCH, regions.get(1).category());
        assertTrue(maxX(regions.get(0)) < 0.40);
        assertTrue(minX(regions.get(1)) > 0.50);
    }

    @Test
    public void contextualOtpWithLowConfidenceExpandsWholeOcrElement() {
        String text = "verification code is 482913";
        OcrRegionMapper.OcrElement element = element(
                0, text.length(), 0.20, 0.20, 0.60, 0.30, 0.55, true);

        List<OcrRegionMapper.MappedRegion> regions = classifier.classify(
                text,
                List.of(element),
                "SG",
                Set.of(),
                CoordinateTransform.identity(),
                OPTIONS);

        assertEquals(1, regions.size());
        assertEquals(FindingCategory.AUTO_OTP, regions.get(0).category());
        assertTrue(regions.get(0).conservativelyExpanded());
        assertEquals(0.15, minX(regions.get(0)), 0.000_001);
        assertEquals(0.65, maxX(regions.get(0)), 0.000_001);
    }

    @Test
    public void harmlessAndAbsentWatchlistTextProducesNoRegions() {
        String text = "Ann inventory reference 482913";

        List<OcrRegionMapper.MappedRegion> regions = classifier.classify(
                text,
                List.of(element(0, text.length(), 0.1, 0.1, 0.8, 0.2, 0.99, true)),
                "SG",
                Set.of(),
                CoordinateTransform.identity(),
                OPTIONS);

        assertTrue(regions.isEmpty());
    }

    @Test
    public void classifierHasNoPayloadFieldsOrPayloadBearingString() {
        for (Field field : OcrPrivacyClassifier.class.getDeclaredFields()) {
            assertFalse(field.getType() == String.class
                    || field.getType() == char[].class
                    || field.getType() == byte[].class
                    || field.getType() == ByteBuffer.class);
        }
        assertEquals("OcrPrivacyClassifier[payload-free]", classifier.toString());
        assertFalse(classifier.toString().contains("creator@example.org"));
    }

    @Test
    public void missingGeometryForValidatedPayloadFailsPrivate() {
        assertThrows(IllegalArgumentException.class, () -> classifier.classify(
                "creator@example.org",
                List.of(),
                "SG",
                Set.of(),
                CoordinateTransform.identity(),
                OPTIONS));
    }

    private static OcrRegionMapper.OcrElement element(
            int start,
            int end,
            double left,
            double top,
            double right,
            double bottom,
            double confidence,
            boolean boundaryCertain) {
        return new OcrRegionMapper.OcrElement(
                start,
                end,
                List.of(
                        new NormalizedPoint(left, top),
                        new NormalizedPoint(right, top),
                        new NormalizedPoint(right, bottom),
                        new NormalizedPoint(left, bottom)),
                confidence,
                boundaryCertain);
    }

    private static double minX(OcrRegionMapper.MappedRegion region) {
        return region.polygon().stream().mapToDouble(NormalizedPoint::x).min().orElseThrow();
    }

    private static double maxX(OcrRegionMapper.MappedRegion region) {
        return region.polygon().stream().mapToDouble(NormalizedPoint::x).max().orElseThrow();
    }
}
