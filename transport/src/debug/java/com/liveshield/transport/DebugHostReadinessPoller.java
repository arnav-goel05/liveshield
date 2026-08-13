package com.liveshield.transport;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Debug-only readiness polling that can never block the sanitized media producer thread. */
final class DebugHostReadinessPoller implements AutoCloseable {
    private static final long POLL_INTERVAL_MILLIS = 50L;

    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Future<?> worker;

    DebugHostReadinessPoller(BooleanSupplier probe) {
        BooleanSupplier checked = Objects.requireNonNull(probe, "probe");
        worker = executor.submit(() -> poll(checked));
    }

    boolean isReady() {
        return ready.get();
    }

    boolean isFinished() {
        return finished.get();
    }

    @Override
    public void close() {
        worker.cancel(true);
        executor.shutdownNow();
    }

    private void poll(BooleanSupplier probe) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (probe.getAsBoolean()) {
                    ready.set(true);
                    return;
                }
                Thread.sleep(POLL_INTERVAL_MILLIS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            finished.set(true);
        }
    }
}
