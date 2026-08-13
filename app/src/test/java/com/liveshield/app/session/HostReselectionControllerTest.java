package com.liveshield.app.session;

import static org.junit.Assert.assertEquals;

import com.liveshield.privacy.host.HostSelectionResult;
import java.util.OptionalLong;
import org.junit.Test;

public final class HostReselectionControllerTest {
    @Test
    public void continuityLossRequiresExplicitReselection() {
        HostReselectionController controller = new HostReselectionController();

        assertEquals(HostReselectionController.State.REQUIRED,
                controller.onContinuityLost());
    }

    @Test
    public void staleAndAmbiguousRejectionsCannotClearRequirement() {
        HostReselectionController controller = requiredController();

        controller.onSelectionResult(rejected(HostSelectionResult.Status.STALE_FACE));
        assertEquals(HostReselectionController.State.REQUIRED, controller.state());
        controller.onSelectionResult(rejected(HostSelectionResult.Status.AMBIGUOUS));
        assertEquals(HostReselectionController.State.REQUIRED, controller.state());
    }

    @Test
    public void acceptedFreshExplicitSelectionClearsRequirement() {
        HostReselectionController controller = requiredController();

        controller.onSelectionResult(new HostSelectionResult(
                HostSelectionResult.Status.SELECTED, OptionalLong.of(9)));

        assertEquals(HostReselectionController.State.NOT_REQUIRED, controller.state());
    }

    @Test
    public void sessionResetClearsPayloadFreePromptState() {
        HostReselectionController controller = requiredController();

        controller.resetSession();

        assertEquals(HostReselectionController.State.NOT_REQUIRED, controller.state());
    }

    private static HostReselectionController requiredController() {
        HostReselectionController controller = new HostReselectionController();
        controller.onContinuityLost();
        return controller;
    }

    private static HostSelectionResult rejected(HostSelectionResult.Status status) {
        return new HostSelectionResult(status, OptionalLong.empty());
    }
}
