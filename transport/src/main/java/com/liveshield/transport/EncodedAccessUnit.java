package com.liveshield.transport;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable copied H.264 video value carrying the fixed sanitized attestation. */
public final class EncodedAccessUnit {
    private final byte[] payload;
    private final long presentationTimeUs;
    private final Set<Flag> flags;
    public enum TrackType {
        VIDEO
    }

    public enum Codec {
        H264
    }

    public enum PrivacyAttestation {
        SANITIZED
    }

    public enum Flag {
        CODEC_CONFIGURATION,
        KEY_FRAME,
        END_OF_STREAM
    }

    private EncodedAccessUnit(
            byte[] payload,
            long presentationTimeUs,
            Set<Flag> flags) {
        this.payload = Objects.requireNonNull(payload, "payload").clone();
        if (presentationTimeUs < 0L) {
            throw new IllegalArgumentException("presentationTimeUs must be non-negative");
        }
        this.presentationTimeUs = presentationTimeUs;
        Objects.requireNonNull(flags, "flags");
        EnumSet<Flag> copiedFlags = EnumSet.noneOf(Flag.class);
        for (Flag flag : flags) {
            copiedFlags.add(Objects.requireNonNull(flag, "flag"));
        }
        this.flags = Collections.unmodifiableSet(copiedFlags);
    }

    static EncodedAccessUnit copySanitizedH264(
            byte[] payload,
            long presentationTimeUs,
            Set<Flag> flags) {
        return new EncodedAccessUnit(payload, presentationTimeUs, flags);
    }

    public byte[] payload() {
        return payload.clone();
    }

    public int size() {
        return payload.length;
    }

    public long presentationTimeUs() {
        return presentationTimeUs;
    }

    public Set<Flag> flags() {
        return flags;
    }

    public TrackType trackType() {
        return TrackType.VIDEO;
    }

    public Codec codec() {
        return Codec.H264;
    }

    public PrivacyAttestation privacyAttestation() {
        return PrivacyAttestation.SANITIZED;
    }

}
