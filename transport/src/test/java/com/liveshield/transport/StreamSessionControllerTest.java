package com.liveshield.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.liveshield.transport.destination.StreamDestination;
import com.liveshield.transport.rtmp.RtmpStreamPublisher;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Deterministic lifecycle and stale-unit tests for sanitized publication sessions. */
public final class StreamSessionControllerTest {
    @Test
    public void connectsBuffersTwoSecondsAndPublishesInOrder() {
        Fixture fixture = fixture(100L);
        fixture.controller.connect();
        fixture.publisher.publishing = true;
        fixture.controller.tick();
        EncodedAccessUnit configuration = configuration(0L, 1, 2);
        EncodedAccessUnit keyFrame = keyFrame(0L, 3, 4);
        EncodedAccessUnit delta = deltaFrame(10L, 5);

        fixture.controller.onAccessUnit(configuration);
        fixture.controller.onAccessUnit(keyFrame);
        fixture.controller.onAccessUnit(delta);
        fixture.clock.nowNanos = 1_999_999_999L;
        fixture.controller.tick();
        assertTrue(fixture.publisher.published.isEmpty());

        fixture.clock.nowNanos = 2_000_010_000L;
        fixture.controller.tick();

        assertEquals(List.of(configuration, keyFrame, delta), fixture.publisher.published);
        assertEquals(3L, fixture.controller.health().releasedUnits());
        assertEquals(1L, fixture.controller.health().offeredConfigurationUnits());
        assertEquals(1L, fixture.controller.health().offeredKeyFrameUnits());
        assertEquals(1L, fixture.controller.health().offeredMediaUnits());
        assertEquals(1L, fixture.controller.health().releasedConfigurationUnits());
        assertEquals(1L, fixture.controller.health().releasedKeyFrameUnits());
        assertEquals(1L, fixture.controller.health().releasedMediaUnits());
        assertEquals(2_000_010_000L,
                fixture.controller.health().firstPublisherCallNanos());
        assertEquals(2_000_010_000L,
                fixture.controller.health().lastPublisherCallNanos());
        assertEquals(2_000_000_000L,
                fixture.controller.health().minimumReleaseDelayNanos());
        assertEquals(2_000_010_000L,
                fixture.controller.health().maximumReleaseDelayNanos());
        assertEquals(2_000_006_666L,
                fixture.controller.health().meanReleaseDelayNanos());
        assertEquals(StreamSessionController.State.PUBLISHING,
                fixture.controller.health().state());
    }

    @Test
    public void congestionDiscardsOldUnitsAndRequiresFreshConfigAndKeyFrame() {
        Fixture fixture = publishingFixture(100L);
        fixture.controller.onAccessUnit(configuration(0L, 1, 2));
        fixture.controller.onAccessUnit(keyFrame(0L, 3));
        fixture.controller.onAccessUnit(deltaFrame(1L, 4));

        fixture.controller.onCongestionDetected();
        fixture.publisher.publishing = true;
        fixture.controller.tick();
        fixture.clock.nowNanos = Long.MAX_VALUE;
        fixture.controller.onAccessUnit(deltaFrame(2L, 5));
        fixture.controller.tick();
        assertTrue(fixture.publisher.published.isEmpty());

        EncodedAccessUnit freshConfiguration = configuration(10L, 6, 7);
        EncodedAccessUnit freshKeyFrame = keyFrame(11L, 8);
        fixture.controller.onAccessUnit(freshConfiguration);
        fixture.controller.onAccessUnit(freshKeyFrame);
        fixture.controller.tick();

        assertEquals(List.of(freshConfiguration, freshKeyFrame),
                fixture.publisher.published);
        assertEquals(1, fixture.idrRequests.get());
        assertEquals(1L, fixture.controller.health().droppedUnits());
        assertEquals(2, fixture.publisher.connectCalls);
    }

    @Test
    public void networkReconnectAlsoClearsStaleUnitsAndRequestsIdr() {
        Fixture fixture = publishingFixture(100L);
        fixture.controller.onAccessUnit(configuration(0L, 1));
        fixture.controller.onAccessUnit(keyFrame(0L, 2));

        fixture.controller.onNetworkDisconnected();

        assertEquals(StreamSessionController.State.RECONNECTING,
                fixture.controller.health().state());
        assertEquals(StreamSessionController.FailureCode.NETWORK_DISCONNECTED,
                fixture.controller.health().failureCode());
        assertEquals(0, fixture.controller.health().queuedUnits());
        assertEquals(1, fixture.idrRequests.get());
    }

