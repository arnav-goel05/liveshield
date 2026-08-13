package com.liveshield.vision.pii;

import android.graphics.Bitmap;
import com.liveshield.vision.contract.AnalysisFrameHandle;

/** Ephemeral text-analysis pixels, accessible only to the offline OCR implementation. */
public interface TextAnalysisFrame extends AnalysisFrameHandle {
    Bitmap bitmap(int rotationDegrees);

    int width();

    int height();
}
