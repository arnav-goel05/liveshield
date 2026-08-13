package com.liveshield.transport;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;

/** Thread-safe bounded two-second queue containing only sanitized H.264 video values. */
public final class DelayedAccessUnitQueue {
    public static final long VIDEO_DELAY_NANOS = 2_000_000_000L;
    public static final long MAX_QUEUE_DURATION_US = 2_000_000L;
    private static final long NANOS_PER_MICROSECOND = 1_000L;

    private final long maxBytes;
    private final ArrayDeque<QueuedUnit> units = new ArrayDeque<>();
    private State state = State.AWAITING_CONFIGURATION;
    private long queuedBytes;
    private long firstPresentationTimeUs = -1L;
    private long lastPresentationTimeUs = -1L;

    public enum State {
        AWAITING_CONFIGURATION,
        AWAITING_KEY_FRAME,
        BUFFERING,
        STOPPED,
        FAILED
    }

    public enum OfferResult {
        ACCEPTED,
        DROPPED_AWAITING_CONFIGURATION,
        DROPPED_AWAITING_KEY_FRAME,
        OVERFLOW_STOP_REQUIRED,
        INVALID_ORDER_STOP_REQUIRED,
        STOPPED
    }

    public DelayedAccessUnitQueue(long maxBytes) {
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    public synchronized OfferResult offer(EncodedAccessUnit unit) {
        EncodedAccessUnit checked = Objects.requireNonNull(unit, "unit");
        if (state == State.STOPPED || state == State.FAILED) {
            return OfferResult.STOPPED;
        }
        boolean configuration = checked.flags().contains(
                EncodedAccessUnit.Flag.CODEC_CONFIGURATION);
        boolean keyFrame = checked.flags().contains(EncodedAccessUnit.Flag.KEY_FRAME);
        if (state == State.AWAITING_CONFIGURATION && !configuration) {
            return OfferResult.DROPPED_AWAITING_CONFIGURATION;
        }
        if (configuration) {
            clearUnits();
            if (!appendWithinBounds(checked)) {
                return fail(OfferResult.OVERFLOW_STOP_REQUIRED);
            }
            state = keyFrame ? State.BUFFERING : State.AWAITING_KEY_FRAME;
            return OfferResult.ACCEPTED;
        }
        if (state == State.AWAITING_KEY_FRAME && !keyFrame) {
            return OfferResult.DROPPED_AWAITING_KEY_FRAME;
        }
        if (lastPresentationTimeUs >= 0L
                && checked.presentationTimeUs() < lastPresentationTimeUs) {
            return fail(OfferResult.INVALID_ORDER_STOP_REQUIRED);
        }
        if (!appendWithinBounds(checked)) {
            return fail(OfferResult.OVERFLOW_STOP_REQUIRED);
        }
        if (keyFrame) {
            state = State.BUFFERING;
        }
        return OfferResult.ACCEPTED;
    }

    public synchronized Optional<EncodedAccessUnit> pollReady(long nowNanos) {
        if (nowNanos < 0L) {
            throw new IllegalArgumentException("nowNanos must be non-negative");
        }
        if (state != State.BUFFERING || units.isEmpty()) {
            return Optional.empty();
        }
        QueuedUnit first = units.peekFirst();
        if (nowNanos < first.releaseAtNanos()) {
            return Optional.empty();
        }
        units.removeFirst();
        queuedBytes -= first.unit().size();
        refreshBoundsAfterRemoval();
        return Optional.of(first.unit());
    }

    public synchronized void beginReconnect() {
        if (state == State.STOPPED || state == State.FAILED) {
            return;
        }
        clearUnits();
        state = State.AWAITING_CONFIGURATION;
    }

    public synchronized void stop() {
        clearUnits();
        state = State.STOPPED;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized int queuedUnits() {
        return units.size();
    }

    public synchronized long queuedBytes() {
        return queuedBytes;
    }

    public synchronized long queuedDurationUs() {
        return units.isEmpty() ? 0L : lastPresentationTimeUs - firstPresentationTimeUs;
    }

    private boolean appendWithinBounds(EncodedAccessUnit unit) {
        long candidateBytes;
        try {
            candidateBytes = Math.addExact(queuedBytes, unit.size());
        } catch (ArithmeticException overflow) {
            return false;
        }
        long candidateFirst = units.isEmpty()
                ? unit.presentationTimeUs() : firstPresentationTimeUs;
        long candidateDuration = unit.presentationTimeUs() - candidateFirst;
        if (candidateBytes > maxBytes
                || candidateDuration < 0L
                || candidateDuration > MAX_QUEUE_DURATION_US) {
            return false;
        }
        long releaseAtNanos;
        try {
            releaseAtNanos = Math.addExact(
                    Math.multiplyExact(unit.presentationTimeUs(), NANOS_PER_MICROSECOND),
                    VIDEO_DELAY_NANOS);
        } catch (ArithmeticException overflow) {
            return false;
        }
        units.addLast(new QueuedUnit(unit, releaseAtNanos));
        queuedBytes = candidateBytes;
        firstPresentationTimeUs = candidateFirst;
        lastPresentationTimeUs = unit.presentationTimeUs();
        return true;
    }

    private OfferResult fail(OfferResult result) {
        clearUnits();
        state = State.FAILED;
        return result;
    }

    private void clearUnits() {
        units.clear();
        queuedBytes = 0L;
        firstPresentationTimeUs = -1L;
        lastPresentationTimeUs = -1L;
    }

    private void refreshBoundsAfterRemoval() {
        if (units.isEmpty()) {
            firstPresentationTimeUs = -1L;
            lastPresentationTimeUs = -1L;
        } else {
            firstPresentationTimeUs = units.peekFirst().unit().presentationTimeUs();
            lastPresentationTimeUs = units.peekLast().unit().presentationTimeUs();
        }
    }

    private record QueuedUnit(EncodedAccessUnit unit, long releaseAtNanos) {
    }
}
