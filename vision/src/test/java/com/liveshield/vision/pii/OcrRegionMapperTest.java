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
import org.junit.Test;

/** Tests-first contract for T096's payload-free OCR range localization. */
public final class OcrRegionMapperTest {
    private static final double EPSILON = 0.000001;
    private static final OcrRegionMapper.MappingOptions OPTIONS =
            new OcrRegionMapper.MappingOptions(0.01, 0.05, 0.80);
    private final OcrRegionMapper mapper = new OcrRegionMapper();

    @Test
    public void characterRangeSelectsOnlyOverlappingElementPolygon() {
        List<OcrRegionMapper.OcrElement> elements = List.of(
                element(0, 5, 0.05, 0.10, 0.18, 0.20),
                element(6, 17, 0.25, 0.10, 0.55, 0.20),
                element(18, 22, 0.65, 0.10, 0.78, 0.20));

        List<OcrRegionMapper.MappedRegion> regions = map(
                22, elements, match(FindingCategory.AUTO_EMAIL, 8, 16));

        assertEquals(1, regions.size());
        assertEquals(FindingCategory.AUTO_EMAIL, regions.get(0).category());
        assertBounds(regions.get(0).polygon(), 0.24, 0.09, 0.56, 0.21);
    }

    @Test
    public void multilineMatchProducesOnePaddedUnionWithoutFillingUnrelatedColumns() {
        List<OcrRegionMapper.OcrElement> elements = List.of(
                element(0, 4, 0.10, 0.10, 0.30, 0.18),
                element(5, 9, 0.12, 0.30, 0.42, 0.38));

        List<OcrRegionMapper.MappedRegion> regions = map(
                9, elements, match(FindingCategory.WATCHLIST_MATCH, 0, 9));

        assertEquals(1, regions.size());
        assertBounds(regions.get(0).polygon(), 0.09, 0.09, 0.43, 0.39);
    }

    @Test
    public void rotationCropAndMirrorTransformEveryPolygonCornerBeforePadding() {
        CoordinateTransform transform = new CoordinateTransform(new double[]{
            0.0, -0.5, 0.80,
            0.5, 0.0, 0.10,
            0.0, 0.0, 1.0
        });
        OcrRegionMapper.OcrElement element = element(0, 4, 0.20, 0.30, 0.40, 0.50);

        List<OcrRegionMapper.MappedRegion> regions = mapper.map(
                4,
                List.of(element),
                List.of(match(FindingCategory.AUTO_OTP, 0, 4)),
                transform,
                OPTIONS);

        assertEquals(1, regions.size());
        assertBounds(regions.get(0).polygon(), 0.39, 0.79, 0.81, 1.0);
    }

    @Test
    public void paddingIsConservativeAndClampedAtEveryFrameEdge() {
        OcrRegionMapper.MappingOptions edgeOptions =
                new OcrRegionMapper.MappingOptions(0.05, 0.10, 0.80);

        List<OcrRegionMapper.MappedRegion> regions = mapper.map(
                4,
                List.of(element(0, 4, 0.01, 0.02, 0.99, 0.98)),
                List.of(match(FindingCategory.AUTO_CARD, 0, 4)),
                CoordinateTransform.identity(),
                edgeOptions);

        assertEquals(1, regions.size());
        assertBounds(regions.get(0).polygon(), 0.0, 0.0, 1.0, 1.0);
    }

    @Test
    public void lowConfidenceOrUncertainBoundaryExpandsAroundWholeElement() {
        OcrRegionMapper.OcrElement lowConfidence = new OcrRegionMapper.OcrElement(
                0, 12, rectangle(0.20, 0.20, 0.60, 0.30), 0.55, true);
        OcrRegionMapper.OcrElement uncertain = new OcrRegionMapper.OcrElement(
                13, 25, rectangle(0.20, 0.40, 0.60, 0.50), 0.99, false);

        List<OcrRegionMapper.MappedRegion> regions = map(
                25,
                List.of(lowConfidence, uncertain),
                match(FindingCategory.AUTO_PHONE, 3, 22));

        assertEquals(1, regions.size());
        assertTrue(regions.get(0).conservativelyExpanded());
        assertBounds(regions.get(0).polygon(), 0.15, 0.15, 0.65, 0.55);
    }

    @Test
    public void disjointSensitiveRangesRemainSeparateAndPreserveSafeMiddleText() {
        List<OcrRegionMapper.OcrElement> elements = List.of(
                element(0, 5, 0.10, 0.20, 0.25, 0.30),
                element(6, 10, 0.40, 0.20, 0.55, 0.30),
                element(11, 16, 0.70, 0.20, 0.85, 0.30));

        List<OcrRegionMapper.MappedRegion> regions = mapper.map(
                16,
                elements,
                List.of(
                        match(FindingCategory.AUTO_EMAIL, 0, 5),
                        match(FindingCategory.AUTO_OTP, 11, 16)),
                CoordinateTransform.identity(),
                OPTIONS);

        assertEquals(2, regions.size());
        assertBounds(regions.get(0).polygon(), 0.09, 0.19, 0.26, 0.31);
        assertBounds(regions.get(1).polygon(), 0.69, 0.19, 0.86, 0.31);
        assertTrue(maxX(regions.get(0).polygon()) < 0.40);
        assertTrue(minX(regions.get(1).polygon()) > 0.55);
    }

