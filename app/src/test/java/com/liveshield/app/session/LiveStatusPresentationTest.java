package com.liveshield.app.session;

import static org.junit.Assert.assertEquals;

import com.liveshield.privacy.session.SessionState;
import org.junit.Test;

public final class LiveStatusPresentationTest {
    @Test
    public void mapsEverySessionStateToAnExplicitPrivateStatus() {
        assertEquals(LiveStatusPresentation.Mode.NOT_LIVE,
                LiveStatusPresentation.from(SessionState.SETUP));
        assertEquals(LiveStatusPresentation.Mode.NOT_LIVE,
                LiveStatusPresentation.from(SessionState.READY));
        assertEquals(LiveStatusPresentation.Mode.HEALTHY,
                LiveStatusPresentation.from(SessionState.LIVE));
        assertEquals(LiveStatusPresentation.Mode.DEGRADED,
                LiveStatusPresentation.from(SessionState.DEGRADED));
        assertEquals(LiveStatusPresentation.Mode.SHIELDING,
                LiveStatusPresentation.from(SessionState.SHIELDING));
        assertEquals(LiveStatusPresentation.Mode.STOPPED,
                LiveStatusPresentation.from(SessionState.STOPPING));
        assertEquals(LiveStatusPresentation.Mode.STOPPED,
                LiveStatusPresentation.from(SessionState.ENDED));
        assertEquals(LiveStatusPresentation.Mode.FAILED,
                LiveStatusPresentation.from(SessionState.FAILED));
    }
}
