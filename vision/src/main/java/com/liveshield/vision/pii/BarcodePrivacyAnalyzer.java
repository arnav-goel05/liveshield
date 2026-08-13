package com.liveshield.vision.pii;

/** Package-local-injection compatibility seam retained for the T090 contract. */
public final class BarcodePrivacyAnalyzer extends OfflineBarcodeAnalyzer {
    BarcodePrivacyAnalyzer(BarcodeEngine engine, int maximumCodes, long freshnessNanos) {
        super(engine, maximumCodes, freshnessNanos);
    }
}
