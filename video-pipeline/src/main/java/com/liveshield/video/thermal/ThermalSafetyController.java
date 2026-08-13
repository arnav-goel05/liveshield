package com.liveshield.video.thermal;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import androidx.annotation.RequiresApi;
import com.liveshield.privacy.session.SessionHealth;
import com.liveshield.privacy.telemetry.SafetyTelemetry;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Converts platform thermal callbacks into the typed state consumed by fail-private policy.
 *
 * <p>This class deliberately owns no recovery timing or hysteresis. Those rules belong to the
 * privacy policy engine, which evaluates this controller's latest typed state alongside freshness
 * and verified renderer recovery evidence.
 */
public final class ThermalSafetyController implements AutoCloseable {
    static final int PLATFORM_STATUS_NONE = 0;
    static final int PLATFORM_STATUS_LIGHT = 1;
    static final int PLATFORM_STATUS_MODERATE = 2;
    static final int PLATFORM_STATUS_SEVERE = 3;
    static final int PLATFORM_STATUS_CRITICAL = 4;
    static final int PLATFORM_STATUS_EMERGENCY = 5;
    static final int PLATFORM_STATUS_SHUTDOWN = 6;

    private final Object lock = new Object();
    private final ThermalStatusSource source;
    private final SafetyTelemetry telemetry;
    private final ThermalStateListener listener;
    private SessionHealth.ThermalState currentState = SessionHealth.ThermalState.SEVERE;
    private boolean hasObservedState;
    private boolean closed;
    private boolean sourceClosed;

    /**
     * Starts platform observation. Listener calls run on {@code callbackExecutor}.
     *
     * <p>Android versions before API 29 cannot expose platform thermal status, so they begin in the
     * conservative {@link SessionHealth.ThermalState#WARNING} degraded state.
     */
    public ThermalSafetyController(
            Context context,
            Executor callbackExecutor,
            SafetyTelemetry telemetry,
            ThermalStateListener listener) {
        this(createPlatformSource(context, callbackExecutor), telemetry, listener);
    }

    ThermalSafetyController(
            ThermalStatusSource source,
            SafetyTelemetry telemetry,
            ThermalStateListener listener) {
        this.source = Objects.requireNonNull(source, "source");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.listener = Objects.requireNonNull(listener, "listener");
        try {
            source.start(this::onPlatformStatus);
        } catch (RuntimeException registrationFailure) {
            closeSourceAfterFailedStart();
            publish(SessionHealth.ThermalState.SEVERE);
        }
    }

    /** Returns the latest state; it is severe until the first trustworthy source observation. */
    public SessionHealth.ThermalState currentState() {
        synchronized (lock) {
            return currentState;
        }
    }

    /** Applies the latest typed state to the health evidence consumed by privacy policy. */
    public SessionHealth.Builder applyTo(SessionHealth.Builder builder) {
        return Objects.requireNonNull(builder, "builder").thermalState(currentState());
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            if (sourceClosed) {
                return;
            }
            sourceClosed = true;
        }
        source.close();
    }

    static SessionHealth.ThermalState mapPlatformStatus(int status) {
        if (status == PLATFORM_STATUS_NONE || status == PLATFORM_STATUS_LIGHT) {
            return SessionHealth.ThermalState.NOMINAL;
        }
        if (status == PLATFORM_STATUS_MODERATE) {
            return SessionHealth.ThermalState.WARNING;
        }
        return SessionHealth.ThermalState.SEVERE;
    }

    private static ThermalStatusSource createPlatformSource(
            Context context, Executor callbackExecutor) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return new UnsupportedThermalStatusSource(callbackExecutor);
        }
        PowerManager powerManager = context.getSystemService(PowerManager.class);
        if (powerManager == null) {
            return new UnavailableThermalStatusSource(callbackExecutor);
        }
        return new Api29ThermalStatusSource(powerManager, callbackExecutor);
    }

    private void onPlatformStatus(int status) {
        publish(mapPlatformStatus(status));
    }

    private void publish(SessionHealth.ThermalState state) {
        synchronized (lock) {
            if (closed || (hasObservedState && currentState == state)) {
                return;
            }
            currentState = state;
            hasObservedState = true;
        }
        telemetry.recordThermalState(state);
        listener.onThermalStateChanged(state);
    }

    private void closeSourceAfterFailedStart() {
        synchronized (lock) {
            sourceClosed = true;
        }
        try {
            source.close();
        } catch (RuntimeException ignored) {
            // Registration already failed; the controller remains severe regardless of cleanup.
        }
    }

    /** Receives only a typed severity enum, never a frame, temperature, or device identifier. */
    public interface ThermalStateListener {
        void onThermalStateChanged(SessionHealth.ThermalState state);
    }

    interface PlatformStatusListener {
        void onPlatformStatus(int status);
    }

    interface ThermalStatusSource extends AutoCloseable {
        void start(PlatformStatusListener listener);

        @Override
        void close();
    }

    private static final class UnsupportedThermalStatusSource implements ThermalStatusSource {
        private final Executor executor;

        private UnsupportedThermalStatusSource(Executor executor) {
            this.executor = executor;
        }

        @Override
        public void start(PlatformStatusListener listener) {
            executor.execute(() -> listener.onPlatformStatus(PLATFORM_STATUS_MODERATE));
        }

        @Override
        public void close() {
            // API 23-28 have no thermal listener to unregister.
        }
    }

    private static final class UnavailableThermalStatusSource implements ThermalStatusSource {
        private final Executor executor;

        private UnavailableThermalStatusSource(Executor executor) {
            this.executor = executor;
        }

        @Override
        public void start(PlatformStatusListener listener) {
            executor.execute(() -> listener.onPlatformStatus(PLATFORM_STATUS_SEVERE));
        }

        @Override
        public void close() {
            // No platform service was available to register.
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static final class Api29ThermalStatusSource implements ThermalStatusSource {
        private final PowerManager powerManager;
        private final Executor executor;
        private PowerManager.OnThermalStatusChangedListener platformListener;

        private Api29ThermalStatusSource(PowerManager powerManager, Executor executor) {
            this.powerManager = powerManager;
            this.executor = executor;
        }

        @Override
        public synchronized void start(PlatformStatusListener listener) {
            if (platformListener != null) {
                throw new IllegalStateException("thermal source already started");
            }
            PowerManager.OnThermalStatusChangedListener candidate =
                    listener::onPlatformStatus;
            powerManager.addThermalStatusListener(executor, candidate);
            platformListener = candidate;
            int initialStatus = powerManager.getCurrentThermalStatus();
            executor.execute(() -> listener.onPlatformStatus(initialStatus));
        }

        @Override
        public synchronized void close() {
            if (platformListener == null) {
                return;
            }
            powerManager.removeThermalStatusListener(platformListener);
            platformListener = null;
        }
    }
}
