package com.liveshield.privacy.policy;

import com.liveshield.privacy.model.ConfidenceClass;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.model.ProtectionAction;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Combines automatic Priority 2 findings with complete creator-configured privacy zones. */
public final class PriorityTwoPolicy {
    private static final int MAXIMUM_ACTIVE_ZONES = 8;
    private final SensitiveFindingPolicy findingPolicy;

    public PriorityTwoPolicy(SensitiveFindingPolicy findingPolicy) {
        this.findingPolicy = Objects.requireNonNull(findingPolicy, "findingPolicy");
    }

    public synchronized Result evaluate(
            FrameTimestamp frameTimestamp,
            List<DetectorSnapshot> detectorSnapshots,
            SessionPrivacyConfigurationView sessionConfiguration,
            boolean sceneChanged) {
        Objects.requireNonNull(frameTimestamp, "frameTimestamp");
        Objects.requireNonNull(detectorSnapshots, "detectorSnapshots");
        Objects.requireNonNull(sessionConfiguration, "sessionConfiguration");
        if (!sessionConfiguration.zonesSafelyTransformed()) {
            return Result.shieldRequired();
        }
        List<NormalizedRect> zones = sessionConfiguration.activePrivacyZones();
        if (zones.size() > MAXIMUM_ACTIVE_ZONES) {
            return Result.shieldRequired();
        }
        SensitiveFindingPolicy.Result findings = findingPolicy.evaluate(
                frameTimestamp, detectorSnapshots, sceneChanged);
        if (findings.basis() == SensitiveFindingPolicy.Basis.SHIELD_REQUIRED) {
            return Result.shieldRequired();
        }
        ArrayList<ProtectedRegion> combined = new ArrayList<>(findings.regions());
        for (NormalizedRect zone : zones) {
            combined.add(new ProtectedRegion(
                    FindingCategory.PRIVACY_ZONE,
                    List.of(Objects.requireNonNull(zone, "privacy zone")),
                    ConfidenceClass.VALIDATED,
                    ProtectionAction.OPAQUE));
        }
        return new Result(findings.basis(), mergeDeterministically(combined));
    }

    public synchronized void reset() {
        findingPolicy.reset();
    }

    private static List<ProtectedRegion> mergeDeterministically(
            List<ProtectedRegion> regions) {
        ArrayList<RegionPart> parts = new ArrayList<>();
        for (ProtectedRegion region : regions) {
            for (NormalizedRect bounds : region.bounds()) {
                parts.add(new RegionPart(
                        region.category(),
                        region.confidenceClass(),
                        region.action(),
                        bounds));
            }
        }
        parts.sort(Comparator
                .comparing((RegionPart part) -> part.category().ordinal())
                .thenComparing(part -> part.confidence().ordinal())
                .thenComparing(part -> part.action().ordinal())
                .thenComparingDouble(part -> part.bounds().left())
                .thenComparingDouble(part -> part.bounds().top())
                .thenComparingDouble(part -> part.bounds().right())
                .thenComparingDouble(part -> part.bounds().bottom()));
        ArrayList<RegionPart> merged = new ArrayList<>();
        for (RegionPart candidate : parts) {
            int mergeIndex = findOverlappingSameProvenance(merged, candidate);
            if (mergeIndex < 0) {
                merged.add(candidate);
            } else {
                RegionPart current = merged.remove(mergeIndex);
                RegionPart union = current.withBounds(union(
                        current.bounds(), candidate.bounds()));
                mergeTransitive(merged, union);
            }
        }
        merged.sort(Comparator
                .comparing((RegionPart part) -> part.category().ordinal())
                .thenComparingDouble(part -> part.bounds().left())
                .thenComparingDouble(part -> part.bounds().top()));
        return merged.stream().map(part -> new ProtectedRegion(
                part.category(),
                List.of(part.bounds()),
                part.confidence(),
                part.action())).toList();
    }

    private static void mergeTransitive(List<RegionPart> merged, RegionPart candidate) {
        RegionPart union = candidate;
        int index;
        while ((index = findOverlappingSameProvenance(merged, union)) >= 0) {
            RegionPart current = merged.remove(index);
            union = union.withBounds(union(union.bounds(), current.bounds()));
        }
        merged.add(union);
    }

    private static int findOverlappingSameProvenance(
            List<RegionPart> regions, RegionPart candidate) {
        for (int index = 0; index < regions.size(); index++) {
            RegionPart current = regions.get(index);
            if (current.sameProvenance(candidate)
                    && overlaps(current.bounds(), candidate.bounds())) {
                return index;
            }
        }
        return -1;
    }

    private static boolean overlaps(NormalizedRect first, NormalizedRect second) {
        return first.left() <= second.right() && first.right() >= second.left()
                && first.top() <= second.bottom() && first.bottom() >= second.top();
    }

    private static NormalizedRect union(NormalizedRect first, NormalizedRect second) {
        return new NormalizedRect(
                Math.min(first.left(), second.left()),
                Math.min(first.top(), second.top()),
                Math.max(first.right(), second.right()),
                Math.max(first.bottom(), second.bottom()));
    }

    public record Result(
            SensitiveFindingPolicy.Basis basis, List<ProtectedRegion> regions) {
        public Result {
            Objects.requireNonNull(basis, "basis");
            regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
            if (basis == SensitiveFindingPolicy.Basis.SHIELD_REQUIRED
                    && !regions.isEmpty()) {
                throw new IllegalArgumentException("Shield-required result has no regions");
            }
        }

        public static Result shieldRequired() {
            return new Result(SensitiveFindingPolicy.Basis.SHIELD_REQUIRED, List.of());
        }
    }

    private record RegionPart(
            FindingCategory category,
            ConfidenceClass confidence,
            ProtectionAction action,
            NormalizedRect bounds) {
        private boolean sameProvenance(RegionPart other) {
            return category == other.category
                    && confidence == other.confidence
                    && action == other.action;
        }

        private RegionPart withBounds(NormalizedRect newBounds) {
            return new RegionPart(category, confidence, action, newBounds);
        }
    }
}
