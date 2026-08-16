package com.liveshield.transport;

import com.liveshield.transport.destination.StreamDestination;
import com.liveshield.transport.rtmp.RtmpStreamPublisher;
import java.util.Objects;

/** Owns one destination-neutral sanitized video publication session. */
public final class StreamSessionController implements EncodedAccessUnitSink {
    private final StreamDestination destination;
    private final DelayedAccessUnitQueue queue;
    private final Publisher publisher;
    private final NanoClock clock;
    private final IdrRequester idrRequester;
    private final HealthListener healthListener;
    private State state = State.IDLE;
    private FailureCode failureCode = FailureCode.NONE;
    private long droppedUnits;
    private long idrRequests;
    private long releasedUnits;
    private long releaseDelayNanosTotal;
    private long minimumReleaseDelayNanos = Long.MAX_VALUE;
    private long maximumReleaseDelayNanos;
    private long offeredConfigurationUnits;
    private long offeredKeyFrameUnits;
    private long offeredMediaUnits;
    private long releasedConfigurationUnits;
    private long releasedKeyFrameUnits;
    private long releasedMediaUnits;
    private long firstPublisherCallNanos = -1L;
    private long lastPublisherCallNanos = -1L;
    private boolean configurationPublishedInEpoch;
    private boolean freshMediaReady;

    public StreamSessionController(
            StreamDestination destination,
            long maxQueueBytes,
            int width,
            int height,
            int framesPerSecond,
            IdrRequester idrRequester) {
        this(
                destination,
                new DelayedAccessUnitQueue(maxQueueBytes),
                new RtmpPublisherAdapter(
                        new RtmpStreamPublisher(width, height, framesPerSecond)),
                System::nanoTime,
                idrRequester,
                HealthListener.NO_OP);
    }

    public StreamSessionController(
            StreamDestination destination,
            long maxQueueBytes,
            int width,
            int height,
            int framesPerSecond,
            IdrRequester idrRequester,
            HealthListener healthListener) {
        this(
                destination,
                new DelayedAccessUnitQueue(maxQueueBytes),
                new RtmpPublisherAdapter(
                        new RtmpStreamPublisher(width, height, framesPerSecond)),
                System::nanoTime,
                idrRequester,
                healthListener);
    }

    StreamSessionController(
            StreamDestination destination,
            DelayedAccessUnitQueue queue,
            Publisher publisher,
            NanoClock clock,
            IdrRequester idrRequester) {
        this(destination, queue, publisher, clock, idrRequester, HealthListener.NO_OP);
    }

    StreamSessionController(
            StreamDestination destination,
            DelayedAccessUnitQueue queue,
            Publisher publisher,
            NanoClock clock,
            IdrRequester idrRequester,
            HealthListener healthListener) {
        this.destination = Objects.requireNonNull(destination, "destination");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idrRequester = Objects.requireNonNull(idrRequester, "idrRequester");
        this.healthListener = Objects.requireNonNull(healthListener, "healthListener");
        publisher.setHealthListener(this::onPublisherHealthChanged);
    }

    public synchronized void connect() {
        requireState(State.IDLE);
        connectPublisher();
    }

    @Override
    public synchronized void onAccessUnit(EncodedAccessUnit accessUnit) {
        ensureActive();
        EncodedAccessUnit unit = Objects.requireNonNull(accessUnit, "accessUnit");
        // Release elapsed units before applying the two-second duration bound to the new unit.
        // Otherwise a normally timed frame just beyond the boundary could falsely overflow a
        // queue whose head is already eligible for publication.
        drainReadyUnits();
        if (state == State.FAILED) {
            notifyHealth();
            return;
        }
        recordOffered(unit);
        DelayedAccessUnitQueue.OfferResult result = queue.offer(unit);
        switch (result) {
            case ACCEPTED -> drainReadyUnits();
            case DROPPED_AWAITING_CONFIGURATION, DROPPED_AWAITING_KEY_FRAME -> droppedUnits++;
            case OVERFLOW_STOP_REQUIRED -> fail(FailureCode.QUEUE_OVERFLOW, true);
            case INVALID_ORDER_STOP_REQUIRED -> fail(FailureCode.INVALID_PTS_ORDER, true);
            case STOPPED -> fail(FailureCode.QUEUE_STOPPED, false);
            default -> throw new IllegalStateException("Unhandled queue result");
        }
        notifyHealth();
    }

