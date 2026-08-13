package com.liveshield.video.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.TypedFailure;
import com.liveshield.privacy.session.SessionHealth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class VisionSchedulerTest {
    private static final VisionScheduler.Configuration CONFIGURATION =
            new VisionScheduler.Configuration(10, 100, 50, 200, 100, 30, 500);

    @Test
    public void dispatchesIndependentLanesAndReleasesEveryInputExactlyOnce() {
        Fixture fixture = new Fixture();
        FakeFrame frame = fixture.submit(0);

        assertEquals(1, frame.closeCount);
        assertEquals(1, fixture.face.requests.size());
        assertEquals(1, fixture.text.requests.size());
        assertEquals(1, fixture.barcode.requests.size());

        fixture.completeAllSuccessfully(500);

        assertEquals(List.of(
                DetectorLane.FACE, DetectorLane.TEXT, DetectorLane.BARCODE),
                fixture.successLanes());
        assertEquals(1, frame.leases.get(DetectorLane.FACE).closeCount);
        assertEquals(1, frame.leases.get(DetectorLane.TEXT).closeCount);
        assertEquals(1, frame.leases.get(DetectorLane.BARCODE).closeCount);
    }

    @Test
    public void oneInFlightPerLaneDropsWorkWithoutQueuingOrRetainingIt() {
        Fixture fixture = new Fixture();
        fixture.submit(0);
        FakeFrame dropped = fixture.submit(10);

        assertEquals(1, fixture.face.requests.size());
        assertEquals(1, fixture.text.requests.size());
        assertEquals(1, fixture.barcode.requests.size());
        assertTrue(dropped.leases.isEmpty());
        assertEquals(3, fixture.countEvent(VisionScheduler.Event.LANE_BUSY_DROP));
        assertEquals(1, dropped.closeCount);
    }

    @Test
    public void faceDeadlineCancelsOnlyFaceAndImmediatelyStartsFreshFace() {
        Fixture fixture = new Fixture();
        fixture.submit(0);
        fixture.clock.now = 31;

        fixture.submit(10);

        assertTrue(fixture.face.requests.get(0).cancelled);
        assertEquals(1, fixture.face.requests.get(0).lease.closeCount);
        assertEquals(2, fixture.face.requests.size());
        assertFalse(fixture.text.requests.get(0).cancelled);
        assertFalse(fixture.barcode.requests.get(0).cancelled);
        assertEquals(TypedFailure.Code.DEADLINE_EXCEEDED,
                fixture.results.get(0).failure().orElseThrow().code());
    }

    @Test
    public void sceneChangeInvalidatesOptionalWorkAndDispatchesFreshBurst() {
        Fixture fixture = new Fixture();
        fixture.submit(0);
        fixture.face.completeSuccess(0, 500);

        fixture.submit(10, SessionHealth.ThermalState.NOMINAL,
                SessionHealth.SceneState.CHANGED);

        assertTrue(fixture.text.requests.get(0).cancelled);
        assertTrue(fixture.barcode.requests.get(0).cancelled);
        assertEquals(2, fixture.text.requests.size());
        assertEquals(2, fixture.barcode.requests.size());
        assertEquals(TypedFailure.Code.STALE_RESULT,
                fixture.results.get(1).failure().orElseThrow().code());
        assertEquals(TypedFailure.Code.STALE_RESULT,
                fixture.results.get(2).failure().orElseThrow().code());

        int resultCount = fixture.results.size();
        fixture.text.requests.get(0).completion.complete(success(DetectorLane.TEXT, 0, 500));
        assertEquals(resultCount, fixture.results.size());
    }

    @Test
    public void warningReducesOptionalCadenceWithoutReducingFaceCadence() {
        Fixture fixture = new Fixture();
        fixture.submit(0);
        fixture.completeAllSuccessfully(500);

        fixture.submit(100, SessionHealth.ThermalState.WARNING,
                SessionHealth.SceneState.STABLE);

        assertEquals(2, fixture.face.requests.size());
        assertEquals(1, fixture.text.requests.size());
        assertEquals(2, fixture.barcode.requests.size());
        assertEquals(1, fixture.countEvent(VisionScheduler.Event.CADENCE_SKIP));
    }

    @Test
    public void severeThermalCancelsOptionalLanesButPreservesFaceProtection() {
        Fixture fixture = new Fixture();
        fixture.submit(0);

        fixture.submit(10, SessionHealth.ThermalState.SEVERE,
                SessionHealth.SceneState.STABLE);

        assertTrue(fixture.text.requests.get(0).cancelled);
        assertTrue(fixture.barcode.requests.get(0).cancelled);
        assertFalse(fixture.face.requests.get(0).cancelled);
        assertEquals(1, fixture.face.requests.size());
        assertEquals(1, fixture.text.requests.size());
        assertEquals(1, fixture.barcode.requests.size());
        assertEquals(2, fixture.results.stream()
                .filter(value -> value.failure().isPresent()
                        && value.failure().orElseThrow().code()
                                == TypedFailure.Code.THERMAL_UNSAFE)
                .count());
    }

    @Test
    public void analyzerThrowBecomesTypedFailureAndReleasesLease() {
        Fixture fixture = new Fixture();
        fixture.text.throwOnAnalyze = true;
        FakeFrame frame = fixture.submit(0);

        DetectorSnapshot failure = fixture.results.get(0);
        assertEquals(DetectorLane.TEXT, failure.lane());
        assertEquals(TypedFailure.Code.ANALYZER_ERROR,
                failure.failure().orElseThrow().code());
        assertEquals(1, frame.leases.get(DetectorLane.TEXT).closeCount);
    }

    @Test
    public void staleOrMismatchedResultCannotReachPolicyAsSuccess() {
        Fixture fixture = new Fixture();
        fixture.submit(0);
        fixture.face.complete(success(DetectorLane.TEXT, 0, 500));
        fixture.text.completeSuccess(0, 2_000);

        assertEquals(2, fixture.results.size());
        for (DetectorSnapshot result : fixture.results) {
            assertEquals(TypedFailure.Code.STALE_RESULT,
                    result.failure().orElseThrow().code());
        }
    }

    @Test
    public void resultThatExpiredWhileAnalyzerRanIsRejected() {
        Fixture fixture = new Fixture();
        fixture.submit(0);
        fixture.submit(10);

        fixture.face.completeSuccess(0, 5);

        assertEquals(TypedFailure.Code.STALE_RESULT,
                fixture.results.get(0).failure().orElseThrow().code());
    }

    @Test
    public void outOfOrderFrameIsReleasedAndFailsEveryPolicyLane() {
        Fixture fixture = new Fixture();
        fixture.submit(10);
        FakeFrame stale = fixture.submit(9);

        assertEquals(1, stale.closeCount);
        assertTrue(stale.leases.isEmpty());
        assertEquals(3, fixture.results.size());
        assertTrue(fixture.results.stream().allMatch(value ->
                value.failure().orElseThrow().code() == TypedFailure.Code.STALE_RESULT));
    }

    @Test
    public void closeIsIdempotentCancelsAndReleasesWithLateCallbacksIgnored() {
        Fixture fixture = new Fixture();
        FakeFrame frame = fixture.submit(0);

        fixture.scheduler.close();
        fixture.scheduler.close();

        for (FakeAnalyzer analyzer : List.of(fixture.face, fixture.text, fixture.barcode)) {
            assertTrue(analyzer.requests.get(0).cancelled);
            analyzer.requests.get(0).completion.complete(
                    success(analyzer.lane, 0, 500));
        }
        assertTrue(fixture.results.isEmpty());
        assertTrue(frame.leases.values().stream().allMatch(value -> value.closeCount == 1));

        FakeFrame afterClose = fixture.submit(1_000);
        assertEquals(1, afterClose.closeCount);
        assertTrue(afterClose.leases.isEmpty());
    }

    @Test
    public void configurationRejectsLoadIncreasingOrUnsafeValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisionScheduler.Configuration(0, 100, 50, 200, 100, 30, 500));
        assertThrows(IllegalArgumentException.class,
                () -> new VisionScheduler.Configuration(10, 100, 50, 99, 100, 30, 500));
        assertThrows(IllegalArgumentException.class,
                () -> new VisionScheduler.Configuration(10, 100, 50, 200, 49, 30, 500));
    }

    private static DetectorSnapshot success(
            DetectorLane lane, long sourceNanos, long validUntilNanos) {
        return DetectorSnapshot.success(
                lane,
                FrameTimestamp.ofNanos(sourceNanos),
                FrameTimestamp.ofNanos(validUntilNanos),
                List.of());
    }

    private static final class Fixture {
        private final FakeAnalyzer face = new FakeAnalyzer(DetectorLane.FACE);
        private final FakeAnalyzer text = new FakeAnalyzer(DetectorLane.TEXT);
        private final FakeAnalyzer barcode = new FakeAnalyzer(DetectorLane.BARCODE);
        private final List<DetectorSnapshot> results = new ArrayList<>();
        private final List<TelemetryEntry> telemetry = new ArrayList<>();
        private final FakeClock clock = new FakeClock();
        private final VisionScheduler scheduler = new VisionScheduler(
                CONFIGURATION,
                face,
                text,
                barcode,
                results::add,
                (event, lane, timestamp) -> telemetry.add(new TelemetryEntry(event, lane)),
                clock);

        private FakeFrame submit(long timestamp) {
            return submit(timestamp, SessionHealth.ThermalState.NOMINAL,
                    SessionHealth.SceneState.STABLE);
        }

        private FakeFrame submit(
                long timestamp,
                SessionHealth.ThermalState thermal,
                SessionHealth.SceneState scene) {
            FakeFrame frame = new FakeFrame(timestamp);
            scheduler.submit(frame, thermal, scene);
            return frame;
        }

        private void completeAllSuccessfully(long validUntil) {
            face.completeSuccess(face.last().lease.timestamp().nanos(), validUntil);
            text.completeSuccess(text.last().lease.timestamp().nanos(), validUntil);
            barcode.completeSuccess(barcode.last().lease.timestamp().nanos(), validUntil);
        }

        private List<DetectorLane> successLanes() {
            return results.stream()
                    .filter(value -> value.failure().isEmpty())
                    .map(DetectorSnapshot::lane)
                    .toList();
        }

        private long countEvent(VisionScheduler.Event event) {
            return telemetry.stream().filter(value -> value.event == event).count();
        }
    }

    private static final class FakeAnalyzer implements VisionScheduler.LaneAnalyzer {
        private final DetectorLane lane;
        private final List<Request> requests = new ArrayList<>();
        private boolean throwOnAnalyze;

        private FakeAnalyzer(DetectorLane lane) {
            this.lane = lane;
        }

        @Override
        public VisionScheduler.Cancellation analyze(
                VisionScheduler.FrameLease frame, VisionScheduler.Completion completion) {
            if (throwOnAnalyze) {
                throw new IllegalStateException("synthetic typed failure");
            }
            Request request = new Request((FakeLease) frame, completion);
            requests.add(request);
            return () -> request.cancelled = true;
        }

        private void completeSuccess(long source, long validUntil) {
            complete(success(lane, source, validUntil));
        }

        private void complete(DetectorSnapshot snapshot) {
            last().completion.complete(snapshot);
        }

        private Request last() {
            return requests.get(requests.size() - 1);
        }
    }

    private static final class FakeFrame implements VisionScheduler.AnalysisFrame {
        private final FrameTimestamp timestamp;
        private final Map<DetectorLane, FakeLease> leases = new EnumMap<>(DetectorLane.class);
        private int closeCount;

        private FakeFrame(long timestamp) {
            this.timestamp = FrameTimestamp.ofNanos(timestamp);
        }

        @Override
        public FrameTimestamp timestamp() {
            return timestamp;
        }

        @Override
        public VisionScheduler.FrameLease retain(DetectorLane lane) {
            FakeLease lease = new FakeLease(timestamp);
            leases.put(lane, lease);
            return lease;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    private static final class FakeLease implements VisionScheduler.FrameLease {
        private final FrameTimestamp timestamp;
        private int closeCount;

        private FakeLease(FrameTimestamp timestamp) {
            this.timestamp = timestamp;
        }

        @Override
        public FrameTimestamp timestamp() {
            return timestamp;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    private static final class FakeClock implements VisionScheduler.NanoClock {
        private long now;

        @Override
        public long nanoTime() {
            return now;
        }
    }

    private static final class Request {
        private final FakeLease lease;
        private final VisionScheduler.Completion completion;
        private boolean cancelled;

        private Request(FakeLease lease, VisionScheduler.Completion completion) {
            this.lease = lease;
            this.completion = completion;
        }
    }

    private record TelemetryEntry(VisionScheduler.Event event, DetectorLane lane) { }
}
