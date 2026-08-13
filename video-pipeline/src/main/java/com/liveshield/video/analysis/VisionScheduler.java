package com.liveshield.video.analysis;

import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.DetectorSnapshot;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.privacy.model.TypedFailure;
import com.liveshield.privacy.session.SessionHealth;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded, independent scheduling for the face, text, and barcode analysis lanes.
 *
 * <p>The scheduler never queues a raw frame. It owns the submitted frame, retains at most one
 * opaque lease per selected lane, and releases every lease after completion, cancellation, or
 * failure. Analyzer adapters may interpret their opaque lease, but this scheduler never exposes a
 * pixel, recognized value, or Android image type.</p>
 */
public final class VisionScheduler implements AutoCloseable {
    private static final List<DetectorLane> ANALYSIS_LANES = List.of(
            DetectorLane.FACE, DetectorLane.TEXT, DetectorLane.BARCODE);

    private final Configuration configuration;
    private final Map<DetectorLane, LaneAnalyzer> analyzers;
    private final Map<DetectorLane, InFlight> inFlight = new EnumMap<>(DetectorLane.class);
    private final Map<DetectorLane, FrameTimestamp> lastStarted =
            new EnumMap<>(DetectorLane.class);
    private final ResultListener resultListener;
    private final Telemetry telemetry;
    private final NanoClock clock;
    private FrameTimestamp latestFrameTimestamp;
    private boolean closed;

