package com.liveshield.app.setup;

import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.NormalizedRect;
import java.util.Objects;

/** Payload-free QR or private-word mask exposed as a session-only tap target. */
public record DismissiblePrivacyMask(
        FindingCategory category, NormalizedRect bounds) {
    public DismissiblePrivacyMask {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(bounds, "bounds");
        if (category != FindingCategory.AUTO_BARCODE
                && category != FindingCategory.WATCHLIST_MATCH) {
            throw new IllegalArgumentException("Only QR and private-word masks are dismissible");
        }
    }
}
