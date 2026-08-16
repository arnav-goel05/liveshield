package com.liveshield.app.setup;

/** Pure viewport sizing for the scrollable setup screen. */
final class SetupViewportLayout {
    private static final float PREVIEW_HEIGHT_FRACTION = 0.75F;

    private SetupViewportLayout() {
    }

    static int previewHeight(int availableViewportHeight) {
        if (availableViewportHeight <= 0) {
            return 0;
        }
        return Math.max(1, Math.round(availableViewportHeight * PREVIEW_HEIGHT_FRACTION));
    }
}
