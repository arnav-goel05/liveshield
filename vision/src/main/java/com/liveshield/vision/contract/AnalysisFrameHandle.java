package com.liveshield.vision.contract;

/** Opaque analysis input whose owner must release it on every completion path. */
public interface AnalysisFrameHandle extends AutoCloseable {
    @Override
    void close();
}
