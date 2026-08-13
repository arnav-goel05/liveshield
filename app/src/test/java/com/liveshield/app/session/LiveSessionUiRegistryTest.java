package com.liveshield.app.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.liveshield.privacy.session.SessionState;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

public final class LiveSessionUiRegistryTest {
    @After
    public void clearRegistry() {
        LiveSessionUiRegistry.resetForTest();
    }

    @Test
    public void recreationRebindGetsCurrentPayloadFreeStateAndStopIsIdempotent() {
        AtomicInteger stops = new AtomicInteger();
        List<SessionState> first = new ArrayList<>();
        LiveSessionUiRegistry.Observer firstObserver = first::add;
        LiveSessionUiRegistry.activate(SessionState.LIVE, stops::incrementAndGet);
        LiveSessionUiRegistry.bind(firstObserver);
        LiveSessionUiRegistry.update(SessionState.SHIELDING);
        LiveSessionUiRegistry.unbind(firstObserver);

        List<SessionState> recreated = new ArrayList<>();
        LiveSessionUiRegistry.bind(recreated::add);
        LiveSessionUiRegistry.requestStop();
        LiveSessionUiRegistry.requestStop();

        assertEquals(List.of(SessionState.LIVE, SessionState.SHIELDING), first);
        assertEquals(List.of(SessionState.SHIELDING), recreated);
        assertEquals(1, stops.get());
    }

    @Test
    public void terminalStateClearsStopOwnershipButRemainsVisibleAfterRecreate() {
        AtomicInteger stops = new AtomicInteger();
        LiveSessionUiRegistry.activate(SessionState.LIVE, stops::incrementAndGet);
        LiveSessionUiRegistry.update(SessionState.FAILED);
        LiveSessionUiRegistry.requestStop();
        List<SessionState> recreated = new ArrayList<>();
        LiveSessionUiRegistry.bind(recreated::add);

        assertEquals(0, stops.get());
        assertEquals(List.of(SessionState.FAILED), recreated);
    }

    @Test
    public void emptyProcessRegistryCannotRestoreALiveLookingSavedMode() {
        List<SessionState> observed = new ArrayList<>();

        boolean active = LiveSessionUiRegistry.bind(observed::add);

        assertFalse(active);
        assertEquals(List.of(), observed);
    }
}
