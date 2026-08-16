package com.liveshield.app.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.liveshield.app.setup.DismissiblePrivacyMask;
import com.liveshield.app.setup.SelectableFace;
import com.liveshield.app.setup.SetupView;
import com.liveshield.privacy.model.CoordinateTransform;
import com.liveshield.privacy.model.FindingCategory;
import com.liveshield.privacy.model.NormalizedRect;
import com.liveshield.video.geometry.FrameTransform;
import com.liveshield.video.render.GlRedactionRenderer;
import java.util.List;
import org.junit.Test;

public final class OutputMappedSetupViewTest {
    @Test
    public void selectableFaceUsesSameSensorToOutputMappingAsPrivacyRenderer() {
        RecordingView delegate = new RecordingView();
        FrameTransform transform = FrameTransform.fromCameraMetadata(
                CoordinateTransform.identity(),
                new NormalizedRect(0.0, 0.0, 1.0, 1.0),
                90,
                true);
        OutputMappedSetupView view = new OutputMappedSetupView(delegate, () -> transform);

        view.showSelectableFaces(List.of(new SelectableFace(
                7L, new NormalizedRect(0.10, 0.20, 0.30, 0.40), true)), 7L);

        NormalizedRect mapped = transform.mapSensorRectToOutput(
                new NormalizedRect(0.10, 0.20, 0.30, 0.40));
        double padding = GlRedactionRenderer.COMPRESSION_GUARD_PADDING;
        NormalizedRect padded = new NormalizedRect(
                Math.max(0.0, mapped.left() - padding),
                Math.max(0.0, mapped.top() - padding),
                Math.min(1.0, mapped.right() + padding),
                Math.min(1.0, mapped.bottom() + padding));
        assertEquals(List.of(new SelectableFace(7L, padded, true)), delegate.faces);
        assertEquals(Long.valueOf(7L), delegate.selectedTrack);
    }

    @Test
    public void missingTransformHidesOverlayInsteadOfDrawingWrongCoordinates() {
        RecordingView delegate = new RecordingView();
        OutputMappedSetupView view = new OutputMappedSetupView(delegate, () -> null);

        view.showSelectableFaces(List.of(new SelectableFace(
                3L, new NormalizedRect(0.10, 0.20, 0.30, 0.40), true)), 3L);

        assertEquals(List.of(), delegate.faces);
        assertNull(delegate.selectedTrack);
    }

    @Test
    public void dismissibleMasksUseSameMappingAndPaddingAsRenderer() {
        RecordingView delegate = new RecordingView();
        FrameTransform transform = FrameTransform.fromCameraMetadata(
                CoordinateTransform.identity(),
                new NormalizedRect(0.0, 0.0, 1.0, 1.0),
                90,
                true);
        OutputMappedSetupView view = new OutputMappedSetupView(delegate, () -> transform);
        NormalizedRect sensor = new NormalizedRect(0.10, 0.20, 0.30, 0.40);

        view.showDismissiblePrivacyMasks(List.of(
                new DismissiblePrivacyMask(FindingCategory.AUTO_BARCODE, sensor)));

        NormalizedRect output = transform.mapSensorRectToOutput(sensor);
        double padding = GlRedactionRenderer.COMPRESSION_GUARD_PADDING;
        assertEquals(List.of(new DismissiblePrivacyMask(
                FindingCategory.AUTO_BARCODE,
                new NormalizedRect(
                        Math.max(0.0, output.left() - padding),
                        Math.max(0.0, output.top() - padding),
                        Math.min(1.0, output.right() + padding),
                        Math.min(1.0, output.bottom() + padding)))), delegate.masks);
    }

    private static final class RecordingView implements SetupView {
        private List<SelectableFace> faces = List.of();
        private List<DismissiblePrivacyMask> masks = List.of();
        private Long selectedTrack;

        @Override
        public void showSelectableFaces(List<SelectableFace> value, Long selected) {
            faces = value;
            selectedTrack = selected;
        }

        @Override
        public void showDismissiblePrivacyMasks(List<DismissiblePrivacyMask> value) {
            masks = List.copyOf(value);
        }

        @Override
        public void showPrivacyReady(boolean ready) {
        }
    }
}
