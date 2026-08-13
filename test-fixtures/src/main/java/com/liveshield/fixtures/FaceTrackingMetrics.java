package com.liveshield.fixtures;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;

/** Pure episode-level temporal face-protection and association metrics. */
public final class FaceTrackingMetrics {
    private FaceTrackingMetrics() {
    }

    public static EpisodeMetrics evaluate(Episode episode) {
        Objects.requireNonNull(episode, "episode");
        int positive = 0;
        int protectedFrames = 0;
        int longestGap = 0;
        int currentGap = 0;
        long longestGapNanos = 0;
        long currentGapNanos = 0;
        int fragments = 0;
        int switches = 0;
        boolean protectedRunSeen = false;
        boolean inProtectedRun = false;
        OptionalLong lastAssignedTrack = OptionalLong.empty();

        for (FrameObservation frame : episode.frames()) {
            if (!frame.faceVisible()) {
                currentGap = 0;
                currentGapNanos = 0;
                protectedRunSeen = false;
                inProtectedRun = false;
                lastAssignedTrack = OptionalLong.empty();
                continue;
            }
            positive++;
            if (frame.protectedFrame()) {
                protectedFrames++;
                currentGap = 0;
                currentGapNanos = 0;
                if (!inProtectedRun) {
                    if (protectedRunSeen) {
                        fragments++;
                    }
                    protectedRunSeen = true;
                    inProtectedRun = true;
                }
            } else {
                currentGap++;
                currentGapNanos = Math.addExact(currentGapNanos, frame.durationNanos());
                longestGap = Math.max(longestGap, currentGap);
                longestGapNanos = Math.max(longestGapNanos, currentGapNanos);
                inProtectedRun = false;
            }
            if (frame.assignedTrackId().isPresent()) {
                if (lastAssignedTrack.isPresent()
                        && lastAssignedTrack.getAsLong() != frame.assignedTrackId().getAsLong()) {
                    switches++;
                }
                lastAssignedTrack = frame.assignedTrackId();
            }
        }
        OptionalDouble coverage = positive == 0
                ? OptionalDouble.empty()
                : OptionalDouble.of((double) protectedFrames / positive);
        return new EpisodeMetrics(
                episode.episodeId(), positive, protectedFrames, coverage,
                longestGap, longestGapNanos, fragments, switches);
    }

    public static Summary aggregate(List<Episode> episodes) {
        List<Episode> safeEpisodes = List.copyOf(Objects.requireNonNull(episodes, "episodes"));
        Set<String> identifiers = new HashSet<>();
        int positiveEpisodes = 0;
        int positiveFrames = 0;
        int protectedFrames = 0;
        int maximumGap = 0;
        long maximumGapNanos = 0;
        int fragmentations = 0;
        int switches = 0;
        int episodesWithGap = 0;
        double coverageSum = 0.0;
        for (Episode episode : safeEpisodes) {
            if (!identifiers.add(episode.episodeId())) {
                throw new IllegalArgumentException("Episode identifiers must be unique");
            }
            EpisodeMetrics metrics = evaluate(episode);
            positiveFrames += metrics.positiveFrames();
            protectedFrames += metrics.protectedFrames();
            maximumGap = Math.max(maximumGap, metrics.longestUnprotectedGapFrames());
            maximumGapNanos = Math.max(
                    maximumGapNanos, metrics.longestUnprotectedGapNanos());
            fragmentations += metrics.fragmentationCount();
            switches += metrics.idSwitchCount();
            if (metrics.longestUnprotectedGapFrames() > 0) {
                episodesWithGap++;
            }
            if (metrics.temporalCoverage().isPresent()) {
                positiveEpisodes++;
                coverageSum += metrics.temporalCoverage().getAsDouble();
            }
        }
        OptionalDouble microCoverage = positiveFrames == 0
                ? OptionalDouble.empty()
                : OptionalDouble.of((double) protectedFrames / positiveFrames);
        OptionalDouble macroCoverage = positiveEpisodes == 0
                ? OptionalDouble.empty()
                : OptionalDouble.of(coverageSum / positiveEpisodes);
        return new Summary(
                safeEpisodes.size(), positiveEpisodes, positiveFrames, protectedFrames,
                microCoverage, macroCoverage, maximumGap, maximumGapNanos,
                fragmentations, switches, episodesWithGap);
    }

    public record FrameObservation(
            long frameIndex,
            long timestampNanos,
            long durationNanos,
            boolean faceVisible,
            boolean protectedFrame,
            OptionalLong assignedTrackId) {
        public FrameObservation {
            if (frameIndex < 0) {
                throw new IllegalArgumentException("frameIndex must be non-negative");
            }
            if (timestampNanos < 0 || durationNanos <= 0) {
                throw new IllegalArgumentException(
                        "timestampNanos must be non-negative and durationNanos must be positive");
            }
            Objects.requireNonNull(assignedTrackId, "assignedTrackId");
            if (!faceVisible && (protectedFrame || assignedTrackId.isPresent())) {
                throw new IllegalArgumentException(
                        "Absent faces cannot be protected or assigned a track");
            }
            if (assignedTrackId.isPresent() && assignedTrackId.getAsLong() < 0) {
                throw new IllegalArgumentException("Track identifier must be non-negative");
            }
        }
    }

    public record Episode(String episodeId, List<FrameObservation> frames) {
        public Episode {
            if (episodeId == null || episodeId.isBlank()) {
                throw new IllegalArgumentException("episodeId must not be blank");
            }
            frames = List.copyOf(Objects.requireNonNull(frames, "frames"));
            long previous = -1;
            long previousTimestamp = -1;
            for (FrameObservation frame : frames) {
                Objects.requireNonNull(frame, "frame");
                if (frame.frameIndex() <= previous) {
                    throw new IllegalArgumentException(
                            "Frame indices must be strictly increasing within an episode");
                }
                if (frame.timestampNanos() <= previousTimestamp) {
                    throw new IllegalArgumentException(
                            "Timestamps must be strictly increasing within an episode");
                }
                previous = frame.frameIndex();
                previousTimestamp = frame.timestampNanos();
            }
        }
    }

    public record EpisodeMetrics(
            String episodeId,
            int positiveFrames,
            int protectedFrames,
            OptionalDouble temporalCoverage,
            int longestUnprotectedGapFrames,
            long longestUnprotectedGapNanos,
            int fragmentationCount,
            int idSwitchCount) {
        public EpisodeMetrics {
            Objects.requireNonNull(episodeId, "episodeId");
            Objects.requireNonNull(temporalCoverage, "temporalCoverage");
        }
    }

    public record Summary(
            int episodeCount,
            int positiveEpisodeCount,
            int positiveFrames,
            int protectedFrames,
            OptionalDouble microTemporalCoverage,
            OptionalDouble macroEpisodeCoverage,
            int maximumEpisodeGapFrames,
            long maximumEpisodeGapNanos,
            int totalFragmentations,
            int totalIdSwitches,
            int episodesWithUnprotectedGap) {
        public Summary {
            Objects.requireNonNull(microTemporalCoverage, "microTemporalCoverage");
            Objects.requireNonNull(macroEpisodeCoverage, "macroEpisodeCoverage");
        }
    }
}
