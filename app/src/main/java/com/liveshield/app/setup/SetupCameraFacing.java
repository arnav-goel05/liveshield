package com.liveshield.app.setup;

import androidx.camera.core.CameraSelector;

/** Session-local choice of the camera used to produce the protected preview. */
enum SetupCameraFacing {
    FRONT(CameraSelector.DEFAULT_FRONT_CAMERA),
    REAR(CameraSelector.DEFAULT_BACK_CAMERA);

    private final CameraSelector selector;

    SetupCameraFacing(CameraSelector selector) {
        this.selector = selector;
    }

    CameraSelector selector() {
        return selector;
    }

    SetupCameraFacing opposite() {
        return this == FRONT ? REAR : FRONT;
    }
}
