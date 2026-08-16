package com.liveshield.app.setup;

import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.policy.SessionPrivacyConfigurationView;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** In-memory, session-only setup for exact watchlist terms and complete fixed privacy zones. */
public final class IndoorPrivacySetupController implements AutoCloseable {
    public static final int MAX_WATCHLIST_TERMS = 32;
    public static final int MAX_TERM_CODE_POINTS = 64;
    public static final int MAX_PRIVACY_ZONES = 8;
    private static final double MINIMUM_ZONE_AREA = 0.000_1;

    private final LinkedHashSet<String> normalizedTerms = new LinkedHashSet<>();
    private List<NormalizedRect> configuredZones = List.of();
    private List<NormalizedRect> activeZones = List.of();
    private boolean zonesSafelyTransformed = true;
    private boolean automaticBarcodeProtectionEnabled = true;
    private long zoneRevision;

    /** Adds one exact session term after Unicode NFKC, root-locale case folding, and whitespace. */
    public synchronized boolean addWatchlistTerm(String term) {
        String normalized = normalizeWatchlistTerm(term);
        if (normalizedTerms.contains(normalized)) {
            return false;
        }
        if (normalizedTerms.size() == MAX_WATCHLIST_TERMS) {
            throw new IllegalStateException("Session watchlist limit reached");
        }
        normalizedTerms.add(normalized);
        return true;
    }

    /** Removes an exact normalized term without exposing the retained set through logs. */
    public synchronized boolean removeWatchlistTerm(String term) {
        return normalizedTerms.remove(normalizeWatchlistTerm(term));
    }

    /** Stages a complete output-space zone and atomically makes policy use fail closed. */
    public synchronized void addPrivacyZone(NormalizedRect zone) {
        requireUsableZone(zone);
        List<NormalizedRect> merged = new ArrayList<>(configuredZones);
        NormalizedRect candidate = zone;
        boolean changed;
        do {
            changed = false;
            for (int index = merged.size() - 1; index >= 0; index--) {
                NormalizedRect existing = merged.get(index);
                if (overlaps(existing, candidate)) {
                    candidate = union(existing, candidate);
                    merged.remove(index);
                    changed = true;
                }
            }
        } while (changed);
        if (merged.size() == MAX_PRIVACY_ZONES) {
            throw new IllegalStateException("Session privacy-zone limit reached");
        }
        merged.add(candidate);
        stageConfiguredZones(merged);
    }

    /** Stages one creator-visible replacement while active policy remains fail closed. */
    public synchronized void replacePrivacyZone(int index, NormalizedRect replacement) {
        requireUsableZone(replacement);
        requireZoneIndex(index);
        List<NormalizedRect> updated = new ArrayList<>(configuredZones);
        updated.set(index, replacement);
        stageConfiguredZones(updated);
    }

    /** Stages one creator-visible removal while active policy remains fail closed. */
    public synchronized void removePrivacyZone(int index) {
        requireZoneIndex(index);
        List<NormalizedRect> updated = new ArrayList<>(configuredZones);
        updated.remove(index);
        stageConfiguredZones(updated);
    }

    /** Immutable geometry for the private editor; it contains no camera pixels or recognized data. */
    public synchronized List<NormalizedRect> configuredPrivacyZones() {
        return configuredZones;
    }

    /** Enables or disables automatic QR/barcode masking for this in-memory session. */
    public synchronized void setAutomaticBarcodeProtectionEnabled(boolean enabled) {
        automaticBarcodeProtectionEnabled = enabled;
    }

    public synchronized boolean automaticBarcodeProtectionEnabled() {
        return automaticBarcodeProtectionEnabled;
    }

    /** Marks camera/crop/rotation geometry unsafe; policy must shield until a safe update arrives. */
    public synchronized void markZoneTransformUnsafe() {
        if (!configuredZones.isEmpty()) {
            zonesSafelyTransformed = false;
            zoneRevision++;
        }
    }

    /** Replaces active zones only after the caller validates the complete camera transform. */
    public synchronized void applySafelyTransformedZones(List<NormalizedRect> transformedZones) {
        applySafelyTransformedZones(zoneTransformSnapshot(), transformedZones);
    }

    /** Captures the exact configured geometry generation that a transform will map. */
    public synchronized ZoneTransformSnapshot zoneTransformSnapshot() {
        return new ZoneTransformSnapshot(zoneRevision, configuredZones);
    }

