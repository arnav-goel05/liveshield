package com.liveshield.app.session;

import com.google.common.util.concurrent.ListenableFuture;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.video.analysis.VisionScheduler;
import com.liveshield.vision.contract.AnalysisFrameHandle;
import com.liveshield.vision.contract.VisionAnalyzer;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/** Adapts one offline detector to the scheduler without exposing its frame or result payload. */
final class VisionAnalyzerLaneAdapter implements VisionScheduler.LaneAnalyzer {
    private final VisionAnalyzer analyzer;
    private final Executor completionExecutor;

    VisionAnalyzerLaneAdapter(VisionAnalyzer analyzer, Executor completionExecutor) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.completionExecutor = Objects.requireNonNull(completionExecutor, "completionExecutor");
    }

    @Override
    public VisionScheduler.Cancellation analyze(
            VisionScheduler.FrameLease frame,
            VisionScheduler.Completion completion) {
        if (!(frame instanceof ImageProxyVisionFrame.DetectorLease metadata)
                || !(frame instanceof AnalysisFrameHandle input)) {
            throw new IllegalArgumentException("Unsupported detector lease");
        }
        ListenableFuture<DetectorSnapshot> result = analyzer.analyze(
                input,
                frame.timestamp(),
                metadata.rotationDegrees(),
                metadata.transform());
        result.addListener(() -> {
            try {
                completion.complete(result.get());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                analyzer.cancelPending();
                completion.complete(null);
            } catch (ExecutionException | RuntimeException failure) {
                analyzer.cancelPending();
                completion.complete(null);
            }
        }, completionExecutor);
        return () -> {
            result.cancel(true);
            analyzer.cancelPending();
        };
    }
}
