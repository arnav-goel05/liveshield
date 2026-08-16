package com.liveshield.app.session;

import com.liveshield.transport.EncodedAccessUnit;
import com.liveshield.transport.EncodedAccessUnitSink;
import com.liveshield.transport.StreamSessionController;
import com.liveshield.transport.destination.StreamDestination;
import java.util.Objects;

/**
 * Session-owned, video-only publication adapter.
 *
 * <p>Only copied sanitized H.264 values enter this class. Destination credentials remain owned by
 * the transport controller and are never represented in coordinator health.</p>
 */
final class SessionPublicationPort
        implements LiveSessionCoordinator.PublicationPort, EncodedAccessUnitSink {
    static final long DEFAULT_MAX_QUEUE_BYTES = 8L * 1024L * 1024L;

    private final ControllerFactory controllerFactory;
    private final StreamSessionController.IdrRequester idrRequester;
    private StreamDestination destination;
    private StreamSessionController controller;
    private EncodedAccessUnit latestConfiguration;
    private LiveSessionCoordinator.PublisherHealth lastHealth =
            LiveSessionCoordinator.PublisherHealth.unconfigured();
    private LiveSessionCoordinator.PublisherHealth deliveredHealth;
    private LiveSessionCoordinator.PublisherHealthListener healthListener =
            LiveSessionCoordinator.PublisherHealthListener.NO_OP;
    private boolean acceptingUnits;
    private boolean closed;

    SessionPublicationPort(
            long maxQueueBytes,
            int width,
            int height,
            int framesPerSecond,
            StreamSessionController.IdrRequester idrRequester) {
        this(
                (destination, requester, listener) -> new StreamSessionController(
                        destination,
                        maxQueueBytes,
                        width,
                        height,
                        framesPerSecond,
                        requester,
                        listener),
                idrRequester);
    }

    SessionPublicationPort(
            ControllerFactory controllerFactory,
            StreamSessionController.IdrRequester idrRequester) {
        this.controllerFactory = Objects.requireNonNull(controllerFactory, "controllerFactory");
        this.idrRequester = Objects.requireNonNull(idrRequester, "idrRequester");
    }

    @Override
    public synchronized boolean isConfigured() {
        return !closed && destination != null && controller == null;
    }

    @Override
    public synchronized void configure(StreamDestination newDestination) {
        Objects.requireNonNull(newDestination, "destination");
        if (closed || controller != null) {
            newDestination.close();
            throw new IllegalStateException("Publication cannot be configured in this state");
        }
        if (destination != null) {
            destination.close();
        }
        destination = newDestination;
        lastHealth = new LiveSessionCoordinator.PublisherHealth(
                LiveSessionCoordinator.PublisherState.CONFIGURED,
                LiveSessionCoordinator.PublisherFailure.NONE,
                0,
                0L);
        notifyHealth();
    }

    @Override
    public synchronized void start() {
        ensureOpen();
        if (destination == null || controller != null) {
            throw new IllegalStateException("A fresh destination is required before publication");
        }
        controller = controllerFactory.create(destination, idrRequester, this::onControllerHealth);
        controller.connect();
        refreshHealth();
        if (controller.health().state() == StreamSessionController.State.FAILED) {
            acceptingUnits = false;
            throw new IllegalStateException("Video publication could not start");
        }
        acceptingUnits = true;
        if (latestConfiguration != null) {
            controller.onAccessUnit(latestConfiguration);
        }
        idrRequester.requestIdrFrame();
        refreshHealth();
    }

    @Override
    public synchronized void onAccessUnit(EncodedAccessUnit accessUnit) {
        EncodedAccessUnit unit = Objects.requireNonNull(accessUnit, "accessUnit");
        if (unit.flags().contains(EncodedAccessUnit.Flag.CODEC_CONFIGURATION)) {
            latestConfiguration = unit;
        }
        if (!acceptingUnits || controller == null) {
            return;
        }
        controller.onAccessUnit(unit);
        refreshHealth();
        StreamSessionController.State state = controller.health().state();
        if (state == StreamSessionController.State.FAILED
                || state == StreamSessionController.State.STOPPED
                || state == StreamSessionController.State.CLOSED) {
            acceptingUnits = false;
        }
    }

    @Override
    public synchronized LiveSessionCoordinator.PublisherHealth health() {
        refreshHealth();
        return lastHealth;
    }

    @Override
    public synchronized void onNetworkDisconnected() {
        if (acceptingUnits && controller != null) {
            controller.onNetworkDisconnected();
            refreshHealth();
        }
    }

    @Override
    public synchronized void onCongestion() {
        if (acceptingUnits && controller != null) {
            controller.onCongestionDetected();
            refreshHealth();
        }
    }

    @Override
    public synchronized void setHealthListener(
            LiveSessionCoordinator.PublisherHealthListener listener) {
        healthListener = Objects.requireNonNull(listener, "listener");
        deliveredHealth = lastHealth;
        healthListener.onHealthChanged(lastHealth);
    }

    @Override
    public synchronized void stop() {
        acceptingUnits = false;
        latestConfiguration = null;
        if (controller != null) {
            controller.stop();
            refreshHealth();
        } else if (destination != null) {
            destination.close();
            lastHealth = new LiveSessionCoordinator.PublisherHealth(
                    LiveSessionCoordinator.PublisherState.STOPPED,
                    LiveSessionCoordinator.PublisherFailure.NONE,
                    0,
                    0L);
            notifyHealth();
        }
        destination = null;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        stop();
        if (controller != null) {
            controller.close();
        }
        controller = null;
        closed = true;
    }

    private void refreshHealth() {
        if (controller == null) {
            return;
        }
        StreamSessionController.Health health = controller.health();
        lastHealth = new LiveSessionCoordinator.PublisherHealth(
                mapState(health.state()),
                mapFailure(health.failureCode()),
                health.queuedUnits(),
                health.droppedUnits(),
                health.freshMediaReady());
        notifyHealth();
    }

    private synchronized void onControllerHealth(StreamSessionController.Health health) {
        boolean enteredReconnect = health.state() == StreamSessionController.State.RECONNECTING
                && lastHealth.state() != LiveSessionCoordinator.PublisherState.RECONNECTING;
        lastHealth = new LiveSessionCoordinator.PublisherHealth(
                mapState(health.state()),
                mapFailure(health.failureCode()),
                health.queuedUnits(),
                health.droppedUnits(),
                health.freshMediaReady());
        if (health.state() == StreamSessionController.State.FAILED
                || health.state() == StreamSessionController.State.STOPPED
                || health.state() == StreamSessionController.State.CLOSED) {
            acceptingUnits = false;
        }
        notifyHealth();
        if (enteredReconnect) {
            // Re-seed the freshly cleared queue exactly once per reconnect epoch. This path also
            // covers disconnects reported directly by the RTMP client, not only explicit calls.
            replayConfigurationForReconnect();
            refreshHealth();
        }
    }

    private void notifyHealth() {
        if (!lastHealth.equals(deliveredHealth)) {
            deliveredHealth = lastHealth;
            healthListener.onHealthChanged(lastHealth);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Publication port is closed");
        }
    }

    private void replayConfigurationForReconnect() {
        if (controller.health().state() == StreamSessionController.State.FAILED) {
            acceptingUnits = false;
        } else if (latestConfiguration != null) {
            controller.onAccessUnit(latestConfiguration);
        }
    }

    private static LiveSessionCoordinator.PublisherState mapState(
            StreamSessionController.State state) {
        return switch (state) {
            case IDLE -> LiveSessionCoordinator.PublisherState.CONFIGURED;
            case CONNECTING -> LiveSessionCoordinator.PublisherState.CONNECTING;
            case PUBLISHING -> LiveSessionCoordinator.PublisherState.PUBLISHING;
            case RECONNECTING -> LiveSessionCoordinator.PublisherState.RECONNECTING;
            case FAILED -> LiveSessionCoordinator.PublisherState.FAILED;
            case STOPPED, CLOSED -> LiveSessionCoordinator.PublisherState.STOPPED;
        };
    }

    private static LiveSessionCoordinator.PublisherFailure mapFailure(
            StreamSessionController.FailureCode failure) {
        return switch (failure) {
            case NONE -> LiveSessionCoordinator.PublisherFailure.NONE;
            case CONNECT_FAILED -> LiveSessionCoordinator.PublisherFailure.CONNECTION;
            case AUTHENTICATION_FAILED ->
                    LiveSessionCoordinator.PublisherFailure.AUTHENTICATION;
            case NETWORK_DISCONNECTED -> LiveSessionCoordinator.PublisherFailure.NETWORK;
            case CONGESTION -> LiveSessionCoordinator.PublisherFailure.CONGESTION;
            case QUEUE_OVERFLOW, INVALID_PTS_ORDER, QUEUE_STOPPED ->
                    LiveSessionCoordinator.PublisherFailure.QUEUE;
            case PUBLISH_FAILED -> LiveSessionCoordinator.PublisherFailure.PUBLICATION;
        };
    }

    @FunctionalInterface
    interface ControllerFactory {
        StreamSessionController create(
                StreamDestination destination,
                StreamSessionController.IdrRequester idrRequester,
                StreamSessionController.HealthListener healthListener);
    }
}