    /** Commits mapped geometry only if no edit or transform invalidation overtook the work. */
    public synchronized boolean applySafelyTransformedZones(
            ZoneTransformSnapshot expected,
            List<NormalizedRect> transformedZones) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(transformedZones, "transformedZones");
        if (expected.revision() != zoneRevision
                || !expected.configuredZones().equals(configuredZones)) {
            return false;
        }
        if (transformedZones.size() != configuredZones.size()) {
            throw new IllegalArgumentException(
                    "Every configured zone requires one complete transformed zone");
        }
        List<NormalizedRect> safe = new ArrayList<>(transformedZones.size());
        for (NormalizedRect zone : transformedZones) {
            requireUsableZone(zone);
            safe.add(zone);
        }
        activeZones = List.copyOf(safe);
        zonesSafelyTransformed = true;
        return true;
    }

    /** Returns an immutable, provenance-safe policy view detached from future setup mutations. */
    public synchronized Configuration snapshot() {
        return new Configuration(
                normalizedTerms,
                activeZones,
                zonesSafelyTransformed,
                automaticBarcodeProtectionEnabled);
    }

    /** Clears every session-local term, zone, and unsafe-transform latch. */
    public synchronized void clearSession() {
        normalizedTerms.clear();
        configuredZones = List.of();
        activeZones = List.of();
        zonesSafelyTransformed = true;
        automaticBarcodeProtectionEnabled = true;
        zoneRevision++;
    }

    @Override
    public void close() {
        clearSession();
    }

    /** Normalizes configuration only; OCR extraction and text matching belong to later US3 tasks. */
    public static String normalizeWatchlistTerm(String term) {
        Objects.requireNonNull(term, "term");
        String unicode = Normalizer.normalize(term, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(unicode.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < unicode.length(); ) {
            int codePoint = unicode.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) {
                throw new IllegalArgumentException("Watchlist terms cannot contain controls");
            }
            if (Character.isWhitespace(codePoint)) {
                pendingSpace = normalized.length() > 0;
            } else {
                if (pendingSpace) {
                    normalized.append(' ');
                    pendingSpace = false;
                }
                normalized.appendCodePoint(codePoint);
            }
        }
        String result = normalized.toString();
        int codePoints = result.codePointCount(0, result.length());
        if (codePoints == 0 || codePoints > MAX_TERM_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "Watchlist term must contain 1 to " + MAX_TERM_CODE_POINTS + " code points");
        }
        return result;
    }

    private static void requireUsableZone(NormalizedRect zone) {
        Objects.requireNonNull(zone, "zone");
        double area = (zone.right() - zone.left()) * (zone.bottom() - zone.top());
        if (area < MINIMUM_ZONE_AREA) {
            throw new IllegalArgumentException("Privacy zone is too small to configure safely");
        }
    }

    /**
     * Publishes configured output geometry and its unsafe latch as one synchronized transition.
     * Existing sensor-space geometry is never replaced with output coordinates. The policy checks
     * the latch before reading active zones, so a non-empty edit remains full-shielded until the
     * caller supplies one complete transformed list.
     */
    private void stageConfiguredZones(List<NormalizedRect> updated) {
        zoneRevision++;
        configuredZones = List.copyOf(updated);
        if (configuredZones.isEmpty()) {
            activeZones = List.of();
            zonesSafelyTransformed = true;
        } else {
            zonesSafelyTransformed = false;
        }
    }

    /** Immutable, payload-free token for one configured-zone geometry generation. */
    public record ZoneTransformSnapshot(
            long revision, List<NormalizedRect> configuredZones) {
        public ZoneTransformSnapshot {
            configuredZones = List.copyOf(configuredZones);
        }
    }

    private void requireZoneIndex(int index) {
        if (index < 0 || index >= configuredZones.size()) {
            throw new IndexOutOfBoundsException("Privacy zone index is outside the session");
        }
    }

    private static boolean overlaps(NormalizedRect first, NormalizedRect second) {
        return first.left() < second.right() && first.right() > second.left()
                && first.top() < second.bottom() && first.bottom() > second.top();
    }

    private static NormalizedRect union(NormalizedRect first, NormalizedRect second) {
        return new NormalizedRect(
                Math.min(first.left(), second.left()),
                Math.min(first.top(), second.top()),
                Math.max(first.right(), second.right()),
                Math.max(first.bottom(), second.bottom()));
    }

    /** Immutable configuration with no payload-bearing {@code toString}. */
    public static final class Configuration implements SessionPrivacyConfigurationView {
        private final Set<String> normalizedTerms;
        private final List<NormalizedRect> activeZones;
        private final boolean zonesSafelyTransformed;
        private final boolean automaticBarcodeProtectionEnabled;

        private Configuration(
                Set<String> normalizedTerms,
                List<NormalizedRect> activeZones,
                boolean zonesSafelyTransformed,
                boolean automaticBarcodeProtectionEnabled) {
            this.normalizedTerms = Set.copyOf(normalizedTerms);
            this.activeZones = List.copyOf(activeZones);
            this.zonesSafelyTransformed = zonesSafelyTransformed;
            this.automaticBarcodeProtectionEnabled = automaticBarcodeProtectionEnabled;
        }

        @Override
        public Set<String> normalizedWatchlistTerms() {
            return normalizedTerms;
        }

        @Override
        public List<NormalizedRect> activePrivacyZones() {
            return activeZones;
        }

        @Override
        public boolean zonesSafelyTransformed() {
            return zonesSafelyTransformed;
        }

        @Override
        public boolean automaticBarcodeProtectionEnabled() {
            return automaticBarcodeProtectionEnabled;
        }

        @Override
        public String toString() {
            return "IndoorPrivacyConfiguration[session-private]";
        }
    }
}
