package com.liveshield.app.session;

import android.content.Intent;
import com.liveshield.app.setup.SetupActivity;
import com.liveshield.privacy.session.SessionState;
import java.util.Objects;

/** Launches private status controls while leaving all protected media in the session owner. */
final class ProductionLiveSessionUi implements LiveSessionCoordinator.SessionUiPort {
    private final SetupActivity setupActivity;
    private final SetupSessionFactory.TerminalListener terminalListener;
    private boolean terminalDelivered;

    ProductionLiveSessionUi(SetupActivity setupActivity) {
        this(setupActivity, SetupSessionFactory.TerminalListener.NO_OP);
    }

    ProductionLiveSessionUi(
            SetupActivity setupActivity,
            SetupSessionFactory.TerminalListener terminalListener) {
        this.setupActivity = Objects.requireNonNull(setupActivity, "setupActivity");
        this.terminalListener = Objects.requireNonNull(terminalListener, "terminalListener");
    }

    @Override
    public void onSessionStarted(SessionState state, Runnable stopAction) {
        LiveSessionUiRegistry.activate(state, stopAction);
        setupActivity.startActivity(new Intent(setupActivity, LiveActivity.class));
    }

    @Override
    public void onSessionStateChanged(SessionState state) {
        LiveSessionUiRegistry.update(state);
        if (!terminalDelivered
                && (state == SessionState.ENDED || state == SessionState.FAILED)) {
            terminalDelivered = true;
            terminalListener.onTerminated(state);
        }
    }

    @Override
    public void onPublisherHealthChanged(LiveSessionCoordinator.PublisherHealth health) {
        LiveSessionUiRegistry.updatePublisherHealth(health);
    }
}
