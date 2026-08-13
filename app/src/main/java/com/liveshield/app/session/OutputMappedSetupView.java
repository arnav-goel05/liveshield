package com.liveshield.app.session;

import com.liveshield.app.diagnostics.AppDiagnostics;
import com.liveshield.app.setup.SelectableFace;
import com.liveshield.app.setup.SetupView;
import com.liveshield.video.geometry.FrameTransform;
import com.liveshield.video.render.GlRedactionRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Maps sensor-space face metadata into the sanitized preview before rendering setup overlays. */
final class OutputMappedSetupView implements SetupView {
    private final SetupView delegate;
    private final Supplier<FrameTransform> transform;

    OutputMappedSetupView(SetupView delegate, Supplier<FrameTransform> transform) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.transform = Objects.requireNonNull(transform, "transform");
    }

    @Override
    public void showSelectableFaces(List<SelectableFace> faces, Long selectedTrack) {
        Objects.requireNonNull(faces, "faces");
        FrameTransform current = transform.get();
        if (current == null) {
            delegate.showSelectableFaces(List.of(), null);
            return;
        }
        ArrayList<SelectableFace> mapped = new ArrayList<>(faces.size());
        try {
            for (SelectableFace face : faces) {
                AppDiagnostics.bounds(
                        AppDiagnostics.Event.FACE_SENSOR_BOUNDS,
                        face.trackId(),
                        face.outputBounds().left(), face.outputBounds().top(),
                        face.outputBounds().right(), face.outputBounds().bottom());
                var outputBounds = current.mapSensorRectToOutput(face.outputBounds());
                double padding = GlRedactionRenderer.COMPRESSION_GUARD_PADDING;
                outputBounds = new com.liveshield.privacy.model.NormalizedRect(
                        Math.max(0.0, outputBounds.left() - padding),
                        Math.max(0.0, outputBounds.top() - padding),
                        Math.min(1.0, outputBounds.right() + padding),
                        Math.min(1.0, outputBounds.bottom() + padding));
                AppDiagnostics.bounds(
                        AppDiagnostics.Event.FACE_OVERLAY_BOUNDS,
                        face.trackId(),
                        outputBounds.left(), outputBounds.top(),
                        outputBounds.right(), outputBounds.bottom());
                mapped.add(new SelectableFace(
                        face.trackId(),
                        outputBounds,
                        face.fresh()));
            }
        } catch (IllegalArgumentException unsafeTransform) {
            delegate.showSelectableFaces(List.of(), null);
            return;
        }
        delegate.showSelectableFaces(List.copyOf(mapped), selectedTrack);
    }

    @Override
    public void showPrivacyReady(boolean ready) {
        delegate.showPrivacyReady(ready);
    }

    @Override
    public void showHostReselectionRequired(boolean required) {
        delegate.showHostReselectionRequired(required);
    }

    @Override
    public void onSafeStopFailure() {
        delegate.onSafeStopFailure();
    }
}
