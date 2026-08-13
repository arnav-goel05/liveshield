package com.liveshield.fixtures;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import com.liveshield.fixtures.FaceTrackingMetrics.Episode;
import com.liveshield.fixtures.FaceTrackingMetrics.FrameObservation;
import com.liveshield.fixtures.FaceTrackingMetrics.Summary;
import java.util.List;
import java.util.OptionalLong;
import org.junit.Test;

public final class FaceTrackingMetricsTest {
    private static final double EPSILON = 1.0e-9;

    @Test
    public void handComputedEpisodeMeasuresCoverageGapFragmentationAndSwitches() {
        Episode episode = new Episode("crossing", List.of(
                visible(0, true, 10),
                visible(1, true, 10),
                visibleWithoutTrack(2, false),
                visibleWithoutTrack(3, false),
                visible(4, true, 11),
                visible(5, true, 11)));

        FaceTrackingMetrics.EpisodeMetrics result = FaceTrackingMetrics.evaluate(episode);

        assertEquals(6, result.positiveFrames());
        assertEquals(4, result.protectedFrames());
        assertEquals(2.0 / 3.0, result.temporalCoverage().orElseThrow(), EPSILON);
        assertEquals(2, result.longestUnprotectedGapFrames());
        assertEquals(80, result.longestUnprotectedGapNanos());
        assertEquals(1, result.fragmentationCount());
        assertEquals(1, result.idSwitchCount());
    }

    @Test
    public void absenceBreaksVisibilitySegmentWithoutCreatingFragmentOrSwitch() {
        Episode episode = new Episode("exit-return", List.of(
                visible(0, true, 7),
                absent(1),
                visible(2, true, 9)));

        FaceTrackingMetrics.EpisodeMetrics result = FaceTrackingMetrics.evaluate(episode);

        assertEquals(2, result.positiveFrames());
        assertEquals(2, result.protectedFrames());
        assertEquals(1.0, result.temporalCoverage().orElseThrow(), EPSILON);
        assertEquals(0, result.fragmentationCount());
        assertEquals(0, result.longestUnprotectedGapNanos());
        assertEquals(0, result.idSwitchCount());
    }

    @Test
    public void zeroPositiveEpisodeHasNoUndefinedNumericCoverage() {
        FaceTrackingMetrics.EpisodeMetrics result = FaceTrackingMetrics.evaluate(
                new Episode("empty-room", List.of(absent(0), absent(1))));

        assertEquals(0, result.positiveFrames());
        assertEquals(0, result.protectedFrames());
        assertFalse(result.temporalCoverage().isPresent());
        assertEquals(0, result.longestUnprotectedGapFrames());
        assertEquals(0, result.longestUnprotectedGapNanos());
        assertEquals(0, result.fragmentationCount());
        assertEquals(0, result.idSwitchCount());
    }

    @Test
    public void aggregationReportsMicroAndEpisodeMacroWithoutFrameIndependenceClaim() {
        Episode first = new Episode("first", List.of(
                visible(0, true, 10),
                visible(1, true, 10),
                visibleWithoutTrack(2, false),
                visibleWithoutTrack(3, false),
                visible(4, true, 11),
                visible(5, true, 11)));
        Episode second = new Episode("second", List.of(
                visible(0, true, 22), visible(1, true, 22)));
        Episode zeroPositive = new Episode("zero", List.of(absent(0)));

        Summary result = FaceTrackingMetrics.aggregate(List.of(first, second, zeroPositive));

        assertEquals(3, result.episodeCount());
        assertEquals(2, result.positiveEpisodeCount());
        assertEquals(8, result.positiveFrames());
        assertEquals(6, result.protectedFrames());
        assertEquals(0.75, result.microTemporalCoverage().orElseThrow(), EPSILON);
        assertEquals(5.0 / 6.0, result.macroEpisodeCoverage().orElseThrow(), EPSILON);
        assertEquals(2, result.maximumEpisodeGapFrames());
        assertEquals(80, result.maximumEpisodeGapNanos());
        assertEquals(1, result.totalFragmentations());
        assertEquals(1, result.totalIdSwitches());
        assertEquals(1, result.episodesWithUnprotectedGap());
    }

    @Test
    public void aggregateNeverCountsTrackChangesAcrossIndependentEpisodes() {
        Summary result = FaceTrackingMetrics.aggregate(List.of(
                new Episode("one", List.of(visible(0, true, 1))),
                new Episode("two", List.of(visible(0, true, 2)))));

        assertEquals(0, result.totalIdSwitches());
        assertEquals(0, result.totalFragmentations());
    }

    @Test
    public void allZeroPositiveAggregateLeavesBothCoverageValuesEmpty() {
        Summary result = FaceTrackingMetrics.aggregate(List.of(
                new Episode("one", List.of(absent(0))),
                new Episode("two", List.of(absent(0)))));

        assertFalse(result.microTemporalCoverage().isPresent());
        assertFalse(result.macroEpisodeCoverage().isPresent());
        assertEquals(0, result.positiveEpisodeCount());
    }

    @Test
    public void invalidFramesOrderingAndDuplicateEpisodeIdsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Episode("unordered", List.of(absent(1), absent(1))));
        assertThrows(IllegalArgumentException.class,
                () -> FaceTrackingMetrics.aggregate(List.of(
                        new Episode("same", List.of()), new Episode("same", List.of()))));
        assertThrows(IllegalArgumentException.class,
                () -> new FrameObservation(
                        0, 0, 40, false, true, OptionalLong.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new FrameObservation(
                        0, 0, 40, false, false, OptionalLong.of(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new FrameObservation(
                        0, 0, 0, true, false, OptionalLong.empty()));
    }

    private static FrameObservation visible(int index, boolean protectedFrame, long trackId) {
        return new FrameObservation(
                index, index * 40L, 40, true, protectedFrame, OptionalLong.of(trackId));
    }

    private static FrameObservation visibleWithoutTrack(int index, boolean protectedFrame) {
        return new FrameObservation(
                index, index * 40L, 40, true, protectedFrame, OptionalLong.empty());
    }

    private static FrameObservation absent(int index) {
        return new FrameObservation(
                index, index * 40L, 40, false, false, OptionalLong.empty());
    }
}
