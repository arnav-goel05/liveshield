package com.liveshield.privacy.policy;

import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.NormalizedRect;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Session-local watchlist and complete-zone policy configuration.
 *
 * <p>Configuration is bounded, in-memory, detached from caller collections, and payload-redacted
 * when rendered as a string.</p>
 */
public final class SessionPrivacyConfiguration
        implements SessionPrivacyConfigurationView, AutoCloseable {
    private static final int MAXIMUM_TERMS = 32;
    private static final int MAXIMUM_TERM_CODE_POINTS = 64;
    private static final int MAXIMUM_ZONES = 8;
    private Set<String> normalizedTerms;
    private List<NormalizedRect> canonicalZones;
    private List<NormalizedRect> activeZones;
    private boolean zonesSafelyTransformed = true;
    private boolean automaticBarcodeProtectionEnabled = true;

    public SessionPrivacyConfiguration(
            Set<String> watchlistTerms, List<NormalizedRect> privacyZones) {
        Objects.requireNonNull(watchlistTerms, "watchlistTerms");
        Objects.requireNonNull(privacyZones, "privacyZones");
        if (watchlistTerms.size() > MAXIMUM_TERMS || privacyZones.size() > MAXIMUM_ZONES) {
            throw new IllegalArgumentException("Session privacy configuration exceeds bounds");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String term : watchlistTerms) {
            normalized.add(normalizeTerm(term));
        }
        normalizedTerms = Set.copyOf(normalized);
        canonicalZones = List.copyOf(privacyZones);
        activeZones = canonicalZones;
    }

    @Override
    public synchronized Set<String> normalizedWatchlistTerms() {
        return normalizedTerms;
    }

    /** Matches one caller-isolated candidate exactly after the configured normalization rules. */
    public synchronized boolean isConfiguredWatchlistTerm(String candidate) {
        return normalizedTerms.contains(normalizeTerm(candidate));
    }

    @Override
    public synchronized List<NormalizedRect> activePrivacyZones() {
        return activeZones;
    }

    @Override
    public synchronized boolean zonesSafelyTransformed() {
        return zonesSafelyTransformed;
    }

    public synchronized void setAutomaticBarcodeProtectionEnabled(boolean enabled) {
        automaticBarcodeProtectionEnabled = enabled;
    }

    @Override
    public synchronized boolean automaticBarcodeProtectionEnabled() {
        return automaticBarcodeProtectionEnabled;
    }

    /**
     * Returns complete fixed zones plus additional OCR-localized regions, never intersections.
     */
    public synchronized List<NormalizedRect> protectionZonesIncluding(
            List<NormalizedRect> detectedTextRegions) {
        Objects.requireNonNull(detectedTextRegions, "detectedTextRegions");
        ArrayList<NormalizedRect> combined = new ArrayList<>(
                activeZones.size() + detectedTextRegions.size());
        combined.addAll(activeZones);
        for (NormalizedRect region : detectedTextRegions) {
            combined.add(Objects.requireNonNull(region, "detected text region"));
        }
        return List.copyOf(combined);
    }

    /** Recomputes active zones from the canonical configured zones using verified geometry. */
    public synchronized void applyVerifiedZoneTransform(
            GeometryChange change, CoordinateTransform canonicalToOutput) {
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(canonicalToOutput, "canonicalToOutput");
        ArrayList<NormalizedRect> transformed = new ArrayList<>(canonicalZones.size());
        try {
            for (NormalizedRect zone : canonicalZones) {
                transformed.add(transform(zone, canonicalToOutput.matrix()));
            }
            activeZones = List.copyOf(transformed);
            zonesSafelyTransformed = true;
        } catch (RuntimeException failure) {
            zonesSafelyTransformed = canonicalZones.isEmpty();
            throw failure;
        }
    }

    /** Suspends regional authorization until complete zone geometry is verified again. */
    public synchronized void markZoneTransformUnsafe(GeometryChange change) {
        Objects.requireNonNull(change, "change");
        if (!canonicalZones.isEmpty()) {
            zonesSafelyTransformed = false;
        }
    }

    /** Returns an immutable policy view detached from later configuration mutations. */
    public synchronized SessionPrivacyConfigurationView snapshot() {
        return new Snapshot(
                normalizedTerms,
                activeZones,
                zonesSafelyTransformed,
                automaticBarcodeProtectionEnabled);
    }

    /** Clears all session-local terms, zones, and geometry state. */
    public synchronized void clearSession() {
        normalizedTerms = Set.of();
        canonicalZones = List.of();
        activeZones = List.of();
        zonesSafelyTransformed = true;
        automaticBarcodeProtectionEnabled = true;
    }

    @Override
    public void close() {
        clearSession();
    }

    @Override
    public String toString() {
        return "SessionPrivacyConfiguration[session-private]";
    }

    private static String normalizeTerm(String term) {
        Objects.requireNonNull(term, "term");
        String unicode = Normalizer.normalize(term, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(unicode.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < unicode.length(); ) {
            int codePoint = unicode.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) {
                throw new IllegalArgumentException("Watchlist term contains a control");
            }
            if (Character.isWhitespace(codePoint)) {
                pendingSpace = result.length() > 0;
            } else {
                if (pendingSpace) {
                    result.append(' ');
                    pendingSpace = false;
                }
                result.appendCodePoint(codePoint);
            }
        }
        String normalized = result.toString();
        int length = normalized.codePointCount(0, normalized.length());
        if (length == 0 || length > MAXIMUM_TERM_CODE_POINTS) {
            throw new IllegalArgumentException("Watchlist term length is outside bounds");
        }
        return normalized;
    }

    private static NormalizedRect transform(NormalizedRect zone, double[] matrix) {
        double[][] corners = {
            {zone.left(), zone.top()},
            {zone.right(), zone.top()},
            {zone.right(), zone.bottom()},
            {zone.left(), zone.bottom()}
        };
        double left = Double.POSITIVE_INFINITY;
        double top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        for (double[] corner : corners) {
            double denominator = matrix[6] * corner[0]
                    + matrix[7] * corner[1] + matrix[8];
            if (!Double.isFinite(denominator) || Math.abs(denominator) < 0.000_000_001) {
                throw new IllegalArgumentException("Zone transform is not projectable");
            }
            double x = (matrix[0] * corner[0] + matrix[1] * corner[1] + matrix[2])
                    / denominator;
            double y = (matrix[3] * corner[0] + matrix[4] * corner[1] + matrix[5])
                    / denominator;
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new IllegalArgumentException("Zone transform produced invalid coordinates");
            }
            left = Math.min(left, x);
            top = Math.min(top, y);
            right = Math.max(right, x);
            bottom = Math.max(bottom, y);
        }
        double clippedLeft = clamp(left);
        double clippedTop = clamp(top);
        double clippedRight = clamp(right);
        double clippedBottom = clamp(bottom);
        if (clippedLeft >= clippedRight || clippedTop >= clippedBottom) {
            throw new IllegalArgumentException("Zone is outside transformed output");
        }
        return new NormalizedRect(
                clippedLeft, clippedTop, clippedRight, clippedBottom);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record Snapshot(
            Set<String> normalizedWatchlistTerms,
            List<NormalizedRect> activePrivacyZones,
            boolean zonesSafelyTransformed,
            boolean automaticBarcodeProtectionEnabled)
            implements SessionPrivacyConfigurationView {
        private Snapshot {
            normalizedWatchlistTerms = Set.copyOf(normalizedWatchlistTerms);
            activePrivacyZones = List.copyOf(activePrivacyZones);
        }

        @Override
        public String toString() {
            return "SessionPrivacyConfigurationView[session-private]";
        }
    }

    public enum GeometryChange {
        ROTATION,
        CROP,
        MIRROR,
        CAMERA_CHANGE
    }
}
