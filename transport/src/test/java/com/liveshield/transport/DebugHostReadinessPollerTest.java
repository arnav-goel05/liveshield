package com.liveshield.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Proves a slow host probe cannot throttle the debug sanitized-frame producer. */
public final class DebugHostReadinessPollerTest {
    @Test
    public void oneSecondUnavailableProbeDoesNotBlockEightFpsSubmissions() throws Exception {
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        AtomicInteger submittedFrames = new AtomicInteger();
        long startedNanos = System.nanoTime();
        try (DebugHostReadinessPoller poller = new DebugHostReadinessPoller(() -> {
            probeStarted.countDown();
            try {
                releaseProbe.await(1L, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return false;
        })) {
            assertTrue(probeStarted.await(1L, TimeUnit.SECONDS));
            for (int frame = 0; frame < 8; frame++) {
                submittedFrames.incrementAndGet();
                Thread.sleep(125L);
            }
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedNanos);
            assertTrue("All eight 8fps submissions must remain on cadence",
                    elapsedMillis < 1_500L);
            assertEquals(8, submittedFrames.get());
            assertFalse(poller.isReady());
            releaseProbe.countDown();
        } finally {
            releaseProbe.countDown();
        }
    }
}
