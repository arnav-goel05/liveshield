package com.liveshield.video.render;

import android.graphics.Bitmap;
import android.graphics.Color;
import com.liveshield.privacy.decision.FramePrivacyDecision;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import java.util.List;
import java.util.Objects;

/** Mechanical decoded-pixel gate for the currently certified opaque treatment. */
public final class DecodedOutputStrengthGate {
    private DecodedOutputStrengthGate() {
    }

    public static Result inspectOpaqueCoverage(
            Bitmap decodedFrame,
            List<NormalizedRect> intendedRegions,
            int channelTolerance,
            double requiredCoverage) {
        Objects.requireNonNull(decodedFrame, "decodedFrame");
        List<NormalizedRect> regions = List.copyOf(
                Objects.requireNonNull(intendedRegions, "intendedRegions"));
        if (regions.isEmpty()) {
            throw new IllegalArgumentException("At least one intended region is required");
        }
        if (channelTolerance < 0 || channelTolerance > 255) {
            throw new IllegalArgumentException("channelTolerance must be in [0, 255]");
        }
        if (!Double.isFinite(requiredCoverage)
                || requiredCoverage <= 0.0 || requiredCoverage > 1.0) {
            throw new IllegalArgumentException("requiredCoverage must be in (0, 1]");
        }
        long inspected = 0;
        long covered = 0;
        int width = decodedFrame.getWidth();
        int height = decodedFrame.getHeight();
        for (NormalizedRect region : regions) {
            int left = clamp((int) Math.floor(
                    (region.left() - GlRedactionRenderer.CERTIFIED_PADDING) * width), width);
            int right = clamp((int) Math.ceil(
                    (region.right() + GlRedactionRenderer.CERTIFIED_PADDING) * width), width);
            int top = clamp((int) Math.floor(
                    (region.top() - GlRedactionRenderer.CERTIFIED_PADDING) * height), height);
            int bottom = clamp((int) Math.ceil(
                    (region.bottom() + GlRedactionRenderer.CERTIFIED_PADDING) * height), height);
            for (int y = top; y < bottom; y++) {
                for (int x = left; x < right; x++) {
                    inspected++;
                    if (nearMask(decodedFrame.getPixel(x, y), channelTolerance)) {
                        covered++;
                    }
                }
            }
        }
        double coverage = inspected == 0 ? 0.0 : (double) covered / inspected;
        Outcome outcome = coverage >= requiredCoverage
                ? Outcome.CERTIFIED_OPAQUE : Outcome.ESCALATE_FULL_SHIELD;
        return new Result(outcome, inspected, covered, coverage);
    }

    public static FramePrivacyDecision fullShieldOnFailure(FrameTimestamp timestamp) {
        return FramePrivacyDecision.fullShield(
                Objects.requireNonNull(timestamp, "timestamp"), FramePrivacyDecision.Basis.ERROR);
    }

    private static boolean nearMask(int color, int tolerance) {
        int expected = GlRedactionRenderer.OPAQUE_MASK_COLOR;
        return Math.abs(Color.red(color) - Color.red(expected)) <= tolerance
                && Math.abs(Color.green(color) - Color.green(expected)) <= tolerance
                && Math.abs(Color.blue(color) - Color.blue(expected)) <= tolerance;
    }

    private static int clamp(int coordinate, int extent) {
        return Math.max(0, Math.min(extent, coordinate));
    }

    public enum Outcome {
        CERTIFIED_OPAQUE,
        ESCALATE_FULL_SHIELD
    }

    public record Result(Outcome outcome, long inspectedPixels, long coveredPixels, double coverage) {
        public Result {
            Objects.requireNonNull(outcome, "outcome");
            if (inspectedPixels < 0 || coveredPixels < 0 || coveredPixels > inspectedPixels) {
                throw new IllegalArgumentException("Invalid decoded-pixel counts");
            }
            if (!Double.isFinite(coverage) || coverage < 0.0 || coverage > 1.0) {
                throw new IllegalArgumentException("coverage must be in [0, 1]");
            }
        }
    }
}
