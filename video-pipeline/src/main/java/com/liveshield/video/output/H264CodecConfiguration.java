package com.liveshield.video.output;

import java.util.Arrays;
import java.util.Objects;

/** Copied video-only AVC configuration needed by muxers and stream packetizers. */
public final class H264CodecConfiguration {
    private final int width;
    private final int height;
    private final byte[] sequenceParameterSet;
    private final byte[] pictureParameterSet;

    public H264CodecConfiguration(
            int width,
            int height,
            byte[] sequenceParameterSet,
            byte[] pictureParameterSet) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Video dimensions must be positive");
        }
        this.width = width;
        this.height = height;
        this.sequenceParameterSet = requireBytes(sequenceParameterSet, "sequenceParameterSet");
        this.pictureParameterSet = requireBytes(pictureParameterSet, "pictureParameterSet");
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public byte[] sequenceParameterSet() {
        return sequenceParameterSet.clone();
    }

    public byte[] pictureParameterSet() {
        return pictureParameterSet.clone();
    }

    private static byte[] requireBytes(byte[] value, String name) {
        Objects.requireNonNull(value, name);
        if (value.length == 0) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value.clone();
    }

    @Override
    public boolean equals(Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (!(candidate instanceof H264CodecConfiguration other)) {
            return false;
        }
        return width == other.width
                && height == other.height
                && Arrays.equals(sequenceParameterSet, other.sequenceParameterSet)
                && Arrays.equals(pictureParameterSet, other.pictureParameterSet);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                width,
                height,
                Arrays.hashCode(sequenceParameterSet),
                Arrays.hashCode(pictureParameterSet));
    }
}