    @Test
    public void queueOverflowFailsClearsDisconnectsAndRequestsIdr() {
        Fixture fixture = publishingFixture(4L);
        fixture.controller.onAccessUnit(configuration(0L, 1, 2));

        fixture.controller.onAccessUnit(keyFrame(0L, 3, 4, 5));

        assertEquals(StreamSessionController.State.FAILED,
                fixture.controller.health().state());
        assertEquals(StreamSessionController.FailureCode.QUEUE_OVERFLOW,
                fixture.controller.health().failureCode());
        assertEquals(0, fixture.controller.health().queuedUnits());
        assertEquals(1, fixture.idrRequests.get());
        assertTrue(fixture.publisher.disconnectCalls > 0);
        assertEquals(StreamDestination.State.CLEARED, fixture.destination.state());
    }

    @Test
    public void publisherFailureIsTypedRedactedAndClearsDestination() {
        Fixture fixture = publishingFixture(100L);
        fixture.publisher.failure = RtmpStreamPublisher.FailureCode.AUTHENTICATION_FAILED;

        fixture.controller.tick();

        StreamSessionController.Health health = fixture.controller.health();
        assertEquals(StreamSessionController.State.FAILED, health.state());
        assertEquals(StreamSessionController.FailureCode.AUTHENTICATION_FAILED,
                health.failureCode());
        assertEquals(RtmpStreamPublisher.ConnectionState.FAILED,
                health.publisherConnectionState());
        assertFalse(health.toString().contains("fixture-key"));
        assertEquals(StreamDestination.State.CLEARED, fixture.destination.state());
    }

    @Test
    public void asyncAuthenticationCallbackIsTypedAndCannotDeadlockController() throws Exception {
        List<StreamSessionController.Health> events = new ArrayList<>();
        Fixture fixture = fixture(100L, events::add);
        fixture.controller.connect();

        Thread callback = new Thread(fixture.publisher::authenticationFailed);
        callback.start();
        callback.join(1_000L);

        assertFalse(callback.isAlive());
        assertEquals(StreamSessionController.State.FAILED,
                fixture.controller.health().state());
        assertEquals(StreamSessionController.FailureCode.AUTHENTICATION_FAILED,
                fixture.controller.health().failureCode());
        assertTrue(events.stream().anyMatch(health ->
                health.failureCode()
                        == StreamSessionController.FailureCode.AUTHENTICATION_FAILED));
        assertFalse(events.toString().contains("fixture-key"));
    }

    @Test
    public void freshMediaReadinessRequiresPublishedConfigurationAndNewKeyFrame() {
        Fixture fixture = publishingFixture(100L);
        fixture.clock.nowNanos = Long.MAX_VALUE;

        fixture.controller.onAccessUnit(configuration(10L, 1, 2));
        assertFalse(fixture.controller.health().freshMediaReady());
        fixture.controller.onAccessUnit(deltaFrame(11L, 3));
        assertFalse(fixture.controller.health().freshMediaReady());
        fixture.controller.onAccessUnit(keyFrame(12L, 4));

        assertTrue(fixture.controller.health().freshMediaReady());
        fixture.controller.onNetworkDisconnected();
        assertFalse(fixture.controller.health().freshMediaReady());
        assertEquals(0, fixture.controller.health().queuedUnits());
    }

    @Test
    public void stopAndCloseAreIdempotentClearQueuePublisherAndSecret() {
        Fixture fixture = publishingFixture(100L);
        fixture.controller.onAccessUnit(configuration(0L, 1));

        fixture.controller.stop();
        fixture.controller.stop();
        fixture.controller.close();
        fixture.controller.close();

        assertEquals(StreamSessionController.State.CLOSED,
                fixture.controller.health().state());
        assertEquals(0, fixture.controller.health().queuedUnits());
        assertEquals(1, fixture.publisher.closeCalls);
        assertEquals(StreamDestination.State.CLEARED, fixture.destination.state());
        assertThrows(IllegalStateException.class,
                () -> fixture.controller.onAccessUnit(configuration(1L, 2)));
    }

