package com.liveshield.app.session;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import androidx.camera.core.ImageProxy;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.DetectorLane;
import com.liveshield.privacy.model.FrameTimestamp;
import com.liveshield.video.analysis.VisionScheduler;
import com.liveshield.vision.face.FaceAnalysisFrame;
import com.liveshield.vision.pii.OfflineBarcodeAnalyzer;
import com.liveshield.vision.pii.TextAnalysisFrame;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Ephemeral CameraX owner that creates bounded, independently releasable detector inputs. */
final class ImageProxyVisionFrame implements VisionScheduler.AnalysisFrame {
    private final ImageProxy image;
    private final FrameTimestamp timestamp;
    private final int rotationDegrees;
    private final CoordinateTransform transform;
    private final AtomicBoolean closed = new AtomicBoolean();

    ImageProxyVisionFrame(
            ImageProxy image,
            FrameTimestamp timestamp,
            int rotationDegrees,
            CoordinateTransform transform) {
        this.image = Objects.requireNonNull(image, "image");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.rotationDegrees = rotationDegrees;
        this.transform = Objects.requireNonNull(transform, "transform");
    }

    @Override
    public FrameTimestamp timestamp() {
        return timestamp;
    }

    @Override
    public synchronized VisionScheduler.FrameLease retain(DetectorLane lane) {
        if (closed.get()) {
            throw new IllegalStateException("Camera frame is already released");
        }
        return switch (Objects.requireNonNull(lane, "lane")) {
            case FACE, TEXT -> new BitmapLease(
                    timestamp, rotationDegrees, transform, image.toBitmap());
            case BARCODE -> new LuminanceLease(
                    timestamp,
                    rotationDegrees,
                    transform,
                    image.getWidth(),
                    image.getHeight(),
                    copyLuminance(image));
            default -> throw new IllegalArgumentException("Unsupported detector lane");
        };
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            image.close();
        }
    }

    private static byte[] copyLuminance(ImageProxy source) {
        ImageProxy.PlaneProxy plane = source.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer().duplicate();
        int width = source.getWidth();
        int height = source.getHeight();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        byte[] luminance = new byte[Math.multiplyExact(width, height)];
        int base = buffer.position();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                luminance[y * width + x] = buffer.get(base + y * rowStride + x * pixelStride);
            }
        }
        return luminance;
    }

    interface DetectorLease extends VisionScheduler.FrameLease {
        int rotationDegrees();

        CoordinateTransform transform();
    }

    private static final class BitmapLease
            implements DetectorLease, FaceAnalysisFrame, TextAnalysisFrame {
        private final FrameTimestamp timestamp;
        private final int rotationDegrees;
        private final CoordinateTransform transform;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Bitmap bitmap;

        private BitmapLease(
                FrameTimestamp timestamp,
                int rotationDegrees,
                CoordinateTransform transform,
                Bitmap bitmap) {
            this.timestamp = timestamp;
            this.rotationDegrees = rotationDegrees;
            this.transform = transform;
            this.bitmap = Objects.requireNonNull(bitmap, "bitmap");
        }

        @Override
        public FrameTimestamp timestamp() {
            return timestamp;
        }

        @Override
        public int rotationDegrees() {
            return rotationDegrees;
        }

        @Override
        public CoordinateTransform transform() {
            return transform;
        }

        @Override
        public synchronized Bitmap bitmap(int requestedRotation) {
            requireOpen();
            if (requestedRotation == 0) {
                return bitmap;
            }
            Matrix matrix = new Matrix();
            matrix.postRotate(requestedRotation);
            Bitmap rotated = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) {
                bitmap.recycle();
                bitmap = rotated;
            }
            return bitmap;
        }

        @Override
        public synchronized int width() {
            requireOpen();
            return bitmap.getWidth();
        }

        @Override
        public synchronized int height() {
            requireOpen();
            return bitmap.getHeight();
        }

        @Override
        public synchronized void close() {
            if (closed.compareAndSet(false, true)) {
                bitmap.recycle();
            }
        }

        private void requireOpen() {
            if (closed.get()) {
                throw new IllegalStateException("Detector bitmap is already released");
            }
        }
    }

    private static final class LuminanceLease
            implements DetectorLease, OfflineBarcodeAnalyzer.BarcodeAnalysisFrame {
        private final FrameTimestamp timestamp;
        private final int rotationDegrees;
        private final CoordinateTransform transform;
        private final int width;
        private final int height;
        private final AtomicBoolean closed = new AtomicBoolean();
        private byte[] luminance;

        private LuminanceLease(
                FrameTimestamp timestamp,
                int rotationDegrees,
                CoordinateTransform transform,
                int width,
                int height,
                byte[] luminance) {
            this.timestamp = timestamp;
            this.rotationDegrees = rotationDegrees;
            this.transform = transform;
            this.width = width;
            this.height = height;
            this.luminance = luminance;
        }

        @Override
        public FrameTimestamp timestamp() {
            return timestamp;
        }

        @Override
        public int rotationDegrees() {
            return rotationDegrees;
        }

        @Override
        public CoordinateTransform transform() {
            return transform;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public synchronized byte[] luminance() {
            if (closed.get()) {
                throw new IllegalStateException("Detector luminance is already released");
            }
            return luminance;
        }

        @Override
        public synchronized void close() {
            if (closed.compareAndSet(false, true)) {
                java.util.Arrays.fill(luminance, (byte) 0);
                luminance = new byte[0];
            }
        }
    }
}
