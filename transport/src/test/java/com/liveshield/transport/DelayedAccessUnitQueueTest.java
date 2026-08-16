package com.liveshield.transport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Tests-first contract for the bounded, video-only two-second publication delay. */
public final class DelayedAccessUnitQueueTest {
    private static final long TEN_BYTES = 10L;

    @Test
    public void releasesAtExactTwoSecondPtsDeadlineInDecodeSafeOrder() {
        DelayedAccessUnitQueue queue = new DelayedAccessUnitQueue(100L);
        EncodedAccessUnit configuration = configuration(0L, 1, 2);
        EncodedAccessUnit keyFrame = keyFrame(0L, 3, 4, 5);
        EncodedAccessUnit deltaFrame = deltaFrame(33_333L, 6);

        assertEquals(DelayedAccessUnitQueue.OfferResult.ACCEPTED, queue.offer(configuration));
        assertEquals(DelayedAccessUnitQueue.OfferResult.ACCEPTED, queue.offer(keyFrame));
        assertEquals(DelayedAccessUnitQueue.OfferResult.ACCEPTED, queue.offer(deltaFrame));
        assertEquals(Optional.empty(), queue.pollReady(1_999_999_999L));
        assertEquals(configuration, queue.pollReady(2_000_000_000L).orElseThrow());
        assertEquals(keyFrame, queue.pollReady(2_000_000_000L).orElseThrow());
        assertEquals(Optional.empty(), queue.pollReady(2_033_332_999L));
        assertEquals(deltaFrame, queue.pollReady(2_033_333_000L).orElseThrow());
    }

    @Test
    public void exactDurationAndByteCapacityAreInclusiveThenOverflowFailsSafe() {
        DelayedAccessUnitQueue durationQueue = readyQueue(100L);
        assertEquals(
                DelayedAccessUnitQueue.OfferResult.ACCEPTED,
                durationQueue.offer(deltaFrame(2_000_000L, 7)));
        assertEquals(DelayedAccessUnitQueue.MAX_QUEUE_DURATION_US,
                durationQueue.queuedDurationUs());
        assertEquals(
                DelayedAccessUnitQueue.OfferResult.OVERFLOW_STOP_REQUIRED,
                durationQueue.offer(deltaFrame(2_000_001L, 8)));
        assertFailedAndEmpty(durationQueue);

        DelayedAccessUnitQueue byteQueue = new DelayedAccessUnitQueue(TEN_BYTES);
        assertEquals(DelayedAccessUnitQueue.OfferResult.ACCEPTED,
                byteQueue.offer(configuration(0L, 1, 2)));
        assertEquals(DelayedAccessUnitQueue.OfferResult.ACCEPTED,
                byteQueue.offer(keyFrame(0L, 3, 4, 5, 6)));
        assertEquals(DelayedAccessUnitQueue.OfferResult.ACCEPTED,
                byteQueue.offer(deltaFrame(1L, 7, 8, 9, 10)));
        assertEquals(TEN_BYTES, byteQueue.queuedBytes());
        assertEquals(
                DelayedAccessUnitQueue.OfferResult.OVERFLOW_STOP_REQUIRED,
                byteQueue.offer(deltaFrame(2L, 11)));
        assertFailedAndEmpty(byteQueue);
    }

    @Test
    public void regressingPtsClearsQueueAndRequiresPublisherStop() {
        DelayedAccessUnitQueue queue = readyQueue(100L);
        assertEquals(DelayedAccessUnitQueue.OfferResult.ACCEPTED,
                queue.offer(deltaFrame(20L, 6)));

        assertEquals(
                DelayedAccessUnitQueue.OfferResult.INVALID_ORDER_STOP_REQUIRED,
                queue.offer(deltaFrame(19L, 7)));

        assertFailedAndEmpty(queue);
    }

    @Test
    public void stopIsIdempotentClearsBytesAndRejectsLaterUnits() {
        DelayedAccessUnitQueue queue = readyQueue(100L);
        queue.offer(deltaFrame(1L, 6));

        queue.stop();
        queue.stop();

        assertEquals(DelayedAccessUnitQueue.State.STOPPED, queue.state());
        assertEquals(0, queue.queuedUnits());
        assertEquals(0L, queue.queuedBytes());
        assertEquals(Optional.empty(), queue.pollReady(Long.MAX_VALUE));
        assertEquals(
                DelayedAccessUnitQueue.OfferResult.STOPPED,
                queue.offer(keyFrame(2L, 7)));
    }

