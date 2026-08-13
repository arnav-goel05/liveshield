package com.liveshield.privacy.decision;

import com.liveshield.privacy.model.FrameTimestamp;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Thread-safe timestamp store that never substitutes a decision from another frame. */
public final class BoundedFrameDecisionStore implements FrameDecisionStore {
    private final int capacity;
    private final long maxDecisionAgeNanos;
    private final NavigableMap<FrameTimestamp, FramePrivacyDecision> decisions = new TreeMap<>();
    private final ArrayDeque<FrameTimestamp> evictionOrder = new ArrayDeque<>();
    private final Set<FrameTimestamp> evictedTimestamps = new HashSet<>();

    public BoundedFrameDecisionStore(int capacity, long maxDecisionAgeNanos) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (maxDecisionAgeNanos < 0) {
            throw new IllegalArgumentException("maxDecisionAgeNanos must be non-negative");
        }
        this.capacity = capacity;
        this.maxDecisionAgeNanos = maxDecisionAgeNanos;
    }

    @Override
    public synchronized void store(FramePrivacyDecision decision) {
        Objects.requireNonNull(decision, "decision");
        FrameTimestamp timestamp = decision.timestamp();
        if (decisions.containsKey(timestamp)) {
            throw new IllegalArgumentException("Exactly one decision is allowed per timestamp");
        }
        decisions.put(timestamp, decision);
        evictedTimestamps.remove(timestamp);
        while (decisions.size() > capacity) {
            FrameTimestamp evicted = decisions.firstKey();
            decisions.remove(evicted);
            rememberEviction(evicted);
        }
    }

    @Override
    public synchronized FramePrivacyDecision lookup(
            FrameTimestamp frameTimestamp, FrameTimestamp lookupTimestamp) {
        Objects.requireNonNull(frameTimestamp, "frameTimestamp");
        Objects.requireNonNull(lookupTimestamp, "lookupTimestamp");
        if (lookupTimestamp.compareTo(frameTimestamp) < 0) {
            throw new IllegalArgumentException("Lookup time cannot precede its camera frame");
        }
        FramePrivacyDecision exact = decisions.get(frameTimestamp);
        if (exact == null) {
            if (evictedTimestamps.contains(frameTimestamp)) {
                return FramePrivacyDecision.fullShield(
                        frameTimestamp, FramePrivacyDecision.Basis.EVICTED);
            }
            FramePrivacyDecision.Basis basis = decisions.higherKey(frameTimestamp) == null
                    ? FramePrivacyDecision.Basis.MISSING : FramePrivacyDecision.Basis.FUTURE;
            return FramePrivacyDecision.fullShield(frameTimestamp, basis);
        }
        if (exact.isExpiredAt(lookupTimestamp)) {
            return FramePrivacyDecision.fullShield(
                    frameTimestamp, FramePrivacyDecision.Basis.EXPIRED);
        }
        long age;
        try {
            age = Math.subtractExact(lookupTimestamp.nanos(), exact.timestamp().nanos());
        } catch (ArithmeticException exception) {
            return FramePrivacyDecision.fullShield(
                    frameTimestamp, FramePrivacyDecision.Basis.STALE);
        }
        if (age > maxDecisionAgeNanos) {
            return FramePrivacyDecision.fullShield(
                    frameTimestamp, FramePrivacyDecision.Basis.STALE);
        }
        return exact;
    }

    @Override
    public synchronized int size() {
        return decisions.size();
    }

    @Override
    public synchronized void clear() {
        decisions.clear();
        evictionOrder.clear();
        evictedTimestamps.clear();
    }

    private void rememberEviction(FrameTimestamp timestamp) {
        evictionOrder.addLast(timestamp);
        evictedTimestamps.add(timestamp);
        while (evictionOrder.size() > capacity) {
            evictedTimestamps.remove(evictionOrder.removeFirst());
        }
    }
}
