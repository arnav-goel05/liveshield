package com.liveshield.vision.pii;

import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.NormalizedPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Payload-free seam for mapping validated OCR source ranges onto output-space polygons.
 *
 * <p>Only source offsets and polygons cross this boundary; recognized text remains
 * caller-owned.</p>
 */
public final class OcrRegionMapper {
    /** Maps source offsets without receiving or retaining the recognized text payload. */
    public List<MappedRegion> map(
            int recognizedTextLength,
            List<OcrElement> elements,
            List<StructuredPiiValidator.Match> matches,
            CoordinateTransform bufferToOutput,
            MappingOptions options) {
        Objects.requireNonNull(elements, "elements");
        Objects.requireNonNull(matches, "matches");
        Objects.requireNonNull(bufferToOutput, "bufferToOutput");
        Objects.requireNonNull(options, "options");
        if (recognizedTextLength < 0) {
            throw new IllegalArgumentException("Recognized text length cannot be negative");
        }
        validateOptions(options);
        if (elements.size() > 512 || matches.size() > 256) {
            throw new IllegalArgumentException("OCR mapping input exceeds configured bounds");
        }
        for (OcrElement element : elements) {
            validateElement(element, recognizedTextLength);
        }
        ArrayList<MappedRegion> regions = new ArrayList<>(matches.size());
        for (StructuredPiiValidator.Match match : matches) {
            validateMatch(match, recognizedTextLength);
            ArrayList<OcrElement> selected = new ArrayList<>();
            for (OcrElement element : elements) {
                if (element.startOffset() < match.endOffset()
                        && element.endOffset() > match.startOffset()) {
                    selected.add(element);
                }
            }
            if (selected.isEmpty()) {
                throw new IllegalArgumentException("Sensitive range has no OCR geometry");
            }
            boolean uncertain = selected.stream().anyMatch(element ->
                    !element.boundaryCertain()
                            || element.confidence() < options.minimumReliableConfidence());
            double padding = uncertain
                    ? options.uncertainPadding() : options.reliablePadding();
            regions.add(new MappedRegion(
                    match.category(),
                    paddedBounds(selected, bufferToOutput, padding),
                    uncertain));
        }
        return List.copyOf(regions);
    }

    private static void validateOptions(MappingOptions options) {
        if (!isNormalized(options.reliablePadding())
                || !isNormalized(options.uncertainPadding())
                || !isNormalized(options.minimumReliableConfidence())
                || options.uncertainPadding() < options.reliablePadding()) {
            throw new IllegalArgumentException("Invalid OCR mapping options");
        }
    }

    private static void validateElement(OcrElement element, int textLength) {
        Objects.requireNonNull(element, "element");
        if (element.startOffset() < 0 || element.endOffset() <= element.startOffset()
                || element.endOffset() > textLength
                || !isNormalized(element.confidence())
                || element.polygon().size() < 3) {
            throw new IllegalArgumentException("Invalid OCR element");
        }
        for (NormalizedPoint point : element.polygon()) {
            Objects.requireNonNull(point, "polygon point");
        }
    }

    private static void validateMatch(StructuredPiiValidator.Match match, int textLength) {
        Objects.requireNonNull(match, "match");
        if (match.startOffset() < 0 || match.endOffset() <= match.startOffset()
                || match.endOffset() > textLength) {
            throw new IllegalArgumentException("Invalid structured PII range");
        }
    }

    private static List<NormalizedPoint> paddedBounds(
            List<OcrElement> elements,
            CoordinateTransform transform,
            double padding) {
        double left = Double.POSITIVE_INFINITY;
        double top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        double[] matrix = transform.matrix();
        for (OcrElement element : elements) {
            for (NormalizedPoint point : element.polygon()) {
                NormalizedPoint mapped = mapPoint(matrix, point);
                left = Math.min(left, mapped.x());
                top = Math.min(top, mapped.y());
                right = Math.max(right, mapped.x());
                bottom = Math.max(bottom, mapped.y());
            }
        }
        left = clamp(left - padding);
        top = clamp(top - padding);
        right = clamp(right + padding);
        bottom = clamp(bottom + padding);
        if (left >= right || top >= bottom) {
            throw new IllegalArgumentException("Mapped OCR region is empty");
        }
        return List.of(
                new NormalizedPoint(left, top),
                new NormalizedPoint(right, top),
                new NormalizedPoint(right, bottom),
                new NormalizedPoint(left, bottom));
    }

    private static NormalizedPoint mapPoint(double[] matrix, NormalizedPoint point) {
        double denominator = matrix[6] * point.x() + matrix[7] * point.y() + matrix[8];
        if (!Double.isFinite(denominator) || Math.abs(denominator) < 0.000_000_001) {
            throw new IllegalArgumentException("OCR transform is not projectable");
        }
        double x = (matrix[0] * point.x() + matrix[1] * point.y() + matrix[2])
                / denominator;
        double y = (matrix[3] * point.x() + matrix[4] * point.y() + matrix[5])
                / denominator;
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("OCR transform produced invalid coordinates");
        }
        return new NormalizedPoint(clamp(x), clamp(y));
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static boolean isNormalized(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    /** One OCR engine element and its caller-owned source offsets. */
    public record OcrElement(
            int startOffset,
            int endOffset,
            List<NormalizedPoint> polygon,
            double confidence,
            boolean boundaryCertain) {
        public OcrElement {
            polygon = List.copyOf(Objects.requireNonNull(polygon, "polygon"));
            if (startOffset < 0 || endOffset <= startOffset
                    || !isNormalized(confidence) || polygon.size() < 3) {
                throw new IllegalArgumentException("Invalid OCR element");
            }
        }
    }

    /** Conservative padding and confidence policy expressed in normalized output coordinates. */
    public record MappingOptions(
            double reliablePadding,
            double uncertainPadding,
            double minimumReliableConfidence) {
        public MappingOptions {
            validateOptions(new MappingOptionsValues(
                    reliablePadding, uncertainPadding, minimumReliableConfidence));
        }
    }

    /** Payload-free mapped finding; polygons are immutable and contain no recognized text. */
    public record MappedRegion(
            FindingCategory category,
            List<NormalizedPoint> polygon,
            boolean conservativelyExpanded) {
        public MappedRegion {
            Objects.requireNonNull(category, "category");
            polygon = List.copyOf(Objects.requireNonNull(polygon, "polygon"));
            if (polygon.size() < 3) {
                throw new IllegalArgumentException(
                        "Mapped polygon must have at least three points");
            }
        }
    }

    private record MappingOptionsValues(
            double reliablePadding,
            double uncertainPadding,
            double minimumReliableConfidence) {
    }

    private static void validateOptions(MappingOptionsValues options) {
        if (!isNormalized(options.reliablePadding())
                || !isNormalized(options.uncertainPadding())
                || !isNormalized(options.minimumReliableConfidence())
                || options.uncertainPadding() < options.reliablePadding()) {
            throw new IllegalArgumentException("Invalid OCR mapping options");
        }
    }
}
