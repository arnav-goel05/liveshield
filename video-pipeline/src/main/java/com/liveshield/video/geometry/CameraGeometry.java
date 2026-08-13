package com.liveshield.video.geometry;

import android.graphics.Rect;

/** Immutable, metadata-only selected-camera active sensor geometry. */
public record CameraGeometry(int left, int top, int right, int bottom) {
    public CameraGeometry {
        if (left < 0 || top < 0 || right <= left || bottom <= top) {
            throw new IllegalArgumentException("Camera active array must be non-empty and non-negative");
        }
    }

    public static CameraGeometry fromRect(Rect rect) {
        if (rect == null) {
            throw new NullPointerException("rect");
        }
        return new CameraGeometry(rect.left, rect.top, rect.right, rect.bottom);
    }

    public int width() {
        return right - left;
    }

    public int height() {
        return bottom - top;
    }
}
