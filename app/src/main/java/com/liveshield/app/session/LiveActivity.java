package com.liveshield.app.session;

import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import com.liveshield.app.R;
import com.liveshield.privacy.session.SessionState;
import java.util.Objects;

/**
 * Private creator controls with no camera, render, encode, audio, or network ownership.
 */
public final class LiveActivity extends ComponentActivity {
    private TextView statusLabel;
    private TextView statusDetail;
    private TextView publisherStatus;
    private Button stopButton;
    private LiveStatusPresentation.Mode mode = LiveStatusPresentation.Mode.NOT_LIVE;
    private LiveUiListener listener = LiveUiListener.NO_OP;
    private volatile boolean destroyed;
    private final LiveSessionUiRegistry.Observer productionObserver = this::showSessionState;
    private final LiveSessionUiRegistry.PublisherObserver publisherObserver =
            this::showPublisherHealth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_live);
        statusLabel = findViewById(R.id.live_status_label);
        statusDetail = findViewById(R.id.live_status_detail);
        publisherStatus = findViewById(R.id.live_publisher_status);
        stopButton = findViewById(R.id.stop_live_session);
        stopButton.setOnClickListener(ignored -> {
            LiveSessionUiRegistry.requestStop();
            listener.onStopRequested();
        });
        // A saved live-looking label cannot prove the process-local session still owns resources.
        // Ordinary Activity recreation is restored from LiveSessionUiRegistry during onStart.
        mode = LiveStatusPresentation.Mode.NOT_LIVE;
        render(mode);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!LiveSessionUiRegistry.bind(productionObserver)) {
            render(LiveStatusPresentation.Mode.NOT_LIVE);
        }
        LiveSessionUiRegistry.bindPublisher(publisherObserver);
    }

    @Override
    protected void onStop() {
        LiveSessionUiRegistry.unbind(productionObserver);
        LiveSessionUiRegistry.unbindPublisher(publisherObserver);
        super.onStop();
    }

    /**
     * Accepts only non-sensitive lifecycle state and safely marshals updates to the main thread.
     */
    public void showSessionState(SessionState state) {
        LiveStatusPresentation.Mode next = LiveStatusPresentation.from(state);
        if (destroyed) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            render(next);
        } else {
            runOnUiThread(() -> {
                if (!destroyed) {
                    render(next);
                }
            });
        }
    }

    void showPublisherHealth(LiveSessionCoordinator.PublisherHealth health) {
        if (destroyed) {
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> showPublisherHealth(health));
            return;
        }
        int detail = switch (health.state()) {
            case UNCONFIGURED, CONFIGURED -> R.string.live_publisher_not_active;
            case CONNECTING -> R.string.live_publisher_connecting;
            case PUBLISHING -> health.freshMediaReady()
                    ? R.string.live_publisher_confirmed
                    : R.string.live_publisher_waiting_fresh_media;
            case RECONNECTING -> health.failure()
                    == LiveSessionCoordinator.PublisherFailure.CONGESTION
                    ? R.string.live_publisher_congestion
                    : R.string.live_publisher_reconnecting;
            case FAILED -> switch (health.failure()) {
                case AUTHENTICATION -> R.string.live_publisher_auth_failed;
                case QUEUE -> R.string.live_publisher_queue_failed;
                default -> R.string.live_publisher_failed;
            };
            case STOPPED -> R.string.live_publisher_stopped;
        };
        publisherStatus.setText(detail);
    }

    /**
     * Installs callbacks without giving this status Activity ownership of media resources.
     */
    public void setLiveUiListener(LiveUiListener newListener) {
        listener = newListener == null ? LiveUiListener.NO_OP : newListener;
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        listener = LiveUiListener.NO_OP;
        super.onDestroy();
    }

    private void render(LiveStatusPresentation.Mode next) {
        boolean stateChanged = mode != next;
        mode = Objects.requireNonNull(next, "next");
        View statusCard = findViewById(R.id.live_status_card);
        if (!stateChanged && statusCard.getContentDescription() != null) {
            return;
        }
        int label;
        int detail;
        int indicator;
        boolean canStop;
        switch (next) {
            case NOT_LIVE -> {
                label = R.string.live_status_not_live_label;
                detail = R.string.live_status_not_live_detail;
                indicator = R.string.live_status_indicator_inactive;
                canStop = false;
            }
            case HEALTHY -> {
                label = R.string.live_status_healthy_label;
                detail = R.string.live_status_healthy_detail;
                indicator = R.string.live_status_indicator_healthy;
                canStop = true;
            }
            case DEGRADED -> {
                label = R.string.live_status_degraded_label;
                detail = R.string.live_status_degraded_detail;
                indicator = R.string.live_status_indicator_degraded;
                canStop = true;
            }
            case SHIELDING -> {
                label = R.string.live_status_shielding_label;
                detail = R.string.live_status_shielding_detail;
                indicator = R.string.live_status_indicator_shielding;
                canStop = true;
            }
            case STOPPED -> {
                label = R.string.live_status_stopped_label;
                detail = R.string.live_status_stopped_detail;
                indicator = R.string.live_status_indicator_inactive;
                canStop = false;
            }
            case FAILED -> {
                label = R.string.live_status_failed_label;
                detail = R.string.live_status_failed_detail;
                indicator = R.string.live_status_indicator_failed;
                canStop = false;
            }
            default -> throw new IllegalStateException("Unsupported private status mode");
        }
        TextView statusIndicator = findViewById(R.id.live_status_indicator);
        String spokenStatus = getString(label) + ". " + getString(detail);
        statusIndicator.setText(indicator);
        statusLabel.setText(label);
        statusDetail.setText(detail);
        stopButton.setEnabled(canStop);
        statusCard.setContentDescription(spokenStatus);
    }

    @FunctionalInterface
    public interface LiveUiListener {
        LiveUiListener NO_OP = () -> { };

        void onStopRequested();
    }
}
