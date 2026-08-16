package com.liveshield.vision.pii;

import com.liveshield.privacy.model.CoordinateTransform;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Validates caller-owned OCR text and immediately maps payload-free source ranges to regions. */
public final class OcrPrivacyClassifier {
    private final StructuredPiiValidator validator;
    private final OcrRegionMapper mapper;

    public OcrPrivacyClassifier() {
        this(new StructuredPiiValidator(), new OcrRegionMapper());
    }

    OcrPrivacyClassifier(StructuredPiiValidator validator, OcrRegionMapper mapper) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Caller retains and clears text; returned values contain no recognized payload. */
    public List<OcrRegionMapper.MappedRegion> classify(
            String recognizedText,
            List<OcrRegionMapper.OcrElement> elements,
            String defaultRegionCode,
            Set<String> normalizedWatchlistTerms,
            CoordinateTransform bufferToOutput,
            OcrRegionMapper.MappingOptions options) {
        Objects.requireNonNull(recognizedText, "recognizedText");
        List<StructuredPiiValidator.Match> matches = validator.validate(
                recognizedText, defaultRegionCode, normalizedWatchlistTerms);
        return mapper.map(
                recognizedText.length(), elements, matches, bufferToOutput, options);
    }

    /** Maps only exact creator-entered private words; automatic PII patterns are disabled. */
    public List<OcrRegionMapper.MappedRegion> classifyWatchlistOnly(
            String recognizedText,
            List<OcrRegionMapper.OcrElement> elements,
            Set<String> normalizedWatchlistTerms,
            CoordinateTransform bufferToOutput,
            OcrRegionMapper.MappingOptions options) {
        Objects.requireNonNull(recognizedText, "recognizedText");
        List<StructuredPiiValidator.Match> matches = validator.validateWatchlistOnly(
                recognizedText, normalizedWatchlistTerms);
        return mapper.map(
                recognizedText.length(), elements, matches, bufferToOutput, options);
    }

    @Override
    public String toString() {
        return "OcrPrivacyClassifier[payload-free]";
    }
}