    @Test
    public void reconnectClearsOldUnitsAndRequiresFreshConfigurationThenKeyFrame() {
        DelayedAccessUnitQueue queue = readyQueue(100L);
        queue.offer(deltaFrame(1L, 6));

        queue.beginReconnect();

        assertEquals(0, queue.queuedUnits());
        assertEquals(DelayedAccessUnitQueue.State.AWAITING_CONFIGURATION, queue.state());
        assertEquals(
                DelayedAccessUnitQueue.OfferResult.DROPPED_AWAITING_CONFIGURATION,
                queue.offer(keyFrame(10L, 7)));
        EncodedAccessUnit freshConfiguration = configuration(10L, 8, 9);
        assertEquals(
                DelayedAccessUnitQueue.OfferResult.ACCEPTED,
                queue.offer(freshConfiguration));
        assertEquals(DelayedAccessUnitQueue.State.AWAITING_KEY_FRAME, queue.state());
        assertEquals(
                DelayedAccessUnitQueue.OfferResult.DROPPED_AWAITING_KEY_FRAME,
                queue.offer(deltaFrame(11L, 10)));
        EncodedAccessUnit freshKeyFrame = keyFrame(12L, 11, 12);
        assertEquals(DelayedAccessUnitQueue.OfferResult.ACCEPTED,
                queue.offer(freshKeyFrame));
        assertEquals(DelayedAccessUnitQueue.State.BUFFERING, queue.state());
        assertEquals(freshConfiguration, queue.pollReady(2_000_012_000L).orElseThrow());
        assertEquals(freshKeyFrame, queue.pollReady(2_000_012_000L).orElseThrow());
        assertEquals(Optional.empty(), queue.pollReady(Long.MAX_VALUE));
    }

    @Test
    public void codecConfigurationAlwaysPrecedesEqualPtsKeyFrame() {
        DelayedAccessUnitQueue queue = new DelayedAccessUnitQueue(100L);
        EncodedAccessUnit configuration = configuration(100L, 1, 2);
        EncodedAccessUnit keyFrame = keyFrame(100L, 3, 4);
        queue.offer(configuration);
        queue.offer(keyFrame);

        assertTrue(queue.pollReady(2_000_100_000L).orElseThrow()
                .flags().contains(EncodedAccessUnit.Flag.CODEC_CONFIGURATION));
        assertTrue(queue.pollReady(2_000_100_000L).orElseThrow()
                .flags().contains(EncodedAccessUnit.Flag.KEY_FRAME));
    }

    @Test
    public void queueApiAcceptsOnlyNominalSanitizedVideoUnitsAndHasNoAudioPath() {
        Method[] methods = DelayedAccessUnitQueue.class.getDeclaredMethods();
        Method offer = java.util.Arrays.stream(methods)
                .filter(method -> method.getName().equals("offer"))
                .findFirst()
                .orElseThrow();

        assertArrayEquals(new Class<?>[]{EncodedAccessUnit.class}, offer.getParameterTypes());
        for (Method method : methods) {
            assertFalse(method.getName().toLowerCase(java.util.Locale.ROOT).contains("audio"));
        }
        assertArrayEquals(
                new EncodedAccessUnit.TrackType[]{EncodedAccessUnit.TrackType.VIDEO},
                EncodedAccessUnit.TrackType.values());
    }

