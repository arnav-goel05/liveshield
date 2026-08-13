package com.liveshield.vision.contract;

import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * On-device analyzer contract for immutable timestamped findings.
 *
 * <p>Detector inputs and their geometry are in analysis-buffer coordinates. The supplied
 * sensor-to-buffer transform must be inverted by analyzers so emitted protected regions use the
 * shared normalized sensor-space contract expected by renderers.</p>
 */
public interface VisionAnalyzer {
    ListenableFuture<DetectorSnapshot> analyze(
            AnalysisFrameHandle input,
            FrameTimestamp timestamp,
            int rotationDegrees,
            CoordinateTransform sensorToBufferTransform);

    void cancelPending();
}
