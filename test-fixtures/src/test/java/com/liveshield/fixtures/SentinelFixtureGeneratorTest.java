package com.liveshield.fixtures;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import java.util.List;
import org.junit.Test;

public final class SentinelFixtureGeneratorTest {
    @Test
    public void sameInputsProduceSamePixelsAndTruth() {
        SentinelFixtureGenerator.Region region =
                new SentinelFixtureGenerator.Region(0.1, 0.2, 0.4, 0.7);
        SentinelFixtureGenerator.SentinelFrame first =
                SentinelFixtureGenerator.generate(64, 48, 7, 123L, 99L, List.of(region));
        SentinelFixtureGenerator.SentinelFrame second =
                SentinelFixtureGenerator.generate(64, 48, 7, 123L, 99L, List.of(region));

        assertEquals(first.protectedRegions(), second.protectedRegions());
        for (int y = 0; y < first.image().getHeight(); y++) {
            for (int x = 0; x < first.image().getWidth(); x++) {
                assertEquals(first.image().getRGB(x, y), second.image().getRGB(x, y));
            }
        }
    }

    @Test
    public void adjacentFrameIdentifiersAndPixelsDiffer() {
        SentinelFixtureGenerator.SentinelFrame first =
                SentinelFixtureGenerator.generate(64, 48, 0, 0L, 5L, List.of());
        SentinelFixtureGenerator.SentinelFrame second =
                SentinelFixtureGenerator.generate(64, 48, 1, 1L, 5L, List.of());

        assertNotEquals(first.image().getRGB(0, 0), second.image().getRGB(0, 0));
        assertNotEquals(first.image().getRGB(20, 20), second.image().getRGB(20, 20));
    }

    @Test
    public void movingSequenceHasMonotonicTimingAndInBoundsRegions() {
        List<SentinelFixtureGenerator.SentinelFrame> frames =
                SentinelFixtureGenerator.movingRegionSequence(64, 48, 5, 10L, 33L, 8L);

        assertEquals(5, frames.size());
        for (int index = 0; index < frames.size(); index++) {
            assertEquals(10L + 33L * index, frames.get(index).timestampNs());
            assertEquals(1, frames.get(index).protectedRegions().size());
        }
    }

    @Test
    public void rejectsInvalidDimensionsAndRegions() {
        assertThrows(IllegalArgumentException.class,
                () -> SentinelFixtureGenerator.generate(8, 8, 0, 0L, 1L, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SentinelFixtureGenerator.Region(0.5, 0.5, 0.5, 0.9));
    }
}
