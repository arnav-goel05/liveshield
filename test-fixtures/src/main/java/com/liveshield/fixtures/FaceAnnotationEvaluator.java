package com.liveshield.fixtures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates identity-free face truth and joins it to decoded-output observations by PTS. */
public final class FaceAnnotationEvaluator {
    private static final Pattern FIXTURE_ID =
            Pattern.compile("^face-(?:dev|holdout)-[0-9]{2,6}$");
    private static final Pattern OBJECT_ID = Pattern.compile("^face-[a-f0-9]{8,32}$");

    private FaceAnnotationEvaluator() {
    }

    public static Evaluation evaluate(
            EpisodeAnnotation annotation,
            List<DecodedOutputFrame> decodedFrames,
            Limits limits) {
        Objects.requireNonNull(annotation, "annotation");
        List<DecodedOutputFrame> safeDecoded = List.copyOf(
                Objects.requireNonNull(decodedFrames, "decodedFrames"));
        Objects.requireNonNull(limits, "limits");
        if (annotation.frames().size() > limits.maximumAnnotationFrames()
                || safeDecoded.size() > limits.maximumDecodedFrames()) {
            throw new IllegalArgumentException("Episode exceeds configured frame limits");
        }
        validateDecoded(safeDecoded, limits);

        Map<Long, DecodedOutputFrame> joined = new HashMap<>();
        Set<Integer> usedDecodedIndices = new HashSet<>();
        long maximumPtsDelta = 0;
        int minimumDecodedIndex = 0;
        for (FrameAnnotation frame : annotation.frames()) {
            int nearest = nearestAtOrAfter(
                    frame.sourceTimestampNs(), safeDecoded, minimumDecodedIndex,
                    limits.presentationTimestampToleranceNs());
            if (nearest >= 0) {
                DecodedOutputFrame decoded = safeDecoded.get(nearest);
                joined.put(frame.sourceTimestampNs(), decoded);
                usedDecodedIndices.add(nearest);
                minimumDecodedIndex = nearest + 1;
                maximumPtsDelta = Math.max(maximumPtsDelta,
                        absoluteDifference(frame.sourceTimestampNs(),
                                decoded.presentationTimestampNs()));
            }
        }

        LinkedHashMap<String, FaceRole> roles = new LinkedHashMap<>();
        LinkedHashMap<String, Long> protectableSince = new LinkedHashMap<>();
        for (FrameAnnotation frame : annotation.frames()) {
            if (frame.objects().size() > limits.maximumObjectsPerFrame()) {
                throw new IllegalArgumentException("Frame exceeds configured object limit");
            }
            for (FaceObject object : frame.objects()) {
                FaceRole previousRole = roles.putIfAbsent(object.objectId(), object.role());
                if (previousRole != null && previousRole != object.role()) {
                    throw new IllegalArgumentException("Face role cannot change within an episode");
                }
                if (object.protectable()) {
                    long timestamp = object.protectableSinceNs().orElseThrow();
                    Long previous = protectableSince.putIfAbsent(object.objectId(), timestamp);
                    if (previous != null && previous != timestamp) {
                        throw new IllegalArgumentException(
                                "protectableSinceNs must remain stable for an object");
                    }
                }
            }
        }

        LinkedHashMap<String, FaceTrackingMetrics.EpisodeMetrics> perObject =
                new LinkedHashMap<>();
        List<FaceTrackingMetrics.Episode> metricEpisodes = new ArrayList<>();
        for (Map.Entry<String, FaceRole> entry : roles.entrySet()) {
            if (entry.getValue() != FaceRole.UNKNOWN) {
                continue;
            }
            FaceTrackingMetrics.Episode metricEpisode = metricEpisode(
                    annotation, entry.getKey(), joined);
            metricEpisodes.add(metricEpisode);
            perObject.put(entry.getKey(), FaceTrackingMetrics.evaluate(metricEpisode));
        }
        return new Evaluation(
                annotation.fixtureId(),
                annotation.frames().size(),
                safeDecoded.size(),
                joined.size(),
                safeDecoded.size() - usedDecodedIndices.size(),
                maximumPtsDelta,
                perObject,
                FaceTrackingMetrics.aggregate(metricEpisodes));
    }

