package com.liveshield.privacy.decision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.liveshield.privacy.model.ConfidenceClass;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.privacy.model.ProtectedRegion;
import com.liveshield.privacy.model.ProtectionAction;
import java.util.List;
import org.junit.Test;

public final class FrameDecisionStoreTest {
    private static final long MAX_AGE_NANOS = 100;

    @Test
    public void missingDecisionReturnsShieldRatherThanNull() {
        FrameDecisionStore store = new BoundedFrameDecisionStore(2, MAX_AGE_NANOS);

        FramePrivacyDecision result = store.lookup(
                FrameTimestamp.ofNanos(10), FrameTimestamp.ofNanos(10));

        assertShieldWithBasis(result, FramePrivacyDecision.Basis.MISSING);
    }

    @Test
    public void lookupUsesExactTimestampAndNeverFallsForwardFromFuture() {
        FrameDecisionStore store = new BoundedFrameDecisionStore(2, MAX_AGE_NANOS);
        store.store(regional(20, 100));

        FramePrivacyDecision result = store.lookup(
                FrameTimestamp.ofNanos(10), FrameTimestamp.ofNanos(10));

        assertShieldWithBasis(result, FramePrivacyDecision.Basis.FUTURE);
    }

    @Test
    public void staleDecisionReturnsShield() {
        FrameDecisionStore store = new BoundedFrameDecisionStore(2, 10);
        store.store(regional(20, 1_000));

        FramePrivacyDecision result = store.lookup(
                FrameTimestamp.ofNanos(20), FrameTimestamp.ofNanos(31));

        assertShieldWithBasis(result, FramePrivacyDecision.Basis.STALE);
    }

    @Test
    public void explicitlyExpiredDecisionReturnsShield() {
        FrameDecisionStore store = new BoundedFrameDecisionStore(2, MAX_AGE_NANOS);
        store.store(regional(20, 25));

        FramePrivacyDecision result = store.lookup(
                FrameTimestamp.ofNanos(20), FrameTimestamp.ofNanos(26));

        assertShieldWithBasis(result, FramePrivacyDecision.Basis.EXPIRED);
    }

    @Test
    public void evictedTimestampReturnsShieldAndCannotExposeOldRegions() {
        FrameDecisionStore store = new BoundedFrameDecisionStore(2, MAX_AGE_NANOS);
        store.store(regional(10, 100));
        store.store(regional(20, 100));
        store.store(regional(30, 100));

        FramePrivacyDecision result = store.lookup(
                FrameTimestamp.ofNanos(10), FrameTimestamp.ofNanos(30));

        assertShieldWithBasis(result, FramePrivacyDecision.Basis.EVICTED);
        assertEquals(0, result.regions().size());
        assertEquals(2, store.size());
    }

    @Test
    public void exactFreshDecisionIsReturnedAndDuplicateTimestampIsRejected() {
        FrameDecisionStore store = new BoundedFrameDecisionStore(2, MAX_AGE_NANOS);
        FramePrivacyDecision expected = regional(20, 100);
        store.store(expected);

        assertEquals(expected, store.lookup(
                FrameTimestamp.ofNanos(20), FrameTimestamp.ofNanos(20)));
        assertThrows(IllegalArgumentException.class, () -> store.store(regional(20, 100)));
    }

    private static FramePrivacyDecision regional(long timestampNanos, long expiresAtNanos) {
        ProtectedRegion region = new ProtectedRegion(
                FindingCategory.FACE,
                List.of(new NormalizedRect(0.1, 0.1, 0.2, 0.2)),
                ConfidenceClass.VALIDATED,
                ProtectionAction.MOSAIC);
        return FramePrivacyDecision.regionalSafe(
                FrameTimestamp.ofNanos(timestampNanos),
                List.of(region),
                FramePrivacyDecision.Basis.FRESH,
                FrameTimestamp.ofNanos(expiresAtNanos));
    }

    private static void assertShieldWithBasis(
            FramePrivacyDecision decision, FramePrivacyDecision.Basis basis) {
        assertEquals(FramePrivacyDecision.Status.FULL_SHIELD, decision.status());
        assertEquals(basis, decision.basis());
    }
}