    @Test
    public void idrCallbackCarriesNoEncoderOrMediaOwnership() {
        assertEquals(0,
                StreamSessionController.IdrRequester.class.getDeclaredMethods()[0]
                        .getParameterCount());
        assertEquals(void.class,
                StreamSessionController.IdrRequester.class.getDeclaredMethods()[0]
                        .getReturnType());
        for (java.lang.reflect.Method method
                : StreamSessionController.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                assertFalse(method.getName().toLowerCase(java.util.Locale.ROOT).contains("audio"));
            }
        }
    }

    private static Fixture publishingFixture(long maxBytes) {
        Fixture fixture = fixture(maxBytes);
        fixture.controller.connect();
        fixture.publisher.publishing = true;
        fixture.controller.tick();
        return fixture;
    }

    private static Fixture fixture(long maxBytes) {
        return fixture(maxBytes, StreamSessionController.HealthListener.NO_OP);
    }

    private static Fixture fixture(
            long maxBytes, StreamSessionController.HealthListener healthListener) {
        StreamDestination destination = StreamDestination.sessionScoped(
                StreamDestination.Kind.TIKTOK_EXTERNAL,
                "Test destination",
                URI.create("rtmps://example.invalid/live"),
                "fixture-key".toCharArray());
        FakePublisher publisher = new FakePublisher();
        FakeClock clock = new FakeClock();
        AtomicInteger idrRequests = new AtomicInteger();
        StreamSessionController controller = new StreamSessionController(
                destination,
                new DelayedAccessUnitQueue(maxBytes),
                publisher,
                clock,
                idrRequests::incrementAndGet,
                healthListener);
        return new Fixture(destination, publisher, clock, idrRequests, controller);
    }

    private static EncodedAccessUnit configuration(long ptsUs, int... payload) {
        return unit(ptsUs, Set.of(EncodedAccessUnit.Flag.CODEC_CONFIGURATION), payload);
    }

    private static EncodedAccessUnit keyFrame(long ptsUs, int... payload) {
        return unit(ptsUs, Set.of(EncodedAccessUnit.Flag.KEY_FRAME), payload);
    }

    private static EncodedAccessUnit deltaFrame(long ptsUs, int... payload) {
        return unit(ptsUs, Set.of(), payload);
    }

    private static EncodedAccessUnit unit(
            long ptsUs,
            Set<EncodedAccessUnit.Flag> flags,
            int... payload) {
        byte[] bytes = new byte[payload.length];
        for (int index = 0; index < payload.length; index++) {
            bytes[index] = (byte) payload[index];
        }
        return EncodedAccessUnit.copySanitizedH264(bytes, ptsUs, flags);
    }

    private record Fixture(
            StreamDestination destination,
            FakePublisher publisher,
            FakeClock clock,
            AtomicInteger idrRequests,
            StreamSessionController controller) {
    }

    private static final class FakeClock implements StreamSessionController.NanoClock {
        private long nowNanos;

        @Override
        public long nowNanos() {
            return nowNanos;
        }
    }

    private static final class FakePublisher implements StreamSessionController.Publisher {
        private final List<EncodedAccessUnit> published = new ArrayList<>();
        private int connectCalls;
        private int disconnectCalls;
        private int closeCalls;
        private boolean publishing;
        private RtmpStreamPublisher.FailureCode failure =
                RtmpStreamPublisher.FailureCode.NONE;
        private RtmpStreamPublisher.HealthListener healthListener =
                RtmpStreamPublisher.HealthListener.NO_OP;

        @Override
        public void connect(String endpoint) {
            connectCalls++;
            failure = RtmpStreamPublisher.FailureCode.NONE;
        }

        @Override
        public boolean isPublishing() {
            return publishing;
        }

        @Override
        public void publish(EncodedAccessUnit accessUnit) {
            published.add(accessUnit);
        }

        @Override
        public void disconnect() {
            disconnectCalls++;
            publishing = false;
        }

        @Override
        public RtmpStreamPublisher.FailureCode lastFailure() {
            return failure;
        }

        @Override
        public RtmpStreamPublisher.ConnectionState connectionState() {
            if (failure != RtmpStreamPublisher.FailureCode.NONE) {
                return RtmpStreamPublisher.ConnectionState.FAILED;
            }
            return publishing
                    ? RtmpStreamPublisher.ConnectionState.CONNECTED
                    : RtmpStreamPublisher.ConnectionState.DISCONNECTED;
        }

        @Override
        public void setHealthListener(RtmpStreamPublisher.HealthListener listener) {
            healthListener = listener;
        }

        private void authenticationFailed() {
            failure = RtmpStreamPublisher.FailureCode.AUTHENTICATION_FAILED;
            healthListener.onHealthChanged(
                    RtmpStreamPublisher.ConnectionState.FAILED, failure);
        }

        @Override
        public void close() {
            closeCalls++;
            publishing = false;
        }
    }
}