    /** Advances connection/readiness and releases only units whose two-second delay elapsed. */
    public synchronized void tick() {
        ensureActive();
        drainReadyUnits();
        notifyHealth();
    }

    /** Clears congestion-affected units and begins a decoder-safe fresh publication sequence. */
    public synchronized void onCongestionDetected() {
        ensureActive();
        reconnect(FailureCode.CONGESTION);
    }

    /** Clears units from the disconnected session and begins a fresh connection. */
    public synchronized void onNetworkDisconnected() {
        ensureActive();
        reconnect(FailureCode.NETWORK_DISCONNECTED);
    }

    public synchronized Health health() {
        return new Health(
                state,
                failureCode,
                queue.queuedUnits(),
                queue.queuedBytes(),
                droppedUnits,
                idrRequests,
                releasedUnits,
                releasedUnits == 0L ? 0L : minimumReleaseDelayNanos,
                maximumReleaseDelayNanos,
                releasedUnits == 0L ? 0L : releaseDelayNanosTotal / releasedUnits,
                offeredConfigurationUnits,
                offeredKeyFrameUnits,
                offeredMediaUnits,
                releasedConfigurationUnits,
                releasedKeyFrameUnits,
                releasedMediaUnits,
                firstPublisherCallNanos,
                lastPublisherCallNanos,
                publisher.connectionState(),
                freshMediaReady);
    }

    public synchronized void stop() {
        if (state == State.STOPPED || state == State.CLOSED) {
            return;
        }
        queue.stop();
        publisher.disconnect();
        destination.close();
        state = State.STOPPED;
        freshMediaReady = false;
        notifyHealth();
    }

    @Override
    public synchronized void close() {
        if (state == State.CLOSED) {
            return;
        }
        stop();
        publisher.close();
        state = State.CLOSED;
        notifyHealth();
    }

    private void drainReadyUnits() {
        RtmpStreamPublisher.FailureCode publisherFailure = publisher.lastFailure();
        if (publisherFailure != RtmpStreamPublisher.FailureCode.NONE) {
            fail(mapFailure(publisherFailure), false);
            return;
        }
        if ((state == State.CONNECTING || state == State.RECONNECTING)
                && publisher.isPublishing()) {
            state = State.PUBLISHING;
            failureCode = FailureCode.NONE;
        }
        if (state != State.PUBLISHING) {
            return;
        }
        while (true) {
            java.util.Optional<EncodedAccessUnit> ready = queue.pollReady(clock.nowNanos());
            if (ready.isEmpty()) {
                return;
            }
            try {
                EncodedAccessUnit released = ready.get();
                publisher.publish(released);
                recordPublished(released);
                recordReleaseDelay(released);
            } catch (RuntimeException failure) {
                fail(FailureCode.PUBLISH_FAILED, false);
                return;
            }
        }
    }

    private void recordReleaseDelay(EncodedAccessUnit released) {
        long presentationNanos;
        try {
            presentationNanos = Math.multiplyExact(released.presentationTimeUs(), 1_000L);
        } catch (ArithmeticException overflow) {
            presentationNanos = Long.MAX_VALUE;
        }
        long delay = Math.max(0L, clock.nowNanos() - presentationNanos);
        releasedUnits++;
        releaseDelayNanosTotal = saturatedAdd(releaseDelayNanosTotal, delay);
        minimumReleaseDelayNanos = Math.min(minimumReleaseDelayNanos, delay);
        maximumReleaseDelayNanos = Math.max(maximumReleaseDelayNanos, delay);
    }