    private static FaceTrackingMetrics.Episode metricEpisode(
            EpisodeAnnotation annotation,
            String objectId,
            Map<Long, DecodedOutputFrame> joined) {
        List<FaceTrackingMetrics.FrameObservation> observations = new ArrayList<>();
        for (int index = 0; index < annotation.frames().size(); index++) {
            FrameAnnotation frame = annotation.frames().get(index);
            FaceObject object = object(frame.objects(), objectId);
            long duration = duration(annotation, index);
            if (object == null || !object.protectable()) {
                observations.add(new FaceTrackingMetrics.FrameObservation(
                        frame.frameIndex(), frame.sourceTimestampNs(), duration,
                        false, false, OptionalLong.empty()));
                continue;
            }
            DecodedFaceObservation decoded = decodedObject(
                    joined.get(frame.sourceTimestampNs()), objectId);
            observations.add(new FaceTrackingMetrics.FrameObservation(
                    frame.frameIndex(), frame.sourceTimestampNs(), duration,
                    true,
                    decoded != null && decoded.fullyProtected(),
                    decoded == null ? OptionalLong.empty() : decoded.assignedTrackId()));
        }
        return new FaceTrackingMetrics.Episode(
                annotation.fixtureId() + ":" + objectId, observations);
    }

    private static long duration(EpisodeAnnotation annotation, int index) {
        if (index + 1 == annotation.frames().size()) {
            return annotation.nominalFrameDurationNs();
        }
        return Math.subtractExact(
                annotation.frames().get(index + 1).sourceTimestampNs(),
                annotation.frames().get(index).sourceTimestampNs());
    }

    private static FaceObject object(List<FaceObject> objects, String objectId) {
        for (FaceObject object : objects) {
            if (object.objectId().equals(objectId)) {
                return object;
            }
        }
        return null;
    }

    private static DecodedFaceObservation decodedObject(
            DecodedOutputFrame decoded, String objectId) {
        if (decoded == null) {
            return null;
        }
        for (DecodedFaceObservation object : decoded.faces()) {
            if (object.objectId().equals(objectId)) {
                return object;
            }
        }
        return null;
    }

    private static int nearestAtOrAfter(
            long sourceTimestamp,
            List<DecodedOutputFrame> decoded,
            int minimumIndex,
            long tolerance) {
        int nearest = -1;
        long nearestDelta = Long.MAX_VALUE;
        for (int index = minimumIndex; index < decoded.size(); index++) {
            long delta = absoluteDifference(
                    sourceTimestamp, decoded.get(index).presentationTimestampNs());
            if (delta <= tolerance && delta < nearestDelta) {
                nearest = index;
                nearestDelta = delta;
            }
        }
        return nearest;
    }

    private static long absoluteDifference(long first, long second) {
        return first >= second ? first - second : second - first;
    }

    private static void validateDecoded(List<DecodedOutputFrame> decoded, Limits limits) {
        long previous = -1;
        for (DecodedOutputFrame frame : decoded) {
            if (frame.presentationTimestampNs() <= previous) {
                throw new IllegalArgumentException(
                        "Decoded presentation timestamps must strictly increase");
            }
            if (frame.faces().size() > limits.maximumObjectsPerFrame()) {
                throw new IllegalArgumentException("Decoded frame exceeds configured object limit");
            }
            previous = frame.presentationTimestampNs();
        }
    }

    public enum FaceRole {
        HOST,
        UNKNOWN
    }

    public record Point(double x, double y) {
        public Point {
            requireUnit(x, "point.x");
            requireUnit(y, "point.y");
        }
    }

    public record Polygon(List<Point> points) {
        public Polygon {
            points = List.copyOf(Objects.requireNonNull(points, "points"));
            if (points.size() < 3) {
                throw new IllegalArgumentException("Polygon requires at least three points");
            }
            if (new HashSet<>(points).size() != points.size()) {
                throw new IllegalArgumentException("Polygon points must be unique");
            }
            double twiceArea = 0;
            for (int index = 0; index < points.size(); index++) {
                Point current = points.get(index);
                Point next = points.get((index + 1) % points.size());
                twiceArea += current.x() * next.y() - next.x() * current.y();
            }
            if (!Double.isFinite(twiceArea) || Math.abs(twiceArea) <= 1.0e-12) {
                throw new IllegalArgumentException("Polygon must have non-zero finite area");
            }
            if (selfIntersects(points)) {
                throw new IllegalArgumentException("Polygon must not self-intersect");
            }
        }
    }

