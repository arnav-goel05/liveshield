package com.liveshield.video.buffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.liveshield.privacy.decision.BoundedFrameDecisionStore;
import com.liveshield.privacy.decision.FrameDecisionStore;
import com.liveshield.privacy.decision.FramePrivacyDecision;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.session.SessionHealth;
import com.liveshield.video.contract.RawTextureHandle;
import com.liveshield.video.contract.RedactionRenderer;
import com.liveshield.video.contract.SanitizedRender;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;

public final class BufferedFrameProcessorTest {
    private FrameDecisionStore decisions;
    private RecordingRenderer renderer;
    private List<SessionHealth.RecoveryState> recovery;
    private List<Throwable> failures;
    private GlBufferedFrameProcessor processor;

    @Before
    public void setUp() {
        decisions = new BoundedFrameDecisionStore(12, 1_000);
        renderer = new RecordingRenderer();
        recovery = new ArrayList<>();
        failures = new ArrayList<>();
        processor = new GlBufferedFrameProcessor(
                3, decisions, renderer, recovery::add, failures::add);
    }

    @Test
    public void exactTimestampDecisionRendersMatchingFrameOnly() {
        TestRawTexture first = accept(10, 20);
        TestRawTexture second = accept(11, 21);
        decisions.store(regional(11, 30));
        processor.processReady(timestamp(15));

        assertEquals(0, renderer.rendered.size());
        assertEquals(2, processor.queuedFrameCount());

        decisions.store(regional(10, 30));
        processor.processReady(timestamp(15));

        assertEquals(List.of(10L, 11L), renderer.rendered);
        assertEquals(1, first.closeCount.get());
        assertEquals(1, second.closeCount.get());
    }

    @Test
    public void missingDecisionWaitsUntilDeadlineThenShields() {
        TestRawTexture raw = accept(10, 20);

        processor.processReady(timestamp(19));
        assertEquals(1, processor.queuedFrameCount());
        assertEquals(0, raw.closeCount.get());

        processor.processReady(timestamp(20));
        assertEquals(List.of(10L), renderer.shielded);
        assertEquals(1, raw.closeCount.get());
        assertTrue(processor.requiresVerifiedRecovery());
    }

    @Test
    public void timeoutDiscardsEveryPreShieldFrameInsteadOfFlushingOnRecovery() {
        TestRawTexture expired = accept(10, 20);
        TestRawTexture later = accept(11, 30);
        decisions.store(regional(11, 40));

        processor.processReady(timestamp(20));

        assertEquals(List.of(10L, 11L), renderer.shielded);
        assertEquals(1, expired.closeCount.get());
        assertEquals(1, later.closeCount.get());
        assertTrue(renderer.rendered.isEmpty());
        assertTrue(processor.requiresVerifiedRecovery());
    }

    @Test
    public void futureDecisionDoesNotFallBackToWrongTimestamp() {
        TestRawTexture raw = accept(10, 20);
        decisions.store(regional(11, 30));

        processor.processReady(timestamp(20));

        assertEquals(List.of(10L), renderer.shielded);
        assertEquals(1, raw.closeCount.get());
        assertEquals(0, renderer.rendered.size());
    }

    @Test
    public void reachingCapacityShieldsAndReleasesAllExactlyOnce() {
        TestRawTexture first = accept(10, 30);
        TestRawTexture second = accept(11, 31);
        TestRawTexture pressure = accept(12, 32);

        assertEquals(List.of(10L, 11L, 12L), renderer.shielded);
        assertEquals(1, first.closeCount.get());
        assertEquals(1, second.closeCount.get());
        assertEquals(1, pressure.closeCount.get());
        assertEquals(0, processor.queuedFrameCount());
        assertTrue(processor.requiresVerifiedRecovery());
        assertEquals(List.of(SessionHealth.RecoveryState.UNSAFE), recovery);
    }

    @Test
    public void unsafeRecoveryShieldsNewFramesAndNeverFlushesOldOnVerification() {
        TestRawTexture first = accept(10, 30);
        TestRawTexture second = accept(11, 31);
        TestRawTexture pressure = accept(12, 32);
        TestRawTexture duringRecovery = accept(13, 33);
        decisions.store(regional(10, 40));
        decisions.store(regional(13, 40));

        processor.verifyRecovery();
        TestRawTexture afterRecovery = accept(14, 34);
        decisions.store(regional(14, 40));
        processor.processReady(timestamp(14));

        assertEquals(List.of(10L, 11L, 12L, 13L), renderer.shielded);
        assertEquals(List.of(14L), renderer.rendered);
        assertEquals(1, first.closeCount.get());
        assertEquals(1, second.closeCount.get());
        assertEquals(1, pressure.closeCount.get());
        assertEquals(1, duringRecovery.closeCount.get());
        assertEquals(1, afterRecovery.closeCount.get());
        assertEquals(List.of(SessionHealth.RecoveryState.UNSAFE,
                SessionHealth.RecoveryState.VERIFIED), recovery);
    }

