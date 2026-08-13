package com.liveshield.video.output;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OutputLifecycleTest {
    @Test
    public void supportsStartDetachStopAndRestartWithoutOverlappingRequests() {
        OutputLifecycle lifecycle = new OutputLifecycle();
        lifecycle.beginRequest();
        assertEquals(OutputLifecycle.State.CONFIGURING, lifecycle.state());
        assertThrows(IllegalStateException.class, lifecycle::beginRequest);

        lifecycle.started();
        lifecycle.surfaceDetached();
        assertEquals(OutputLifecycle.State.STOPPING, lifecycle.state());
        assertTrue(lifecycle.beginStop());
        lifecycle.stopped();
        assertEquals(OutputLifecycle.State.IDLE, lifecycle.state());

        lifecycle.beginRequest();
        lifecycle.started();
        assertEquals(OutputLifecycle.State.RUNNING, lifecycle.state());
    }

    @Test
    public void failureIsStickyUntilCloseAndCloseIsIdempotent() {
        OutputLifecycle lifecycle = new OutputLifecycle();
        RuntimeException failure = new RuntimeException("codec");
        lifecycle.failed(failure);

        assertSame(failure, lifecycle.failure());
        assertEquals(OutputLifecycle.State.FAILED, lifecycle.state());
        assertTrue(lifecycle.beginStop());
        lifecycle.stopped();
        assertEquals(OutputLifecycle.State.FAILED, lifecycle.state());

        lifecycle.closed();
        assertEquals(OutputLifecycle.State.CLOSED, lifecycle.state());
        assertFalse(lifecycle.beginStop());
        lifecycle.closed();
        assertEquals(OutputLifecycle.State.CLOSED, lifecycle.state());
    }
}
