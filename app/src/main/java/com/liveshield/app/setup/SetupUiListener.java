package com.liveshield.app.setup;

/** User actions emitted by the setup screen. */
public interface SetupUiListener {
    SetupUiListener NO_OP = new SetupUiListener() {
        @Override
        public void onHostSelectionRequested(long trackId) {
        }

        @Override
        public void onStartRequested() {
        }
    };

    void onHostSelectionRequested(long trackId);

    void onStartRequested();
}
