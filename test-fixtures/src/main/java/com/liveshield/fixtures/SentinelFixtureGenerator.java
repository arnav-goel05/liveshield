package com.liveshield.fixtures;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import javax.imageio.ImageIO;

/** Creates deterministic, synthetic frames for renderer and raw-pixel escape tests. */
public final class SentinelFixtureGenerator {
    private static final int MIN_DIMENSION = 16;
    private static final int CHECKER_SIZE = 16;

    /** Immutable normalized protected region. */
    public record Region(double left, double top, double right, double bottom) {
        public Region {
            if (!Double.isFinite(left) || !Double.isFinite(top)
                    || !Double.isFinite(right) || !Double.isFinite(bottom)) {
                throw new IllegalArgumentException("Region coordinates must be finite");
            }
            if (left < 0.0 || top < 0.0 || right > 1.0 || bottom > 1.0
                    || left >= right || top >= bottom) {
                throw new IllegalArgumentException("Region must be a non-empty normalized rectangle");
            }
        }
    }

    /** One generated source frame plus the regions policy expects to protect. */
    public record SentinelFrame(
            int frameIndex,
            long timestampNs,
            long seed,
            BufferedImage image,
            List<Region> protectedRegions) {
        public SentinelFrame {
            if (frameIndex < 0 || timestampNs < 0) {
                throw new IllegalArgumentException("Frame index and timestamp must be non-negative");
            }
            Objects.requireNonNull(image, "image");
            protectedRegions = List.copyOf(protectedRegions);
        }
    }

    private SentinelFixtureGenerator() {
    }

    /**
     * Generates a deterministic frame. Each source frame includes checker pixels, a seed-derived
     * sentinel color, and a machine-readable binary frame identifier along the first scanline.
     */
    public static SentinelFrame generate(
            int width,
            int height,
            int frameIndex,
            long timestampNs,
            long seed,
            List<Region> protectedRegions) {
        if (width < MIN_DIMENSION || height < MIN_DIMENSION) {
            throw new IllegalArgumentException("Frame dimensions must be at least 16 pixels");
        }
        Objects.requireNonNull(protectedRegions, "protectedRegions");

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            paintChecker(graphics, width, height, frameIndex, seed);
            paintFrameIdentifier(image, frameIndex, seed);
        } finally {
            graphics.dispose();
        }
        return new SentinelFrame(frameIndex, timestampNs, seed, image, protectedRegions);
    }

    /** Produces a deterministic moving-region sequence at a fixed rational frame cadence. */
    public static List<SentinelFrame> movingRegionSequence(
            int width,
            int height,
            int frameCount,
            long firstTimestampNs,
            long frameDurationNs,
            long seed) {
        if (frameCount <= 0 || firstTimestampNs < 0 || frameDurationNs <= 0) {
            throw new IllegalArgumentException("Sequence timing and count must be positive");
        }
        List<SentinelFrame> frames = new ArrayList<>(frameCount);
        double regionWidth = 0.25;
        double regionHeight = 0.30;
        for (int index = 0; index < frameCount; index++) {
            double progress = frameCount == 1 ? 0.0 : (double) index / (frameCount - 1);
            double left = progress * (1.0 - regionWidth);
            double top = (1.0 - regionHeight) * (0.25 + 0.5 * progress);
            Region region = new Region(left, top, left + regionWidth, top + regionHeight);
            frames.add(generate(
                    width,
                    height,
                    index,
                    Math.addExact(firstTimestampNs, Math.multiplyExact(frameDurationNs, index)),
                    seed,
                    Collections.singletonList(region)));
        }
        return List.copyOf(frames);
    }

    /** Writes one lossless PNG fixture. Parent directories are created explicitly. */
    public static void writePng(SentinelFrame frame, Path output) throws IOException {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(output, "output");
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!ImageIO.write(frame.image(), "png", output.toFile())) {
            throw new IOException("PNG encoder unavailable");
        }
    }

    private static void paintChecker(
            Graphics2D graphics,
            int width,
            int height,
            int frameIndex,
            long seed) {
        Random random = new Random(seed ^ Integer.toUnsignedLong(frameIndex));
        int sentinel = Color.HSBtoRGB(random.nextFloat(), 0.85f, 1.0f) | 0xFF000000;
        int inverse = 0xFF000000 | (~sentinel & 0x00FFFFFF);
        for (int y = 0; y < height; y += CHECKER_SIZE) {
            for (int x = 0; x < width; x += CHECKER_SIZE) {
                boolean alternate = ((x / CHECKER_SIZE) + (y / CHECKER_SIZE) + frameIndex) % 2 == 0;
                graphics.setColor(new Color(alternate ? sentinel : inverse, true));
                graphics.fillRect(x, y, Math.min(CHECKER_SIZE, width - x),
                        Math.min(CHECKER_SIZE, height - y));
            }
        }
    }

    private static void paintFrameIdentifier(BufferedImage image, int frameIndex, long seed) {
        long identifier = seed ^ Integer.toUnsignedLong(frameIndex);
        int limit = Math.min(Long.SIZE, image.getWidth());
        for (int bit = 0; bit < limit; bit++) {
            boolean set = ((identifier >>> bit) & 1L) == 1L;
            image.setRGB(bit, 0, set ? Color.WHITE.getRGB() : Color.BLACK.getRGB());
        }
    }
}