    private void recordOffered(EncodedAccessUnit unit) {
        if (unit.flags().contains(EncodedAccessUnit.Flag.CODEC_CONFIGURATION)) {
            offeredConfigurationUnits++;
        } else if (unit.flags().contains(EncodedAccessUnit.Flag.KEY_FRAME)) {
            offeredKeyFrameUnits++;
        } else {
            offeredMediaUnits++;
        }
    }

    private void recordPublished(EncodedAccessUnit unit) {
        long now = clock.nowNanos();
        if (firstPublisherCallNanos < 0L) {
            firstPublisherCallNanos = now;
        }
        lastPublisherCallNanos = now;
        if (unit.flags().contains(EncodedAccessUnit.Flag.CODEC_CONFIGURATION)) {
            releasedConfigurationUnits++;
            configurationPublishedInEpoch = true;
        } else if (unit.flags().contains(EncodedAccessUnit.Flag.KEY_FRAME)) {
            releasedKeyFrameUnits++;
            freshMediaReady = configurationPublishedInEpoch;
        } else {
            releasedMediaUnits++;
        }
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private void reconnect(FailureCode reason) {
        state = State.RECONNECTING;
        failureCode = reason;
        resetPublicationEpoch();
        publisher.disconnect();
        queue.beginReconnect();
        requestIdr();
        connectPublisher();
        notifyHealth();
    }

    /** Reconnects after the RTMP client has already reported an unexpected disconnect. */
    private void reconnectAfterPublisherDisconnect() {
        state = State.RECONNECTING;
        failureCode = FailureCode.NETWORK_DISCONNECTED;
        resetPublicationEpoch();
        queue.beginReconnect();
        requestIdr();
        connectPublisher();
        notifyHealth();
    }

    private void connectPublisher() {
        try {
            if (state != State.RECONNECTING) {
                state = State.CONNECTING;
            }
            resetPublicationEpoch();
            String endpoint = destination.useSecret(secret -> connectionAddress(secret));
            publisher.connect(endpoint);
            notifyHealth();
        } catch (RuntimeException failure) {
            fail(FailureCode.CONNECT_FAILED, false);
        }
    }

    private String connectionAddress(char[] secret) {
        // RootEncoder's low-level connect API requires one immutable String. Keep this value inside
        // the transport call boundary; never retain it in health, callbacks, telemetry, or errors.
        // The session-owned char[] loan is still zeroized by StreamDestination immediately after
        // this callback returns, but the dependency-required String itself cannot be zeroized.
        String base = destination.endpoint().toASCIIString();
        if (secret.length == 0) {
            return base;
        }
        return base + (base.endsWith("/") ? "" : "/") + new String(secret);
    }

    private void fail(FailureCode reason, boolean requestIdr) {
        failureCode = reason;
        state = State.FAILED;
        resetPublicationEpoch();
        publisher.disconnect();
        queue.stop();
        destination.close();
        if (requestIdr) {
            requestIdr();
        }
        notifyHealth();
    }

    private void requestIdr() {
        idrRequests++;
        idrRequester.requestIdrFrame();
    }

    private void ensureActive() {
        if (state == State.IDLE || state == State.STOPPED
                || state == State.FAILED || state == State.CLOSED) {
            throw new IllegalStateException("Stream session is not active");
        }
    }

    private void requireState(State expected) {
        if (state != expected) {
            throw new IllegalStateException("Unexpected stream session state");
        }
    }

    private static FailureCode mapFailure(RtmpStreamPublisher.FailureCode failure) {
        return switch (failure) {
            case CONNECTION_FAILED -> FailureCode.CONNECT_FAILED;
            case AUTHENTICATION_FAILED -> FailureCode.AUTHENTICATION_FAILED;
            case DISCONNECTED -> FailureCode.NETWORK_DISCONNECTED;
            case NONE -> FailureCode.NONE;
        };
    }

    private synchronized void onPublisherHealthChanged(
            RtmpStreamPublisher.ConnectionState connectionState,
            RtmpStreamPublisher.FailureCode failure) {
        if (state == State.FAILED || state == State.STOPPED || state == State.CLOSED) {
            return;
        }
        if (failure != RtmpStreamPublisher.FailureCode.NONE) {
            if (failure == RtmpStreamPublisher.FailureCode.DISCONNECTED
                    && state == State.PUBLISHING) {
                reconnectAfterPublisherDisconnect();
            } else {
                // Initial connection/authentication failures are terminal. Retrying them without
                // fresh user credentials would be misleading and could create an unbounded loop.
                fail(mapFailure(failure), false);
            }
            return;
        }
        if (connectionState == RtmpStreamPublisher.ConnectionState.CONNECTED
                && (state == State.CONNECTING || state == State.RECONNECTING)) {
            state = State.PUBLISHING;
            failureCode = FailureCode.NONE;
        }
        notifyHealth();
    }

    private void resetPublicationEpoch() {
        configurationPublishedInEpoch = false;
        freshMediaReady = false;
    }

    private void notifyHealth() {
        healthListener.onHealthChanged(health());
    }

    public enum State {
        IDLE,
        CONNECTING,
        PUBLISHING,
        RECONNECTING,
        FAILED,
        STOPPED,
        CLOSED
    }

    public enum FailureCode {
        NONE,
        CONNECT_FAILED,
        AUTHENTICATION_FAILED,
        NETWORK_DISCONNECTED,
        CONGESTION,
        QUEUE_OVERFLOW,
        INVALID_PTS_ORDER,
        QUEUE_STOPPED,
        PUBLISH_FAILED
    }

    public record Health(
            State state,
            FailureCode failureCode,
            int queuedUnits,
            long queuedBytes,
            long droppedUnits,
            long idrRequests,
            long releasedUnits,
            long minimumReleaseDelayNanos,
            long maximumReleaseDelayNanos,
            long meanReleaseDelayNanos,
            long offeredConfigurationUnits,
            long offeredKeyFrameUnits,
            long offeredMediaUnits,
            long releasedConfigurationUnits,
            long releasedKeyFrameUnits,
            long releasedMediaUnits,
            long firstPublisherCallNanos,
            long lastPublisherCallNanos,
            RtmpStreamPublisher.ConnectionState publisherConnectionState,
            boolean freshMediaReady) {
    }

    @FunctionalInterface
    public interface IdrRequester {
        void requestIdrFrame();
    }

    @FunctionalInterface
    public interface HealthListener {
        HealthListener NO_OP = health -> { };

        void onHealthChanged(Health health);
    }

    @FunctionalInterface
    interface NanoClock {
        long nowNanos();
    }

    interface Publisher extends AutoCloseable {
        void connect(String endpoint);

        boolean isPublishing();

        void publish(EncodedAccessUnit accessUnit);

        void disconnect();

        RtmpStreamPublisher.FailureCode lastFailure();

        RtmpStreamPublisher.ConnectionState connectionState();

        default void setHealthListener(RtmpStreamPublisher.HealthListener listener) {
        }

        @Override
        void close();
    }

    private record RtmpPublisherAdapter(RtmpStreamPublisher publisher) implements Publisher {
        @Override
        public void connect(String endpoint) {
            publisher.connect(endpoint);
        }

        @Override
        public boolean isPublishing() {
            return publisher.isPublishing();
        }

        @Override
        public void publish(EncodedAccessUnit accessUnit) {
            publisher.publish(accessUnit);
        }

        @Override
        public void disconnect() {
            publisher.disconnect();
        }

        @Override
        public RtmpStreamPublisher.FailureCode lastFailure() {
            return publisher.lastFailure();
        }

        @Override
        public RtmpStreamPublisher.ConnectionState connectionState() {
            return publisher.connectionState();
        }

        @Override
        public void setHealthListener(RtmpStreamPublisher.HealthListener listener) {
            publisher.setHealthListener(listener);
        }

        @Override
        public void close() {
            publisher.close();
        }
    }
}
