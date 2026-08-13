package com.liveshield.vision.contract;

import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import com.google.common.util.concurrent.ListenableFuture;

/** On-device analyzer contract for immutable timestamped findings. */
public interface VisionAnalyzer {
    ListenableFuture<DetectorSnapshot> analyze(
            AnalysisFrameHandle input,
            FrameTimestamp timestamp,
            int rotationDegrees,
            CoordinateTransform sensorToBufferTransform);

    void cancelPending();
}