    @Test
    public void malformedOffsetsAndInvalidOptionsAreRejectedFailPrivate() {
        List<OcrRegionMapper.OcrElement> elements =
                List.of(element(0, 5, 0.10, 0.10, 0.20, 0.20));

        assertThrows(IllegalArgumentException.class,
                () -> map(5, elements, match(FindingCategory.AUTO_EMAIL, -1, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> map(5, elements, match(FindingCategory.AUTO_EMAIL, 4, 3)));
        assertThrows(IllegalArgumentException.class,
                () -> map(5, elements, match(FindingCategory.AUTO_EMAIL, 0, 6)));
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(5, elements,
                        List.of(match(FindingCategory.AUTO_EMAIL, 0, 5)),
                        CoordinateTransform.identity(),
                        new OcrRegionMapper.MappingOptions(-0.01, 0.05, 0.80)));
    }

    @Test
    public void mappedContractCannotRetainRecognizedTextOrMutablePolygons() {
        for (Field field : OcrRegionMapper.class.getDeclaredFields()) {
            assertFalse(isPayloadType(field.getType()));
        }
        for (Field field : OcrRegionMapper.MappedRegion.class.getDeclaredFields()) {
            assertFalse(isPayloadType(field.getType()));
        }
        List<NormalizedPoint> mutable = new java.util.ArrayList<>(
                rectangle(0.10, 0.10, 0.20, 0.20));
        OcrRegionMapper.MappedRegion region = new OcrRegionMapper.MappedRegion(
                FindingCategory.AUTO_EMAIL, mutable, false);
        mutable.clear();
        assertEquals(4, region.polygon().size());
        assertThrows(UnsupportedOperationException.class,
                () -> region.polygon().add(new NormalizedPoint(0.5, 0.5)));
    }

    @Test
    public void missingGeometryAndNonProjectableTransformFailPrivate() {
        assertThrows(IllegalArgumentException.class, () -> mapper.map(
                5,
                List.of(element(0, 2, 0.1, 0.1, 0.2, 0.2)),
                List.of(match(FindingCategory.AUTO_EMAIL, 3, 5)),
                CoordinateTransform.identity(),
                OPTIONS));
        CoordinateTransform nonProjectable = new CoordinateTransform(new double[]{
            1.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 0.0
        });
        assertThrows(IllegalArgumentException.class, () -> mapper.map(
                2,
                List.of(element(0, 2, 0.1, 0.1, 0.2, 0.2)),
                List.of(match(FindingCategory.AUTO_OTP, 0, 2)),
                nonProjectable,
                OPTIONS));
    }

    @Test
    public void mappingInputsAndResultsAreBoundedAndImmutable() {
        List<OcrRegionMapper.MappedRegion> regions = mapper.map(
                4,
                List.of(element(0, 4, 0.1, 0.1, 0.2, 0.2)),
                List.of(match(FindingCategory.AUTO_EMAIL, 0, 4)),
                CoordinateTransform.identity(),
                OPTIONS);

        assertThrows(UnsupportedOperationException.class, regions::clear);
        List<OcrRegionMapper.OcrElement> tooMany = new java.util.ArrayList<>();
        for (int index = 0; index < 513; index++) {
            tooMany.add(element(0, 4, 0.1, 0.1, 0.2, 0.2));
        }
        assertThrows(IllegalArgumentException.class, () -> mapper.map(
                4,
                tooMany,
                List.of(match(FindingCategory.AUTO_EMAIL, 0, 4)),
                CoordinateTransform.identity(),
                OPTIONS));
    }

    private List<OcrRegionMapper.MappedRegion> map(
            int textLength,
            List<OcrRegionMapper.OcrElement> elements,
            StructuredPiiValidator.Match match) {
        return mapper.map(
                textLength,
                elements,
                List.of(match),
                CoordinateTransform.identity(),
                OPTIONS);
    }

    private static StructuredPiiValidator.Match match(
            FindingCategory category, int start, int end) {
        return new StructuredPiiValidator.Match(category, start, end);
    }

    private static OcrRegionMapper.OcrElement element(
            int start, int end, double left, double top, double right, double bottom) {
        return new OcrRegionMapper.OcrElement(
                start, end, rectangle(left, top, right, bottom), 0.99, true);
    }

    private static List<NormalizedPoint> rectangle(
            double left, double top, double right, double bottom) {
        return List.of(
                new NormalizedPoint(left, top),
                new NormalizedPoint(right, top),
                new NormalizedPoint(right, bottom),
                new NormalizedPoint(left, bottom));
    }

    private static void assertBounds(
            List<NormalizedPoint> polygon,
            double left,
            double top,
            double right,
            double bottom) {
        assertEquals(left, minX(polygon), EPSILON);
        assertEquals(top, minY(polygon), EPSILON);
        assertEquals(right, maxX(polygon), EPSILON);
        assertEquals(bottom, maxY(polygon), EPSILON);
    }

    private static double minX(List<NormalizedPoint> polygon) {
        return polygon.stream().mapToDouble(NormalizedPoint::x).min().orElseThrow();
    }

    private static double maxX(List<NormalizedPoint> polygon) {
        return polygon.stream().mapToDouble(NormalizedPoint::x).max().orElseThrow();
    }

    private static double minY(List<NormalizedPoint> polygon) {
        return polygon.stream().mapToDouble(NormalizedPoint::y).min().orElseThrow();
    }

    private static double maxY(List<NormalizedPoint> polygon) {
        return polygon.stream().mapToDouble(NormalizedPoint::y).max().orElseThrow();
    }

    private static boolean isPayloadType(Class<?> type) {
        return type == String.class
                || type == char[].class
                || type == byte[].class
                || type == ByteBuffer.class;
    }
}