    @Test
    public void rendererFailureDiscardsQueuedFramesAndLatchesUnsafe() {
        TestRawTexture first = accept(10, 30);
        TestRawTexture second = accept(11, 31);
        decisions.store(regional(10, 30));
        renderer.renderFailure = new IllegalStateException("GL failed");

        processor.processReady(timestamp(10));

        assertEquals(1, failures.size());
        assertEquals(List.of(11L), renderer.shielded);
        assertEquals(1, first.closeCount.get());
        assertEquals(1, second.closeCount.get());
        assertTrue(processor.requiresVerifiedRecovery());
    }

    @Test
    public void typedSafetyInvalidationShieldsAndRecoversWithoutComponentFailure() {
        TestRawTexture first = accept(10, 30);
        TestRawTexture second = accept(11, 31);

        processor.invalidateForSafety();

        assertEquals(List.of(10L, 11L), renderer.shielded);
        assertEquals(1, first.closeCount.get());
        assertEquals(1, second.closeCount.get());
        assertTrue(failures.isEmpty());
        assertEquals(List.of(SessionHealth.RecoveryState.UNSAFE), recovery);
        processor.verifyRecovery();
        assertEquals(List.of(SessionHealth.RecoveryState.UNSAFE,
                SessionHealth.RecoveryState.VERIFIED), recovery);
    }

    @Test
    public void closeReleasesQueuedFramesExactlyOnceWithoutRendering() {
        TestRawTexture first = accept(10, 30);
        TestRawTexture second = accept(11, 31);

        processor.close();
        processor.close();

        assertEquals(1, first.closeCount.get());
        assertEquals(1, second.closeCount.get());
        assertTrue(renderer.rendered.isEmpty());
        assertTrue(renderer.shielded.isEmpty());
    }

    @Test
    public void invalidDeadlineAndClosedProcessorReleaseOwnership() {
        TestRawTexture invalid = new TestRawTexture(10);
        assertThrows(IllegalArgumentException.class, () -> processor.accept(
                invalid, timestamp(10), timestamp(9)));
        assertEquals(1, invalid.closeCount.get());

        processor.close();
        TestRawTexture afterClose = new TestRawTexture(11);
        processor.accept(afterClose, timestamp(11), timestamp(20));
        assertEquals(1, afterClose.closeCount.get());
    }

    @Test
    public void unsafeTransformForcesShieldEvenWithFreshRegionalDecision() {
        TestRawTexture raw = new TestRawTexture(10);
        processor.accept(raw, timestamp(10), timestamp(20), false);
        decisions.store(regional(10, 30));

        processor.processReady(timestamp(10));

        assertEquals(List.of(10L), renderer.shielded);
        assertEquals(1, raw.closeCount.get());
    }

    @Test
    public void recoveryCannotBeVerifiedWhileFramesRemainQueued() {
        accept(10, 30);

        assertThrows(IllegalStateException.class, processor::verifyRecovery);
    }

    private TestRawTexture accept(long timestamp, long deadline) {
        TestRawTexture raw = new TestRawTexture(timestamp);
        processor.accept(raw, timestamp(timestamp), timestamp(deadline));
        return raw;
    }

    private static FramePrivacyDecision regional(long timestamp, long expiresAt) {
        return FramePrivacyDecision.regionalSafe(
                timestamp(timestamp), List.of(), FramePrivacyDecision.Basis.FRESH,
                timestamp(expiresAt));
    }

    private static FrameTimestamp timestamp(long nanos) {
        return FrameTimestamp.ofNanos(nanos);
    }

    private static final class TestRawTexture implements RawTextureHandle {
        private final long timestamp;
        private final AtomicInteger closeCount = new AtomicInteger();

        private TestRawTexture(long timestamp) {
            this.timestamp = timestamp;
        }

        @Override
        public void close() {
            if (closeCount.incrementAndGet() > 1) {
                throw new IllegalStateException("Raw handle closed more than once");
            }
        }
    }

    private static final class RecordingRenderer implements RedactionRenderer {
        private final List<Long> rendered = new ArrayList<>();
        private final List<Long> shielded = new ArrayList<>();
        private RuntimeException renderFailure;

        @Override
        public SanitizedRender render(
                RawTextureHandle rawTexture, FramePrivacyDecision privacyDecision) {
            if (renderFailure != null) {
                throw renderFailure;
            }
            TestRawTexture raw = (TestRawTexture) rawTexture;
            if (privacyDecision.status() == FramePrivacyDecision.Status.FULL_SHIELD) {
                throw new AssertionError("Full shield must not sample a raw texture");
            } else {
                rendered.add(raw.timestamp);
            }
            return new SanitizedRender(privacyDecision.timestamp());
        }

        @Override
        public SanitizedRender renderShield(FramePrivacyDecision privacyDecision) {
            assertEquals(FramePrivacyDecision.Status.FULL_SHIELD, privacyDecision.status());
            shielded.add(privacyDecision.timestamp().nanos());
            return new SanitizedRender(privacyDecision.timestamp());
        }
    }
}
