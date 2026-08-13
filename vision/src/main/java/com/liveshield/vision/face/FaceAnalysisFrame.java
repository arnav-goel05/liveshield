package com.liveshield.vision.face;

import android.graphics.Bitmap;
import com.liveshield.vision.contract.AnalysisFrameHandle;

/** Ephemeral face-analysis input. Pixel access is intentionally limited to the on-device engine. */
public interface FaceAnalysisFrame extends AnalysisFrameHandle {
    /** Returns an ephemeral upright bitmap owned by this frame and valid until {@link #close()}. */
    Bitmap bitmap(int rotationDegrees);

    int width();

    int height();
}
