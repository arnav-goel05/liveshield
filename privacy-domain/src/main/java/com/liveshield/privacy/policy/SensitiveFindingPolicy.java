package com.liveshield.privacy.policy;

import com.liveshield.privacy.model.ConfidenceClass;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.model.ProtectionAction;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Timestamped, independent carry-forward policy for non-face visual findings. */
public final class SensitiveFindingPolicy {
    private final Configuration configuration;
    private final Map<DetectorLane, LaneState> lanes = new EnumMap<>(DetectorLane.class);
    private final Map<DetectorLane, FrameTimestamp> consumedSnapshots =
            new EnumMap<>(DetectorLane.class);

    public SensitiveFindingPolicy(Configuration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    /**
     * Evaluates the latest snapshots at or before the frame. Any unsafe lane shields the result.
     */
    public synchronized Result evaluate(
            FrameTimestamp frameTimestamp,
            List<DetectorSnapshot> detectorSnapshots,
            boolean sceneChanged) {
        return evaluate(frameTimestamp, detectorSnapshots, sceneChanged, true);
    }

    /** Evaluates enabled lanes and immediately forgets barcode carry when protection is off. */
    public synchronized Result evaluate(
            FrameTimestamp frameTimestamp,
            List<DetectorSnapshot> detectorSnapshots,
            boolean sceneChanged,
            boolean automaticBarcodeProtectionEnabled) {
        return evaluate(
                frameTimestamp,
                detectorSnapshots,
                sceneChanged,
                automaticBarcodeProtectionEnabled,
                true);
    }

    /** Evaluates creator-enabled lanes and forgets disabled-lane carry immediately. */
    public synchronized Result evaluate(
            FrameTimestamp frameTimestamp,
            List<DetectorSnapshot> detectorSnapshots,
            boolean sceneChanged,
            boolean automaticBarcodeProtectionEnabled,
            boolean privateWordProtectionEnabled) {
        Objects.requireNonNull(frameTimestamp, "frameTimestamp");
        Objects.requireNonNull(detectorSnapshots, "detectorSnapshots");
        if (detectorSnapshots.size() > configuration.maximumSnapshotsPerEvaluation()) {
            return shieldAndClear();
        }
        if (sceneChanged) {
            lanes.clear();
            consumedSnapshots.clear();
        }
        Map<DetectorLane, DetectorSnapshot> latest = latestAtOrBefore(
                frameTimestamp, detectorSnapshots);
        ArrayList<ProtectedRegion> combined = new ArrayList<>();
        Basis overall = Basis.FRESH;
        for (DetectorLane lane : configuration.requiredLanes().stream().sorted().toList()) {
            if (lane == DetectorLane.BARCODE && !automaticBarcodeProtectionEnabled) {
                lanes.remove(lane);
                continue;
            }
            if (lane == DetectorLane.TEXT && !privateWordProtectionEnabled) {
                lanes.remove(lane);
                continue;
            }
            DetectorSnapshot snapshot = latest.get(lane);
            LaneState previous = lanes.get(lane);
            if (snapshot != null && snapshot.failure().isPresent()) {
                // A bounded detector timeout must not erase protection already established by a
                // prior successful snapshot. Continue through the normal carry/expansion clocks;
                // a lane with no prior safe assessment still shields immediately.
                if (previous == null) {
                    return Result.shieldRequired();
                }
                snapshot = null;
            }
            boolean newSnapshot = snapshot != null
                    && (consumedSnapshots.get(lane) == null
                            || snapshot.sourceTimestamp().compareTo(
                                    consumedSnapshots.get(lane)) > 0);
            if (sceneChanged && snapshot != null
                    && !snapshot.sourceTimestamp().equals(frameTimestamp)) {
                snapshot = null;
                newSnapshot = false;
            }
            if (newSnapshot
                    && snapshot.findings().isEmpty()
                    && previous != null
                    && !previous.regions().isEmpty()) {
                // Camera focus, motion, and partial occlusion commonly produce an isolated
                // successful "nothing found" frame between positive barcode frames. Preserve
                // the last protected geometry through the bounded carry/expansion windows so
                // its mask does not blink; sustained absence still expires normally.
                snapshot = null;
                newSnapshot = false;
            }
            if (newSnapshot) {
                // Remember every inspected snapshot even if it fails validation. Once a bounded
                // assessment expires, repeatedly presenting that same old snapshot must never
                // resurrect its mask on alternating frames.
                consumedSnapshots.put(lane, snapshot.sourceTimestamp());
                if (!snapshot.isFreshAt(frameTimestamp)
                        || !validForLane(lane, snapshot.findings())
                        || !withinBounds(snapshot.findings())) {
                    lanes.remove(lane);
                    return Result.shieldRequired();
                }
                // The source timestamp can substantially precede delivery for an offline
                // analyzer such as OCR. Freshness above still limits how old an assessment may
                // be when accepted, but carry must start at acceptance; counting again from the
                // capture timestamp can consume the entire carry window during inference and
                // flash the full shield before the next successful result arrives.
                previous = new LaneState(
                        frameTimestamp, List.copyOf(snapshot.findings()));
                lanes.put(lane, previous);
            }
            if (previous == null) {
                return Result.shieldRequired();
            }
            long age;
            try {
                age = Math.subtractExact(
                        frameTimestamp.nanos(), previous.acceptedTimestamp().nanos());
            } catch (ArithmeticException overflow) {
                return shieldAndClear();
            }
            if (age < 0) {
                return shieldAndClear();
            }
            List<ProtectedRegion> selected;
            Basis laneBasis;
            if (newSnapshot) {
                selected = previous.regions();
                laneBasis = Basis.FRESH;
            } else if (age <= configuration.carryWindowNanos()) {
                selected = previous.regions();
                laneBasis = Basis.CARRIED;
            } else if (age <= configuration.expansionWindowNanos()) {
                selected = expand(previous.regions());
                laneBasis = Basis.EXPANDED;
            } else {
                lanes.remove(lane);
                return Result.shieldRequired();
            }
            if (combined.size() + selected.size() > configuration.maximumTotalRegions()) {
                return shieldAndClear();
            }
            combined.addAll(selected);
            overall = Basis.stronger(overall, laneBasis);
        }
        return new Result(overall, combined);
    }

    /** Clears state at lifecycle stop or before a new session. */
    public synchronized void reset() {
        lanes.clear();
        consumedSnapshots.clear();
    }

    private Result shieldAndClear() {
        lanes.clear();
        return Result.shieldRequired();
    }

    private Map<DetectorLane, DetectorSnapshot> latestAtOrBefore(
            FrameTimestamp frameTimestamp, List<DetectorSnapshot> snapshots) {
        Map<DetectorLane, DetectorSnapshot> latest = new EnumMap<>(DetectorLane.class);
        for (DetectorSnapshot snapshot : snapshots) {
            Objects.requireNonNull(snapshot, "detector snapshot");
            if (!configuration.requiredLanes().contains(snapshot.lane())
                    || snapshot.sourceTimestamp().compareTo(frameTimestamp) > 0) {
                continue;
            }
            DetectorSnapshot prior = latest.get(snapshot.lane());
            if (prior == null || snapshot.sourceTimestamp().compareTo(
                    prior.sourceTimestamp()) > 0) {
                latest.put(snapshot.lane(), snapshot);
            }
        }
        return latest;
    }

    private boolean withinBounds(List<ProtectedRegion> regions) {
        if (regions.size() > configuration.maximumRegionsPerLane()) {
            return false;
        }
        int bounds = 0;
        for (ProtectedRegion region : regions) {
            bounds += region.bounds().size();
            if (bounds > configuration.maximumBoundsPerLane()) {
                return false;
            }
        }
        return true;
    }

    private static boolean validForLane(
            DetectorLane lane, List<ProtectedRegion> regions) {
        for (ProtectedRegion region : regions) {
            Objects.requireNonNull(region, "protected region");
            if (lane == DetectorLane.TEXT && !isTextCategory(region.category())) {
                return false;
            }
            if (lane == DetectorLane.BARCODE
                    && region.category() != FindingCategory.AUTO_BARCODE) {
                return false;
            }
        }
        return lane == DetectorLane.TEXT || lane == DetectorLane.BARCODE;
    }

    private static boolean isTextCategory(FindingCategory category) {
        return category == FindingCategory.WATCHLIST_MATCH;
    }

    private List<ProtectedRegion> expand(List<ProtectedRegion> regions) {
        return regions.stream().map(region -> new ProtectedRegion(
                region.category(),
                region.bounds().stream().map(this::expand).toList(),
                ConfidenceClass.UNCERTAIN,
                strengthen(region.action()))).toList();
    }

    private NormalizedRect expand(NormalizedRect bounds) {
        double horizontal = bounds.right() - bounds.left();
        double vertical = bounds.bottom() - bounds.top();
        double fraction = configuration.expansionFraction();
        return new NormalizedRect(
                Math.max(0.0, bounds.left() - horizontal * fraction),
                Math.max(0.0, bounds.top() - vertical * fraction),
                Math.min(1.0, bounds.right() + horizontal * fraction),
                Math.min(1.0, bounds.bottom() + vertical * fraction));
    }

    private static ProtectionAction strengthen(ProtectionAction action) {
        return action == ProtectionAction.BLUR ? ProtectionAction.MOSAIC : action;
    }

    public record Configuration(
            Set<DetectorLane> requiredLanes,
            long carryWindowNanos,
            long expansionWindowNanos,
            double expansionFraction,
            int maximumRegionsPerLane,
            int maximumBoundsPerLane,
            int maximumTotalRegions,
            int maximumSnapshotsPerEvaluation) {
        public Configuration {
            Objects.requireNonNull(requiredLanes, "requiredLanes");
            if (requiredLanes.isEmpty()
                    || requiredLanes.stream().anyMatch(lane ->
                            lane != DetectorLane.TEXT && lane != DetectorLane.BARCODE)) {
                throw new IllegalArgumentException(
                        "Sensitive finding lanes must contain only TEXT or BARCODE");
            }
            requiredLanes = Set.copyOf(EnumSet.copyOf(requiredLanes));
            if (carryWindowNanos < 0 || expansionWindowNanos < carryWindowNanos
                    || !Double.isFinite(expansionFraction)
                    || expansionFraction <= 0.0 || expansionFraction > 1.0
                    || maximumRegionsPerLane <= 0 || maximumBoundsPerLane <= 0
                    || maximumTotalRegions <= 0 || maximumSnapshotsPerEvaluation <= 0) {
                throw new IllegalArgumentException("Invalid sensitive finding policy bounds");
            }
        }
    }

    public record Result(Basis basis, List<ProtectedRegion> regions) {
        public Result {
            Objects.requireNonNull(basis, "basis");
            regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
            if (basis == Basis.SHIELD_REQUIRED && !regions.isEmpty()) {
                throw new IllegalArgumentException("Shield-required result cannot carry regions");
            }
        }

        public static Result shieldRequired() {
            return new Result(Basis.SHIELD_REQUIRED, List.of());
        }
    }

    public enum Basis {
        FRESH,
        CARRIED,
        EXPANDED,
        SHIELD_REQUIRED;

        private static Basis stronger(Basis first, Basis second) {
            return first.ordinal() >= second.ordinal() ? first : second;
        }
    }

    private record LaneState(
            FrameTimestamp acceptedTimestamp, List<ProtectedRegion> regions) {
    }
}
