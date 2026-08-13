package com.liveshield.vision.face;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import androidx.camera.core.ImageProxy;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-owner adapter that closes its CameraX ImageProxy at most once. */
public final class ImageProxyFaceAnalysisFrame implements FaceAnalysisFrame {
    private final ImageProxy imageProxy;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ImageProxyFaceAnalysisFrame(ImageProxy imageProxy) {
        this.imageProxy = Objects.requireNonNull(imageProxy, "imageProxy");
    }

    @Override
    public synchronized Bitmap bitmap(int rotationDegrees) {
        if (closed.get()) {
            throw new IllegalStateException("Analysis frame is already closed");
        }
        if (!isValidRotation(rotationDegrees)) {
            throw new IllegalArgumentException("rotationDegrees must be 0, 90, 180, or 270");
        }
        Bitmap source = imageProxy.toBitmap();
        if (rotationDegrees == 0) {
            return source;
        }
        Matrix rotation = new Matrix();
        rotation.postRotate(rotationDegrees);
        Bitmap upright = Bitmap.createBitmap(
                source, 0, 0, source.getWidth(), source.getHeight(), rotation, true);
        if (upright != source) {
            source.recycle();
        }
        return upright;
    }

    @Override
    public int width() {
        return imageProxy.getWidth();
    }

    @Override
    public int height() {
        return imageProxy.getHeight();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            imageProxy.close();
        }
    }

    private static boolean isValidRotation(int rotationDegrees) {
        return rotationDegrees == 0 || rotationDegrees == 90
                || rotationDegrees == 180 || rotationDegrees == 270;
    }
}
