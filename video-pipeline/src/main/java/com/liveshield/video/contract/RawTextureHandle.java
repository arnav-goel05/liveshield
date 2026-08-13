package com.liveshield.video.contract;

/** Opaque, bounded-pool raw texture ownership token. */
public interface RawTextureHandle extends AutoCloseable {
    @Override
    void close();
}
