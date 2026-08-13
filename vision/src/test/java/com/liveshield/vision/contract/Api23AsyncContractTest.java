package com.liveshield.vision.contract;

import static org.junit.Assert.assertTrue;

import com.google.common.util.concurrent.ListenableFuture;
import com.liveshield.privacy.model.DetectorSnapshot;
import java.lang.reflect.Method;
import org.junit.Test;

public final class Api23AsyncContractTest {
    @Test
    public void analyzerReturnsApi23CompatibleListenableFuture() throws Exception {
        Method analyze = VisionAnalyzer.class.getMethod(
                "analyze",
                AnalysisFrameHandle.class,
                com.liveshield.privacy.model.FrameTimestamp.class,
                int.class,
                com.liveshield.privacy.model.CoordinateTransform.class);

        assertTrue(ListenableFuture.class.isAssignableFrom(analyze.getReturnType()));
    }
}
