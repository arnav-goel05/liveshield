package com.liveshield.privacy.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.liveshield.privacy.host.HostSelectionResult;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.NormalizedRect;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.Test;

public final class LiveSessionStateMachineTest {
    @Test
    public void readinessIsBlockedUntilHostAndEveryRequiredComponentAreReady() {
        LiveSessionStateMachine machine = machine();
        machine.acceptHostSelection(selected(7));
        machine.markComponentReady(LiveSessionStateMachine.RequiredComponent.CAMERA);
        machine.markComponentReady(LiveSessionStateMachine.RequiredComponent.ANALYSIS);
        machine.markComponentReady(LiveSessionStateMachine.RequiredComponent.RENDERER);

        assertFalse(machine.tryBecomeReady(FrameTimestamp.ofNanos(10)));
        assertEquals(SessionState.SETUP, machine.snapshot().state());

        machine.markComponentReady(LiveSessionStateMachine.RequiredComponent.ENCODER);
        assertTrue(machine.tryBecomeReady(FrameTimestamp.ofNanos(11)));
        assertEquals(SessionState.READY, machine.snapshot().state());
    }

    @Test
    public void rejectedHostSelectionCannotSatisfyReadiness() {
        LiveSessionStateMachine machine = machine();
        markAllReady(machine);

        machine.acceptHostSelection(new HostSelectionResult(
                HostSelectionResult.Status.STALE_FACE, OptionalLong.empty()));

        assertFalse(machine.tryBecomeReady(FrameTimestamp.ofNanos(10)));
        assertFalse(machine.snapshot().hostTrackId().isPresent());
    }

    @Test
    public void startIsRejectedBeforeReadyAndAllowsOnlyRenderedOutputWhenLive() {
        LiveSessionStateMachine machine = machine();

        assertThrows(IllegalStateException.class,
                () -> machine.start(FrameTimestamp.ofNanos(10)));
        assertFalse(machine.mayEmitRenderedOutput());

        machine.acceptHostSelection(selected(7));
        markAllReady(machine);
        machine.tryBecomeReady(FrameTimestamp.ofNanos(11));
        machine.start(FrameTimestamp.ofNanos(12));

        assertEquals(SessionState.LIVE, machine.snapshot().state());
        assertTrue(machine.mayEmitRenderedOutput());
    }

    @Test
    public void componentLossDuringLiveImmediatelyEntersShielding() {
        LiveSessionStateMachine machine = startedMachine();

        machine.markComponentUnavailable(
                LiveSessionStateMachine.RequiredComponent.ANALYSIS);

        assertEquals(SessionState.SHIELDING, machine.snapshot().state());
        assertTrue(machine.mayEmitRenderedOutput());
    }

    @Test
    public void typedWarningDegradesSevereShieldsAndVerifiedFreshStateCanRecover() {
        LiveSessionStateMachine machine = startedMachine();

        machine.enterDegraded(FrameTimestamp.ofNanos(12));
        assertEquals(SessionState.DEGRADED, machine.snapshot().state());
        machine.recoverDegraded(FrameTimestamp.ofNanos(13));
        assertEquals(SessionState.LIVE, machine.snapshot().state());
        machine.enterDegraded(FrameTimestamp.ofNanos(14));
        machine.enterShielding(FrameTimestamp.ofNanos(15));
        assertEquals(SessionState.SHIELDING, machine.snapshot().state());
        assertTrue(machine.canResumeLive());
        machine.resumeLive(FrameTimestamp.ofNanos(16));
        assertEquals(SessionState.LIVE, machine.snapshot().state());
    }

    @Test
    public void rendererOrEncoderLossBlocksEvenShieldOutput() {
        LiveSessionStateMachine machine = startedMachine();

        machine.markComponentUnavailable(
                LiveSessionStateMachine.RequiredComponent.RENDERER);

        assertEquals(SessionState.SHIELDING, machine.snapshot().state());
        assertFalse(machine.mayEmitRenderedOutput());
    }

    @Test
    public void hostRevocationDuringLiveEntersShieldingAndRequiresExplicitReselection() {
        LiveSessionStateMachine machine = startedMachine();

        machine.revokeHost();

        assertEquals(SessionState.SHIELDING, machine.snapshot().state());
        assertFalse(machine.snapshot().hostTrackId().isPresent());
        assertFalse(machine.canResumeLive());

        machine.acceptHostSelection(selected(9));
        assertTrue(machine.canResumeLive());
        machine.resumeLive(FrameTimestamp.ofNanos(20));
        assertEquals(SessionState.LIVE, machine.snapshot().state());
    }

    @Test
    public void safeStopClearsSessionOnlyHostAndSensitiveConfiguration() {
        LiveSessionStateMachine machine = startedMachine();

        machine.stop(FrameTimestamp.ofNanos(20));

        assertEquals(SessionState.ENDED, machine.snapshot().state());
        assertFalse(machine.snapshot().hostTrackId().isPresent());
        assertTrue(machine.snapshot().watchlist().isEmpty());
        assertTrue(machine.snapshot().privacyZones().isEmpty());
        assertFalse(machine.mayEmitRenderedOutput());
        assertThrows(IllegalStateException.class,
                () -> machine.start(FrameTimestamp.ofNanos(21)));
    }

    private static LiveSessionStateMachine startedMachine() {
        LiveSessionStateMachine machine = machine();
        machine.acceptHostSelection(selected(7));
        markAllReady(machine);
        machine.tryBecomeReady(FrameTimestamp.ofNanos(10));
        machine.start(FrameTimestamp.ofNanos(11));
        return machine;
    }

    private static LiveSessionStateMachine machine() {
        return new LiveSessionStateMachine(LiveSession.setup(
                "state-machine-test",
                Set.of("fictional name"),
                List.of(new NormalizedRect(0.1, 0.1, 0.2, 0.2))));
    }

    private static HostSelectionResult selected(long trackId) {
        return new HostSelectionResult(
                HostSelectionResult.Status.SELECTED, OptionalLong.of(trackId));
    }

    private static void markAllReady(LiveSessionStateMachine machine) {
        for (LiveSessionStateMachine.RequiredComponent component
                : LiveSessionStateMachine.RequiredComponent.values()) {
            machine.markComponentReady(component);
        }
    }
}