    @Test
    public void rejectsNonPositiveByteCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new DelayedAccessUnitQueue(0L));
        assertThrows(IllegalArgumentException.class, () -> new DelayedAccessUnitQueue(-1L));
    }

    @Test
    public void reconfigurationDiscardsPendingUnitsAndWaitsForFreshKeyFrame() {
        DelayedAccessUnitQueue queue = readyQueue(100L);
        queue.offer(deltaFrame(10L, 6));

        EncodedAccessUnit replacement = configuration(20L, 7, 8);
        assertEquals(DelayedAccessUnitQueue.OfferResult.ACCEPTED,
                queue.offer(replacement));

        assertEquals(1, queue.queuedUnits());
        assertEquals(DelayedAccessUnitQueue.State.AWAITING_KEY_FRAME, queue.state());
        assertEquals(Optional.empty(), queue.pollReady(Long.MAX_VALUE));
        assertEquals(
                DelayedAccessUnitQueue.OfferResult.DROPPED_AWAITING_KEY_FRAME,
                queue.offer(deltaFrame(21L, 9)));
        EncodedAccessUnit replacementKey = keyFrame(22L, 10);
        assertEquals(DelayedAccessUnitQueue.OfferResult.ACCEPTED,
                queue.offer(replacementKey));
        assertEquals(replacement, queue.pollReady(2_000_022_000L).orElseThrow());
        assertEquals(replacementKey, queue.pollReady(2_000_022_000L).orElseThrow());
    }

    @Test
    public void onePrimingConfigurationReleasesGopAndEvaluationConfigClearsOnlyTail() {
        DelayedAccessUnitQueue queue = new DelayedAccessUnitQueue(1_000L);
        EncodedAccessUnit primingConfiguration = configuration(0L, 1, 2);
        assertEquals(DelayedAccessUnitQueue.OfferResult.ACCEPTED,
                queue.offer(primingConfiguration));
        int releasedPrimingUnits = 0;
        for (int index = 0; index < 24; index++) {
            long ptsUs = index * 125_000L;
            EncodedAccessUnit frame = index % 8 == 0
                    ? keyFrame(ptsUs, index) : deltaFrame(ptsUs, index);
            assertEquals(DelayedAccessUnitQueue.OfferResult.ACCEPTED, queue.offer(frame));
            while (queue.pollReady(ptsUs * 1_000L).isPresent()) {
                releasedPrimingUnits++;
            }
        }
        assertTrue("The continuous priming GOP must age through the delay",
                releasedPrimingUnits > 1);
        assertTrue("Only the not-yet-released priming tail remains", queue.queuedUnits() > 0);

        long evaluationPtsUs = 3_000_000L;
        EncodedAccessUnit evaluationConfiguration = configuration(evaluationPtsUs, 30, 31);
        EncodedAccessUnit evaluationKeyFrame = keyFrame(evaluationPtsUs, 32);
        assertEquals(DelayedAccessUnitQueue.OfferResult.ACCEPTED,
                queue.offer(evaluationConfiguration));
        assertEquals(1, queue.queuedUnits());
        assertEquals(DelayedAccessUnitQueue.OfferResult.ACCEPTED,
                queue.offer(evaluationKeyFrame));

        assertEquals(evaluationConfiguration,
                queue.pollReady(5_000_000_000L).orElseThrow());
        assertEquals(evaluationKeyFrame,
                queue.pollReady(5_000_000_000L).orElseThrow());
        assertEquals(Optional.empty(), queue.pollReady(Long.MAX_VALUE));
    }

    @Test
    public void invalidClockNullUnitAndReleaseTimestampOverflowFailConservatively() {
        DelayedAccessUnitQueue queue = new DelayedAccessUnitQueue(100L);
        assertThrows(NullPointerException.class, () -> queue.offer(null));
        assertThrows(IllegalArgumentException.class, () -> queue.pollReady(-1L));

        assertEquals(
                DelayedAccessUnitQueue.OfferResult.ACCEPTED,
                queue.offer(configuration(0L, 1)));
        assertEquals(
                DelayedAccessUnitQueue.OfferResult.OVERFLOW_STOP_REQUIRED,
                queue.offer(keyFrame(Long.MAX_VALUE, 2)));
        assertFailedAndEmpty(queue);
        assertEquals(
                DelayedAccessUnitQueue.OfferResult.STOPPED,
                queue.offer(configuration(0L, 3)));
        assertEquals(DelayedAccessUnitQueue.State.FAILED, queue.state());
    }

    @Test
    public void concurrentEqualPtsOffersRemainBoundedAndUncorrupted() throws Exception {
        DelayedAccessUnitQueue queue = readyQueue(100L);
        int workerCount = 4;
        int unitsPerWorker = 10;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(workerCount);
        try {
            for (int worker = 0; worker < workerCount; worker++) {
                executor.execute(() -> {
                    try {
                        start.await();
                        for (int index = 0; index < unitsPerWorker; index++) {
                            assertEquals(
                                    DelayedAccessUnitQueue.OfferResult.ACCEPTED,
                                    queue.offer(deltaFrame(1L, index)));
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        completed.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(completed.await(5L, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(2 + workerCount * unitsPerWorker, queue.queuedUnits());
        assertEquals(5L + workerCount * unitsPerWorker, queue.queuedBytes());
        assertEquals(1L, queue.queuedDurationUs());
        assertEquals(DelayedAccessUnitQueue.State.BUFFERING, queue.state());
    }

    private static DelayedAccessUnitQueue readyQueue(long maxBytes) {
        DelayedAccessUnitQueue queue = new DelayedAccessUnitQueue(maxBytes);
        queue.offer(configuration(0L, 1, 2));
        queue.offer(keyFrame(0L, 3, 4, 5));
        return queue;
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

    private static void assertFailedAndEmpty(DelayedAccessUnitQueue queue) {
        assertEquals(DelayedAccessUnitQueue.State.FAILED, queue.state());
        assertEquals(0, queue.queuedUnits());
        assertEquals(0L, queue.queuedBytes());
        assertEquals(Optional.empty(), queue.pollReady(Long.MAX_VALUE));
    }
}
