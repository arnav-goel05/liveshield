package com.liveshield.video.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class ResourceCleanupTest {
    @Test
    public void everyResourceIsReleasedWhenEarlierCleanupFails() {
        AtomicInteger calls = new AtomicInteger();
        RuntimeException first = new RuntimeException("stop");
        RuntimeException second = new RuntimeException("release");

        RuntimeException result = ResourceCleanup.runAll(
                () -> {
                    calls.incrementAndGet();
                    throw first;
                },
                calls::incrementAndGet,
                () -> {
                    calls.incrementAndGet();
                    throw second;
                });

        assertEquals(3, calls.get());
        assertSame(first, result);
        assertEquals(1, result.getSuppressed().length);
        assertSame(second, result.getSuppressed()[0]);
    }

    @Test
    public void successfulCleanupReturnsNoFailure() {
        AtomicInteger calls = new AtomicInteger();

        assertNull(ResourceCleanup.runAll(calls::incrementAndGet, calls::incrementAndGet));
        assertEquals(2, calls.get());
    }
}
