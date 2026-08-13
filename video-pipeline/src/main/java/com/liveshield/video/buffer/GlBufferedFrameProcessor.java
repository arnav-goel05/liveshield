package com.liveshield.video.buffer;

import com.liveshield.privacy.decision.FrameDecisionStore;
import com.liveshield.privacy.decision.FramePrivacyDecision;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.session.SessionHealth;
import com.liveshield.video.contract.BufferedFrameProcessor;
import com.liveshield.video.contract.RawTextureHandle;
import com.liveshield.video.contract.RedactionRenderer;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Sole bounded owner of copied raw GL textures awaiting exact timestamped privacy decisions.
 *
 * <p>The class contains no OpenGL or Android API so ownership, timeout, and recovery behavior can
 * be proved on the JVM. Actual texture handles and rendering remain behind opaque contracts.</p>
 */
public final class GlBufferedFrameProcessor implements BufferedFrameProcessor {
    private final int capacity;
    private final FrameDecisionStore decisions;
    private final RedactionRenderer renderer;
    private final RecoveryListener recoveryListener;
    private final FailureListener failureListener;
    private final ArrayDeque<PendingFrame> pending = new ArrayDeque<>();
    private boolean unsafeRecovery;
    private boolean closed;

    public GlBufferedFrameProcessor(
            int capacity,
            FrameDecisionStore decisions,
            RedactionRenderer renderer,
            RecoveryListener recoveryListener,
            FailureListener failureListener) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.recoveryListener = Objects.requireNonNull(recoveryListener, "recoveryListener");
        this.failureListener = Objects.requireNonNull(failureListener, "failureListener");
    }

    @Override
    public synchronized void accept(
            RawTextureHandle rawTexture,
            FrameTimestamp cameraTimestamp,
            FrameTimestamp deadline) {
        accept(rawTexture, cameraTimestamp, deadline, true);
    }

    /** Accepts one owned texture and captures whether regional geometry was safe at acquisition. */
    public synchronized void accept(
            RawTextureHandle rawTexture,
            FrameTimestamp cameraTimestamp,
            FrameTimestamp deadline,
            boolean regionalTransformReady) {
        Objects.requireNonNull(rawTexture, "rawTexture");
        Objects.requireNonNull(cameraTimestamp, "cameraTimestamp");
        Objects.requireNonNull(deadline, "deadline");
        if (deadline.compareTo(cameraTimestamp) < 0) {
            rawTexture.close();
            throw new IllegalArgumentException("deadline cannot precede camera timestamp");
        }
        if (closed) {
            rawTexture.close();
            return;
        }
        PendingFrame incoming = new PendingFrame(
                rawTexture, cameraTimestamp, deadline, regionalTransformReady);
        if (unsafeRecovery) {
            renderShieldAndRelease(incoming, FramePrivacyDecision.Basis.ERROR);
            return;
        }
        if (pending.size() + 1 >= capacity) {
            pending.addLast(incoming);
            enterUnsafeAndShieldAll(FramePrivacyDecision.Basis.TIMEOUT);
            return;
        }
        pending.addLast(incoming);
    }

    @Override
    public synchronized void processReady(FrameTimestamp now) {
        Objects.requireNonNull(now, "now");
        if (closed || unsafeRecovery) {
            return;
        }
        while (!pending.isEmpty()) {
            PendingFrame frame = pending.peekFirst();
            if (now.compareTo(frame.cameraTimestamp) < 0) {
                return;
            }
            FramePrivacyDecision decision = decisions.lookup(frame.cameraTimestamp, now);
            boolean awaitingExactDecision = decision.basis() == FramePrivacyDecision.Basis.MISSING
                    || decision.basis() == FramePrivacyDecision.Basis.FUTURE;
            if (awaitingExactDecision && now.compareTo(frame.deadline) < 0) {
                return;
            }
            pending.removeFirst();
            if (awaitingExactDecision) {
                pending.addFirst(frame);
                enterUnsafeAndShieldAll(FramePrivacyDecision.Basis.TIMEOUT);
            } else {
                FramePrivacyDecision guarded = guardTransform(frame, decision);
                if (guarded.status() == FramePrivacyDecision.Status.FULL_SHIELD) {
                    renderShieldAndRelease(frame, guarded.basis());
                    if (isUnsafeDecision(guarded.basis())) {
                        enterUnsafeAndShieldAll(guarded.basis());
                    }
                } else {
                    renderAndRelease(frame, guarded);
                }
            }
            if (unsafeRecovery) {
                return;
            }
        }
    }

    /**
     * Processes only work whose own camera-domain deadline has arrived.
     *
     * <p>A frame can complete before its scheduled callback runs. In that case a newer frame may
     * already be at the queue head, and the older callback must be ignored instead of treating its
     * timestamp as time moving backwards.</p>
     */
    public synchronized void processDeadline(FrameTimestamp deadline) {
        Objects.requireNonNull(deadline, "deadline");
        if (closed || unsafeRecovery || pending.isEmpty()) {
            return;
        }
        PendingFrame frame = pending.peekFirst();
        if (deadline.compareTo(frame.deadline) < 0) {
            return;
        }
        processReady(deadline);
    }

    /**
     * Reports that all pre-shield textures and unsafe surfaces were discarded and replaced.
     *
     * <p>No queued raw frame survives into this transition, so recovery can never flush old work.</p>
     */
    public synchronized void verifyRecovery() {
        if (closed) {
            return;
        }
        if (!pending.isEmpty()) {
            throw new IllegalStateException("Recovery cannot be verified with queued raw frames");
        }
        if (unsafeRecovery) {
            unsafeRecovery = false;
            recoveryListener.onRecoveryState(SessionHealth.RecoveryState.VERIFIED);
        }
    }

    /** Latches unsafe state, shields all queued timestamps, and releases every raw handle. */
    public synchronized void fail(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        failureListener.onFailure(failure);
        enterUnsafeAndShieldAll(FramePrivacyDecision.Basis.ERROR);
    }

    /** Typed safety invalidation is recoverable and therefore does not report a component fault. */
    public synchronized void invalidateForSafety() {
        if (!closed) {
            enterUnsafeAndShieldAll(FramePrivacyDecision.Basis.ERROR);
        }
    }

    public synchronized int queuedFrameCount() {
        return pending.size();
    }

    public synchronized boolean requiresVerifiedRecovery() {
        return unsafeRecovery;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        releaseAllWithoutRendering();
    }

    private FramePrivacyDecision guardTransform(
            PendingFrame frame, FramePrivacyDecision decision) {
        if (frame.regionalTransformReady
                || decision.status() == FramePrivacyDecision.Status.FULL_SHIELD) {
            return decision;
        }
        return FramePrivacyDecision.fullShield(
                frame.cameraTimestamp, FramePrivacyDecision.Basis.MISSING);
    }

    private void renderAndRelease(PendingFrame frame, FramePrivacyDecision decision) {
        try {
            renderer.render(frame.rawTexture, decision);
        } catch (RuntimeException failure) {
            failureListener.onFailure(failure);
            enterUnsafeAndShieldAll(FramePrivacyDecision.Basis.ERROR);
        } finally {
            frame.rawTexture.close();
        }
    }

    private void renderShieldAndRelease(
            PendingFrame frame, FramePrivacyDecision.Basis basis) {
        try {
            renderer.renderShield(FramePrivacyDecision.fullShield(frame.cameraTimestamp, basis));
        } catch (RuntimeException failure) {
            failureListener.onFailure(failure);
            if (!unsafeRecovery) {
                unsafeRecovery = true;
                recoveryListener.onRecoveryState(SessionHealth.RecoveryState.UNSAFE);
            }
        } finally {
            frame.rawTexture.close();
        }
    }

    private void enterUnsafeAndShieldAll(FramePrivacyDecision.Basis basis) {
        if (!unsafeRecovery) {
            unsafeRecovery = true;
            recoveryListener.onRecoveryState(SessionHealth.RecoveryState.UNSAFE);
        }
        PendingFrame frame;
        while ((frame = pending.pollFirst()) != null) {
            renderShieldAndRelease(frame, basis);
        }
    }

    private void releaseAllWithoutRendering() {
        PendingFrame frame;
        while ((frame = pending.pollFirst()) != null) {
            frame.rawTexture.close();
        }
    }

    private static boolean isUnsafeDecision(FramePrivacyDecision.Basis basis) {
        return basis == FramePrivacyDecision.Basis.ERROR
                || basis == FramePrivacyDecision.Basis.TIMEOUT
                || basis == FramePrivacyDecision.Basis.STALE
                || basis == FramePrivacyDecision.Basis.EXPIRED
                || basis == FramePrivacyDecision.Basis.EVICTED;
    }

    private record PendingFrame(
            RawTextureHandle rawTexture,
            FrameTimestamp cameraTimestamp,
            FrameTimestamp deadline,
            boolean regionalTransformReady) {
    }

    @FunctionalInterface
    public interface RecoveryListener {
        void onRecoveryState(SessionHealth.RecoveryState state);
    }

    @FunctionalInterface
    public interface FailureListener {
        void onFailure(Throwable failure);
    }
}
