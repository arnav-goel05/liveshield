package com.liveshield.app.setup;

import com.liveshield.privacy.model.NormalizedRect;
import java.util.Objects;

/** Non-biometric, session-local face track presented for explicit creator selection. */
public record SelectableFace(long trackId, NormalizedRect outputBounds, boolean fresh) {
    public SelectableFace {
        if (trackId < 0) {
            throw new IllegalArgumentException("trackId must be non-negative");
        }
        Objects.requireNonNull(outputBounds, "outputBounds");
    }
}

