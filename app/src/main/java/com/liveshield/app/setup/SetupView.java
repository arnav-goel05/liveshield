package com.liveshield.app.setup;

import java.util.List;

/** Metadata-only coordinator updates rendered by the setup screen. */
public interface SetupView {
    void showSelectableFaces(List<SelectableFace> faces, Long selectedTrack);

    default void showDismissiblePrivacyMasks(List<DismissiblePrivacyMask> masks) {
    }

    void showPrivacyReady(boolean ready);

    default void showHostReselectionRequired(boolean required) {
    }

    default void onSafeStopFailure() {
    }
}
