package com.liveshield.video.output;

/** Runs every cleanup action and preserves all failures without skipping later releases. */
final class ResourceCleanup {
    private ResourceCleanup() {
    }

    static RuntimeException runAll(Runnable... actions) {
        RuntimeException failure = null;
        for (Runnable action : actions) {
            try {
                action.run();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        return failure;
    }
}