    public VisionScheduler(
            Configuration configuration,
            LaneAnalyzer faceAnalyzer,
            LaneAnalyzer textAnalyzer,
            LaneAnalyzer barcodeAnalyzer,
            ResultListener resultListener,
            Telemetry telemetry,
            NanoClock clock) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        analyzers = new EnumMap<>(DetectorLane.class);
        analyzers.put(DetectorLane.FACE, Objects.requireNonNull(faceAnalyzer, "faceAnalyzer"));
        analyzers.put(DetectorLane.TEXT, Objects.requireNonNull(textAnalyzer, "textAnalyzer"));
        analyzers.put(
                DetectorLane.BARCODE,
                Objects.requireNonNull(barcodeAnalyzer, "barcodeAnalyzer"));
        this.resultListener = Objects.requireNonNull(resultListener, "resultListener");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Takes ownership of {@code frame}. The frame is always closed before this method returns;
     * only independently releasable, bounded lane leases may remain in flight.
     */
    public synchronized void submit(
            AnalysisFrame frame,
            SessionHealth.ThermalState thermalState,
            SessionHealth.SceneState sceneState) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(thermalState, "thermalState");
        Objects.requireNonNull(sceneState, "sceneState");
        FrameTimestamp timestamp = Objects.requireNonNull(frame.timestamp(), "frame.timestamp");
        try {
            if (closed) {
                telemetry.record(Event.FRAME_DROPPED_CLOSED, DetectorLane.FACE, timestamp);
                return;
            }
            if (latestFrameTimestamp != null
                    && timestamp.compareTo(latestFrameTimestamp) <= 0) {
                for (DetectorLane lane : ANALYSIS_LANES) {
                    publishFailure(lane, timestamp, TypedFailure.Code.STALE_RESULT);
                    telemetry.record(Event.FRAME_DROPPED_STALE, lane, timestamp);
                }
                return;
            }
            latestFrameTimestamp = timestamp;
            expireFaceDeadline(timestamp);
            if (sceneState == SessionHealth.SceneState.CHANGED) {
                cancelLane(DetectorLane.TEXT, TypedFailure.Code.STALE_RESULT, Event.SCENE_INVALIDATED);
                cancelLane(
                        DetectorLane.BARCODE,
                        TypedFailure.Code.STALE_RESULT,
                        Event.SCENE_INVALIDATED);
            }
            if (thermalState == SessionHealth.ThermalState.SEVERE) {
                cancelLane(
                        DetectorLane.TEXT,
                        TypedFailure.Code.THERMAL_UNSAFE,
                        Event.OPTIONAL_LANE_DISABLED);
                cancelLane(
                        DetectorLane.BARCODE,
                        TypedFailure.Code.THERMAL_UNSAFE,
                        Event.OPTIONAL_LANE_DISABLED);
            }

            startIfEligible(frame, DetectorLane.FACE, thermalState, sceneState);
            if (thermalState != SessionHealth.ThermalState.SEVERE) {
                startIfEligible(frame, DetectorLane.TEXT, thermalState, sceneState);
                startIfEligible(frame, DetectorLane.BARCODE, thermalState, sceneState);
            }
        } finally {
            frame.close();
        }
    }

    private void startIfEligible(
            AnalysisFrame frame,
            DetectorLane lane,
            SessionHealth.ThermalState thermalState,
            SessionHealth.SceneState sceneState) {
        FrameTimestamp timestamp = frame.timestamp();
        if (inFlight.containsKey(lane)) {
            telemetry.record(Event.LANE_BUSY_DROP, lane, timestamp);
            return;
        }
        boolean sceneBurst = sceneState == SessionHealth.SceneState.CHANGED
                && lane != DetectorLane.FACE;
        if (!sceneBurst && !cadenceElapsed(lane, timestamp, thermalState)) {
            telemetry.record(Event.CADENCE_SKIP, lane, timestamp);
            return;
        }

        FrameLease lease;
        try {
            lease = Objects.requireNonNull(frame.retain(lane), "frame.retain");
            if (!timestamp.equals(lease.timestamp())) {
                lease.close();
                publishFailure(lane, timestamp, TypedFailure.Code.ANALYZER_ERROR);
                telemetry.record(Event.INVALID_INPUT, lane, timestamp);
                return;
            }
        } catch (RuntimeException retainFailure) {
            publishFailure(lane, timestamp, TypedFailure.Code.ANALYZER_ERROR);
            telemetry.record(Event.INVALID_INPUT, lane, timestamp);
            return;
        }

        InFlight running = new InFlight(lease, clock.nanoTime());
        inFlight.put(lane, running);
        lastStarted.put(lane, timestamp);
        telemetry.record(Event.STARTED, lane, timestamp);
        try {
            Cancellation cancellation = analyzers.get(lane).analyze(
                    lease, snapshot -> complete(lane, running, snapshot));
            if (inFlight.get(lane) == running) {
                running.cancellation = Objects.requireNonNull(cancellation, "cancellation");
            }
        } catch (RuntimeException analyzerFailure) {
            failIfCurrent(lane, running, TypedFailure.Code.ANALYZER_ERROR, Event.FAILED);
        }
    }

    private boolean cadenceElapsed(
            DetectorLane lane,
            FrameTimestamp timestamp,
            SessionHealth.ThermalState thermalState) {
        FrameTimestamp previous = lastStarted.get(lane);
        if (previous == null) {
            return true;
        }
        long interval = configuration.intervalNanos(lane, thermalState);
        return timestamp.nanos() - previous.nanos() >= interval;
    }

    private synchronized void complete(
            DetectorLane lane, InFlight expected, DetectorSnapshot snapshot) {
        if (inFlight.get(lane) != expected) {
            return;
        }
        inFlight.remove(lane);
        expected.lease.close();
        FrameTimestamp source = expected.lease.timestamp();
        if (closed) {
            return;
        }
        if (!validSnapshot(lane, source, snapshot)) {
            publishFailure(lane, source, TypedFailure.Code.STALE_RESULT);
            telemetry.record(Event.INVALID_OR_STALE_RESULT, lane, source);
            return;
        }
        resultListener.onSnapshot(snapshot);
        telemetry.record(Event.COMPLETED, lane, source);
    }

    private boolean validSnapshot(
            DetectorLane lane, FrameTimestamp source, DetectorSnapshot snapshot) {
        if (snapshot == null
                || snapshot.lane() != lane
                || !snapshot.sourceTimestamp().equals(source)) {
            return false;
        }
        if (snapshot.failure().isPresent()) {
            return snapshot.failure().orElseThrow().occurredAt().equals(source);
        }
        long validity = snapshot.validUntil().nanos() - source.nanos();
        return validity <= configuration.maxValidityNanos()
                && latestFrameTimestamp != null
                && snapshot.isFreshAt(latestFrameTimestamp);
    }

    private void expireFaceDeadline(FrameTimestamp currentTimestamp) {
        InFlight face = inFlight.get(DetectorLane.FACE);
        if (face == null || clock.nanoTime() - face.startedAtNanos < configuration.faceDeadlineNanos()) {
            return;
        }
        cancelLane(
                DetectorLane.FACE,
                TypedFailure.Code.DEADLINE_EXCEEDED,
                Event.FACE_DEADLINE_EXCEEDED);
        lastStarted.remove(DetectorLane.FACE);
    }

    private void cancelLane(
            DetectorLane lane, TypedFailure.Code failureCode, Event event) {
        InFlight running = inFlight.remove(lane);
        if (running == null) {
            return;
        }
        running.cancellation.cancel();
        running.lease.close();
        publishFailure(lane, running.lease.timestamp(), failureCode);
        telemetry.record(event, lane, running.lease.timestamp());
    }

    private void failIfCurrent(
            DetectorLane lane,
            InFlight expected,
            TypedFailure.Code failureCode,
            Event event) {
        if (inFlight.get(lane) != expected) {
            return;
        }
        inFlight.remove(lane);
        expected.lease.close();
        publishFailure(lane, expected.lease.timestamp(), failureCode);
        telemetry.record(event, lane, expected.lease.timestamp());
    }

    private void publishFailure(
            DetectorLane lane, FrameTimestamp timestamp, TypedFailure.Code code) {
        resultListener.onSnapshot(DetectorSnapshot.failure(
                lane, timestamp, new TypedFailure(code, timestamp)));
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (DetectorLane lane : ANALYSIS_LANES) {
            InFlight running = inFlight.remove(lane);
            if (running != null) {
                running.cancellation.cancel();
                running.lease.close();
                telemetry.record(Event.CLOSED_IN_FLIGHT, lane, running.lease.timestamp());
            }
        }
        lastStarted.clear();
        latestFrameTimestamp = null;
    }

    /** Scheduler timing configuration; all values are explicit and validated. */
    public record Configuration(
            long faceIntervalNanos,
            long textIntervalNanos,
            long barcodeIntervalNanos,
            long degradedTextIntervalNanos,
            long degradedBarcodeIntervalNanos,
            long faceDeadlineNanos,
            long maxValidityNanos) {
        public Configuration {
            if (faceIntervalNanos <= 0
                    || textIntervalNanos <= 0
                    || barcodeIntervalNanos <= 0
                    || faceDeadlineNanos <= 0
                    || maxValidityNanos < 0) {
                throw new IllegalArgumentException("Intervals and deadline must be positive");
            }
            if (degradedTextIntervalNanos < textIntervalNanos
                    || degradedBarcodeIntervalNanos < barcodeIntervalNanos) {
                throw new IllegalArgumentException("Degraded intervals cannot increase load");
            }
        }

        public static Configuration defaults() {
            return new Configuration(
                    66_666_667L,
                    500_000_000L,
                    250_000_000L,
                    1_000_000_000L,
                    500_000_000L,
                    250_000_000L,
                    1_000_000_000L);
        }

        private long intervalNanos(
                DetectorLane lane, SessionHealth.ThermalState thermalState) {
            return switch (lane) {
                case FACE -> faceIntervalNanos;
                case TEXT -> thermalState == SessionHealth.ThermalState.WARNING
                        ? degradedTextIntervalNanos : textIntervalNanos;
                case BARCODE -> thermalState == SessionHealth.ThermalState.WARNING
                        ? degradedBarcodeIntervalNanos : barcodeIntervalNanos;
                default -> throw new IllegalArgumentException("Unsupported analysis lane");
            };
        }
    }

    /** Opaque input whose retained lane leases are independently releasable. */
    public interface AnalysisFrame extends AutoCloseable {
        FrameTimestamp timestamp();

        FrameLease retain(DetectorLane lane);

        @Override
        void close();
    }

    /** Opaque analyzer input; concrete adapters keep pixel access private to their detector. */
    public interface FrameLease extends AutoCloseable {
        FrameTimestamp timestamp();

        @Override
        void close();
    }

    /** Pluggable asynchronous detector seam used by face, OCR, and barcode implementations. */
    public interface LaneAnalyzer {
        Cancellation analyze(FrameLease frame, Completion completion);
    }

    /** Exactly-once analyzer completion callback. Duplicate completions are ignored. */
    public interface Completion {
        void complete(DetectorSnapshot snapshot);
    }

    /** Cancellation handle returned by an analyzer immediately after dispatch. */
    public interface Cancellation {
        Cancellation NONE = () -> { };

        void cancel();
    }

    /** Receives policy-ready snapshots or typed, payload-free lane failures. */
    public interface ResultListener {
        void onSnapshot(DetectorSnapshot snapshot);
    }

    /** Payload-free operational telemetry. */
    public interface Telemetry {
        void record(Event event, DetectorLane lane, FrameTimestamp timestamp);
    }

    /** Monotonic elapsed-time source used only for in-flight deadlines. */
    public interface NanoClock {
        long nanoTime();
    }

    public enum Event {
        STARTED,
        COMPLETED,
        FAILED,
        LANE_BUSY_DROP,
        CADENCE_SKIP,
        FRAME_DROPPED_STALE,
        FRAME_DROPPED_CLOSED,
        SCENE_INVALIDATED,
        OPTIONAL_LANE_DISABLED,
        FACE_DEADLINE_EXCEEDED,
        INVALID_INPUT,
        INVALID_OR_STALE_RESULT,
        CLOSED_IN_FLIGHT
    }

    private static final class InFlight {
        private final FrameLease lease;
        private final long startedAtNanos;
        private Cancellation cancellation = Cancellation.NONE;

        private InFlight(FrameLease lease, long startedAtNanos) {
            this.lease = lease;
            this.startedAtNanos = startedAtNanos;
        }
    }
}