    public record Rect(double left, double top, double right, double bottom) {
        public Rect {
            requireUnit(left, "crop.left");
            requireUnit(top, "crop.top");
            requireUnit(right, "crop.right");
            requireUnit(bottom, "crop.bottom");
            if (left >= right || top >= bottom) {
                throw new IllegalArgumentException("Crop must be a non-empty rectangle");
            }
        }
    }

    public record Transform(
            int rotationDegrees,
            boolean mirrored,
            Rect crop,
            List<Double> sensorToBuffer) {
        public Transform {
            if (rotationDegrees != 0 && rotationDegrees != 90
                    && rotationDegrees != 180 && rotationDegrees != 270) {
                throw new IllegalArgumentException("Unsupported rotation");
            }
            Objects.requireNonNull(crop, "crop");
            sensorToBuffer = List.copyOf(
                    Objects.requireNonNull(sensorToBuffer, "sensorToBuffer"));
            if (sensorToBuffer.size() != 9
                    || sensorToBuffer.stream().anyMatch(value -> !Double.isFinite(value))) {
                throw new IllegalArgumentException("Transform requires nine finite matrix values");
            }
            double determinant = determinant(sensorToBuffer);
            if (!Double.isFinite(determinant) || Math.abs(determinant) <= 1.0e-12) {
                throw new IllegalArgumentException("Transform matrix must be invertible");
            }
        }
    }

    public record FaceObject(
            String objectId,
            FaceRole role,
            Polygon polygon,
            double visibility,
            boolean protectable,
            OptionalLong protectableSinceNs) {
        public FaceObject {
            requireObjectId(objectId);
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(polygon, "polygon");
            requireUnit(visibility, "visibility");
            Objects.requireNonNull(protectableSinceNs, "protectableSinceNs");
            if (protectable != protectableSinceNs.isPresent()) {
                throw new IllegalArgumentException(
                        "Exactly protectable objects require protectableSinceNs");
            }
            if (protectableSinceNs.isPresent()
                    && protectableSinceNs.getAsLong() < 0) {
                throw new IllegalArgumentException(
                        "protectableSinceNs must be non-negative");
            }
        }
    }

    public record FrameAnnotation(
            long frameIndex,
            long sourceTimestampNs,
            Transform transform,
            List<FaceObject> objects) {
        public FrameAnnotation {
            if (frameIndex < 0 || sourceTimestampNs < 0) {
                throw new IllegalArgumentException(
                        "Frame index and timestamp must be non-negative");
            }
            Objects.requireNonNull(transform, "transform");
            objects = List.copyOf(Objects.requireNonNull(objects, "objects"));
            Set<String> identifiers = new HashSet<>();
            for (FaceObject object : objects) {
                Objects.requireNonNull(object, "object");
                if (!identifiers.add(object.objectId())) {
                    throw new IllegalArgumentException("Object IDs must be unique within a frame");
                }
                if (object.protectableSinceNs().isPresent()
                        && object.protectableSinceNs().getAsLong() > sourceTimestampNs) {
                    throw new IllegalArgumentException(
                            "A face cannot be protectable before its protectable timestamp");
                }
            }
        }
    }

    public record EpisodeAnnotation(
            String fixtureId,
            long nominalFrameDurationNs,
            List<FrameAnnotation> frames) {
        public EpisodeAnnotation {
            if (fixtureId == null || !FIXTURE_ID.matcher(fixtureId).matches()) {
                throw new IllegalArgumentException("Fixture ID must be an opaque face corpus ID");
            }
            if (nominalFrameDurationNs <= 0) {
                throw new IllegalArgumentException("Nominal frame duration must be positive");
            }
            frames = List.copyOf(Objects.requireNonNull(frames, "frames"));
            long previousIndex = -1;
            long previousTimestamp = -1;
            for (FrameAnnotation frame : frames) {
                Objects.requireNonNull(frame, "frame");
                if (frame.frameIndex() <= previousIndex
                        || frame.sourceTimestampNs() <= previousTimestamp) {
                    throw new IllegalArgumentException(
                            "Annotation frames must strictly increase by index and timestamp");
                }
                previousIndex = frame.frameIndex();
                previousTimestamp = frame.sourceTimestampNs();
            }
        }
    }

