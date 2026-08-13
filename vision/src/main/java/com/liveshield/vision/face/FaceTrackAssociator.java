package com.liveshield.vision.face;

import com.liveshield.privacy.model.DetectorObservation;
import com.liveshield.privacy.model.FaceTrackSnapshot;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Pure-Java, session-local geometric face association with conservative ambiguity handling.
 *
 * <p>The associator consumes only rectangles, monotonic timestamps, and optional detector tracking
 * hints. Hints can break a geometric tie, but cannot override an impossible jump. It never accepts
 * pixels, embeddings, names, or any other biometric identity.</p>
 */
public final class FaceTrackAssociator {
    private final Configuration configuration;
    private final Map<Long, Track> activeTracks = new LinkedHashMap<>();
    private final Map<Long, Long> hintToTrack = new HashMap<>();
    private FrameTimestamp latestTimestamp;
    private long nextTrackId;

    public FaceTrackAssociator(Configuration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    /** Associates one strictly increasing detector frame within the active session. */
    public synchronized AssociationFrame update(
            FrameTimestamp timestamp, List<DetectorObservation> observations) {
        Objects.requireNonNull(timestamp, "timestamp");
        List<DetectorObservation> safeObservations = List.copyOf(
                Objects.requireNonNull(observations, "observations"));
        if (latestTimestamp != null && timestamp.compareTo(latestTimestamp) <= 0) {
            throw new IllegalArgumentException("Association timestamps must strictly increase");
        }
        List<Observation> faces = validateObservations(safeObservations);
        Set<Long> expired = expireAt(timestamp);
        Set<Long> continuityLost = new HashSet<>();

        List<Candidate> candidates = candidates(timestamp, faces);
        markImpossibleHintJumps(faces, candidates, continuityLost);
        Set<Long> ambiguousTracks = ambiguousTracks(faces, candidates);
        continuityLost.addAll(ambiguousTracks);

        Set<Integer> assignedObservations = new HashSet<>();
        Set<Long> assignedTracks = new HashSet<>();
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
        for (Candidate candidate : candidates) {
            if (assignedObservations.contains(candidate.observationIndex)
                    || assignedTracks.contains(candidate.trackId)) {
                continue;
            }
            Track track = activeTracks.get(candidate.trackId);
            Observation observation = faces.get(candidate.observationIndex);
            boolean ambiguous = track.continuityLost
                    || ambiguousTracks.contains(track.id)
                    || hasAmbiguousObservation(candidate.observationIndex, faces);
            updateTrack(track, observation, timestamp, ambiguous);
            if (ambiguous) {
                continuityLost.add(track.id);
            }
            assignedObservations.add(candidate.observationIndex);
            assignedTracks.add(candidate.trackId);
        }

        for (int index = 0; index < faces.size(); index++) {
            if (!assignedObservations.contains(index)) {
                Observation observation = faces.get(index);
                Track track = newTrack(observation, timestamp);
                if (hasAmbiguousObservation(index, faces)) {
                    track.continuityLost = true;
                    track.confidence = FaceTrackSnapshot.ConfidenceState.AMBIGUOUS;
                    continuityLost.add(track.id);
                }
                activeTracks.put(track.id, track);
                assignedTracks.add(track.id);
            }
        }

        List<FaceTrackSnapshot> snapshots = new ArrayList<>();
        for (Track track : activeTracks.values()) {
            if (!assignedTracks.contains(track.id)) {
                track.bounds = predict(track, timestamp);
                if (track.continuityLost || ambiguousTracks.contains(track.id)) {
                    track.continuityLost = true;
                    continuityLost.add(track.id);
                    track.confidence = FaceTrackSnapshot.ConfidenceState.AMBIGUOUS;
                } else {
                    track.confidence = FaceTrackSnapshot.ConfidenceState.PREDICTED;
                }
            }
            snapshots.add(track.snapshot());
        }
        snapshots.sort(Comparator.comparingLong(FaceTrackSnapshot::trackId));
        latestTimestamp = timestamp;
        return new AssociationFrame(timestamp, snapshots, continuityLost, expired);
    }

    /** Clears every ephemeral identifier, motion estimate, and detector-hint association. */
    public synchronized void resetSession() {
        activeTracks.clear();
        hintToTrack.clear();
        latestTimestamp = null;
        nextTrackId = 0;
    }

    private List<Observation> validateObservations(List<DetectorObservation> observations) {
        List<Observation> faces = new ArrayList<>(observations.size());
        for (DetectorObservation observation : observations) {
            if (observation.region().category() != FindingCategory.FACE
                    || observation.region().bounds().size() != 1) {
                throw new IllegalArgumentException(
                        "Face association requires one rectangle per FACE observation");
            }
            faces.add(new Observation(
                    observation.region().bounds().get(0), observation.detectorTrackingId()));
        }
        return faces;
    }

    private Set<Long> expireAt(FrameTimestamp timestamp) {
        Set<Long> expired = new HashSet<>();
        List<Long> ids = new ArrayList<>(activeTracks.keySet());
        for (long id : ids) {
            Track track = activeTracks.get(id);
            long age = timestamp.nanos() - track.lastDetected.nanos();
            if (age >= configuration.predictionExpiryNanos) {
                activeTracks.remove(id);
                expired.add(id);
                if (track.detectorHint != null) {
                    hintToTrack.remove(track.detectorHint, id);
                }
            }
        }
        return expired;
    }

    private List<Candidate> candidates(FrameTimestamp timestamp, List<Observation> observations) {
        List<Candidate> result = new ArrayList<>();
        for (int observationIndex = 0; observationIndex < observations.size(); observationIndex++) {
            Observation observation = observations.get(observationIndex);
            for (Track track : activeTracks.values()) {
                NormalizedRect predicted = predict(track, timestamp);
                double overlap = intersectionOverUnion(predicted, observation.bounds);
                double distance = centerDistance(predicted, observation.bounds);
                double scale = scaleRatio(predicted, observation.bounds);
                if (overlap >= configuration.minimumIntersectionOverUnion
                        || (scale <= configuration.maximumScaleRatio
                        && distance <= configuration.maximumCenterDistance)) {
                    double hintBonus = observation.hint.isPresent()
                            && hintToTrack.get(observation.hint.getAsLong()) != null
                            && hintToTrack.get(observation.hint.getAsLong()) == track.id
                            ? 2.0 : 0.0;
                    double score = hintBonus + overlap + 1.0 - Math.min(1.0,
                            distance / configuration.maximumCenterDistance);
                    result.add(new Candidate(track.id, observationIndex, score));
                }
            }
        }
        return result;
    }

    private void markImpossibleHintJumps(
            List<Observation> observations,
            List<Candidate> candidates,
            Set<Long> continuityLost) {
        for (int index = 0; index < observations.size(); index++) {
            int observationIndex = index;
            OptionalLong hint = observations.get(index).hint;
            if (hint.isEmpty()) {
                continue;
            }
            Long priorTrackId = hintToTrack.get(hint.getAsLong());
            if (priorTrackId != null && candidates.stream().noneMatch(
                    candidate -> candidate.observationIndex == observationIndex
                            && candidate.trackId == priorTrackId)) {
                Track prior = activeTracks.get(priorTrackId);
                if (prior != null) {
                    prior.continuityLost = true;
                    continuityLost.add(priorTrackId);
                    hintToTrack.remove(hint.getAsLong());
                }
            }
        }
    }

    private Set<Long> ambiguousTracks(List<Observation> observations, List<Candidate> candidates) {
        Set<Long> ambiguous = new HashSet<>();
        for (int first = 0; first < observations.size(); first++) {
            for (int second = first + 1; second < observations.size(); second++) {
                if (intersectionOverUnion(
                        observations.get(first).bounds,
                        observations.get(second).bounds)
                        >= configuration.ambiguityIntersectionOverUnion) {
                    addCandidateTracks(candidates, first, ambiguous);
                    addCandidateTracks(candidates, second, ambiguous);
                }
            }
        }
        for (int observation = 0; observation < observations.size(); observation++) {
            Set<Long> matching = new HashSet<>();
            addCandidateTracks(candidates, observation, matching);
            if (matching.size() > 1) {
                ambiguous.addAll(matching);
            }
        }
        for (Track track : activeTracks.values()) {
            if (track.continuityLost) {
                ambiguous.add(track.id);
            }
        }
        return ambiguous;
    }

    private static void addCandidateTracks(
            List<Candidate> candidates, int observationIndex, Set<Long> destination) {
        for (Candidate candidate : candidates) {
            if (candidate.observationIndex == observationIndex) {
                destination.add(candidate.trackId);
            }
        }
    }

    private boolean hasAmbiguousObservation(int index, List<Observation> observations) {
        for (int other = 0; other < observations.size(); other++) {
            if (index != other && intersectionOverUnion(
                    observations.get(index).bounds, observations.get(other).bounds)
                    >= configuration.ambiguityIntersectionOverUnion) {
                return true;
            }
        }
        return false;
    }

    private Track newTrack(Observation observation, FrameTimestamp timestamp) {
        if (nextTrackId == Long.MAX_VALUE) {
            throw new IllegalStateException("Session track identifier space exhausted");
        }
        Track track = new Track(nextTrackId++, observation.bounds, timestamp);
        if (observation.hint.isPresent()) {
            track.detectorHint = observation.hint.getAsLong();
            hintToTrack.put(track.detectorHint, track.id);
        }
        return track;
    }

    private void updateTrack(
            Track track,
            Observation observation,
            FrameTimestamp timestamp,
            boolean ambiguous) {
        long elapsed = timestamp.nanos() - track.lastDetected.nanos();
        if (elapsed > 0) {
            track.velocityCenterX = (centerX(observation.bounds) - centerX(track.detectedBounds))
                    / elapsed;
            track.velocityCenterY = (centerY(observation.bounds) - centerY(track.detectedBounds))
                    / elapsed;
            track.velocityWidth = (width(observation.bounds) - width(track.detectedBounds))
                    / elapsed;
            track.velocityHeight = (height(observation.bounds) - height(track.detectedBounds))
                    / elapsed;
        }
        track.detectedBounds = observation.bounds;
        track.bounds = observation.bounds;
        track.lastDetected = timestamp;
        track.continuityLost |= ambiguous;
        track.confidence = track.continuityLost
                ? FaceTrackSnapshot.ConfidenceState.AMBIGUOUS
                : FaceTrackSnapshot.ConfidenceState.FRESH;
        if (observation.hint.isPresent()) {
            if (track.detectorHint != null && track.detectorHint != observation.hint.getAsLong()) {
                hintToTrack.remove(track.detectorHint, track.id);
            }
            track.detectorHint = observation.hint.getAsLong();
            hintToTrack.put(track.detectorHint, track.id);
        }
    }

    private NormalizedRect predict(Track track, FrameTimestamp timestamp) {
        long elapsed = timestamp.nanos() - track.lastDetected.nanos();
        if (elapsed <= 0) {
            return track.detectedBounds;
        }
        double predictedWidth = Math.max(width(track.detectedBounds),
                width(track.detectedBounds) + track.velocityWidth * elapsed);
        double predictedHeight = Math.max(height(track.detectedBounds),
                height(track.detectedBounds) + track.velocityHeight * elapsed);
        double centerX = centerX(track.detectedBounds) + track.velocityCenterX * elapsed;
        double centerY = centerY(track.detectedBounds) + track.velocityCenterY * elapsed;
        return clampedRect(centerX, centerY, predictedWidth, predictedHeight);
    }

    private static NormalizedRect clampedRect(
            double centerX, double centerY, double width, double height) {
        double safeWidth = Math.min(1.0, Math.max(0.000_001, width));
        double safeHeight = Math.min(1.0, Math.max(0.000_001, height));
        double left = Math.max(0.0, Math.min(1.0 - safeWidth, centerX - safeWidth / 2.0));
        double top = Math.max(0.0, Math.min(1.0 - safeHeight, centerY - safeHeight / 2.0));
        return new NormalizedRect(left, top, left + safeWidth, top + safeHeight);
    }

    private static double intersectionOverUnion(NormalizedRect first, NormalizedRect second) {
        double width = Math.max(0.0,
                Math.min(first.right(), second.right()) - Math.max(first.left(), second.left()));
        double height = Math.max(0.0,
                Math.min(first.bottom(), second.bottom()) - Math.max(first.top(), second.top()));
        double intersection = width * height;
        return intersection / (area(first) + area(second) - intersection);
    }

    private static double centerDistance(NormalizedRect first, NormalizedRect second) {
        return Math.hypot(centerX(first) - centerX(second), centerY(first) - centerY(second));
    }

    private static double scaleRatio(NormalizedRect first, NormalizedRect second) {
        return Math.max(
                Math.max(width(first) / width(second), width(second) / width(first)),
                Math.max(height(first) / height(second), height(second) / height(first)));
    }

    private static double area(NormalizedRect bounds) {
        return width(bounds) * height(bounds);
    }

    private static double width(NormalizedRect bounds) {
        return bounds.right() - bounds.left();
    }

    private static double height(NormalizedRect bounds) {
        return bounds.bottom() - bounds.top();
    }

    private static double centerX(NormalizedRect bounds) {
        return (bounds.left() + bounds.right()) / 2.0;
    }

    private static double centerY(NormalizedRect bounds) {
        return (bounds.top() + bounds.bottom()) / 2.0;
    }

    /** Explicit geometry/time configuration for association and bounded prediction. */
    public record Configuration(
            long predictionExpiryNanos,
            double minimumIntersectionOverUnion,
            double maximumCenterDistance,
            double maximumScaleRatio,
            double ambiguityIntersectionOverUnion) {
        public Configuration {
            if (predictionExpiryNanos <= 0) {
                throw new IllegalArgumentException("predictionExpiryNanos must be positive");
            }
            requireUnitInterval(minimumIntersectionOverUnion,
                    "minimumIntersectionOverUnion");
            requirePositive(maximumCenterDistance, "maximumCenterDistance");
            if (!Double.isFinite(maximumScaleRatio) || maximumScaleRatio < 1.0) {
                throw new IllegalArgumentException("maximumScaleRatio must be finite and >= 1");
            }
            requireUnitInterval(ambiguityIntersectionOverUnion,
                    "ambiguityIntersectionOverUnion");
        }

        private static void requireUnitInterval(double value, String name) {
            if (!Double.isFinite(value) || value <= 0.0 || value > 1.0) {
                throw new IllegalArgumentException(name + " must be finite and in (0, 1]");
            }
        }

        private static void requirePositive(double value, String name) {
            if (!Double.isFinite(value) || value <= 0.0) {
                throw new IllegalArgumentException(name + " must be finite and positive");
            }
        }
    }

    /** Immutable output for one detector frame. */
    public record AssociationFrame(
            FrameTimestamp timestamp,
            List<FaceTrackSnapshot> tracks,
            Set<Long> continuityLostTrackIds,
            Set<Long> expiredTrackIds) {
        public AssociationFrame {
            Objects.requireNonNull(timestamp, "timestamp");
            tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks"));
            continuityLostTrackIds = Set.copyOf(
                    Objects.requireNonNull(continuityLostTrackIds, "continuityLostTrackIds"));
            expiredTrackIds = Set.copyOf(
                    Objects.requireNonNull(expiredTrackIds, "expiredTrackIds"));
        }
    }

    private record Observation(NormalizedRect bounds, OptionalLong hint) {
    }

    private record Candidate(long trackId, int observationIndex, double score) {
    }

    private static final class Track {
        private final long id;
        private NormalizedRect detectedBounds;
        private NormalizedRect bounds;
        private FrameTimestamp lastDetected;
        private FaceTrackSnapshot.ConfidenceState confidence =
                FaceTrackSnapshot.ConfidenceState.FRESH;
        private Long detectorHint;
        private double velocityCenterX;
        private double velocityCenterY;
        private double velocityWidth;
        private double velocityHeight;
        private boolean continuityLost;

        private Track(long id, NormalizedRect bounds, FrameTimestamp timestamp) {
            this.id = id;
            this.detectedBounds = bounds;
            this.bounds = bounds;
            this.lastDetected = timestamp;
        }

        private FaceTrackSnapshot snapshot() {
            return new FaceTrackSnapshot(
                    id,
                    bounds,
                    lastDetected,
                    confidence,
                    FaceTrackSnapshot.Policy.PROTECTED);
        }
    }
}
