package com.liveshield.transport;

/** Receives only copied, sanitized H.264 video values at the transport boundary. */
@FunctionalInterface
public interface EncodedAccessUnitSink extends AutoCloseable {
    void onAccessUnit(EncodedAccessUnit accessUnit);

    @Override
    default void close() {
    }
}