    public record DecodedFaceObservation(
            String objectId,
            boolean fullyProtected,
            OptionalLong assignedTrackId) {
        public DecodedFaceObservation {
            requireObjectId(objectId);
            Objects.requireNonNull(assignedTrackId, "assignedTrackId");
            if (assignedTrackId.isPresent() && assignedTrackId.getAsLong() < 0) {
                throw new IllegalArgumentException("Track identifier must be non-negative");
            }
        }
    }

    public record DecodedOutputFrame(
            long presentationTimestampNs,
            List<DecodedFaceObservation> faces) {
        public DecodedOutputFrame {
            if (presentationTimestampNs < 0) {
                throw new IllegalArgumentException("Decoded PTS must be non-negative");
            }
            faces = List.copyOf(Objects.requireNonNull(faces, "faces"));
            Set<String> identifiers = new HashSet<>();
            for (DecodedFaceObservation face : faces) {
                Objects.requireNonNull(face, "face");
                if (!identifiers.add(face.objectId())) {
                    throw new IllegalArgumentException(
                            "Decoded object IDs must be unique within a frame");
                }
            }
        }
    }

    public record Limits(
            int maximumAnnotationFrames,
            int maximumDecodedFrames,
            int maximumObjectsPerFrame,
            long presentationTimestampToleranceNs) {
        public Limits {
            if (maximumAnnotationFrames <= 0 || maximumDecodedFrames <= 0
                    || maximumObjectsPerFrame <= 0
                    || presentationTimestampToleranceNs < 0) {
                throw new IllegalArgumentException(
                        "Evaluation limits must be positive and bounded");
            }
        }
    }

    public record Evaluation(
            String fixtureId,
            int annotationFrameCount,
            int decodedFrameCount,
            int matchedFrameCount,
            int unmatchedDecodedFrameCount,
            long maximumPresentationTimestampDeltaNs,
            Map<String, FaceTrackingMetrics.EpisodeMetrics> perUnknownObject,
            FaceTrackingMetrics.Summary summary) {
        public Evaluation {
            Objects.requireNonNull(fixtureId, "fixtureId");
            perUnknownObject = Map.copyOf(
                    Objects.requireNonNull(perUnknownObject, "perUnknownObject"));
            Objects.requireNonNull(summary, "summary");
        }
    }

    private static void requireObjectId(String objectId) {
        if (objectId == null || !OBJECT_ID.matcher(objectId).matches()) {
            throw new IllegalArgumentException("Object ID must be an opaque session-local face ID");
        }
    }

    private static void requireUnit(double value, String label) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(label + " must be finite and normalized");
        }
    }

    private static double determinant(List<Double> matrix) {
        double a = matrix.get(0);
        double b = matrix.get(1);
        double c = matrix.get(2);
        double d = matrix.get(3);
        double e = matrix.get(4);
        double f = matrix.get(5);
        double g = matrix.get(6);
        double h = matrix.get(7);
        double i = matrix.get(8);
        return a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    }

    private static boolean selfIntersects(List<Point> points) {
        int size = points.size();
        for (int first = 0; first < size; first++) {
            int firstNext = (first + 1) % size;
            for (int second = first + 1; second < size; second++) {
                int secondNext = (second + 1) % size;
                if (first == second || firstNext == second
                        || secondNext == first) {
                    continue;
                }
                if (segmentsIntersect(
                        points.get(first), points.get(firstNext),
                        points.get(second), points.get(secondNext))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean segmentsIntersect(Point a, Point b, Point c, Point d) {
        double first = direction(a, b, c);
        double second = direction(a, b, d);
        double third = direction(c, d, a);
        double fourth = direction(c, d, b);
        return ((first > 0 && second < 0) || (first < 0 && second > 0))
                && ((third > 0 && fourth < 0) || (third < 0 && fourth > 0));
    }

    private static double direction(Point a, Point b, Point c) {
        return (c.x() - a.x()) * (b.y() - a.y())
                - (c.y() - a.y()) * (b.x() - a.x());
    }
}
