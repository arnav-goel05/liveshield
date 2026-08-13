package com.liveshield.video.output;

/** Small fail-closed lifecycle shared by the CameraX surface and codec drain paths. */
final class OutputLifecycle {
    private State state = State.IDLE;
    private RuntimeException failure;

    synchronized void beginRequest() {
        require(State.IDLE, "Output is not ready for a surface request");
        state = State.CONFIGURING;
    }

    synchronized void started() {
        require(State.CONFIGURING, "Output did not begin configuration");
        state = State.RUNNING;
    }

    synchronized void surfaceDetached() {
        if (state == State.RUNNING) {
            state = State.STOPPING;
        }
    }

    synchronized boolean beginStop() {
        if (state == State.CLOSED) {
            return false;
        }
        if (state != State.FAILED) {
            state = State.STOPPING;
        }
        return true;
    }

    synchronized void stopped() {
        if (state != State.CLOSED && state != State.FAILED) {
            state = State.IDLE;
        }
    }

    synchronized void failed(RuntimeException exception) {
        failure = exception;
        state = State.FAILED;
    }

    synchronized void closed() {
        state = State.CLOSED;
    }

    synchronized State state() {
        return state;
    }

    synchronized RuntimeException failure() {
        return failure;
    }

    private void require(State required, String message) {
        if (state != required) {
            throw new IllegalStateException(message + ": " + state);
        }
    }

    enum State {
        IDLE,
        CONFIGURING,
        RUNNING,
        STOPPING,
        FAILED,
        CLOSED
    }
}
